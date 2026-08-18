package dev.dsh.host.plugin;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.PluginRuntimeResolver;
import dev.dsh.cordis.loader.DshProfileReader;
import dev.dsh.cordis.loader.Entry;
import dev.dsh.cordis.loader.LoadedPlugin;
import dev.dsh.cordis.loader.PluginLoaderService;
import dev.dsh.cordis.reload.UrlPluginClassLoaderFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-7 的 Java 侧安装器:jar 从 MavenLocal / 本地路径,源码从本地目录 / 单文件 / 类引用 /
 * git clone;配置写回 cordis.yml 或 dsh profile 的 cordis.patch.yml;断言写入的配置可被
 * loader / DshProfileReader 读回并加载。
 */
class PluginInstallerTest {

    @TempDir
    Path tmp;

    private final PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    private PluginInstaller installer() {
        return new PluginInstaller(new PluginInstaller.Config(tmp.resolve("profiles"),
                tmp.resolve("m2/repository"), List.of(), tmp));
    }

    // ---- jar 形式 ----

    @Test
    void installJarFromMavenLocalCopiesAndIsLoadable() throws Exception {
        Path mavenLocal = tmp.resolve("m2/repository");
        Path jar = PluginTestFixtures.compilePluginJar(tmp.resolve("build/fix.jar"));
        PluginTestFixtures.seedMavenLocal(mavenLocal, "test.group", "demo-plugin", "0.1.0", jar);

        PluginInstaller.Installed r = installer().install("web", "jar:test.group:demo-plugin:0.1.0", out, err);

        Path copied = tmp.resolve("profiles/web/plugins/jars/demo-plugin-0.1.0.jar");
        assertThat(copied).exists();
        assertThat(r.name()).isEqualTo("demo-plugin");
        assertThat(r.source()).isEqualTo("jar:./plugins/jars/demo-plugin-0.1.0.jar");

        Path yml = tmp.resolve("profiles/web/cordis.yml");
        assertThat(r.configFile()).isEqualTo(yml);
        assertThat(read(yml)).contains("jar:./plugins/jars/demo-plugin-0.1.0.jar");

        // 写入的配置可被 loader 加载(jar 源类型)
        assertThat(loadNames(yml)).containsExactly("jar-plugin");
    }

    @Test
    void installJarFromLocalPathCopies() throws Exception {
        Path jar = PluginTestFixtures.compilePluginJar(tmp.resolve("plugins/hello.jar"));
        // cwd = tmp,spec 相对路径锚到 cwd(镜像 dsh anchorPathSpec)
        PluginInstaller.Installed r = installer().install("web", "jar:./plugins/hello.jar", out, err);

        assertThat(tmp.resolve("profiles/web/plugins/jars/hello.jar")).exists();
        assertThat(r.name()).isEqualTo("hello");
        assertThat(r.source()).isEqualTo("jar:./plugins/jars/hello.jar");
    }

    // ---- java: 源码形式 ----

    @Test
    void installSourceDirectoryCopiesTreeAndIsLoadable() throws Exception {
        Path srcRoot = PluginTestFixtures.writeSourceDir(tmp.resolve("my-plugin"));

        PluginInstaller.Installed r = installer().install("web", "java:" + srcRoot, out, err);

        Path dst = tmp.resolve("profiles/web/plugins/src/my-plugin");
        assertThat(dst.resolve("src/com/acme/src/SrcPlugin.java")).exists();
        assertThat(r.name()).isEqualTo("my-plugin");
        assertThat(r.source()).isEqualTo("java:./plugins/src/my-plugin");

        Path yml = tmp.resolve("profiles/web/cordis.yml");
        assertThat(read(yml)).contains("java:./plugins/src/my-plugin");

        // 目录源:loader 整目录编译 + 类发现
        assertThat(loadNames(yml)).containsExactly("src-plugin");
    }

    @Test
    void installSingleSourceFileCopies() throws Exception {
        Path file = PluginTestFixtures.writeSingleSourceFile(tmp.resolve("src/SrcPlugin.java"));

        PluginInstaller.Installed r = installer().install("web", "java:" + file, out, err);

        assertThat(tmp.resolve("profiles/web/plugins/src/SrcPlugin.java")).exists();
        assertThat(r.name()).isEqualTo("SrcPlugin");
        assertThat(r.source()).isEqualTo("java:./plugins/src/SrcPlugin.java");

        Path yml = tmp.resolve("profiles/web/cordis.yml");
        assertThat(loadNames(yml)).containsExactly("src-plugin");
    }

    @Test
    void installClassReferenceWritesEntryOnly() throws Exception {
        // java:<类名> 引用 harness classpath 已有类,只写条目不落盘
        PluginInstaller.Installed r = installer().install("web", "java:dev.dsh.demo.CounterPlugin", out, err);

        assertThat(r.source()).isEqualTo("java:dev.dsh.demo.CounterPlugin");
        assertThat(r.name()).isEqualTo("CounterPlugin");
        assertThat(tmp.resolve("profiles/web/plugins/src")).doesNotExist();

        Path yml = tmp.resolve("profiles/web/cordis.yml");
        assertThat(loadNames(yml)).containsExactly("counter");
    }

    // ---- dsh profile 写 cordis.patch.yml ----

    @Test
    void installIntoDshProfileWritesUserPatchLayer() throws Exception {
        Path profileDir = tmp.resolve("profiles/web");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("package.json"),
                "{\"name\":\"web\",\"private\":true,\"dsh\":{\"profile\":{\"bundles\":[]}}}");
        Path mavenLocal = tmp.resolve("m2/repository");
        Path jar = PluginTestFixtures.compilePluginJar(tmp.resolve("build/fix.jar"));
        PluginTestFixtures.seedMavenLocal(mavenLocal, "test.group", "demo-plugin", "0.1.0", jar);

        PluginInstaller.Installed r = installer().install("web", "jar:test.group:demo-plugin:0.1.0", out, err);

        Path patch = profileDir.resolve("cordis.patch.yml");
        assertThat(r.configFile()).isEqualTo(patch);
        assertThat(read(patch)).contains("jar:./plugins/jars/demo-plugin-0.1.0.jar");

        // DshProfileReader 组合:用户 patch 层 → Entry.source 带 jar: 前缀
        DshProfileReader reader = new DshProfileReader();
        DshProfileReader.Profile profile = reader.read(profileDir, null);
        List<Entry> entries = reader.composeEntries(profile);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).source()).isEqualTo("jar:./plugins/jars/demo-plugin-0.1.0.jar");
        assertThat(entries.get(0).name()).isEqualTo("demo-plugin");
    }

    @Test
    void appendSecondPluginPreservesExistingEntry() throws Exception {
        // jar(提供 jar-greet)+ 源码目录(提供 src-greet)→ 两条互不冲突的条目共存
        Path mavenLocal = tmp.resolve("m2/repository");
        Path jar = PluginTestFixtures.compilePluginJar(tmp.resolve("build/fix.jar"));
        PluginTestFixtures.seedMavenLocal(mavenLocal, "test.group", "demo-plugin", "0.1.0", jar);
        installer().install("web", "jar:test.group:demo-plugin:0.1.0", out, err);
        Path srcRoot = PluginTestFixtures.writeSourceDir(tmp.resolve("greet"));
        installer().install("web", "java:" + srcRoot, out, err);

        Path yml = tmp.resolve("profiles/web/cordis.yml");
        assertThat(read(yml)).contains("jar:./plugins/jars/demo-plugin-0.1.0.jar")
                .contains("java:./plugins/src/greet");
        assertThat(loadNames(yml)).containsExactly("jar-plugin", "src-plugin");
    }

    // ---- git 源 ----

    @Test
    void installGitPlusClonesLocalRepo() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "skipped: no git on PATH");
        Path repo = tmp.resolve("repo");
        PluginTestFixtures.writeSourceDir(repo.resolve("hello"));
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");

        PluginInstaller.Installed r = installer().install("web", "java:git+" + repo.toAbsolutePath(), out, err);

        assertThat(tmp.resolve("profiles/web/plugins/src/repo/hello/src/com/acme/src/SrcPlugin.java")).exists();
        assertThat(r.name()).isEqualTo("repo");
        assertThat(r.source()).isEqualTo("java:./plugins/src/repo");
    }

    @Test
    void invalidGithubSpecFails() throws Exception {
        assertThatThrownBy(() -> installer().install("web", "java:github:noslash", out, err))
                .isInstanceOf(PluginInstaller.InstallException.class)
                .hasMessageContaining("github");
    }

    /**
     * M7-2 全链路:自包含插件 git 仓库 → 真实 git clone 路径安装
     * ({@code java:git+<url>} 与公开 {@code github:u/r} / {@code git+https://...} 共用
     * 同一 {@code gitCloneTo})→ clone 落地 → loader 编译 → 类发现 → 插件进 registry →
     * 配置写回可复用(全新 loader 重载同 cordis.yml 仍加载)。
     */
    @Test
    void installGitSourceClonesCompilesAndLoadsIntoRegistry() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "skipped: no git on PATH");
        // ① 自包含插件 git 仓库(git init + commit,核心 provided 经测试运行时 classpath)
        Path repo = tmp.resolve("greet-repo");
        PluginTestFixtures.writeGitPluginRepo(repo);
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");

        // ② 真实 git clone 进程路径安装
        PluginInstaller.Installed r = installer().install("web", "java:git+" + repo.toAbsolutePath(), out, err);

        // ③ 断言:clone 落地(plugins/src/<name>/ 下是克隆出的仓库内容)+ 配置写回
        Path dst = tmp.resolve("profiles/web/plugins/src/greet-repo");
        assertThat(dst.resolve("src/main/java/dev/acme/greeter/GreetPlugin.java")).exists();
        assertThat(r.name()).isEqualTo("greet-repo");
        assertThat(r.source()).isEqualTo("java:./plugins/src/greet-repo");
        Path yml = tmp.resolve("profiles/web/cordis.yml");
        assertThat(read(yml)).contains("java:./plugins/src/greet-repo");

        // ④ 断言:编译产物 + 插件加载进 registry + 配置写入可复用(注入输出目录可断言产物)
        Context root = new Context();
        Path outDir = tmp.resolve("out");
        PluginLoaderService loader = new PluginLoaderService(root, new PluginRuntimeResolver(),
                new UrlPluginClassLoaderFactory(), outDir);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(1);
            LoadedPlugin lp = loaded.get(0);
            assertThat(lp.kind()).isEqualTo(HostKind.JAVA);
            assertThat(lp.plugin().name()).isEqualTo("greet-plugin");
            assertThat(lp.ref()).contains("greet-repo");
            // 编译产物落在输出目录 <name>.classes/
            assertThat(outDir.resolve("greet-repo.classes/dev/acme/greeter/GreetPlugin.class")).exists();
            // 插件 apply 提供的服务经 registry 可及
            assertThat(gitGreet(root)).isEqualTo("hi from git");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void githubUrlConstructsPublicCloneUrl() {
        // 公开 github:u/r 与本地 git 源共用 gitCloneTo;这里只断言 URL 构造
        assertThat(PluginInstaller.githubUrl("deepseek-harness", "dsh-java"))
                .isEqualTo("https://github.com/deepseek-harness/dsh-java.git");
    }

    // ---- 失败与边界 ----

    @Test
    void missingLocalJarFails() throws Exception {
        assertThatThrownBy(() -> installer().install("web", "jar:./nope.jar", out, err))
                .isInstanceOf(PluginInstaller.InstallException.class)
                .hasMessageContaining("jar not found");
    }

    @Test
    void invalidMavenCoordsFails() throws Exception {
        assertThatThrownBy(() -> installer().install("web", "jar:dev.dsh:demo", out, err))
                .isInstanceOf(PluginInstaller.InstallException.class)
                .hasMessageContaining("maven coordinates");
    }

    @Test
    void unresolvableMavenArtifactFails() throws Exception {
        assertThatThrownBy(() -> installer().install("web", "jar:test.group:missing:9.9.9", out, err))
                .isInstanceOf(PluginInstaller.InstallException.class)
                .hasMessageContaining("cannot resolve maven artifact");
    }

    @Test
    void jsSideSpecPromptsRealDsh() throws Exception {
        assertThatThrownBy(() -> installer().install("web", "@koishijs/plugin-echo", out, err))
                .isInstanceOf(PluginInstaller.InstallException.class)
                .hasMessageContaining("real dsh plugin add");
        assertThatThrownBy(() -> installer().install("web", "graaljs:./plugins/greeter", out, err))
                .isInstanceOf(PluginInstaller.InstallException.class)
                .hasMessageContaining("real dsh plugin add");
    }

    @Test
    void invalidProfileNameFails() throws Exception {
        assertThatThrownBy(() -> installer().install("../evil", "java:dev.dsh.demo.CounterPlugin", out, err))
                .isInstanceOf(PluginInstaller.InstallException.class)
                .hasMessageContaining("invalid profile name");
    }

    // ---- 单元:锚定与命名 ----

    @Test
    void anchorResolvesRelativeToCwd() throws Exception {
        Path cwd = Path.of("/work");
        assertThat(PluginInstaller.anchor(cwd, "./x.jar")).isEqualTo(Path.of("/work/x.jar"));
        assertThat(PluginInstaller.anchor(cwd, "../y")).isEqualTo(Path.of("/y"));
        assertThat(PluginInstaller.anchor(cwd, "/abs/z.jar")).isEqualTo(Path.of("/abs/z.jar"));
    }

    @Test
    void nameFromGitUrlStripsPathAndSuffix() {
        assertThat(PluginInstaller.nameFromGitUrl("https://github.com/a/b.git")).isEqualTo("b");
        assertThat(PluginInstaller.nameFromGitUrl("https://github.com/a/b.git#v1")).isEqualTo("b");
        assertThat(PluginInstaller.nameFromGitUrl("git@github.com:o/r.git")).isEqualTo("r");
    }

    @Test
    void looksLikeMavenCoords() {
        assertThat(PluginInstaller.looksLikeMavenCoords("dev.dsh:demo:0.1.0")).isTrue();
        assertThat(PluginInstaller.looksLikeMavenCoords("./plugins/x.jar")).isFalse();
        assertThat(PluginInstaller.looksLikeMavenCoords("dev.dsh:demo")).isFalse();
    }

    // ---- 工具 ----

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    /** 用 ProfileBoot 同路径加载 cordis.yml,返回加载的插件名列表。 */
    private static List<String> loadNames(Path yml) throws Exception {
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).allSatisfy(lp -> assertThat(lp.kind()).isEqualTo(HostKind.JAVA));
            return loaded.stream().map(lp -> lp.plugin().name()).toList();
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    /** 经 registry 取 git 插件的 'git-greet' 服务并调用 greet()(反射,类在隔离 ClassLoader)。 */
    private static String gitGreet(Context root) {
        Object svc = root.get("git-greet");
        if (svc == null) return null;
        try {
            return (String) svc.getClass().getMethod("greet").invoke(svc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        Process p = new ProcessBuilder(command(dir, args)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
    }

    private static List<String> command(Path dir, String... args) {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(List.of(args));
        return cmd;
    }
}
