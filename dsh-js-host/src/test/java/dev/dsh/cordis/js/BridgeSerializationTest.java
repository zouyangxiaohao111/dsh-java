package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.FiberState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-6 桥值序列化容忍性(node-bridge.js serializeValue / deserializeValue + ctx.mixin):
 *
 * <p>覆盖 m7-4-tree-load-map.md §4 C 组"桥形状"13 行里可在这层修复的 4 类:
 * <ol>
 *   <li>循环 this.ctx / ownerFiber 回环 → serializeValue 降级为 {@code $kind:'cycle'} 标记,
 *       不再 "cannot serialize cyclic value"(session-title / commands / goal);</li>
 *   <li>Symbol 实例字段 → serializeValue 降级为 {@code $kind:'symbol'} 标记,
 *       不再 "cannot serialize value across bridge: symbol"(session-query-sqlite);</li>
 *   <li>失效 fn 句柄(已 release / 跨 worker)→ deserializeValue 降级为记录性 no-op stub,
 *       不再 "unknown fn handle"(llm-pi-ai / skill-filesystem / subagent-* / web-search-deepseek);</li>
 *   <li>ctx.mixin() 桥面暴露(Java Context.mixin 已有,shim 补方法)——timer 不再
 *       "cannot get property 'mixin' without inject"。</li>
 * </ol>
 *
 * <p>前置:系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class BridgeSerializationTest {

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

    /** 插件 apply 经 ctx.emit('ready', msg) 汇报进度。 */
    private AtomicReference<String> capture(Context root) {
        AtomicReference<String> got = new AtomicReference<>();
        root.on("ready", (c, args) -> {
            got.set(String.valueOf(args[0]));
            return null;
        });
        return got;
    }

    // ---- 1) 循环 this.ctx / 自引用链(Service 实例结构)----

    @Test
    void cyclicServiceInstanceResultDoesNotHangApply() throws Exception {
        // 镜像 cordis Service 实例的 self.ctx 回环 + ctx.fiber 的自引用 parent.fiber 链
        Path plugin = writePlugin("cyclic-plugin.cjs", """
                module.exports = {
                  name: 'cyclic-plugin',
                  apply(ctx, config) {
                    const svc = { ping: () => 'pong' };
                    svc.ctx = ctx;                     // this.ctx back-ref(如 Service 构造)
                    const fiber = { state: 'active' };
                    fiber.parent = { fiber };          // ctx.fiber seam 的自引用链
                    svc.ownerFiber = fiber;
                    ctx.emit('ready', 'cycle-built');
                    return svc;
                  }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        Fiber fiber;
        try (JsHost host = new NodeWorkerJsHost()) {
            fiber = root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            // apply 完成:emit 已发出,结果序列化不抛 "cannot serialize cyclic value"
            assertThat(got.get()).isEqualTo("cycle-built");
            assertThat(fiber.error()).isNull();
            assertThat(fiber.state).isNotEqualTo(FiberState.FAILED);
        }
        root.fiber.dispose().join();
    }

    // ---- 2) Symbol 实例字段(Symbol 值,不是键)----

    @Test
    void symbolValuedInstanceFieldDoesNotFailApply() throws Exception {
        // 镜像 session-query-sqlite 的 own 可枚举字段 _persistenceBinding = { identity: Symbol() }
        Path plugin = writePlugin("symbol-plugin.cjs", """
                module.exports = {
                  name: 'symbol-plugin',
                  apply(ctx, config) {
                    const binding = { identity: Symbol('persistence-binding') };
                    const svc = { ping: () => 'pong', _persistenceBinding: binding };
                    ctx.emit('ready', 'symbol-built');
                    return svc;
                  }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        Fiber fiber;
        try (JsHost host = new NodeWorkerJsHost()) {
            fiber = root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            assertThat(got.get()).isEqualTo("symbol-built");
            assertThat(fiber.error()).isNull();
            assertThat(fiber.state).isNotEqualTo(FiberState.FAILED);
        }
        root.fiber.dispose().join();
    }

    // ---- 3) 失效 fn 句柄(已 release / 跨 worker)----

    @Test
    void staleFnHandleDegradesToNoOpInsteadOfUnknownHandle() throws Exception {
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            PluginModule pm = host.eval("(function () { return 42 })");
            NodePluginModule npm = (NodePluginModule) pm;
            NodeRef fnRef = npm.ref();
            // 释放后句柄失效(等价于跨 worker 服务值里的 fn 句柄:当前 worker 从未注册过)
            host.releaseFn(fnRef);
            // 调用失效句柄:降级为 no-op stub,不再抛 "unknown fn handle",返回 undefined 哨兵
            Object result = host.invokeFn(fnRef, List.of());
            assertThat(result).isSameAs(NodeWorkerJsHost.UNDEFINED);
        }
    }

    // ---- 4) ctx.mixin 桥面暴露(timer 的框架方法)----

    @Test
    void ctxMixinExposesServiceMembersToContext() throws Exception {
        // 镜像 timer(TimerService):provide('timer', self) 后 ctx.mixin('timer', [...]),
        // 之后 ctx.timeout 应可读为函数 —— 此前 Proxy 把 ctx.mixin 当服务 get 抛 "without inject"
        Path plugin = writePlugin("mixin-plugin.cjs", """
                module.exports = {
                  name: 'mixin-plugin',
                  apply(ctx, config) {
                    ctx.provide('timer', { timeout: () => 't', interval: () => 'i' });
                    ctx.mixin('timer', ['timeout', 'interval']);
                    const t = ctx.timeout;
                    ctx.emit('ready', typeof t === 'function' ? 'mixin-fn' : 'mixin-missing');
                  }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        Fiber fiber;
        try (JsHost host = new NodeWorkerJsHost()) {
            fiber = root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            assertThat(got.get()).isEqualTo("mixin-fn");
            assertThat(fiber.error()).isNull();
        }
        root.fiber.dispose().join();
    }

    @Test
    void ctxMixinRenamedMapFormIsAccepted() throws Exception {
        // renamed 映射形式(ctx.mixin(source, {srcKey: ctxKey}))也经桥(Java Context.mixin Map 重载)
        Path plugin = writePlugin("mixin-rename.cjs", """
                module.exports = {
                  name: 'mixin-rename',
                  apply(ctx, config) {
                    ctx.provide('clock', { now: () => 'now' });
                    ctx.mixin('clock', { now: 'currentTime' });
                    const t = ctx.currentTime;
                    ctx.emit('ready', typeof t === 'function' ? 'renamed-fn' : 'renamed-missing');
                  }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        Fiber fiber;
        try (JsHost host = new NodeWorkerJsHost()) {
            fiber = root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            assertThat(got.get()).isEqualTo("renamed-fn");
            assertThat(fiber.error()).isNull();
        }
        root.fiber.dispose().join();
    }
}
