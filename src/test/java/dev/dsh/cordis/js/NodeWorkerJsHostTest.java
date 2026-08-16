package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
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
