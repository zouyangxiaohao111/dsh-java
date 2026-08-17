package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 深化 · ① 多 worker 并行(design §2):每个 JS 插件持有独立 {@link NodeWorkerJsHost},
 * 独立插件的 ctx 调用跨 worker 并行、互不阻塞。
 *
 * <p>两个独立 ESM 插件(.mjs → {@link HostKind#NODE})各经
 * {@link PluginRuntimeResolver#loadJs} 加载,断言:
 * <ul>
 *   <li><b>独立 worker</b>:两个插件持有不同的 {@code NodeWorkerJsHost} 实例(不同 Node 进程,
 *       非同一实例);</li>
 *   <li><b>并行</b>:两线程同时触发各自插件,每个 listener 在其 worker 内做一段 busy-wait
 *       同步忙等 —— 若真跨 worker 并行,两个 listener 的活跃窗口重叠,共享并发计数器
 *       {@code maxActive == 2}(并发峰值),总墙钟 ≈ 单次 busy-wait(而非两段之和)。</li>
 * </ul>
 *
 * <p>前置:{@code node} 可执行可用(与其它 Node 融合测试同,{@link NodeEnv#assumeNode})。
 * 测试与 {@code NodeWorkerJsHost} 的 worker 复用无关 —— 两个插件各自新建 worker,进程级
 * 隔离天然并行。
 */
class WorkerParallelismTest {

    /** listener busy-wait 时长(ms):足够大以保证两 worker 活跃窗口重叠。 */
    private static final long BUSY_MS = 400;

    @TempDir
    Path tmp;

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    /**
     * 跨 worker 并发计数器:worker 经 ctx.get 取得 svc 句柄,enter/exit 成对调用,记录
     * 并发峰值。串行(共享单 worker / 单锁)时峰值恒为 1;跨 worker 并行时峰值到 2。
     */
    public static final class ParallelProbe {
        public final AtomicInteger active = new AtomicInteger();
        public final AtomicInteger maxActive = new AtomicInteger();
        public final AtomicInteger completed = new AtomicInteger();

        /** 进入 listener 忙等窗口。 */
        public Object enter(String tag) {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            return null;
        }

        /** 离开 listener 忙等窗口。 */
        public Object exit(String tag) {
            active.decrementAndGet();
            completed.incrementAndGet();
            return null;
        }
    }

    @Test
    void independentPluginsRunInParallelAcrossWorkers() throws Exception {
        Path dir = tmp.resolve("parallel");
        Files.createDirectories(dir);
        Path pluginA = dir.resolve("worker-a.mjs");
        Path pluginB = dir.resolve("worker-b.mjs");
        Files.writeString(pluginA, pluginSource("parallel-a", "goA", "a"));
        Files.writeString(pluginB, pluginSource("parallel-b", "goB", "b"));

        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(pluginA)).isEqualTo(HostKind.NODE);
        assertThat(resolver.detect(pluginB)).isEqualTo(HostKind.NODE);

        Context root = new Context();
        ParallelProbe probe = new ParallelProbe();
        root.provide("parallelProbe", probe);

        ResolvedJsPlugin ra = null;
        ResolvedJsPlugin rb = null;
        try {
            ra = resolver.loadJs(pluginA);
            rb = resolver.loadJs(pluginB);
            assertThat(ra.kind()).isEqualTo(HostKind.NODE);
            assertThat(rb.kind()).isEqualTo(HostKind.NODE);
            // ① 独立 worker:两个插件持有不同的 NodeWorkerJsHost(不同 Node 进程)
            assertThat(ra.host()).isInstanceOf(NodeWorkerJsHost.class);
            assertThat(rb.host()).isInstanceOf(NodeWorkerJsHost.class);
            assertThat(ra.host()).isNotSameAs(rb.host());

            root.plugin(ra.adapter(), null);
            root.plugin(rb.adapter(), null);

            // ② 并行:两线程同时触发各自插件(同一 latch 释放),互不阻塞
            CountDownLatch start = new CountDownLatch(1);
            Thread t1 = new Thread(() -> awaitThenEmit(start, root, "goA"));
            Thread t2 = new Thread(() -> awaitThenEmit(start, root, "goB"));
            long begin = System.nanoTime();
            t1.start();
            t2.start();
            start.countDown();
            t1.join(15_000);
            t2.join(15_000);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

            assertThat(t1.isAlive()).isFalse();
            assertThat(t2.isAlive()).isFalse();
            // 并发峰值 2:两 worker 活跃窗口重叠(串行实现只能到 1)
            assertThat(probe.maxActive.get()).isEqualTo(2);
            assertThat(probe.completed.get()).isEqualTo(2);
            // 时序:并行 ≈ 单次 busy-wait,而非两段之和
            assertThat(elapsedMs).isLessThan(BUSY_MS * 2);
        } finally {
            if (ra != null) ra.close();
            if (rb != null) rb.close();
            root.fiber.dispose().join();
        }
    }

    /** 等 latch 后发事件(触发各自插件的 listener),跨 worker 并行执行。 */
    private static void awaitThenEmit(CountDownLatch start, Context root, String event) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        root.emit(event);
    }

    private static String pluginSource(String name, String event, String tag) {
        return "export const name = '" + name + "'\n"
                + "export function apply(ctx) {\n"
                + "  ctx.on('" + event + "', () => {\n"
                + "    const probe = ctx.get('parallelProbe')\n"
                + "    probe.enter('" + tag + "')\n"
                + "    const t0 = Date.now()\n"
                + "    while (Date.now() - t0 < " + BUSY_MS + ") {}\n"
                + "    probe.exit('" + tag + "')\n"
                + "  })\n"
                + "}\n";
    }
}
