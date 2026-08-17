package dev.dsh.cordis.js;

import com.sun.net.httpserver.HttpServer;
import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 深化 · ② Async worker 事件循环(macrotask 支持,design §3):进程外 Node worker 从
 * 同步阻塞宿主升级为 async 事件循环 —— apply/invokeFn 的 await 在宿主内真跑(macrotask
 * 可 settle),同步 ctx 访问经 sync-over-async 泵保持 cordis 语义,泵期间重入消息入队不
 * 内联执行,单 worker 内允许多在途(虚拟线程驱动)。
 *
 * <p>覆盖(design §5 验证):
 * <ul>
 *   <li><b>async 插件</b>:apply 内 {@code await setTimeout} 真跑(不再抛 macrotask
 *       unsupported);listener 内 await 也真跑;</li>
 *   <li><b>同步 ctx.get 仍工作</b>:async apply 里同步 {@code ctx.get} + 服务调用仍同步
 *       返回(兼容 cordis 同步语义);</li>
 *   <li><b>重入安全</b>:同步泵期间 Java 并发发来的重入 invokeFn 入队,调用返回后才执行
 *       (监听器不在等待中途触发)—— 断言顺序 after-emit → inner-listener;</li>
 *   <li><b>真 I/O(fetch mock)</b>:worker 内 {@code await fetch} 打本机 HTTP mock 真跑
 *       (宿主内网络 I/O,替代子进程 runner);</li>
 *   <li><b>单 worker 多在途</b>:虚拟线程并发触发同一 worker 的两个 listener,断言并发
 *       峰值到 2(单个异步 worker 内多个在途,互不阻塞)。</li>
 * </ul>
 *
 * <p>前置:{@code node} 可执行可用(与其它 Node 融合测试同,{@link NodeEnv#assumeNode})。
 */
class AsyncWorkerEventLoopTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private Path writePlugin(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    // ---- ① async 插件:await setTimeout 在宿主内真跑 ----

    @Test
    void asyncPluginAwaitsSetTimeoutInsideHost() throws Exception {
        Path plugin = writePlugin("async-timer.cjs", """
                module.exports = {
                  name: 'async-timer',
                  apply: async (ctx, config) => {
                    await new Promise((r) => setTimeout(r, 50));
                    ctx.emit('applied-after-timer', 'applied');
                    ctx.on('fire', async () => {
                      await new Promise((r) => setTimeout(r, 30));
                      ctx.emit('listener-after-timer', 'fired');
                    });
                  }
                }
                """);
        Context root = new Context();
        AtomicReference<String> applied = new AtomicReference<>();
        AtomicReference<String> fired = new AtomicReference<>();
        root.on("applied-after-timer", (c, args) -> { applied.set(String.valueOf(args[0])); return null; });
        root.on("listener-after-timer", (c, args) -> { fired.set(String.valueOf(args[0])); return null; });

        try (JsHost host = new NodeWorkerJsHost()) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin));
            // root.plugin 阻塞到 async apply 完成(含 50ms setTimeout 的 await)
            root.plugin(adapter, null);
            assertThat(applied.get()).isEqualTo("applied");
            assertThat(fired.get()).isNull();
            // listener 是 async:root.emit 阻塞到 await 完成,listener-after-timer 收到
            root.emit("fire");
            assertThat(fired.get()).isEqualTo("fired");
        }
        root.fiber.dispose().join();
    }

    // ---- ② 同步 ctx.get 在 async apply 内仍同步返回 ----

    public static final class Probe {
        public final AtomicInteger value = new AtomicInteger(41);
        public int value() { return value.incrementAndGet(); }
    }

    @Test
    void syncCtxGetStillWorksInsideAsyncApply() throws Exception {
        Path plugin = writePlugin("sync-get.cjs", """
                module.exports = {
                  name: 'sync-get',
                  apply: async (ctx, config) => {
                    const probe = ctx.get('probe');        // 同步 ctx.get(cordis 语义)
                    const v = probe.value();                // 同步服务调用
                    await new Promise((r) => setTimeout(r, 10));  // 之后 await macrotask 仍真跑
                    ctx.emit('done', 'v=' + v);
                  }
                }
                """);
        Context root = new Context();
        AtomicReference<String> done = new AtomicReference<>();
        root.on("done", (c, args) -> { done.set(String.valueOf(args[0])); return null; });
        root.provide("probe", new Probe());

        try (JsHost host = new NodeWorkerJsHost()) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin));
            root.plugin(adapter, null);
            assertThat(done.get()).isEqualTo("v=42");
        }
        root.fiber.dispose().join();
    }

    // ---- ③ 重入安全:泵期间重入消息入队,不中途触发 ----

    /** 顺序探针:worker 经 ctx.get 取得服务句柄,record 按调用顺序追加。 */
    public static final class OrderProbe {
        private final List<String> order = new CopyOnWriteArrayList<>();
        public void record(String tag) { order.add(tag); }
        public List<String> snapshot() { return List.copyOf(order); }
    }

    @Test
    void reentrantMessagesDeferredDuringSyncPump() throws Exception {
        Path plugin = writePlugin("reentrant.cjs", """
                module.exports = {
                  name: 'reentrant',
                  apply(ctx, config) {
                    ctx.on('inner', () => {
                      const p = ctx.get('probe');
                      p.record('inner-listener');
                      ctx.emit('inner-done');
                    });
                    ctx.on('trigger', () => {
                      const p = ctx.get('probe');
                      // 同步泵:emit 阻塞等 Java 回复;泵期间 Java 并发发来 inner 的
                      // invokeFn(重入消息)→ 入队,不内联执行
                      ctx.emit('inner');
                      p.record('after-emit');
                      ctx.emit('trigger-done');
                    });
                  }
                }
                """);
        Context root = new Context();
        OrderProbe probe = new OrderProbe();
        root.provide("probe", probe);
        CountDownLatch innerDone = new CountDownLatch(1);
        CountDownLatch triggerDone = new CountDownLatch(1);
        root.on("inner-done", (c, args) -> { innerDone.countDown(); return null; });
        root.on("trigger-done", (c, args) -> { triggerDone.countDown(); return null; });

        try (JsHost host = new NodeWorkerJsHost()) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin));
            root.plugin(adapter, null);
            // root.emit 阻塞到 trigger listener 完成(after-emit 已记录,inner 的重入 invokeFn 仍在队列)
            root.emit("trigger");
            assertThat(triggerDone.await(5, TimeUnit.SECONDS)).isTrue();
            // inner 的重入 invokeFn 在泵返回后按序执行 → inner-listener 记录、inner-done 触发
            assertThat(innerDone.await(5, TimeUnit.SECONDS)).isTrue();
            // 关键断言:inner-listener 在 after-emit **之后** —— 泵期间事件被排队而非内联触发
            assertThat(probe.snapshot()).isEqualTo(List.of("after-emit", "inner-listener"));
        }
        root.fiber.dispose().join();
    }

    // ---- ④ 真 I/O(fetch mock)在宿主内可跑 ----

    @Test
    void fetchRunsInsideHostAgainstLocalMock() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mock", ex -> {
            byte[] body = "hello-from-mock".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path plugin = writePlugin("fetch-plugin.cjs",
                    "module.exports = {\n"
                    + "  name: 'fetch-plugin',\n"
                    + "  apply: async (ctx, config) => {\n"
                    + "    const res = await fetch('http://127.0.0.1:" + port + "/mock');\n"
                    + "    const text = await res.text();\n"
                    + "    ctx.emit('fetch-done', text);\n"
                    + "  }\n"
                    + "}\n");
            Context root = new Context();
            AtomicReference<String> got = new AtomicReference<>();
            root.on("fetch-done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

            try (JsHost host = new NodeWorkerJsHost()) {
                JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin));
                root.plugin(adapter, null);   // 阻塞到 await fetch 完成
                assertThat(got.get()).isEqualTo("hello-from-mock");
            }
            root.fiber.dispose().join();
        } finally {
            server.stop(0);
        }
    }

    // ---- ⑤ 单 worker 多在途:虚拟线程并发驱动同一 worker ----

    public static final class ConcurrencyProbe {
        public final AtomicInteger active = new AtomicInteger();
        public final AtomicInteger maxActive = new AtomicInteger();
        public final AtomicInteger completed = new AtomicInteger();
        public Object enter(String id) {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            return null;
        }
        public Object exit(String id) {
            active.decrementAndGet();
            completed.incrementAndGet();
            return null;
        }
    }

    @Test
    void singleWorkerHandlesMultipleInFlightViaVirtualThreads() throws Exception {
        Path plugin = writePlugin("inflight.cjs", """
                module.exports = {
                  name: 'inflight',
                  apply(ctx, config) {
                    ctx.on('work', async (id) => {
                      const probe = ctx.get('probe');
                      probe.enter(id);
                      await new Promise((r) => setTimeout(r, 120));
                      probe.exit(id);
                    });
                  }
                }
                """);
        Context root = new Context();
        ConcurrencyProbe probe = new ConcurrencyProbe();
        root.provide("probe", probe);

        try (JsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            // 两个虚拟线程并发触发同一 worker:两个 invokeFn 在途,async listener 各自 await
            Thread t1 = Thread.ofVirtual().start(() -> root.emit("work", "a"));
            Thread t2 = Thread.ofVirtual().start(() -> root.emit("work", "b"));
            t1.join(15_000);
            t2.join(15_000);
            assertThat(t1.isAlive()).isFalse();
            assertThat(t2.isAlive()).isFalse();
            // 单 worker 内在途并发峰值到 2(串行实现只能到 1)
            assertThat(probe.maxActive.get()).isEqualTo(2);
            assertThat(probe.completed.get()).isEqualTo(2);
        }
        root.fiber.dispose().join();
    }
}
