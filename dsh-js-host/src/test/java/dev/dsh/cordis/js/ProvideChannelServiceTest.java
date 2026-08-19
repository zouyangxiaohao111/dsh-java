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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-8:provide 通道服务句柄化 —— 跨 worker 服务方法 / getter 经桥路由到属主 worker 执行。
 *
 * <p>机制(node-bridge.js / NodeWorkerJsHost / RemoteObject):
 * <ol>
 *   <li>提供方 worker {@code ctx.provide(name, svc)} 把 live 服务对象(带原型方法的类实例)
 *       序列化为 {@code {$kind:'obj'}} 句柄(注册进 objById),Java 核心分配全局句柄 id 并把
 *       worker 侧条目 rehandle 到全局 id(rehandleObj),注册"全局 id → 属主 worker"路由;</li>
 *   <li>读方 worker {@code ctx.get(name)} 拿到指向属主 worker 的 live 代理;方法调用路由
 *       读方 worker → Java → 属主 worker → 执行 → 结果递归句柄化(嵌套 live 对象同样导出为
 *       全局句柄);getter/数据字段经 {@code $get → invokeGet} 读值(触发 worker 侧 accessor);</li>
 *   <li>兼容:纯数据服务仍走 JSON(不强转);Java 侧 {@code root.get(name)} 得
 *       {@link RemoteObject}({@link Map} 门面)仍可按成员名读(方法 → fn 句柄)。</li>
 * </ol>
 *
 * <p>目标行同形:session-persistence-jsonl / goal-round-driver 的 {@code ctx.sessions.list()}、
 * {@code ctx.agents.list()/get()}(遍历方法返回,见 {@link #crossWorkerServiceMethodsAndIterableReturns});
 * permission 的 {@code ctx.shell.sandboxMode}(getter 属性读,见
 * {@link #crossWorkerGetterPropertyReadsValue});plan-mode 的
 * {@code ctx.systemPrompt.section(...)} + 方法返回 live 对象再句柄化(见
 * {@link #crossWorkerMethodReturningLiveObjectIsHandleized})。
 *
 * <p>前置:系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class ProvideChannelServiceTest {

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

    // ---- 1) 跨 worker 方法调用 + 遍历方法返回(session-persistence / goal-round 同形)----

    /**
     * provider 提供带方法({@code list()/get()})与迭代器返回的 live 服务;consumer(独立 worker)
     * get 后调方法、遍历返回数组、方法返回的 live 对象再读 getter 与调用方法。
     */
    @Test
    void crossWorkerServiceMethodsAndIterableReturns() throws Exception {
        Path provider = writePlugin("sessions-provider.cjs", """
                module.exports = {
                  name: 'sessions-provider',
                  apply(ctx) {
                    class Session {
                      constructor(id, title) { this.id = id; this.title = title; this._events = [] }
                      get name() { return this.title.toUpperCase() }
                      events() { return this._events }
                    }
                    class Sessions {
                      constructor() { this.store = new Map() }
                      list() { return [...this.store.values()] }
                      get(id) { return this.store.get(id) }
                      add(s) { this.store.set(s.id, s) }
                    }
                    const svc = new Sessions()
                    svc.add(new Session('s1', 'hello'))
                    svc.add(new Session('s2', 'world'))
                    ctx.provide('sessions', svc)
                  }
                }
                """);
        Path consumer = writePlugin("sessions-consumer.cjs", """
                module.exports = {
                  name: 'sessions-consumer',
                  inject: ['sessions'],
                  apply(ctx) {
                    const sessions = ctx.sessions
                    const ids = []
                    for (const s of sessions.list()) ids.push(s.id)
                    const s1 = sessions.get('s1')
                    ctx.provide('sessionsProbe', {
                      ids: ids.join(','),
                      title: s1.name,
                      eventsCount: s1.events().length,
                    })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost();
             NodeWorkerJsHost consumerHost = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(providerHost, providerHost.loadModule(provider)), null);
            root.plugin(new JsPluginAdapter(consumerHost, consumerHost.loadModule(consumer)), null);

            Map<String, Object> probe = map(root.get("sessionsProbe"));
            assertThat(probe.get("ids")).isEqualTo("s1,s2");            // list() 跨 worker 返回数组可遍历
            assertThat(probe.get("title")).isEqualTo("HELLO");          // 方法返回 live 对象 → getter 跨 worker 读值
            assertThat(((Number) probe.get("eventsCount")).intValue()).isEqualTo(0);
        } finally {
            root.fiber.dispose().join();
        }
    }

    // ---- 2) getter 属性跨 worker 可读(permission sandboxMode 同形)----

    @Test
    void crossWorkerGetterPropertyReadsValue() throws Exception {
        Path provider = writePlugin("shell-provider.cjs", """
                module.exports = {
                  name: 'shell-provider',
                  apply(ctx) {
                    class Shell {
                      constructor() { this._mode = 'workspace-write' }
                      get sandboxMode() { return this._mode }
                      confine(argv) { return ['confined', ...argv] }
                    }
                    ctx.provide('shell', new Shell())
                  }
                }
                """);
        Path consumer = writePlugin("permission-consumer.cjs", """
                module.exports = {
                  name: 'permission-consumer',
                  inject: ['shell'],
                  apply(ctx) {
                    // permission-presets 同形:ctx.shell.sandboxMode === undefined 的功能检查
                    const mode = ctx.shell.sandboxMode
                    const argv = ctx.shell.confine(['bash', '-c', 'ls'])
                    ctx.provide('permissionProbe', { mode, confined: argv.join('|') })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost();
             NodeWorkerJsHost consumerHost = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(providerHost, providerHost.loadModule(provider)), null);
            root.plugin(new JsPluginAdapter(consumerHost, consumerHost.loadModule(consumer)), null);

            Map<String, Object> probe = map(root.get("permissionProbe"));
            assertThat(probe.get("mode")).isEqualTo("workspace-write");   // getter 读到真实值,不是函数
            assertThat(probe.get("confined")).isEqualTo("confined|bash|-c|ls");
        } finally {
            root.fiber.dispose().join();
        }
    }

    // ---- 3) 跨 worker 方法返回 live 对象再句柄化(plan-mode systemPrompt.section 同形)----

    @Test
    void crossWorkerMethodReturningLiveObjectIsHandleized() throws Exception {
        Path provider = writePlugin("system-prompt-provider.cjs", """
                module.exports = {
                  name: 'system-prompt-provider',
                  apply(ctx) {
                    class Section {
                      constructor(name, text) { this.name = name; this.text = text }
                    }
                    class SystemPrompt {
                      constructor() { this._sections = [] }
                      section(opts) { this._sections.push(new Section(opts.name, opts.text)); return this._sections.length }
                      assemble() { return { sections: this._sections } }
                    }
                    ctx.provide('systemPrompt', new SystemPrompt())
                  }
                }
                """);
        Path consumer = writePlugin("plan-mode-consumer.cjs", """
                module.exports = {
                  name: 'plan-mode-consumer',
                  inject: ['systemPrompt'],
                  apply(ctx) {
                    ctx.systemPrompt.section({ name: 'plan', text: 'Follow the plan.' })
                    ctx.systemPrompt.section({ name: 'guard', text: 'Guard rails.' })
                    const count = ctx.systemPrompt.section({ name: 'extra', text: 'Extra.' })
                    // assemble() 返回 { sections: [live Section 对象] }:嵌套 live 对象递归句柄化,
                    // 跨 worker 读回为代理,继续读 getter/数据字段。
                    const assembled = ctx.systemPrompt.assemble()
                    const names = assembled.sections.map(s => s.name)
                    ctx.provide('planProbe', { count, names: names.join(',') })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost();
             NodeWorkerJsHost consumerHost = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(providerHost, providerHost.loadModule(provider)), null);
            root.plugin(new JsPluginAdapter(consumerHost, consumerHost.loadModule(consumer)), null);

            Map<String, Object> probe = map(root.get("planProbe"));
            assertThat(((Number) probe.get("count")).intValue()).isEqualTo(3);
            assertThat(probe.get("names")).isEqualTo("plan,guard,extra");
        } finally {
            root.fiber.dispose().join();
        }
    }

    // ---- 4) Java 侧经 Map 门面读提供服务(兼容:方法 → fn 句柄)----

    @Test
    void providedServiceReadableFromJavaViaMapFacade() throws Exception {
        Path provider = writePlugin("sessions-provider.cjs", """
                module.exports = {
                  name: 'sessions-provider',
                  apply(ctx) {
                    class Session {
                      constructor(id) { this.id = id }
                    }
                    class Sessions {
                      constructor() {
                        this.store = new Map()
                        // 绑定的方法(镜像 cordis shim Service 构造器的绑定行为:原型方法绑成
                        // own 属性,跨桥成 fn 句柄后 invokeFn 直接调、this 不丢)
                        this.list = () => [...this.store.values()]
                      }
                      add(s) { this.store.set(s.id, s) }
                    }
                    const svc = new Sessions()
                    svc.add(new Session('a1'))
                    svc.add(new Session('a2'))
                    ctx.provide('sessions', svc)
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(providerHost, providerHost.loadModule(provider)), null);

            Object svc = root.get("sessions");
            assertThat(svc).isInstanceOf(RemoteObject.class);
            Map<String, Object> sessions = map(svc);                     // RemoteObject 即 Map 门面
            NodeRef list = (NodeRef) sessions.get("list");               // 方法成员 → fn 句柄(已绑定)
            List<?> all = (List<?>) providerHost.invokeFn(list, List.of());
            assertThat(all).hasSize(2);
            Map<String, Object> first = map(all.get(0));                 // live 元素 → RemoteObject(Map 门面)
            assertThat(first.get("id")).isEqualTo("a1");
        } finally {
            root.fiber.dispose().join();
        }
    }

    // ---- 5) 跨 worker 回调参数(fn 句柄):服务方法收到的回调经 Java 路由回调用方 worker 执行 ----
    // skill-filesystem 的 ctx.skills.registerProvider(cb) 同形:cb 在 provider worker 注册,
    // skill 服务 worker 实际调用它(create(control)),返回值(live provider)递归句柄化后读 .name。

    @Test
    void crossWorkerCallbackArgumentRoutesBackToCallingWorker() throws Exception {
        Path provider = writePlugin("skills-provider.cjs", """
                module.exports = {
                  name: 'skills-provider',
                  apply(ctx) {
                    class Skills {
                      constructor() { this.providers = [] }
                      registerProvider(create) {
                        const provider = create({ signal: 'ready' })
                        this.providers.push(provider)
                        return provider.name
                      }
                    }
                    ctx.provide('skills', new Skills())
                  }
                }
                """);
        Path consumer = writePlugin("skill-filesystem-consumer.cjs", """
                module.exports = {
                  name: 'skill-filesystem-consumer',
                  inject: ['skills'],
                  apply(ctx) {
                    const name = ctx.skills.registerProvider((control) => {
                      class Provider { get name() { return 'filesystem' } }
                      return new Provider()
                    })
                    ctx.provide('skillsProbe', { name })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost();
             NodeWorkerJsHost consumerHost = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(providerHost, providerHost.loadModule(provider)), null);
            root.plugin(new JsPluginAdapter(consumerHost, consumerHost.loadModule(consumer)), null);

            Map<String, Object> probe = map(root.get("skillsProbe"));
            assertThat(probe.get("name")).isEqualTo("filesystem");   // 回调跨 worker 执行并返回 live 对象
        } finally {
            root.fiber.dispose().join();
        }
    }

    // ---- 6) 服务释放 / 回收:跨 worker 路由表与 worker 侧注册表同步清理(无泄漏)----

    @Test
    void serviceReleaseCleansRouteAndWorkerRegistry() throws Exception {
        Path provider = writePlugin("sessions-provider.cjs", """
                module.exports = {
                  name: 'sessions-provider',
                  apply(ctx) {
                    class Sessions {
                      list() { return [] }
                    }
                    ctx.provide('sessions', new Sessions())
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(providerHost, providerHost.loadModule(provider)), null);

            RemoteObject svc = (RemoteObject) root.get("sessions");
            long globalHandle = svc.handle();
            assertThat(NodeWorkerJsHost.routeRegistered(globalHandle)).isTrue();   // 全局路由已注册
            assertThat(providerHost.invokeObj(globalHandle, "list", List.of())).isNotNull();

            svc.release();                                                          // 显式释放
            assertThat(NodeWorkerJsHost.routeRegistered(globalHandle)).isFalse();   // 跨 worker 路由清理
            assertThatThrownBy(() -> providerHost.invokeObj(globalHandle, "list", List.of()))
                    .isInstanceOf(NodeBridgeError.class)
                    .hasMessageContaining("unknown obj handle");                    // worker 侧 objById 已删
        } finally {
            root.fiber.dispose().join();
        }
    }
}
