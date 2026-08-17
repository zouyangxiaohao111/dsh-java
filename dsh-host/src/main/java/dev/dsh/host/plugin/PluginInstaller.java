package dev.dsh.host.plugin;

import dev.dsh.host.cli.PluginCommand;
import dev.dsh.host.cli.ProfileBoot;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * M6-7 的 Java 侧插件安装 —— 补 dsh/pnpm 装不了 Java 插件的洞(<b>不是</b>复刻 dsh plugin add)。
 *
 * <p>{@code dshj plugin --profile <name> add <spec>} 的 {@code <spec>} Java 侧形式:
 * <ul>
 *   <li>{@code jar:<maven坐标>} 如 {@code jar:dev.dsh:demo:0.1.0} —— 从 MavenLocal / 配置仓库 /
 *       Central 解析并复制到 {@code <profile>/plugins/jars/},写 {@code source: jar:} 条目;</li>
 *   <li>{@code jar:<path>} —— 本地 jar(路径锚到 cwd)复制到 {@code plugins/jars/};</li>
 *   <li>{@code java:<path|github:u/r|git+https://...>} —— 本地源码目录/单文件复制、或 git
 *       clone,落到 {@code <profile>/plugins/src/},写 {@code source: java:} 条目(目录源经 loader
 *       整目录编译);</li>
 *   <li>{@code java:<类名>} —— 引用 harness classpath 上已有的类(如
 *       {@code dev.dsh.demo.CounterPlugin}),只写条目不落盘;</li>
 *   <li>裸 {@code github:u/r} / {@code git+...} —— 按 Java 源码源安装(与 dsh 的 git 语义对齐)。</li>
 * </ul>
 *
 * <p><b>路径锚到 cwd</b>(镜像 dsh {@code anchorPathSpec}):用户从哪个目录调用 {@code dshj},
 * 相对路径就以那个目录为基准解析(cwd = 进程 user.dir;经 {@code ./dshj} 即仓库根)。
 *
 * <p>JS 侧({@code node:}/{@code graaljs:}/裸 npm 包名)不在此安装 —— 交真实 dsh
 * {@code plugin add}(pnpm),这里给出明确提示。
 */
public final class PluginInstaller {

    /** Maven Central 根。 */
    public static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2";

    /** 安装失败(用户可读信息);与 IOException 同为受检,PluginCommand 打印后返回退出码 1。 */
    public static final class InstallException extends IOException {
        public InstallException(String message) {
            super(message);
        }

        public InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 安装器配置(默认从环境读;测试注入临时根)。 */
    public record Config(Path profileHome, Path mavenLocal, List<String> repos, Path cwd) {
        /** 便捷:仅替换 profile 根与 mavenLocal,其余默认。 */
        public Config withLocal(Path profileHome, Path mavenLocal) {
            return new Config(profileHome, mavenLocal, repos, cwd);
        }
    }

    /** 一次安装结果。 */
    public record Installed(String name, String source, String mainClass, Path location, Path configFile) {
    }

    private static final Pattern CLASS_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.]*$");
    private static final Pattern GITHUB_SPEC = Pattern.compile("^([^/]+)/(.+)$");

    private final Config config;
    private final HttpClient http;

    public PluginInstaller() {
        this(defaultConfig());
    }

    public PluginInstaller(Config config) {
        this.config = config == null ? defaultConfig() : config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 默认配置:profile 根同 {@link ProfileBoot} boot;mavenLocal = {@code DSH_MAVEN_LOCAL} 或
     *  {@code ~/.m2/repository};额外仓库 {@code DSH_MAVEN_REPOS}(分号/逗号分隔 URL,在 Central
     *  前试);cwd = 进程 user.dir(锚相对路径)。 */
    public static Config defaultConfig() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path profileHome = ProfileBoot.defaultProfilesRoot();
        Path mavenLocal = Path.of(System.getProperty("user.home"), ".m2", "repository");
        String localEnv = System.getenv("DSH_MAVEN_LOCAL");
        if (localEnv != null && !localEnv.isBlank()) mavenLocal = Path.of(localEnv);
        List<String> repos = new ArrayList<>();
        String reposEnv = System.getenv("DSH_MAVEN_REPOS");
        if (reposEnv != null && !reposEnv.isBlank()) {
            for (String r : reposEnv.split("[;,]+")) {
                if (!r.isBlank()) repos.add(r.trim());
            }
        }
        return new Config(profileHome, mavenLocal, List.copyOf(repos), cwd);
    }

    /**
     * 安装一个 Java 侧 {@code <spec>} 到 profile。
     *
     * @param profile profile 名(写入 {@code <profileHome>/<name>})
     * @param spec    {@code jar:...} / {@code java:...} / 裸 {@code github:} / {@code git+...}
     * @param out     进度输出
     * @param err     警告输出
     * @return 安装结果(含写回的配置条目与文件)
     * @throws InstallException 解析/解析/安装失败(含 JS 侧提示,走真实 dsh plugin add)
     */
    public Installed install(String profile, String spec, PrintStream out, PrintStream err) throws InstallException {
        PluginCommand.Spec parsed = PluginCommand.parseSpec(spec);
        if (parsed == null) {
            throw new InstallException("invalid spec '" + spec + "'");
        }
        Path profileDir = resolveProfileDir(profile);
        return switch (parsed.prefix() == null ? "" : parsed.prefix()) {
            case "jar" -> installJar(profileDir, parsed.target(), out);
            case "java" -> installSource(profileDir, parsed.target(), out);
            case "github", "git+" -> installSource(profileDir, parsed.entryValue(), out);
            case "node", "graaljs" -> throw new InstallException(
                    "'" + spec + "' is a JS plugin: run the real dsh plugin add (pnpm) into the profile "
                            + "and it loads via the JS bridge; this Java-side installer covers jar:/java: only");
            default -> throw new InstallException(
                    "'" + spec + "' is a dsh JS plugin: run the real dsh plugin add (pnpm) into the profile; "
                            + "this Java-side installer covers jar:/java: (github:/git+) only");
        };
    }

    // ---- jar 安装 ----

    private Installed installJar(Path profileDir, String target, PrintStream out) throws InstallException {
        if (looksLikeMavenCoords(target)) {
            return installJarFromMaven(profileDir, target, out);
        }
        if (target.indexOf(':') >= 0) {
            // 带冒号却非 g:a:v → 残缺 maven 坐标(如缺 version),给出明确提示
            throw new InstallException("invalid jar spec '" + target + "': neither a local jar path nor maven "
                    + "coordinates (expect group:artifact:version, e.g. jar:dev.dsh:demo:0.1.0)");
        }
        Path source = anchor(cwd(), target);
        if (!Files.isRegularFile(source) || !source.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new InstallException("jar not found at " + source + " (relative specs anchor to "
                    + cwd() + ", mirroring dsh anchorPathSpec)");
        }
        Path jars = profileDir.resolve("plugins/jars");
        createDirs(jars);
        String fileName = source.getFileName().toString();
        Path copy = jars.resolve(fileName);
        copyFile(source, copy);
        String name = fileName.endsWith(".jar")
                ? fileName.substring(0, fileName.length() - ".jar".length())
                : fileName;
        out.println("dshj plugin add: copied jar " + copy);
        return writeEntry(profileDir, name, "jar:./plugins/jars/" + fileName);
    }

    /** Maven 坐标解析:从 MavenLocal → 配置仓库 → Central 拿 jar,复制到 plugins/jars/。 */
    private Installed installJarFromMaven(Path profileDir, String coords, PrintStream out) throws InstallException {
        String[] parts = coords.split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new InstallException("invalid maven coordinates '" + coords + "' (expect group:artifact:version, "
                    + "e.g. jar:dev.dsh:demo:0.1.0)");
        }
        String group = parts[0], artifact = parts[1], version = parts[2];
        String rel = group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + ".jar";
        Path fileName = Path.of(artifact + "-" + version + ".jar");

        // ① MavenLocal
        Path local = config.mavenLocal().resolve(rel).normalize();
        if (Files.isRegularFile(local)) {
            Path jars = profileDir.resolve("plugins/jars");
            createDirs(jars);
            Path copy = jars.resolve(fileName);
            copyFile(local, copy);
            out.println("dshj plugin add: resolved " + artifact + ":" + version + " from MavenLocal " + local);
            return writeEntry(profileDir, artifact, "jar:./plugins/jars/" + fileName);
        }

        // ② 配置仓库 / ③ Central
        List<String> urls = new ArrayList<>();
        for (String repo : config.repos()) urls.add(joinUrl(repo, rel));
        urls.add(joinUrl(MAVEN_CENTRAL, rel));
        List<String> failures = new ArrayList<>();
        for (String url : urls) {
            Path downloaded = downloadToTemp(url, failures);
            if (downloaded == null) continue;
            try {
                Path jars = profileDir.resolve("plugins/jars");
                Files.createDirectories(jars);
                Path copy = jars.resolve(fileName);
                Files.move(downloaded, copy, StandardCopyOption.REPLACE_EXISTING);
                out.println("dshj plugin add: downloaded " + artifact + ":" + version + " from " + url);
                return writeEntry(profileDir, artifact, "jar:./plugins/jars/" + fileName);
            } catch (IOException e) {
                throw new InstallException("failed to move downloaded jar to " + profileDir.resolve("plugins/jars"), e);
            }
        }
        throw new InstallException("cannot resolve maven artifact " + coords + " from MavenLocal "
                + config.mavenLocal() + " or repositories" + (failures.isEmpty() ? "" : ": " + String.join("; ", failures)));
    }

    // ---- 源码安装 ----

    private Installed installSource(Path profileDir, String target, PrintStream out) throws InstallException {
        if (target.startsWith("github:")) {
            String spec = target.substring("github:".length());
            java.util.regex.Matcher m = GITHUB_SPEC.matcher(spec);
            if (!m.matches()) {
                throw new InstallException("invalid github spec '" + target + "' (expect github:owner/repo)");
            }
            String owner = m.group(1), repo = m.group(2);
            return gitCloneTo(profileDir, "https://github.com/" + owner + "/" + repo + ".git", repo, out);
        }
        if (target.startsWith("git+")) {
            String url = target.substring("git+".length());
            if (url.isBlank()) throw new InstallException("invalid git spec '" + target + "' (expect git+<url>)");
            String repo = nameFromGitUrl(url);
            return gitCloneTo(profileDir, url, repo, out);
        }
        // 本地路径(锚到 cwd)
        Path source = anchor(cwd(), target);
        if (Files.exists(source)) {
            Path src = profileDir.resolve("plugins/src");
            if (Files.isDirectory(source)) {
                Path dst = src.resolve(source.getFileName().toString());
                copyTree(source, dst);
                out.println("dshj plugin add: copied source dir " + dst);
                return writeEntry(profileDir, source.getFileName().toString(),
                        "java:./plugins/src/" + source.getFileName().toString());
            }
            if (Files.isRegularFile(source) && source.toString().endsWith(".java")) {
                createDirs(src);
                String fileName = source.getFileName().toString();
                Path dst = src.resolve(fileName);
                copyFile(source, dst);
                out.println("dshj plugin add: copied source file " + dst);
                String name = fileName.endsWith(".java")
                        ? fileName.substring(0, fileName.length() - ".java".length())
                        : fileName;
                return writeEntry(profileDir, name, "java:./plugins/src/" + fileName);
            }
            throw new InstallException("unsupported source target '" + target + "': only .java files or source "
                    + "directories can be installed (found " + source + ")");
        }
        // 引用 harness classpath 上已有的类(如 java:dev.dsh.demo.CounterPlugin)
        if (CLASS_NAME.matcher(target).matches()) {
            String name = target;
            int dot = name.lastIndexOf('.');
            if (dot >= 0) name = name.substring(dot + 1);
            out.println("dshj plugin add: referencing class on harness classpath: " + target);
            return writeEntry(profileDir, name, "java:" + target);
        }
        throw new InstallException("source not found at " + source + " and '" + target + "' is not a class name "
                + "(relative specs anchor to " + cwd() + ")");
    }

    /** git clone url → plugins/src/&lt;name&gt; → 写 java: 目录源条目。 */
    private Installed gitCloneTo(Path profileDir, String url, String name, PrintStream out) throws InstallException {
        Path dst = profileDir.resolve("plugins/src").resolve(name);
        if (!Files.exists(dst)) {
            // Windows 本地路径的反斜杠归一为正斜杠,避免 git CLI 把 C:\ 当协议前缀
            String cloneUrl = url.replace('\\', '/');
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", cloneUrl, dst.toString());
            pb.redirectErrorStream(true);
            Process proc;
            try {
                proc = pb.start();
            } catch (IOException e) {
                throw new InstallException("cannot run git (is git on PATH?): " + e.getMessage(), e);
            }
            String output;
            try (InputStream is = proc.getInputStream()) {
                output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                output = "<unreadable git output>";
            }
            try {
                int rc = proc.waitFor();
                if (rc != 0) {
                    throw new InstallException("git clone failed for " + url + ": " + output.trim());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InstallException("git clone interrupted for " + url, e);
            }
        } else {
            out.println("dshj plugin add: source already cloned at " + dst + ", reusing");
        }
        out.println("dshj plugin add: cloned " + url + " -> " + dst);
        return writeEntry(profileDir, name, "java:./plugins/src/" + name);
    }

    // ---- 配置写回 ----

    private Installed writeEntry(Path profileDir, String name, String source) throws InstallException {
        return writeEntry(profileDir, name, source, null);
    }

    private Installed writeEntry(Path profileDir, String name, String source, String mainClass) throws InstallException {
        try {
            Path configFile = new ProfileConfigWriter().append(profileDir, new ProfileConfigWriter.Entry(name, source, mainClass));
            return new Installed(name, source, mainClass, null, configFile);
        } catch (IOException e) {
            throw new InstallException("failed to write profile config for '" + name + "': " + e.getMessage(), e);
        }
    }

    // ---- 解析与工具 ----

    /** profile 目录(默认 {@code <profileHome>/<name>});校验名字合法性并创建目录。 */
    Path resolveProfileDir(String profile) throws InstallException {
        if (profile == null || profile.isBlank() || profile.contains("/") || profile.contains("\\")
                || profile.equals(".") || profile.equals("..") || profile.equals("node_modules")) {
            throw new InstallException("invalid profile name '" + profile + "'");
        }
        Path dir = config.profileHome().resolve(profile).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new InstallException("cannot create profile dir " + dir + ": " + e.getMessage(), e);
        }
        return dir;
    }

    /**
     * 路径锚到 cwd(镜像 dsh anchorPathSpec):绝对路径原样,相对路径以 cwd 为基准。
     * 目标不是合法路径(如 Windows 下含非法 {@code :} 的残缺 maven 坐标)→ 安装失败。
     */
    static Path anchor(Path cwd, String target) throws InstallException {
        Path p;
        try {
            p = Path.of(target);
        } catch (java.nio.file.InvalidPathException e) {
            throw new InstallException("invalid spec path '" + target + "': " + e.getMessage());
        }
        if (p.isAbsolute()) return p.normalize();
        return cwd.resolve(p).normalize();
    }

    /** Maven 坐标形态判定:{@code g:a:v},三段且无路径分隔符。 */
    static boolean looksLikeMavenCoords(String target) {
        if (target.indexOf('/') >= 0 || target.indexOf('\\') >= 0) return false;
        String[] parts = target.split(":", -1);
        return parts.length == 3 && !parts[0].isBlank() && !parts[1].isBlank() && !parts[2].isBlank();
    }

    private static String joinUrl(String base, String rel) {
        String b = base.endsWith("/") ? base : base + "/";
        return b + rel;
    }

    /** 下载到临时文件;404/网络失败返回 null 并把诊断写入 failures。 */
    private Path downloadToTemp(String url, List<String> failures) throws InstallException {
        Path temp;
        try {
            temp = Files.createTempFile("dshj-mvn-", ".jar");
        } catch (IOException e) {
            throw new InstallException("cannot create temp file for download: " + e.getMessage(), e);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(temp));
            int code = resp.statusCode();
            if (code == 200) return temp;
            if (code == 404) {
                failures.add(url + " -> 404");
            } else {
                failures.add(url + " -> HTTP " + code);
            }
            Files.deleteIfExists(temp);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstallException("download interrupted for " + url, e);
        } catch (IOException e) {
            failures.add(url + " -> " + e.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /** 建目录(IOException 包装为安装失败)。 */
    private static void createDirs(Path dir) throws InstallException {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new InstallException("cannot create directory " + dir + ": " + e.getMessage(), e);
        }
    }

    /** 复制单文件(父目录已建)。 */
    private static void copyFile(Path from, Path to) throws InstallException {
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallException("failed to copy " + from + " -> " + to + ": " + e.getMessage(), e);
        }
    }

    /** 整目录递归复制。 */
    private static void copyTree(Path from, Path to) throws InstallException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path dst = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new InstallException("failed to copy source dir " + from + " -> " + to + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code git+https://host/a/b.git} → {@code b};含 {@code #ref} 时先剥 ref。
     * 同时识别 Windows 反斜杠路径(本地仓库 {@code git+C:\...\repo} → {@code repo})。
     */
    static String nameFromGitUrl(String url) {
        String u = url;
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf('\\'));
        String last = slash >= 0 ? u.substring(slash + 1) : u;
        if (last.endsWith(".git")) last = last.substring(0, last.length() - ".git".length());
        return last.isBlank() ? "plugin" : last;
    }

    private Path cwd() {
        return config.cwd();
    }
}
