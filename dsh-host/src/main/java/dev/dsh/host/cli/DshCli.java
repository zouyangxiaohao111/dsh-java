package dev.dsh.host.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
              dshj web --dev [appArgs...]                   dev mode: dev patch layer + tsdown/vite watchers (M10-1)
              dshj plugin --profile <name> add <spec>       add a plugin to a profile (M6-7)
              dshj --help | -h                              show this help
              dshj --version | -V                           show version

            Profiles:
              web, headless, cli - shipped aliases; any name under profiles/<name>/cordis.yml
              works via --profile <name> (or $DSH_HOME/profiles/<name> when DSH_HOME is set).
              dshj --profile web boot   is the same as:  dshj web boot

            Dev mode (--dev, launcher flag — not passed to the app):
              boots the profile with profiles/<name>/cordis.patch.dev.yml applied on top of the
              user layer (web profile: re-enables the client-hmr reload chain), then starts the
              tsdown watch (client plugin bundles) and vite build --watch (frontend shell dist).
              Browser: http://127.0.0.1:3080/ — edit a client plugin source → bundle rebuild →
              SSE /plugins/events → browser hot-swaps the plugin.

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
            case CliInvocation.Boot b -> runBoot(b.profile(), b.appArgs(), b.dev(), out, err);
            case CliInvocation.Plugin p -> PluginCommand.run(p.profile(), p.args(), out, err);
        };
    }

    /** web HTTP 状态页默认端口(M6-5a;{@code --port <n>} 覆盖)。 */
    static final int DEFAULT_WEB_PORT = 8080;

    /** boot 一个 profile:加载插件树 → 打印概览 → 阻塞运行(Ctrl+C 卸载)。
     *  <p>M6-5a 起:非 webServer profile boot 后启动 HTTP 状态页,浏览器打开即可看到
     *  harness/profile/已加载插件/桥基址/启动日志。
     *  <p>M8 起:webServer 服务存在(真实 dsh web profile,经 dsh 插件 webserver 宿主)
     *  → 不启动状态页(避免 shadow 前端),打印 dsh webserver 的真实 URL —— 浏览器打开的
     *  是真实 dsh agent UI(前端 dist 由 web-runtime 的 frontend-static fallback 提供)。 */
    private static int runBoot(String profile, List<String> appArgs, boolean dev, PrintStream out, PrintStream err) {
        ProfileBoot boot = new ProfileBoot();
        try {
            ProfileBoot.Handle handle = dev
                    ? boot.bootOnceDev(profile, appArgs, out)
                    : boot.bootOnce(profile, appArgs, out);
            // M11:懒加载后台线程完成后才提示 URL —— 否则用户打开 UI 时 web 核心服务
            // (api-gateway 的 apiProxy 依赖 deferred workspace/storageDomain/directoryPicker)
            // 尚未注册 → /api 404 → UI 渲染但无法交互(工作区选择器 inert)。settle 阻塞
            // 等待后台全部加载;端口此时已绑(priority webserver),浏览器可见壳但 URL 打印
            // 才意味着功能就绪。后台耗时主要来自 88 个 deferred 的顺序 registerAll(apply)。
            handle.loader().settle();
            out.println();
            out.println("dshj: profile '" + profile + "' is running on the Java harness. Ctrl+C to stop.");
            if (dev) {
                out.println("dshj: dev mode ON (--dev): dev patch layer applied"
                        + (profile.equals("web") ? " + tsdown/vite watchers started" : "")
                        + ".");
            }
            if (!appArgs.isEmpty()) {
                out.println("dshj: app args passed to the profile: " + appArgs);
            }
            WebStatusServer srv = null;
            // M8:真实 dsh webserver 插件已宿主(ctx.webServer 服务在)——跳过 Java 状态页,
            // 打印真实 UI 的 URL。URL 端口读 webServer 服务的 port getter(跨桥 live 句柄),
            // 读失败回退 dsh 默认 3080 / --port。
            if (hasService(handle, "webServer")) {
                int port = webServerPort(handle, parsePort(appArgs));
                out.println("dshj: dsh web UI at http://127.0.0.1:" + port + "/");
            } else {
                // M6-5a:状态页对任意 profile 生效(不再限定 web)——boot 成功即可在
                // http://127.0.0.1:8080/ 看到该 profile 已加载的插件列表/桥基址/启动日志。
                int port = parsePort(appArgs);
                try {
                    srv = new WebStatusServer(profile, handle, port);
                    srv.start();
                    out.println("dshj: web status page at http://127.0.0.1:" + srv.port() + "/");
                } catch (IOException e) {
                    out.println("dshj: web status server failed to start on port " + port + ": "
                            + e.getMessage());
                }
            }
            WebStatusServer server = srv;
            // M10-1:dev 模式(web profile)起 tsdown/vite watcher 子进程 —— 通用进程机制,
            // 关宿主时一并回收。前置缺 → 诚实降级(打印提示,仍可访问静态 UI)。
            DevPipeline devPipeline = null;
            if (dev && "web".equals(profile)) {
                Path repoRoot = ProfileBoot.defaultRepoRoot();
                if (DevPipeline.prereqs(repoRoot, out)) {
                    devPipeline = new DevPipeline(repoRoot);
                    devPipeline.start();
                    out.println("dshj: [dev] tsdown watch (client bundles) + vite build --watch"
                            + " (frontend shell dist) started — edit sources to see HMR/refresh.");
                } else {
                    out.println("dshj: [dev] watcher prerequisites missing — dev mode degraded"
                            + " (host serves static dist, no HMR chain).");
                }
            }
            DevPipeline pipeline = devPipeline;
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (pipeline != null) pipeline.close();
                } finally {
                    try {
                        if (server != null) server.close();
                    } finally {
                        try {
                            handle.close();
                        } finally {
                            latch.countDown();
                        }
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

    /** ctx 是否有某服务(经 getService 非 NO_SERVICE 判定;JS 桥语义下缺服务 → undefined)。 */
    private static boolean hasService(ProfileBoot.Handle handle, String name) {
        return handle.ctx().getService(name) != dev.dsh.cordis.Context.NO_SERVICE;
    }

    /**
     * 读 webServer 服务当前监听端口:getService 返回 worker 提供的 live 句柄(Map 门面,
     * M7-8),成员 {@code port} 是 getter → Map.get("port") 触发 invokeGet 跨桥读值。
     * 读失败(句柄类型不符/超时)回退 {@code fallback}(--port 或 dsh 默认 3080)。
     */
    @SuppressWarnings("unchecked")
    private static int webServerPort(ProfileBoot.Handle handle, int fallback) {
        try {
            Object svc = handle.ctx().getService("webServer");
            if (svc instanceof Map<?, ?> m && m.get("port") instanceof Number n) {
                int p = n.intValue();
                if (p > 0 && p < 65536) return p;
            }
        } catch (Throwable ignored) {
            // 跨桥读失败 → 回退
        }
        return fallback > 0 ? fallback : 3080;
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
