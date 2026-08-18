package dev.dsh.host.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * dshj — dsh-java 命令行入口(m6-design §4 应用层)。
 *
 * <p>命令形状镜像 dsh 启动器:{@code --profile <name> boot} / {@code web|headless|cli} 别名 /
 * {@code plugin --profile <name> add <spec>} / {@code --help} / {@code --version}。M6-4 的
 * boot 是 Java harness 插件树(M6-5/6 才接 dsh 完整 web/headless bundle);{@code plugin add}
 * 是解析校验骨架(M6-7 落地 Java 侧安装)。
 *
 * <p>{@link #run} 供测试直接调用(注入输出流,不 {@code System.exit});{@link #main} 是进程入口。
 */
public final class DshCli {

    static final String VERSION = "0.1.0";

    static final String HELP = """
            dshj — dsh-java command line (mirrors the dsh launcher)

            Usage:
              dshj [--profile <name>] boot [appArgs...]    boot a profile (default profile: web)
              dshj web|headless|cli [boot] [appArgs...]     profile alias (= --profile <name>)
              dshj plugin --profile <name> add <spec>       add a plugin to a profile (M6-7)
              dshj --help | -h                              show this help
              dshj --version | -V                           show version

            Profiles:
              web, headless, cli - shipped aliases; any name under profiles/<name>/cordis.yml
              works via --profile <name> (or $DSH_HOME/profiles/<name> when DSH_HOME is set).
              dshj --profile web boot   is the same as:  dshj web boot

            App arguments after the launcher flags reach the booted profile:
              dshj --profile headless "run the tests"
              dshj web "open the dashboard"

            Web status page (M6-5a): every 'dshj <profile> boot' serves an HTML
              status page at http://127.0.0.1:8080/  (--port <n> overrides the port).
              It shows the loaded plugins, their host, the bridge base, and the startup log.

            Plugins (plugin add <spec>):
              jar:<path|coords>     external Java jar plugin (install: M6-7)
              java:<class|source>   Java source/class plugin (install: M6-7)
              node:<path>           explicit Node-worker JS plugin
              graaljs:<path>        explicit in-process GraalJS plugin
              <npm-package>         dsh JS plugin (e.g. @koishijs/plugin-echo, pnpm install: M6-6)
            """;

    private DshCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    /**
     * 运行一次调用并返回进程退出码(测试可直接调用,不退出 JVM)。
     *
     * @param args 启动器参数(不含程序名)
     * @param out  标准输出
     * @param err  标准错误
     * @return 退出码(0 成功;1 运行失败;2 用法错误)
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        CliInvocation invocation;
        try {
            invocation = CliArgs.parse(args, new String[1]);
        } catch (CliArgs.UsageError ue) {
            err.println("dshj: " + ue.getMessage());
            err.println("Run 'dshj --help' for usage.");
            return 2;
        }
        return dispatch(invocation, out, err);
    }

    private static int dispatch(CliInvocation invocation, PrintStream out, PrintStream err) {
        return switch (invocation) {
            case CliInvocation.Help h -> {
                out.print(HELP);
                yield 0;
            }
            case CliInvocation.Version v -> {
                out.println("dshj " + VERSION);
                yield 0;
            }
            case CliInvocation.Boot b -> runBoot(b.profile(), b.appArgs(), out, err);
            case CliInvocation.Plugin p -> PluginCommand.run(p.profile(), p.args(), out, err);
        };
    }

    /** web HTTP 状态页默认端口(M6-5a;{@code --port <n>} 覆盖)。 */
    static final int DEFAULT_WEB_PORT = 8080;

    /** boot 一个 profile:加载插件树 → 打印概览 → 阻塞运行(Ctrl+C 卸载)。
     *  web profile 额外启动 HTTP 状态页(M6-5a),浏览器打开即可看到 harness/profile/
     *  已加载插件/桥基址/启动日志。 */
    private static int runBoot(String profile, List<String> appArgs, PrintStream out, PrintStream err) {
        ProfileBoot boot = new ProfileBoot();
        try {
            ProfileBoot.Handle handle = boot.bootOnce(profile, out);
            out.println();
            out.println("dshj: profile '" + profile + "' is running on the Java harness. Ctrl+C to stop.");
            // M7-1:HTTP 状态页对任意 profile 生效(不再限定 web)——boot 成功即可在
            // http://127.0.0.1:8080/ 看到该 profile 已加载的插件列表/桥基址/启动日志。
            WebStatusServer server = null;
            {
                int port = parsePort(appArgs);
                try {
                    server = new WebStatusServer(profile, handle, port);
                    server.start();
                    out.println("dshj: web status page at http://127.0.0.1:" + server.port() + "/");
                } catch (IOException e) {
                    out.println("dshj: web status server failed to start on port " + port + ": "
                            + e.getMessage());
                }
            }
            if (!appArgs.isEmpty()) {
                out.println("dshj: app args passed to the profile: " + appArgs);
            }
            WebStatusServer srv = server;
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (srv != null) srv.close();
                } finally {
                    try {
                        handle.close();
                    } finally {
                        latch.countDown();
                    }
                }
            }, "dshj-shutdown"));
            latch.await();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (ProfileBoot.BootException e) {
            err.println("dshj: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 从 boot 的 app 参数里解析 web 状态页端口:{@code --port <n>} / {@code --port=<n>},
     * 非法或缺失回退 {@link #DEFAULT_WEB_PORT}。解析值不拦截 —— app 参数原样交给 profile。
     */
    static int parsePort(List<String> appArgs) {
        if (appArgs == null) return DEFAULT_WEB_PORT;
        for (int i = 0; i < appArgs.size(); i++) {
            String a = appArgs.get(i);
            String value = null;
            if ("--port".equals(a) && i + 1 < appArgs.size()) {
                value = appArgs.get(i + 1);
            } else if (a.startsWith("--port=")) {
                value = a.substring("--port=".length());
            }
            if (value != null) {
                try {
                    int p = Integer.parseInt(value.trim());
                    if (p > 0 && p < 65536) return p;
                } catch (NumberFormatException ignored) {
                    // 非法端口 → 回退默认
                }
            }
        }
        return DEFAULT_WEB_PORT;
    }
}
