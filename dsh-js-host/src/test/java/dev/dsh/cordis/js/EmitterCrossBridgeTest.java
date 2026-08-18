package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-7:live 事件发射器跨桥 —— JS 侧 live emitter(方法形 on/emit、Session 形成员属性
 * {@code events} 子发射器 + getter 派生值)→ Java 侧句柄订阅收事件。
 *
 * <p>机制(node-bridge.js / NodeWorkerJsHost / RemoteObject):
 * <ol>
 *   <li>live emitter 经方法返回或 {@code provide} 跨桥为 {@code {$kind:'obj'}} 句柄 → Java 得
 *       {@link RemoteObject};方法调用 RPC 回 worker({@code invokeObj});</li>
 *   <li>Java 订阅把回调(Consumer 等)注册为 svc 句柄 → JS 侧得可调代理,{@code on(name, cb)}
 *       收纳;JS {@code emit} 时调代理 → {@code invokeService $call} → Java 回调收事件
 *       (与现有 listener fold 的 fn 句柄相反方向,同一 RPC 面);</li>
 *   <li>发射器形状补齐:{@link RemoteObject#get} 读 live 对象属性(含 getter)——
 *       {@code session.events} 子发射器经 {@code invokeGet} 再句柄化,可继续 {@code on()}
 *       订阅;getter 派生值(config/sandboxMode 同形)直接读回。</li>
 * </ol>
 *
 * <p>前置:系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class EmitterCrossBridgeTest {

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    /** JS 插件:方法形 Emitter + Session 形(events 子发射器 + getter + 数据字段)。 */
    private Path writeEmitterPlugin() throws Exception {
        return writePlugin("emitter-plugin.cjs", """
                module.exports = {
                  name: 'emitter-plugin',
                  apply(ctx) {
                    class Emitter {
                      constructor() { this.handlers = new Map() }
                      on(name, cb) {
                        if (!this.handlers.has(name)) this.handlers.set(name, new Set())
                        this.handlers.get(name).add(cb)
                        return () => this.handlers.get(name).delete(cb)
                      }
                      emit(name, ...args) {
                        const set = this.handlers.get(name)
                        if (set) for (const cb of [...set]) cb(...args)
                      }
                    }
                    class Session {
                      constructor(id, title) { this.id = id; this._title = title; this.events = new Emitter() }
                      get title() { return this._title.toUpperCase() }
                      append(payload) { this.events.emit('append', payload) }
                      getId() { return this.id }
                    }
                    const makeEmitter = () => new Emitter()
                    const makeSession = () => new Session('s1', 'hello')
                    ctx.provide('probe', { makeEmitter, makeSession })
                  }
                }
                """);
    }

    // ---- 1) 方法形发射器:JS emitter → Java 订阅收事件 ----

    @Test
    void jsMethodEmitterSubscriptionDeliversEventsToJava() throws Exception {
        Path plugin = writeEmitterPlugin();
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Map<String, Object> probe = map(root.get("probe"));

            RemoteObject emitter = (RemoteObject) host.invokeFn((NodeRef) probe.get("makeEmitter"), List.of());
            List<Object> received = new ArrayList<>();
            // Java 回调 → svc 句柄 → JS on() 收纳 → emit 时 invokeService $call 回来。
            Object disposer = emitter.call("on", "tick", (Consumer<Object>) received::add);
            assertThat(disposer).isInstanceOf(NodeRef.class);   // JS on() 返回的退订 disposer(fn 句柄)

            emitter.call("emit", "tick", Map.of("n", 1));
            emitter.call("emit", "tick", Map.of("n", 2));
            // JS number 跨桥 → Java Long(桥序列化 JSON number → Long)。
            assertThat(received).containsExactly(map(Map.of("n", 1L)), map(Map.of("n", 2L)));

            // 退订:调用 disposer 后 JS 侧移除该监听器,后续 emit 不再投递。
            host.invokeFn((NodeRef) disposer, List.of());
            emitter.call("emit", "tick", Map.of("n", 3));
            assertThat(received).hasSize(2);
        }
        root.fiber.dispose().join();
    }

    // ---- 2) Session 形发射器:live 对象属性读补齐发射器形状 ----

    @Test
    void sessionEventsSubEmitterSubscribedViaPropertyGet() throws Exception {
        Path plugin = writeEmitterPlugin();
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Map<String, Object> probe = map(root.get("probe"));

            RemoteObject session = (RemoteObject) host.invokeFn((NodeRef) probe.get("makeSession"), List.of());

            // 形状补齐:live 对象成员属性(子发射器)可读 → RemoteObject,继续订阅。
            Object events = session.get("events");
            assertThat(events).isInstanceOf(RemoteObject.class);
            RemoteObject emitter = (RemoteObject) events;

            List<Object> received = new ArrayList<>();
            emitter.call("on", "append", (Consumer<Object>) received::add);
            session.call("append", Map.of("id", "s1", "seq", 1));
            session.call("append", Map.of("id", "s1", "seq", 2));
            assertThat(received).containsExactly(map(Map.of("id", "s1", "seq", 1L)), map(Map.of("id", "s1", "seq", 2L)));

            // getter 派生值可读(与 service 的 get config()/get sandboxMode() 同形)。
            assertThat(session.get("title")).isEqualTo("HELLO");
            // 数据字段可读;方法仍是方法。
            assertThat(session.call("getId")).isEqualTo("s1");
        }
        root.fiber.dispose().join();
    }

    // ---- 3) live 发射器随方法返回递归句柄化(嵌套子发射器)----

    @Test
    void nestedEmitterFromLiveObjectMethodIsSubscribable() throws Exception {
        Path plugin = writeEmitterPlugin();
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Map<String, Object> probe = map(root.get("probe"));

            RemoteObject session = (RemoteObject) host.invokeFn((NodeRef) probe.get("makeSession"), List.of());
            // 从 live 对象方法返回的嵌套发射器:递归句柄化 → RemoteObject → 订阅。
            RemoteObject emitter = (RemoteObject) session.get("events");
            assertThat(emitter).isNotNull();

            List<Object> received = new ArrayList<>();
            emitter.call("on", "append", (Consumer<Object>) received::add);
            session.call("append", "ping");
            assertThat(received).containsExactly("ping");
        }
        root.fiber.dispose().join();
    }
}
