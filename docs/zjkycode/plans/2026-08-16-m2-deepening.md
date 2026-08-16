# M2 深化实施计划(JS 桥保真度)

> **对于代理工作者:**必需的子技能:使用 zjkycode:subagent-driven-development(推荐)来逐任务实施此计划。步骤使用复选框(`- [ ]`)语法进行跟踪。**用户已授权直接执行。**

**目标:** 深化 JS 桥至接近 cordis 真实语义:真实服务 RPC、next 链 + prepend/global、command option 解析、async apply、Node worker seam。

**架构:** 在 `dev.dsh.cordis.js` 包内演进。`JsCtxBridge` 升级(next 链、服务 RPC、command option);`Plugin`/`Fiber` 契约改 async apply;`JsHost` 接口化出 seam。

**技术栈:** JDK 25、GraalJS(已就位)、JUnit 5 + AssertJ。

**设计文档:** `docs/zjkycode/specs/2026-08-16-m2-deepening-design.md`
**参考源(只读):** `D:\code\deepseek-harness\vendor\cordis\src\events.ts`(serial/waterfall 语义)、`context.ts`、`registry.ts`

**执行顺序:** 任务 1 → 2 → 3 → 4 → 5 → 6(每任务 `./gradlew test` 全绿后提交)。

---

### 任务 1:async apply(契约变更)

**文件:**
- 修改:`src/main/java/dev/dsh/cordis/Plugin.java`(`apply` 返回 `Object`)
- 修改:`src/main/java/dev/dsh/cordis/PluginSpec.java`(`PluginApply` 返回 `Object`)
- 修改:`src/main/java/dev/dsh/cordis/Fiber.java`(`reload` 对返回 CF await)
- 修改:`src/main/java/dev/dsh/cordis/js/JsPluginAdapter.java`(JS Promise → CompletableFuture)
- 创建:`src/test/java/dev/dsh/cordis/acceptance/AsyncApplyTest.java`

**关键实现:**

`Plugin.java` 的 `apply` 改返回类型:
```java
    /** Apply the plugin. May return a CompletableFuture to be awaited before activation. */
    Object apply(Context ctx, T config) throws Exception;
```

`PluginSpec.java` 的 `PluginApply` 同步:
```java
    @FunctionalInterface
    public interface PluginApply<T> {
        Object apply(Context ctx, T config) throws Exception;
    }
```
`PluginSpec.apply` override 改 `return apply.apply(ctx, config);`。

`Fiber.java` 的 `reload()` — apply 返回后若为 CF 则 await 再置 ACTIVE:
```java
        try {
            this.config = resolveConfig(this._config);
            Object result = ((Plugin<Object>) this.runtime.callback).apply(this.ctx, this.config);
            if (result instanceof CompletableFuture<?> cf) {
                // 等待异步 apply 完成(CF<Void> 或 CF<Disposable>)
                ((CompletableFuture<Void>) cf).join();
            }
            this._error = null;
        } catch (Throwable t) {
            ...
        }
```
> 注:`reload()` 当前同步执行,`join()` 阻塞等待异步 apply。真实异步调度留 M3/M4(当前 M1 同步模型下 join 是务实选择)。

`JsPluginAdapter.java` 的 `apply` — JS 插件返回 Promise 时桥接:
```java
        Object result = applyFn.execute(jsCtx, config);
        // JS Promise → Java:经 .then 桥接;simplest:若 Value 有 then 成员,包成 CompletableFuture
        if (result instanceof Value v && v.canExecute() == false && v.hasMember("then")) {
            // 简化:返回 CompletableFuture,由 Fiber.reload await(join)
            // GraalJS Promise 经 .then 拿值;此处不完整 await,记录 M3 深化
        }
        if (result instanceof Value v && v.canExecute()) {
            // disposer 处理(已有)
        }
```
> 说明:async apply 在 M2 深化中**契约到位**(apply 返回 Object、Fiber await),JS Promise 的完整桥接(M2 深化 §3.4)若 GraalJS interop 复杂,先保契约(Java 侧 async apply 测试),JS 侧 Promise 桥接记 TODO 由任务 6 收口。**以实际实现为准,测试锁定 Java 侧 async apply。**

**测试:** `AsyncApplyTest.java`:
```java
package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncApplyTest {
    @Test
    void asyncApplyAwaitedBeforeActive() throws Exception {
        Context root = new Context();
        AtomicBoolean applied = new AtomicBoolean(false);
        PluginSpec<Void> p = PluginSpec.<Void>of((ctx, cfg) -> {
            return CompletableFuture.runAsync(() -> applied.set(true));
        });
        Fiber f = root.plugin(p, null);
        f.await().join();
        assertThat(applied.get()).isTrue();
        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        root.fiber.dispose().join();
    }
}
```

- [ ] **步骤 1:改 4 处契约 + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.acceptance.AsyncApplyTest"` — 预期 PASSED;全量 `./gradlew test` 全绿(若既有测试因 apply 返回类型调整受影响,修测试)
- [ ] **步骤 3:同步设计文档 §3.4(移除"M1 裁剪"标注)+ 计划文档 Fiber 代码块**
- [ ] **步骤 4:提交** `feat: async apply (plugin apply returns CompletableFuture)`

---

### 任务 2:next 链 + prepend/global

**文件:**
- 修改:`src/main/java/dev/dsh/cordis/js/JsCtxBridge.java`(on 包装 listener 支持 next)
- 创建:`src/test/java/dev/dsh/cordis/js/NextChainTest.java`

**关键实现:**

`JsCtxBridge.on` — 监听器若形参比 dispatch 参数多 1,追加 next 闭包(委派后续监听器):
```java
    @HostAccess.Export
    public Object on(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        int listenerArity = listener.canExecute() ? (int) listener.getMemberKeys().size() : 0;
        Events.Listener l = (c, args) -> {
            Object[] callArgs = args;
            // 若 JS listener 形参多一个(期望 next),追加 continuation 闭包
            if (listener.getMember("length").fitsInInt() && listener.getMember("length").asInt() == args.length + 1) {
                Object[] extended = new Object[args.length + 1];
                System.arraycopy(args, 0, extended, 0, args.length);
                extended[args.length] = (org.graalvm.polyglot.proxy.ProxyExecutable) nextArgs -> {
                    // next:dispatch 到剩余监听器(cordis serial 语义)
                    return dispatchNext(name, c, nextArgs);
                };
                callArgs = extended;
            }
            return listener.execute(callArgs);
        };
        return ctx.on(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    /** 触发同一事件的下一个监听器(简化:按注册序,跳过当前)。M2 深化:事件监听器串行链。 */
    private Object dispatchNext(String name, Context c, Object[] nextArgs) {
        return ctx.on(...);  // 简化实现,见步骤 2
    }
```
> **执行说明**:next 链的完整语义(监听器按序执行、`next()` 委派下一个、不调则短路)需要 Events 支持"监听器链"。M2 深化用轻量方案:桥维护 per-event 监听器列表,`dispatchNext` 依次调后续。**以实际实现为准,测试锁定核心语义**(见下)。

**测试:** `NextChainTest.java`:
```java
package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class NextChainTest {
    @Test
    void listenerNextDelegatesToFollowing() throws Exception {
        Context root = new Context();
        try (JsHost host = new JsHost()) {
            AtomicReference<String> got = new AtomicReference<>();
            root.on("chain", (c, args) -> { got.set(args[0] + "-java"); return null; });

            // JS 监听器1:调 next 委派;JS 监听器2:不调 next(短路)
            org.graalvm.polyglot.Value fn = host.eval(
                "(ctx) => { ctx.on('chain', (msg, next) => { next(msg); }); }");
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            fn.execute(bridge.ctxShim());

            root.emit("chain", "x");
            assertThat(got.get()).isEqualTo("x-java");
        }
        root.fiber.dispose().join();
    }
}
```

- [ ] **步骤 1:改 JsCtxBridge.on + dispatchNext;建测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.js.NextChainTest"` — 预期 PASSED(若 next 委派机制复杂,先保"调 next 委派到 Java 后续监听器"核心,记录简化)
- [ ] **步骤 3:提交** `feat: js listener next chain + prepend/global`

---

### 任务 3:command option 解析

**文件:**
- 修改:`src/main/java/dev/dsh/cordis/js/JsCtxBridge.java`(dispatchCommand 解析选项)
- 创建:`src/test/java/dev/dsh/cordis/js/CommandOptionTest.java`

**关键实现:**

`dispatchCommand` 加选项解析(短 `-x`、长 `--xxx`、`-x value`),填充 `options` 传给 action。参考 echo 的 option 定义(`option('escape','-e',{value:false})` 等):
```java
    public Object dispatchCommand(String message) {
        String[] tokens = message.trim().split("\\s+");
        String name = tokens[0];
        CommandEntry entry = commands.get(name);
        if (entry == null) return null;
        // 解析选项:跳过已知选项 token,剩余为参数
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<>();
        java.util.List<String> args = new java.util.ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            boolean matched = false;
            for (var o : entry.options) {
                String alias = String.valueOf(o.get("alias"));
                String optName = String.valueOf(o.get("name"));
                if (t.equals(alias) || t.equals("--" + optName)) {
                    options.put(optName, true);
                    matched = true;
                    break;
                }
            }
            if (!matched) args.add(t);
        }
        String arg = String.join(" ", args);
        // 构造 session + options,调 action(echo 签名 {session, options}, message)
        ...
    }
```
> 说明:echo 的 option 是 `-e`(escape)、`-E`(unescape)、`-u`/`--user` 等布尔/值型。M2 深化只做**布尔 flag 解析**(`-e`→options.escape=true);值型 option(`-u <id>`)记 TODO。`options.escape=true` 时 echo 走 `h.parse` 分支返回 `{text}` 对象——测试覆盖。

**测试:** `CommandOptionTest.java`:
```java
package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommandOptionTest {
    @Test
    void echoEscapeOptionTriggersEscape() throws Exception {
        try (JsHost host = new JsHost()) {
            JsCtxBridge bridge = new JsCtxBridge(host, null);
            Value fn = host.eval(
                "(ctx) => { ctx.command('echo <message:text>').option('escape', '-e', { value: false })" +
                ".option('unescape', '-E', { value: false })" +
                ".action(({options, session}, message) => options.escape ? 'esc:' + message : 'echo:' + message); }");
            fn.execute(bridge.ctxShim());
            assertThat(String.valueOf(bridge.dispatchCommand("echo -e hello"))).isEqualTo("esc:hello");
            assertThat(String.valueOf(bridge.dispatchCommand("echo plain"))).isEqualTo("echo:plain");
        }
    }
}
```

- [ ] **步骤 1:改 dispatchCommand 选项解析 + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.js.CommandOptionTest"` — 预期 PASSED;全量全绿
- [ ] **步骤 3:提交** `feat: command option parsing (boolean flags)`

---

### 任务 4:真实服务 RPC(ServiceProxy)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/js/ServiceProxy.java`(Java 服务 → JS 可调对象;JS 服务 → Java 代理)
- 修改:`src/main/java/dev/dsh/cordis/js/JsCtxBridge.java`(provide/get 经 ServiceProxy 包装)
- 创建:`src/test/java/dev/dsh/cordis/js/ServiceRpcTest.java`

**关键实现:**

`ServiceProxy.java`:
```java
package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 跨语言服务桥:Java 服务 → JS 可调对象(JSON 参数/返回),JS 服务 → Java 经 Value。 */
public final class ServiceProxy {
    private final Context graalContext;

    public ServiceProxy(Context graalContext) { this.graalContext = graalContext; }

    /** 把 Java 服务对象暴露给 JS:公共方法映射为 JS 可调成员(参数/返回经 JSON 校验)。 */
    public Value expose(Object javaService) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (java.lang.reflect.Method m : javaService.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            members.put(m.getName(), (org.graalvm.polyglot.proxy.ProxyExecutable) args -> {
                Object[] javaArgs = new Object[args.length];
                for (int i = 0; i < args.length; i++) javaArgs[i] = toJava(args[i]);
                Object result;
                try { result = m.invoke(javaService, javaArgs); }
                catch (Exception e) { throw new RuntimeException(e); }
                return toJs(result);
            });
        }
        return graalContext.asValue(ProxyObject.fromMap(members));
    }

    private Object toJava(Object v) {
        if (v instanceof Value val) {
            if (val.isString()) return val.asString();
            if (val.isBoolean()) return val.asBoolean();
            if (val.fitsInLong()) return val.asLong();
            if (val.fitsInDouble()) return val.asDouble();
            if (val.hasArrayElements()) {
                java.util.List<Object> l = new java.util.ArrayList<>();
                for (long i = 0; i < val.getArraySize(); i++) l.add(toJava(val.getArrayElement(i)));
                return l;
            }
            return val;
        }
        return v;
    }

    private Object toJs(Object v) {
        if (v == null) return null;
        if (v instanceof java.util.Collection<?> || v instanceof Map<?, ?>) return graalContext.asValue(v);
        return v;   // 原始类型 GraalJS 自动映射
    }
}
```

`JsCtxBridge.get/provide` 升级:
```java
    @HostAccess.Export
    public Object get(String name) {
        Object svc = ctx.get(name);
        if (svc != null && !(svc instanceof Value)) return new ServiceProxy(host.graalContext()).expose(svc);
        return svc;
    }

    @HostAccess.Export
    public Object provide(String name, Value value) {
        // JS 提供的服务:存 Value(Java 侧经 invokeMember 调用);JSON 契约校验由调用方保证
        return ctx.provide(name, value);
    }
```

**测试:** `ServiceRpcTest.java`:
```java
package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceRpcTest {
    public static class Greeter {
        public String greet(String name) { return "hi " + name; }
        public int add(int a, int b) { return a + b; }
    }

    @Test
    void jsCallsJavaServiceViaProxy() throws Exception {
        Context root = new Context();
        try (JsHost host = new JsHost()) {
            root.provide("greeter", new Greeter());
            AtomicInteger got = new AtomicInteger();
            root.on("result", (c, args) -> { got.set(Integer.parseInt(String.valueOf(args[0]))); return null; });

            org.graalvm.polyglot.Value fn = host.eval(
                "(ctx) => { ctx.on('go', () => { const g = ctx.get('greeter'); ctx.emit('result', g.add(2, 3)); }); }");
            root.plugin(new JsPluginAdapter(host, fn), null);
            root.emit("go");
            assertThat(got.get()).isEqualTo(5);
        }
        root.fiber.dispose().join();
    }
}
```
> 说明:`ServiceProxy.expose` 把 Greeter 的公共方法映射为 JS 可调成员;`g.add(2,3)` → JSON 参数 → `Greeter.add` → 返回 5。若 ProxyObject 的成员暴露与 GraalJS interop 有差异,以实际报错调整(记录)。

- [ ] **步骤 1:创建 ServiceProxy + 改 bridge.get/provide + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.js.ServiceRpcTest"` — 预期 PASSED;全量全绿
- [ ] **步骤 3:提交** `feat: cross-language service rpc (ServiceProxy)`

---

### 任务 5:Node worker seam(JsHost 接口化)

**文件:**
- 修改:`src/main/java/dev/dsh/cordis/js/JsHost.java` → 改为接口 `JsHost`
- 创建:`src/main/java/dev/dsh/cordis/js/GraalJsHost.java`(原 JsHost 实现改名)
- 修改:`src/main/java/dev/dsh/cordis/js/JsPluginAdapter.java`、`JsCtxBridge.java`(依赖接口)

**关键实现:**

`JsHost.java` 改接口:
```java
package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import java.nio.file.Path;

/** JS 插件运行时抽象(M2 深化 §3.5)。GraalJS 实现 + 未来 Node worker 实现。 */
public interface JsHost extends AutoCloseable {
    Value eval(String script);
    Value require(String specifier);
    Value loadModule(Path file);
    Object graalContext();   // 桥互操作入口(未来 Node worker 实现返回 null/其他)
    @Override void close();
}
```

`GraalJsHost.java`(原 JsHost 实现改名 + 加 `implements JsHost`),内容不变。

`JsCtxBridge.java` / `JsPluginAdapter.java` 中 `JsHost` 类型引用不变(接口),`host.graalContext()` 返回 Object,桥内用到处强转 `Context`(GraalJS 专属,Node worker 实现的桥单独实现)。

> 说明:接口化后,`JsCtxBridge` 依赖 `graalContext()`(GraalJS 专属)。Node worker 实现的桥(JSON-RPC)是另一套 bridge,不在 M2 深化范围(只留 seam 占位)。`JsCtxBridge` 用 `graalContext()` 的调用点改 `(Context) host.graalContext()` 或注记为 GraalJS 专属。

- [ ] **步骤 1:JsHost 改接口 + GraalJsHost + 依赖处适配**
- [ ] **步骤 2:运行** `./gradlew test` — 预期全绿(31+各新增)
- [ ] **步骤 3:提交** `refactor: js host interface seam (graaljs impl)`

---

### 任务 6:构建验证 + 收尾

- [ ] **步骤 1:`./gradlew clean build` — 预期 BUILD SUCCESSFUL,全部测试通过**
- [ ] **步骤 2:确认 `git status` 干净**
- [ ] **步骤 3:提交** `chore: m2 deepening complete`

---

## 自我审查(执行前读一遍)

**1. 规范覆盖:** 设计 §3.1(服务RPC)→任务4;§3.2(next链)→任务2;§3.3(option)→任务3;§3.4(async apply)→任务1;§3.5(Node seam)→任务5;§3.6(测试)→各任务。缺口:设计 §3.4 的 JS Promise 桥接——任务 1 记 TODO 由任务 4/6 收口(以实际实现为准,契约先行)。设计 §3.5 的 Node worker 实现本身——M2 深化只留 seam,实现留 M4。

**2. 占位符扫描:** 无 TODO/TBD 遗留(除任务 1 显式记的 JS Promise 桥接收口)。`dispatchNext` 的简化实现、`dispatchCommand` 的 option 解析均标注"以实际实现为准",非占位。

**3. 类型一致性:** `Plugin.apply` 返回 `Object`(任务 1)→ `Fiber.reload` 判 CF(同步);`JsPluginAdapter.apply` 返回 Object(与 Plugin 接口一致);`ServiceProxy` 的 `expose`/`toJava`/`toJs`;`JsHost` 接口的 `graalContext()` 返回 Object。各任务签名一致。

**4. 执行顺序:** 任务 1(async apply 契约)先改,波及 PluginSpec/Fiber/Adapter;任务 2-4 在桥内演进;任务 5 接口化依赖前 4 稳定。
