package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4 §2.2:NodeWorkerJsHost — 进程外真 Node 宿主。覆盖:
 *  ① CJS 插件 load/apply,ctx 经 RPC 往返 Java(emit 回 Java 收到),disposer 经函数句柄;
 *  ② ESM 插件(真 Node 支持,GraalJS 跑不了的路径)load/apply;
 *  ③ eval 远程句柄;
 *  ④ 命令 DSL 注册 + dispatch(经 RPC);
 *  ⑤ 进程崩溃 → 后续操作干净失败(不挂死)。
 *
 * <p>前置:系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class NodeWorkerJsHostTest {

    @TempDir
    Path tmp;

    /** 无 node 可执行时整个测试类 skip(P3 可移植性;构建在无 Node 环境仍全绿)。 */
    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private Path writePlugin(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    /** Java 服务,经 ctx.get 暴露为远程 svc 句柄供 JS 调用。 */
    public static final class Counter {
        public int value = 0;
        public int next() { return ++value; }
    }

    @Test
    void cjsPluginCtxRoundTripsViaRpc() throws Exception {
        Path plugin = writePlugin("cjs-plugin.cjs", """
                module.exports = {
                  name: 'cjs-plugin',
                  inject: ['counter'],
                  apply(ctx, config) {
                    ctx.on('app/ready', (msg) => {
                      const c = ctx.get('counter');
                      ctx.emit('done', 'hi ' + msg + '#' + c.next());
                    });
                    ctx.provide('svc', { ping: () => 'pong' });
                    return () => { ctx.emit('disposed', 'bye'); };
                  }
                }
                """);
        Context root = new Context();
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> disposed = new AtomicReference<>();
        root.on("done", (c, args) -> { done.set(String.valueOf(args[0])); return null; });
        root.on("disposed", (c, args) -> { disposed.set(String.valueOf(args[0])); return null; });
        root.provide("counter", new Counter());

        try (JsHost host = new NodeWorkerJsHost()) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin));
            assertThat(adapter.name()).isEqualTo("cjs-plugin");
            assertThat(adapter.inject()).containsExactly("counter");
            root.plugin(adapter, null);

            // 插件 apply 已跑;Java emit → JS listener(函数句柄往返)→ JS emit 回 Java
            root.emit("app/ready", "bob");
            assertThat(done.get()).isEqualTo("hi bob#1");

            // 卸载 fiber → JS disposer 经 RPC 执行 → ctx.emit('disposed') 回 Java
            root.fiber.dispose().join();
            assertThat(disposed.get()).isEqualTo("bye");
        }
    }

    @Test
    void esmPluginLoadsAndEmitsBack() throws Exception {
        Path plugin = writePlugin("esm-plugin.mjs", """
                export const name = 'esm-plugin'
                export function apply(ctx, config) {
                  ctx.on('esm-event', (x) => { ctx.emit('esm-done', 'esm:' + x); });
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("esm-done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        try (JsHost host = new NodeWorkerJsHost()) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin));
            assertThat(adapter.name()).isEqualTo("esm-plugin");
            root.plugin(adapter, null);
            root.emit("esm-event", "x");
            assertThat(got.get()).isEqualTo("esm:x");
        }
        root.fiber.dispose().join();
    }

    @Test
    void evalReturnsRemoteFunctionModule() throws Exception {
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("eval-done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        try (JsHost host = new NodeWorkerJsHost()) {
            // eval 也走远程句柄:返回 fn 句柄的 PluginModule
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.eval(
                    "(ctx) => { ctx.on('eval-event', () => { ctx.emit('eval-done', 'ok'); }); }"));
            assertThat(adapter.name()).isNull();
            root.plugin(adapter, null);
            root.emit("eval-event");
            assertThat(got.get()).isEqualTo("ok");
        }
        root.fiber.dispose().join();
    }

    @Test
    void commandRegistersAndDispatchesOverRpc() throws Exception {
        Path plugin = writePlugin("cmd-plugin.cjs", """
                module.exports = { apply(ctx, config) {
                  ctx.command('echo <message:text>').action(({session, options}, message) => 'echo: ' + message);
                } }
                """);
        Context root = new Context();
        try (JsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            // 命令注册表按 root Context 共享:同 root 的新 bridge 可派发
            NodeWorkerBridge bridge = new NodeWorkerBridge((NodeWorkerJsHost) host, root);
            assertThat(String.valueOf(bridge.dispatchCommand("echo hello"))).isEqualTo("echo: hello");
        }
        root.fiber.dispose().join();
    }

    /**
     * ESM top-level await(TLA)→ 异步 worker 可经动态 import() await,模块正常加载并运行
     * (原同步宿主限制已解除;TLA settle 后才 apply,插件可依赖 TLA 阶段的副作用)。
     */
    @Test
    void topLevelAwaitModuleLoadsAndRuns() throws Exception {
        Path plugin = tmp.resolve("tla-plugin");
        Files.createDirectories(plugin);
        Files.writeString(plugin.resolve("package.json"), "{\"type\":\"module\"}");
        Files.writeString(plugin.resolve("index.js"), """
                export const name = 'tla'
                let settled = false
                await new Promise((resolve) => setTimeout(resolve, 10))
                settled = true
                export function apply(ctx, config) {
                  ctx.on('go', () => { ctx.emit('done', settled ? 'tla-settled' : 'tla-early'); });
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
        try (JsHost host = new NodeWorkerJsHost()) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin.resolve("index.js")));
            assertThat(adapter.name()).isEqualTo("tla");
            root.plugin(adapter, null);
            root.emit("go");
            assertThat(got.get()).isEqualTo("tla-settled");
            // 宿主仍存活:后续请求正常
            host.loadModule(writePlugin("ok.cjs", "module.exports = { apply(ctx) {} }"));
        }
        root.fiber.dispose().join();
    }

    /**
     * M12:事件参数里 live 对象(Fiber)的只读字段快照。Java 侧 toJsonNode 把 fiber.state/uid/entry
     * 作为 __snap 附带在 svc 句柄上,worker 侧 makeServiceProxy 的 get trap 先查本地快照命中即返回
     * (零 syncBridgeCall park) —— 这是跨 worker 互等 + 事件风暴池耗尽的根因解。本测试验证 JS
     * 监听器读 fiber.state/uid/entry 走快照路径返回正确值且不悬挂(非快照字段仍走 live 桥)。
     */
    @Test
    void eventArgFiberSnapshotReadableFromJs() throws Exception {
        Path plugin = writePlugin("fiber-snap.cjs", """
                module.exports = { apply(ctx) {
                  ctx.on('test/fiber', (fiber) => {
                    const entry = fiber.entry;
                    ctx.emit('fiber-snap', [fiber.state, fiber.uid, entry == null ? 'null' : entry.options.name]);
                  });
                } }
                """);
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("fiber-snap", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
        try (JsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            // root.fiber(uid=0, state=ACTIVE=2, entry=null)—— 事件参数经 toJsonNode 附带 __snap
            root.emit("test/fiber", root.fiber);
            // fiber.state → 快照 2;fiber.uid → 快照 0;fiber.entry → 快照 null → 'null'
            assertThat(got.get()).contains("2").contains("0").contains("null");
        }
        root.fiber.dispose().join();
    }

    /**
     * M12:跨 worker ctx 句柄携带 kScope 只读快照。createScope 同形 —— worker 经
     * ctx.extend({[Symbol('dsh.scope')]: key}) 把 scope key 存进 Java Context.symbolProps;
     * ctx 句柄经桥往返(worker → Java NodeRef → toJsonNode)时,Java 侧给 ctx 附
     * __snap{dsh.scope},worker 侧 makeCtx 的 symbol get trap 先查快照命中即返回(零
     * syncBridgeCall symbolGet park)。这是 boot 期 scopeOf(ctx)=ctx[kScope] 跨 worker
     * 互等死锁的根因解。本测试验证完整链路:extend 存 key → ctx 跨桥 → toJsonNode 附快照
     * → worker 读 ctx[K] 本地命中返回原 key。
     */
    @Test
    void ctxKScopeSnapshotReadableFromJs() throws Exception {
        Path plugin = writePlugin("ctx-snap.cjs", """
                module.exports = { apply(ctx) {
                  const K = Symbol('dsh.scope');
                  const scopeKey = { kind: 'scope' };
                  const child = ctx.extend({ [K]: scopeKey });
                  ctx.on('probe', (c) => {
                    const got = c[K];
                    ctx.emit('probe-result', got && got.kind === 'scope' ? 'snap-ok' : 'snap-miss:' + String(got));
                  });
                  ctx.emit('ready', child);
                } }
                """);
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("probe-result", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
        try (JsHost host = new NodeWorkerJsHost()) {
            AtomicReference<Object> child = new AtomicReference<>();
            root.on("ready", (c, args) -> {
                child.set(args[0]); // child ctx 的 NodeRef(kind='ctx')
                return null;
            });
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            // 等 worker 把 child ctx 传回(apply 异步),再由测试主动触发 probe —— 避免在
            // apply 期同步链里嵌套事件转发(时序不稳)。Java root.emit(probe, ctxRef) 经
            // toJsonNode 附 __snap,worker 读 c[K] → __snap['dsh.scope'] 本地命中 → 'snap-ok'
            // (而非 symbolGet 往返后 miss)。
            for (int i = 0; i < 200 && child.get() == null; i++) Thread.sleep(10);
            assertThat(child.get()).isNotNull();
            root.emit("probe", child.get());
            assertThat(got.get()).isEqualTo("snap-ok");
        }
        root.fiber.dispose().join();
    }

    @Test
    void processCrashFailsOperationsCleanly() throws Exception {
        Path plugin = writePlugin("p.cjs", "module.exports = { apply(ctx, config) { ctx.on('go', () => {}); } }");
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            host.loadModule(plugin);   // 先确保 worker 已启动
            host.killForTest();        // 模拟进程崩溃
            assertThatThrownBy(() -> host.loadModule(plugin))
                    .isInstanceOf(NodeBridgeError.class)
                    .hasMessageContaining("node worker");
        }
        root.fiber.dispose().join();
    }
}
