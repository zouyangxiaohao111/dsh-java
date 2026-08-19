package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.loader.LoadedPlugin;
import dev.dsh.host.plugin.PluginInstaller;
import dev.dsh.host.plugin.PluginTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * M6-4 应用层 CLI:参数解析(镜像 dsh 命令形状)+ {@code plugin add}(M6-7 真实安装)+ profile boot。
 */
class DshCliTest {

    @TempDir
    Path tmp;

    /** M6-7 CLI 安装测试用:临时 profile 根 + mavenLocal,不写真实仓库 profiles/。 */
    @BeforeEach
    void injectTempInstaller() {
        Path profileHome = tmp.resolve("profiles");
        Path mavenLocal = tmp.resolve("m2/repository");
        PluginCommand.setInstaller(new PluginInstaller(
                new PluginInstaller.Config(profileHome, mavenLocal, List.of(), tmp)));
    }

    @AfterEach
    void resetInstaller() {
        PluginCommand.setInstaller(new PluginInstaller());
    }

    private static CliInvocation parse(String... args) {
        return CliArgs.parse(args, new String[1]);
    }

    private static String[] args(String... values) {
        return values;
    }

    // ---- 参数解析(命令形状镜像 dsh)----

    @Test
    void parseProfileBoot() {
        CliInvocation inv = parse(args("--profile", "web", "boot"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of()));
    }

    @Test
    void parseProfileBootWithAppArgs() {
        CliInvocation inv = parse(args("--profile", "tui", "boot", "--resume", "abc"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("tui", List.of("--resume", "abc")));
    }

    @Test
    void parseProfileEqForm() {
        CliInvocation inv = parse(args("--profile=web", "boot"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of()));
    }

    @Test
    void parseWebAliasBoot() {
        // web 别名 = --profile web
        CliInvocation inv = parse(args("web", "boot"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of()));
    }

    @Test
    void parseWebAliasBare() {
        CliInvocation inv = parse(args("web"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of()));
    }

    @Test
    void parseHeadlessAliasWithAppArgs() {
        CliInvocation inv = parse(args("headless", "run", "the", "tests"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("headless", List.of("run", "the", "tests")));
    }

    @Test
    void parseProfileWithoutSubcommandFeedsAppArgs() {
        // dsh 兼容:dshj --profile headless "run the tests"
        CliInvocation inv = parse(args("--profile", "headless", "run", "the", "tests"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("headless", List.of("run", "the", "tests")));
    }

    @Test
    void parseBootDefaultsWeb() {
        CliInvocation inv = parse(args("boot"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of()));
    }

    // ---- M10-1 dev 模式 --dev(启动器 flag,不传给 app)----

    @Test
    void parseWebAliasDevFlag() {
        // ./dshj web --dev:--dev 是启动器 flag,被消费;appArgs 为空
        CliInvocation inv = parse(args("web", "--dev"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of(), true));
    }

    @Test
    void parseProfileBootDevFlag() {
        CliInvocation inv = parse(args("--profile", "web", "--dev", "boot"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of(), true));
    }

    @Test
    void parseDevFlagEqualsBool() {
        assertThat(parse(args("web", "--dev=true"))).isEqualTo(new CliInvocation.Boot("web", List.of(), true));
        assertThat(parse(args("web", "--dev=false"))).isEqualTo(new CliInvocation.Boot("web", List.of(), false));
    }

    @Test
    void parseDevKeepsAppArgs() {
        // --dev 被启动器消费,之后的 app 参数原样到达 profile
        CliInvocation inv = parse(args("web", "--dev", "--port", "4000"));
        assertThat(inv).isEqualTo(new CliInvocation.Boot("web", List.of("--port", "4000"), true));
    }

    @Test
    void parseDevInvalidValueRejected() {
        assertThatThrownBy(() -> parse(args("web", "--dev=yes")))
                .isInstanceOf(CliArgs.UsageError.class)
                .hasMessageContaining("--dev");
    }

    @Test
    void parseWithoutDevStaysFalse() {
        // 既有形状不变:无 --dev → dev=false
        assertThat(parse(args("web"))).isEqualTo(new CliInvocation.Boot("web", List.of(), false));
        assertThat(parse(args("--profile", "tui", "boot", "arg"))).isEqualTo(
                new CliInvocation.Boot("tui", List.of("arg"), false));
    }

    @Test
    void parsePluginAdd() {
        CliInvocation inv = parse(args("plugin", "--profile", "web", "add", "@koishijs/plugin-echo"));
        assertThat(inv).isEqualTo(new CliInvocation.Plugin("web", List.of("add", "@koishijs/plugin-echo")));
    }

    @Test
    void parsePluginRequiresProfile() {
        assertThatThrownBy(() -> parse(args("plugin", "add", "x")))
                .isInstanceOf(CliArgs.UsageError.class)
                .hasMessageContaining("--profile");
    }

    @Test
    void parseHelpAndVersion() {
        assertThat(parse(args("--help"))).isEqualTo(new CliInvocation.Help());
        assertThat(parse(args("-h"))).isEqualTo(new CliInvocation.Help());
        assertThat(parse(args("--version"))).isEqualTo(new CliInvocation.Version());
        assertThat(parse(args("-V"))).isEqualTo(new CliInvocation.Version());
    }

    @Test
    void parseEmptyIsHelp() {
        assertThat(parse(args())).isEqualTo(new CliInvocation.Help());
    }

    @Test
    void parseUnknownCommand() {
        assertThatThrownBy(() -> parse(args("frobnicate")))
                .isInstanceOf(CliArgs.UsageError.class)
                .hasMessageContaining("unknown command");
    }

    @Test
    void parseAliasConflictsWithProfile() {
        assertThatThrownBy(() -> parse(args("--profile", "tui", "web")))
                .isInstanceOf(CliArgs.UsageError.class)
                .hasMessageContaining("conflicts");
    }

    // ---- run:非阻塞路径(帮助/版本/plugin add 骨架)----

    @Test
    void runHelpPrintsProfiles() {
        String out = runCaptured(args("--help"));
        assertThat(out).contains("web").contains("headless").contains("cli")
                .contains("dshj [--profile <name>] boot")
                .contains("plugin --profile <name> add <spec>");
    }

    @Test
    void runVersion() {
        assertThat(runCaptured(args("--version"))).contains("dshj " + DshCli.VERSION);
    }

    // ---- M6-5a web 状态页端口解析 ----

    @Test
    void parsePortDefaultsTo8080() {
        assertThat(DshCli.parsePort(List.of())).isEqualTo(DshCli.DEFAULT_WEB_PORT);
        assertThat(DshCli.parsePort(null)).isEqualTo(DshCli.DEFAULT_WEB_PORT);
        assertThat(DshCli.parsePort(List.of("open", "the", "dashboard"))).isEqualTo(DshCli.DEFAULT_WEB_PORT);
    }

    @Test
    void parsePortHonorsPortFlag() {
        assertThat(DshCli.parsePort(List.of("--port", "9000"))).isEqualTo(9000);
        assertThat(DshCli.parsePort(List.of("--port=9001"))).isEqualTo(9001);
        assertThat(DshCli.parsePort(List.of("--resume", "abc", "--port", "9100"))).isEqualTo(9100);
    }

    @Test
    void parsePortIgnoresInvalidValues() {
        assertThat(DshCli.parsePort(List.of("--port", "abc"))).isEqualTo(DshCli.DEFAULT_WEB_PORT);
        assertThat(DshCli.parsePort(List.of("--port", "0"))).isEqualTo(DshCli.DEFAULT_WEB_PORT);
        assertThat(DshCli.parsePort(List.of("--port", "-1"))).isEqualTo(DshCli.DEFAULT_WEB_PORT);
        assertThat(DshCli.parsePort(List.of("--port", "70000"))).isEqualTo(DshCli.DEFAULT_WEB_PORT);
        assertThat(DshCli.parsePort(List.of("--port"))).isEqualTo(DshCli.DEFAULT_WEB_PORT);
    }

    @Test
    void runPluginAddJsSidePromptsRealDsh() {
        // JS 侧(node:/graaljs:/裸 npm)不安装:提示走真实 dsh plugin add(pnpm),退出码非零(未安装)。
        Captured c = runCapture(args("plugin", "--profile", "web", "add", "@koishijs/plugin-echo"));
        assertThat(c.exit).isEqualTo(1);
        assertThat(c.out).contains("@koishijs/plugin-echo").contains("web")
                .contains("real dsh plugin add").contains("not installed");
    }

    @Test
    void runPluginAddMissingLocalJarErrors() {
        // Java 侧安装:本地 jar 缺失 → 报错(不写配置),退出码 1
        Captured c = runCapture(args("plugin", "--profile", "web", "add", "jar:./nope-xyz.jar"));
        assertThat(c.exit).isEqualTo(1);
        assertThat(c.err).contains("jar not found");
    }

    @Test
    void runPluginAddInstallsJarEndToEnd() throws Exception {
        // M6-7 CLI 端到端:jar:<maven 坐标> 从临时 mavenLocal 解析 → 复制 → 写 cordis.yml
        Path mavenLocal = tmp.resolve("m2/repository");
        Path jar = PluginTestFixtures.compilePluginJar(tmp.resolve("fix.jar"));
        PluginTestFixtures.seedMavenLocal(mavenLocal, "test.group", "demo-plugin", "0.1.0", jar);

        Captured c = runCapture(args("plugin", "--profile", "web", "add", "jar:test.group:demo-plugin:0.1.0"));
        assertThat(c.exit).isEqualTo(0);
        assertThat(c.out).contains("installed").contains("jar:./plugins/jars/demo-plugin-0.1.0.jar");
        Path copied = tmp.resolve("profiles/web/plugins/jars/demo-plugin-0.1.0.jar");
        assertThat(copied).exists();
        Path yml = tmp.resolve("profiles/web/cordis.yml");
        assertThat(Files.readString(yml)).contains("jar:./plugins/jars/demo-plugin-0.1.0.jar");
    }

    @Test
    void runPluginAddEmptySpecIsUsageError() {
        Captured c = runCapture(args("plugin", "--profile", "web", "add"));
        assertThat(c.exit).isEqualTo(2);
        assertThat(c.err).contains("missing <spec>");
    }

    @Test
    void runPluginUnknownSubcommand() {
        Captured c = runCapture(args("plugin", "--profile", "web", "frobnicate"));
        assertThat(c.exit).isEqualTo(2);
        assertThat(c.err).contains("unknown subcommand");
    }

    @Test
    void runUsageErrorReturnsTwo() {
        Captured c = runCapture(args("plugin", "add", "x"));
        assertThat(c.exit).isEqualTo(2);
        assertThat(c.err).contains("--profile");
    }

    // ---- bootOnce:profile 加载 ----

    @Test
    void bootOnceLoadsJavaProfile() throws Exception {
        Path root = tmp.resolve("profiles");
        Files.createDirectories(root.resolve("web"));
        Files.writeString(root.resolve("web").resolve("cordis.yml"), """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                """);
        ProfileBoot boot = new ProfileBoot(root, tmp);
        try (ProfileBoot.Handle h = boot.bootOnce("web", System.out)) {
            assertThat(h.loaded()).hasSize(1);
            assertThat(h.loaded().get(0).entry().name()).isEqualTo("counter");
            assertThat(h.loaded().get(0).kind()).isEqualTo(HostKind.JAVA);
        }
    }

    @Test
    void bootOnceMixedJavaAndJs() throws Exception {
        Path root = tmp.resolve("profiles");
        Files.createDirectories(root.resolve("demo"));
        Files.writeString(root.resolve("demo").resolve("greeter.js"), """
                module.exports = { name: 'greeter', apply(ctx) { ctx.emit('greeter/up'); } }
                """);
        Files.writeString(root.resolve("demo").resolve("cordis.yml"), """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                  - name: greeter
                    path: ./greeter.js
                """);
        ProfileBoot boot = new ProfileBoot(root, tmp);
        try (ProfileBoot.Handle h = boot.bootOnce("demo", System.out)) {
            assertThat(h.loaded()).hasSize(2);
            assertThat(h.loaded()).extracting(lp -> lp.kind())
                    .containsExactly(HostKind.JAVA, HostKind.GRAAL);
        }
    }

    @Test
    void bootOnceLoadsDshProfileBundles() throws Exception {
        // M6-6:dsh profile(manifest dsh.profile.bundles)→ 组合 bundle patch → loader 加载
        Path root = tmp.resolve("profiles");
        Files.createDirectories(root.resolve("web/node_modules/@test/demo-bundle"));
        Files.writeString(root.resolve("web").resolve("package.json"),
                "{\"name\":\"dsh-profile-web\",\"private\":true,\"dsh\":{\"profile\":{\"bundles\":[\"@test/demo-bundle\"]}}}");
        Files.writeString(root.resolve("web/node_modules/@test/demo-bundle/package.json"),
                "{\"name\":\"@test/demo-bundle\",\"version\":\"0.0.1\",\"dsh\":{\"bundle\":{\"patch\":\"./cordis.patch.yml\"}}}");
        Files.writeString(root.resolve("web/node_modules/@test/demo-bundle/cordis.patch.yml"), """
                - insert:
                    - id: greeter
                      name: './greeter.js'
                """);
        Files.writeString(root.resolve("web").resolve("greeter.js"),
                "module.exports = { name: 'greeter', apply(ctx) { ctx.emit('greeter/up'); } }");

        ProfileBoot boot = new ProfileBoot(root, tmp);
        try (ProfileBoot.Handle h = boot.bootOnce("web", System.out)) {
            assertThat(h.loaded()).hasSize(1);
            assertThat(h.loaded().get(0).entry().name()).isEqualTo("greeter");
            assertThat(h.loaded().get(0).kind()).isEqualTo(HostKind.GRAAL);
        }
    }

    @Test
    void bootOnceMissingProfileThrows() {
        ProfileBoot boot = new ProfileBoot(tmp.resolve("profiles"), tmp);
        assertThatThrownBy(() -> boot.bootOnce("nope", System.out))
                .isInstanceOf(ProfileBoot.BootException.class)
                .hasMessageContaining("not found");
    }

    // ---- 工具 ----

    private static String runCaptured(String... args) {
        return runCapture(args).out;
    }

    private static Captured runCapture(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = DshCli.run(argv,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Captured(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Captured(int exit, String out, String err) {
    }
}
