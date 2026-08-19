package dev.dsh.host.cli;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.JsHostFactory;
import dev.dsh.cordis.js.PluginRuntimeResolver;
import dev.dsh.cordis.loader.DshProfileReader;
import dev.dsh.cordis.loader.Entry;
import dev.dsh.cordis.loader.LoadedPlugin;
import dev.dsh.cordis.loader.PluginLoaderService;
import dev.dsh.cordis.reload.UrlPluginClassLoaderFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用层 profile boot(m6-design §4):解析 profile 目录 → 经 {@link PluginLoaderService}
 * 加载 cordis.yml → 注册进 root Context → 持有句柄供 CLI 阻塞运行。
 *
 * <p>profile 解析镜像 dsh:{@code $DSH_HOME/profiles/<name>}({@code DSH_HOME} 未设时回退
 * {@code <repo>/profiles})。{@code vendor/dsh} 子模块 node_modules 与 profile 自身 node_modules
 * 作为 Node worker 的裸模块解析基址(bareModuleBaseUrl 等价物,见 dsh-js-host NodeWorkerJsHost
 * 的 {@code moduleBases} seam)。M6-4 只启动 Java harness 插件树(dsh 完整 web/headless bundle
 * 是 M6-5/6)。
 */
public final class ProfileBoot {

    /** boot 失败(profile 不存在等),run 侧打印并返回 1。 */
    public static final class BootException extends RuntimeException {
        public BootException(String message) {
            super(message);
        }
    }

    /**
     * 一次成功 boot 的句柄:root Context + loader + 已加载插件,close 时 dispose。
     * 对 Java harness 而言 root fiber 是整棵插件树的宿主,dispose 即卸载全部。
     *
     * <p>{@code moduleBases} 为 Node 裸模块解析基址(桥基址,如 vendor/dsh/node_modules +
     * profile/node_modules);{@code startupLog} 为 boot 过程逐行日志(供 web 状态页展示
     * 启动日志片段,M6-5a)。两者都是 live 数据源——web 页面每次请求实时读取。
     */
    public record Handle(Context ctx, PluginLoaderService loader, List<LoadedPlugin> loaded, Path yml,
                         List<Path> moduleBases, List<String> startupLog)
            implements AutoCloseable {

        @Override
        public void close() {
            try {
                loader.dispose();
            } finally {
                ctx.fiber.dispose().join();
            }
        }
    }

    private final Path profilesRoot;
    private final Path repoRoot;

    public ProfileBoot() {
        this(defaultProfilesRoot(), defaultRepoRoot());
    }

    /** 测试 seam:显式指定 profile 根与仓库根(不依赖 user.dir / DSH_HOME)。 */
    public ProfileBoot(Path profilesRoot, Path repoRoot) {
        this.profilesRoot = profilesRoot.toAbsolutePath().normalize();
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    public Path profilesRoot() {
        return profilesRoot;
    }

    /**
     * 解析 profile 根:DSH_HOME(未设回退 repo/profiles),镜像 dsh 的 {@code resolveDshHome}。
     * M6-7 安装器复用同一解析,保证 {@code plugin add} 写的位置与 {@code boot} 读的一致。
     */
    public static Path defaultProfilesRoot() {
        String home = System.getenv("DSH_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home).resolve("profiles");
        }
        return defaultRepoRoot().resolve("profiles");
    }

    /** CLI 经 gradle run 以仓库根为工作目录(user.dir = 仓库根,见 dsh-host build)。 */
    static Path defaultRepoRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    /**
     * 加载一个 profile 的插件树(不阻塞;调用方持有句柄并决定运行/关闭)。
     *
     * @param profile profile 名(web/headless/cli 或任意 {@code profiles/<name>})
     * @param out     进度输出流
     * @return boot 句柄
     * @throws BootException profile 目录/配置不存在
     */
    public Handle bootOnce(String profile, PrintStream out) {
        if (profile == null || profile.isBlank()) {
            throw new BootException("--profile needs a name");
        }
        Path profileDir = profilesRoot.resolve(profile);
        Path yml = profileDir.resolve("cordis.yml");
        if (!Files.isRegularFile(yml) && !DshProfileReader.isDshProfile(profileDir)) {
            throw new BootException("profile '" + profile + "' not found under " + profilesRoot
                    + " (create " + profileDir + "/cordis.yml, a dsh profile package.json with"
                    + " dsh.profile.bundles, or set DSH_HOME)");
        }
        // 裸模块解析基址(seam):vendor/dsh 子模块 node_modules + profile node_modules
        List<Path> bases = new ArrayList<>();
        addIfDirectory(bases, repoRoot.resolve("vendor/dsh/node_modules"));
        addIfDirectory(bases, profileDir.resolve("node_modules"));

        // 启动日志逐行捕获(web 状态页的"启动日志片段"数据源);同时原样写向 out。
        List<String> startupLog = new ArrayList<>();
        PrintStream logOut = tee(out, startupLog);

        Context root = new Context();
        // M7-8 C:ctx.baseUrl —— hmr 的 new URL(config.base || '.', ctx.baseUrl) 需要合法绝对
        // URL。核心默认 null(仅测试设置),真实 boot 未设 → hmr apply "Invalid URL"。以 profile
        // 目录的 file:// URL 作为 baseUrl(镜像真实 dsh:ctx.baseUrl = 插件加载位置)。
        root.baseUrl = profileDir.toUri().toString();
        PluginLoaderService loader = new PluginLoaderService(root,
                new PluginRuntimeResolver(new JsHostFactory(bases)),
                new UrlPluginClassLoaderFactory(), outputDir(), new JsHostFactory(bases));
        try {
            List<LoadedPlugin> loaded;
            if (Files.isRegularFile(yml)) {
                // M6-4 起始形状:cordis.yml(Java harness 插件树)
                loaded = loader.load(yml);
                logOut.println("dshj: profile '" + profile + "' booted (" + yml + "):");
            } else {
                // M6-6 dsh profile:读 manifest → 组合 bundle patch 层 → entries → loader 加载。
                // bundle 第一 anchor = vendor/dsh 安装(package.json);缺则仅 profile 自身。
                Path installAnchor = repoRoot.resolve("vendor/dsh/package.json");
                List<Entry> entries = new DshProfileReader().load(profileDir, installAnchor);
                loaded = loader.loadEntries(entries, profileDir);
                logOut.println("dshj: profile '" + profile + "' booted (dsh profile " + profileDir + "):");
            }
            for (LoadedPlugin lp : loaded) {
                logOut.println("  - " + lp.entry().name() + "  [" + lp.kind() + "]  " + lp.ref());
            }
            if (!bases.isEmpty()) {
                logOut.println("dshj: node module bases: " + bases);
            }
            logOut.flush();
            return new Handle(root, loader, loaded, yml, List.copyOf(bases), List.copyOf(startupLog));
        } catch (Exception e) {
            try {
                loader.dispose();
            } finally {
                root.fiber.dispose().join();
            }
            throw new BootException("boot failed for profile '" + profile + "': " + e.getMessage());
        }
    }

    private static void addIfDirectory(List<Path> out, Path p) {
        if (Files.isDirectory(p)) out.add(p.toAbsolutePath().normalize());
    }

    /**
     * 返回一个同时写向 {@code out} 与逐行缓冲 {@code lines} 的 PrintStream:boot 日志
     * 既实时打印,又被捕获为 web 状态页的启动日志片段数据。{@code lines} 以 '\n' 切分
     * (Windows println 的 "\r\n" 尾 '\r' 被剥离),空行忽略。
     */
    private static PrintStream tee(PrintStream out, List<String> lines) {
        return new PrintStream(new OutputStream() {
            private final StringBuilder pending = new StringBuilder();

            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                out.write(b, off, len);
                out.flush();
                for (int i = off; i < off + len; i++) {
                    char c = (char) (b[i] & 0xff);
                    if (c == '\n') {
                        flushLine();
                    } else {
                        pending.append(c);
                    }
                }
            }

            private void flushLine() {
                String line = pending.toString();
                pending.setLength(0);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (!line.isEmpty()) lines.add(line);
            }
        }, true);
    }

    private static Path outputDir() {
        try {
            return Files.createTempDirectory("dshj-out");
        } catch (IOException e) {
            throw new BootException("cannot create plugin output dir: " + e.getMessage());
        }
    }
}
