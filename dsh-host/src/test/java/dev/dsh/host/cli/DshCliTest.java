package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.loader.LoadedPlugin;
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
 * M6-4 应用层 CLI:参数解析(镜像 dsh 命令形状)+ {@code plugin add} 骨架 + profile boot。
 */
class DshCliTest {

    @TempDir
    Path tmp;

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

    @Test
    void runPluginAddPrintsPlan() {
        String out = runCaptured(args("plugin", "--profile", "web", "add", "@koishijs/plugin-echo"));
        assertThat(out).contains("cordis.yml entry").contains("@koishijs/plugin-echo").contains("web");
    }

    @Test
    void runPluginAddValidatesJavaSpec() {
        String out = runCaptured(args("plugin", "--profile", "web", "add", "jar:./plugins/x.jar"));
        assertThat(out).contains("jar:./plugins/x.jar").contains("M6-7");
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
