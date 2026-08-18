package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-7:通用句柄机制核心扩展 —— 迭代器跨桥 + 方法返回 live 对象递归句柄化。
 *
 * <p>机制(node-bridge.js serializeValue / NodeWorkerJsHost):
 * <ol>
 *   <li>非数组 iterable/iterator → {@code {$kind:'iter'}} 句柄;Java 侧得 {@link JsIterable}
 *       ({@code Iterable}+{@code Iterator}),{@code for-each} 经 {@code invokeObj} RPC next(),
 *       遍历终结自动释放句柄(worker 注册表删除,无泄漏);</li>
 *   <li>方法返回(invokeFn/invokeObj)里的 live 对象(带原型方法的类实例)→ {@code {$kind:'obj'}}
 *       句柄;Java 侧得 {@link RemoteObject},方法调用 RPC 回 worker,返回的嵌套 live 对象
 *       递归句柄化(不强转纯数据);</li>
 *   <li>Java Iterable/Iterator → JS 可遍历(数组物化 + svc 代理 {@code Symbol.iterator} 经
 *       hasNext/next RPC 适配),两侧对称。</li>
 * </ol>
 *
 * <p>目标行同形:goal-round-driver 的 {@code ctx.agents.list()} 与 agent-loop 的
 * {@code for...of ctx.agents} 在 agents 为 Java 服务后不再 "not iterable"(见
 * {@link #agentsIterationIsomorphic_noNotIterable})。
 *
 * <p>前置:系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class HandleMechanismTest {

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) { return (Map<String, Object>) v; }

    // ---- 1) JS iterable → Java 遍历(agent-loop 场景同形)----

    @Test
    void jsIterableMethodReturnIsIterableFromJava() throws Exception {
        Path plugin = writePlugin("iter-plugin.cjs", """
                module.exports = {
                  name: 'iter-plugin',
                  apply(ctx) {
                    const makeNums = () => {
                      function* gen() { yield 10; yield 20; yield 30 }
                      return gen()
                    }
                    ctx.provide('probe', { makeNums })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Map<String, Object> probe = map(root.get("probe"));
            NodeRef makeNums = (NodeRef) probe.get("makeNums");

            Object result = host.invokeFn(makeNums, List.of());
            assertThat(result).isInstanceOf(JsIterable.class);
            JsIterable iterable = (JsIterable) result;

            List<Object> got = new ArrayList<>();
            for (Object x : iterable) got.add(x);
            assertThat(got).containsExactly(10L, 20L, 30L);

            // 遍历终结自动释放句柄:worker 侧 objById 已删除,后续 next() 抛 unknown obj handle
            long h = iterable.handle();
            assertThatThrownBy(() -> host.invokeObj(h, "next", List.of()))
                    .isInstanceOf(NodeBridgeError.class)
                    .hasMessageContaining("unknown obj handle");
        }
        root.fiber.dispose().join();
    }

    @Test
    void jsProvidedIterableIsIterableFromJava() throws Exception {
        Path plugin = writePlugin("iter-provide.cjs", """
                module.exports = {
                  name: 'iter-provide',
                  apply(ctx) {
                    function* gen() { yield 'a'; yield 'b'; yield 'c' }
                    ctx.provide('stream', gen())
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Object svc = root.get("stream");
            assertThat(svc).isInstanceOf(JsIterable.class);
            List<Object> got = new ArrayList<>();
            for (Object x : (JsIterable) svc) got.add(x);
            assertThat(got).containsExactly("a", "b", "c");
        }
        root.fiber.dispose().join();
    }

    // ---- 2) 方法返回 live 对象 → Java 得 RPC 代理(递归)----

    @Test
    void liveObjectMethodReturnIsRemoteProxyRecursively() throws Exception {
        Path plugin = writePlugin("live-plugin.cjs", """
                module.exports = {
                  name: 'live-plugin',
                  apply(ctx) {
                    class Counter {
                      constructor() { this.n = 0 }
                      inc() { this.n++ }
                      value() { return this.n }
                    }
                    class Greeter {
                      constructor(prefix) { this.prefix = prefix }
                      greet(name) { return this.prefix + ' ' + name }
                      nested() { return new Greeter(this.prefix + '!') }
                    }
                    class Session {
                      constructor(id) { this.id = id; this.events = 0 }
                      append() { this.events++ }
                      count() { return this.events }
                    }
                    const makeCounter = () => new Counter()
                    const makeGreeter = () => new Greeter('hi')
                    const makeSessionBag = () => ({ session: new Session('s1'), label: 'bag' })
                    ctx.provide('probe', { makeCounter, makeGreeter, makeSessionBag })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Map<String, Object> probe = map(root.get("probe"));

            // 方法返回 live 对象 → RemoteObject,可调其方法(状态跨调用保持)
            NodeRef makeCounter = (NodeRef) probe.get("makeCounter");
            Object c = host.invokeFn(makeCounter, List.of());
            assertThat(c).isInstanceOf(RemoteObject.class);
            RemoteObject counter = (RemoteObject) c;
            counter.call("inc");
            counter.call("inc");
            counter.call("inc");
            assertThat(((Number) counter.call("value")).intValue()).isEqualTo(3);

            // 递归:live 对象方法返回的嵌套 live 对象也句柄化
            NodeRef makeGreeter = (NodeRef) probe.get("makeGreeter");
            RemoteObject greeter = (RemoteObject) host.invokeFn(makeGreeter, List.of());
            assertThat(greeter.call("greet", "world")).isEqualTo("hi world");
            Object nested = greeter.call("nested");
            assertThat(nested).isInstanceOf(RemoteObject.class);
            assertThat(((RemoteObject) nested).call("greet", "moon")).isEqualTo("hi! moon");

            // 类型化动态代理:按接口方法名直调
            GreeterIface g = greeter.as(GreeterIface.class);
            assertThat(g.greet("java")).isEqualTo("hi java");

            // 嵌套字段:纯对象里的 live 对象字段同样句柄化
            NodeRef makeSessionBag = (NodeRef) probe.get("makeSessionBag");
            Map<String, Object> bag = map(host.invokeFn(makeSessionBag, List.of()));
            assertThat(bag.get("label")).isEqualTo("bag");
            assertThat(bag.get("session")).isInstanceOf(RemoteObject.class);
            RemoteObject session = (RemoteObject) bag.get("session");
            session.call("append");
            session.call("append");
            assertThat(((Number) session.call("count")).intValue()).isEqualTo(2);
        }
        root.fiber.dispose().join();
    }

    public interface GreeterIface {
        String greet(String name);
        Object nested();
    }

    // ---- 3) Java Iterable / Iterator → JS 可遍历 ----

    /** Java 侧服务:names() 返回 List(物化数组);lazy() 返回 Iterator(svc 代理 Symbol.iterator RPC)。 */
    public static final class IterProvider {
        public List<String> names() { return List.of("x", "y", "z"); }
        public Iterator<String> lazy() { return List.of("p", "q").iterator(); }
    }

    @Test
    void javaIterableIsIterableFromJs() throws Exception {
        Context root = new Context();
        root.provide("iterProvider", new IterProvider());
        List<String> ready = new ArrayList<>();
        root.on("ready", (c, args) -> { ready.add(String.valueOf(args[0])); return null; });
        Path plugin = writePlugin("iter-java.cjs", """
                module.exports = {
                  name: 'iter-java',
                  inject: ['iterProvider'],
                  apply(ctx) {
                    const p = ctx.get('iterProvider')
                    // 物化数组:names() 返回 List → JSON array
                    const names = []
                    for (const n of p.names()) names.push(n)
                    // svc 代理 Symbol.iterator:lazy() 返回 java.util.Iterator → hasNext/next RPC
                    const lazies = []
                    for (const q of p.lazy()) lazies.push(q)
                    ctx.emit('ready', names.join(',') + '|' + lazies.join(','))
                  }
                }
                """);
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            assertThat(ready).containsExactly("x,y,z|p,q");
        }
        root.fiber.dispose().join();
    }

    // ---- 4) 句柄在遍历终结 / 显式释放后释放(无泄漏)----

    @Test
    void handlesReleasedOnIterationCompleteAndExplicitRelease() throws Exception {
        Path plugin = writePlugin("release-plugin.cjs", """
                module.exports = {
                  name: 'release-plugin',
                  apply(ctx) {
                    const makeNums = () => {
                      function* gen() { yield 1; yield 2; yield 3 }
                      return gen()
                    }
                    class Counter {
                      constructor() { this.n = 0 }
                      inc() { this.n++ }
                      value() { return this.n }
                    }
                    const makeCounter = () => new Counter()
                    ctx.provide('probe', { makeNums, makeCounter })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Map<String, Object> probe = map(root.get("probe"));

            // 部分遍历 + 显式 release → worker 侧句柄已删
            NodeRef makeNums = (NodeRef) probe.get("makeNums");
            JsIterable partial = (JsIterable) host.invokeFn(makeNums, List.of());
            assertThat(partial.hasNext()).isTrue();
            partial.release();
            assertThatThrownBy(() -> host.invokeObj(partial.handle(), "next", List.of()))
                    .isInstanceOf(NodeBridgeError.class)
                    .hasMessageContaining("unknown obj handle");

            // RemoteObject 显式 release → 调用抛 "already released"
            NodeRef makeCounter = (NodeRef) probe.get("makeCounter");
            RemoteObject counter = (RemoteObject) host.invokeFn(makeCounter, List.of());
            counter.call("inc");
            assertThat(((Number) counter.call("value")).intValue()).isEqualTo(1);
            counter.release();
            assertThatThrownBy(() -> counter.call("value"))
                    .isInstanceOf(NodeBridgeError.class)
                    .hasMessageContaining("already released");
        }
        root.fiber.dispose().join();
    }

    // ---- 5) 目标行同形场景:agents 服务可被 JS 遍历,不再 "not iterable" ----

    /** 目标行同形:Java 核心提供的 agents 服务 —— Iterable(直接遍历)+ list()(goal-round-driver)。 */
    public static final class AgentRegistry implements Iterable<Map<String, Object>> {
        private final List<Map<String, Object>> agents;
        public AgentRegistry(List<Map<String, Object>> agents) { this.agents = agents; }
        @Override public Iterator<Map<String, Object>> iterator() { return agents.iterator(); }
        public List<Map<String, Object>> list() { return agents; }
    }

    @Test
    void agentsIterationIsomorphic_noNotIterable() throws Exception {
        Context root = new Context();
        List<String> got = new ArrayList<>();
        root.on("ready", (c, args) -> { got.add(String.valueOf(args[0])); return null; });
        root.provide("agents", new AgentRegistry(List.of(
                Map.of("id", "agent-a", "status", "idle"),
                Map.of("id", "agent-b", "status", "busy"))));
        Path plugin = writePlugin("agents-iter.cjs", """
                module.exports = {
                  name: 'agents-iter',
                  inject: ['agents'],
                  apply(ctx) {
                    // goal-round-driver 同形:ctx.agents.list() 返回数组 → for...of
                    const ids = []
                    for (const agent of ctx.agents.list()) ids.push(agent.id)
                    // agent-loop 同形:直接遍历 ctx.agents(服务本身是 Iterable)
                    const direct = []
                    for (const agent of ctx.agents) direct.push(agent.id)
                    ctx.emit('ready', ids.join(',') + '|' + direct.join(','))
                  }
                }
                """);
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            assertThat(got).containsExactly("agent-a,agent-b|agent-a,agent-b");
        }
        root.fiber.dispose().join();
    }
}
