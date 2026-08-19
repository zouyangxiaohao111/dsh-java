package dev.dsh.host.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * M10-1 dev 模式子进程管线:Java 核心(宿主)boot 后,由 CLI 起两个 watcher 子进程 ——
 * 与真实 dsh 的 dev 双循环对齐:
 *
 * <ul>
 *   <li><b>tsdown watch</b>(镜像 dsh {@code pnpm run dev:web}):{@code scripts/dev-web.ts --poll}
 *       在 vendor/dsh 下以轮询模式 watch,任何 {@code dsh.client} 插件包源变更 → 重建
 *       {@code lib/client.js};host 侧的 client-hmr 插件(dev patch 层解 pin)stat-poll 该 bundle,
 *       变更经 {@code /plugins/events} SSE 广播 → 浏览器热换插件。</li>
 *   <li><b>vite build --watch</b>(镜像 dsh {@code apps/web} 的 {@code watch} script):前端壳
 *       源码/workspace 包(resolve 到 source)变更 → 重建 {@code apps/web/dist};host 的
 *       frontend-static fallback 每请求读 dist,刷新即生效 —— "host serve 壳" 的 dev 前端循环。
 *       (bare {@code vite serve} 被 apps/web 的 rejectStandaloneServe 拒绝 —— 与真实 dsh
 *       一致,{@code __DSH_BOOT__} 必须由 host 注入,见 vite.config.ts 的 STANDALONE_ERROR。)</li>
 * </ul>
 *
 * <p>两者都是 <b>通用进程机制</b>:Java 核心不感知 watcher 内部,只负责 spawn + 吞输出 +
 * close 时回收;换 watcher 命令/位置 = 改这里的启动参数(或改 profile 的 dev patch 层)。
 *
 * <p>失败纪律:watcher 起不来或中途退出不 kill 宿主 —— 打印警告,dev 模式降级为静态
 * (无热换但 UI 仍可访问)。
 */
public final class DevPipeline implements AutoCloseable {

    private final List<Process> children = new ArrayList<>();
    private final Path repoRoot;

    /** 构造(不 spawn):{@code start()} 才起子进程 —— 测试可构造后直接 close(无子进程,无害)。 */
    public DevPipeline(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    /** 起两个 watcher 子进程(web dev 管线)。repoRoot 为仓库根(cwd)。 */
    public void start() {
        Path vendor = repoRoot.resolve("vendor/dsh");
        spawn("dev-web (tsdown watch, client bundles)",
                List.of("node", "--import", "tsx/esm", "scripts/dev-web.ts", "--poll"), vendor);
        Path webApp = vendor.resolve("apps/web");
        spawn("vite build --watch (frontend shell dist)",
                List.of("node", "node_modules/vite/bin/vite.js", "build", "--watch"), webApp);
    }

    /** 起一个子进程:stderr 并入 stdout,输出逐行转写(watcher 无交互输入)。 */
    private void spawn(String label, List<String> command, Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            children.add(process);
            drain(label, process);
        } catch (IOException e) {
            System.out.println("dshj: [dev] failed to start " + label + ": " + e.getMessage()
                    + " — dev HMR degraded (static serve only)");
        }
    }

    /** 后台守护线程逐行转写子进程输出到宿主 stdout(prefix 标识来源,子进程退出即结束)。 */
    private void drain(String label, Process process) {
        Thread t = new Thread(() -> {
            StringBuilder line = new StringBuilder();
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    for (int i = 0; i < n; i++) {
                        char c = (char) (buf[i] & 0xff);
                        if (c == '\n') {
                            System.out.println("[" + label + "] " + line);
                            line.setLength(0);
                        } else {
                            line.append(c);
                        }
                    }
                }
            } catch (IOException ignored) {
                // 子进程已退出/读失败,结束该转写线程
            }
            if (line.length() > 0) System.out.println("[" + label + "] " + line);
        }, "dshj-dev-" + label);
        t.setDaemon(true);
        t.start();
    }

    /** 回收子进程(Ctrl+C 关机钩子调用):先 destroy 再等待退出。 */
    @Override
    public void close() {
        for (Process p : children) {
            p.destroy();
        }
        for (Process p : children) {
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        children.clear();
    }

    /**
     * 校验 web dev 前置存在(vendor/dsh 子模块 + tsx + apps/web dist + profile junctions);
     * 缺任一 → 打印提示并返回 false(CLI 据此跳过 watcher,诚实降级)。
     */
    public static boolean prereqs(Path repoRoot, PrintStream out) {
        boolean ok = true;
        if (!Files.isDirectory(repoRoot.resolve("vendor/dsh/node_modules"))) {
            out.println("dshj: [dev] vendor/dsh/node_modules missing — run ./setup.sh before dev mode");
            ok = false;
        }
        if (!Files.isRegularFile(repoRoot.resolve("vendor/dsh/apps/web/dist/index.html"))) {
            out.println("dshj: [dev] apps/web dist missing — run ./setup.sh (build:web) before dev mode");
            ok = false;
        }
        if (!Files.isDirectory(repoRoot.resolve("profiles/web/node_modules/@deepseek-ai"))) {
            out.println("dshj: [dev] web profile node_modules junctions missing —"
                    + " run node scripts/link-web-profile.mjs before dev mode");
            ok = false;
        }
        return ok;
    }
}
