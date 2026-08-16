# JS 插件 host(里程碑 2)实施计划

> **对于代理工作者:**必需的子技能:使用 zjkycode:subagent-driven-development(推荐)来逐任务实施此计划。步骤使用复选框(`- [ ]`)语法进行跟踪。**用户已授权直接执行,不再逐任务等待批准。**

**目标:** 在 M1 cordis Java 核心之上,加 GraalJS 桥 + JsPluginAdapter,让真实 koishi 插件(echo v2.2.5)经统一 registry 运行,证明"Java 触发 → JS 插件响应"。

**架构:** 进程内 GraalJS。`JsHost`(Engine/Context 管理)→ `JsCtxBridge`(Java ↔ JS 互操作,JS ctx shim 转发)→ `JsPluginAdapter`(把 JS 插件函数包成 Java `Plugin`)。JS 侧 `ctx.js` 实现 cordis ctx 契约子集;koishi 模块 shim + command DSL 支撑 echo 插件。

**技术栈:** JDK 25、Gradle 9.7.0(代理 7897)、`org.graalvm.polyglot:{polyglot,js}:24.1.1`、JUnit 5 + AssertJ。

**设计文档:** `docs/zjkycode/specs/2026-08-16-js-plugin-host-design.md`
**参考源(只读):** `D:\code\deepseek-harness\vendor\cordis\src\*.ts`;echo 源码 `https://unpkg.com/@koishijs/plugin-echo@2.2.5/lib/index.js`(经代理 curl 拉取,见任务 5)

**移植原则:**
- JS ctx shim 转发到 M1 的 caller-aware API(`Context.on/emit/get/provide/effect` 已就位)。
- 跨语言服务 = 接口 + JSON 可序列化签名。
- 每任务完成 `./gradlew compileJava`(或对应测试)必须通过再提交。

---

### 任务 0:GraalJS 依赖 + 冒烟测试

**文件:**
- 修改:`build.gradle.kts`(加 GraalJS 依赖)
- 创建:`src/test/java/dev/dsh/cordis/js/GraalJsSmokeTest.java`

- [ ] **步骤 1:build.gradle.kts 加依赖**
```kotlin
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.graalvm.polyglot:polyglot:24.1.1")
    implementation("org.graalvm.polyglot:js:24.1.1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
```

- [ ] **步骤 2:冒烟测试** `src/test/java/dev/dsh/cordis/js/GraalJsSmokeTest.java`:
```java
package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GraalJsSmokeTest {
    @Test
    void evalAndCallJsFunction() {
        try (Context ctx = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .option("js.node-require", "true")
                .build()) {
            Value fn = ctx.eval("js", "(a, b) => a + b");
            assertThat(fn.canExecute()).isTrue();
            assertThat(fn.execute(2, 3).asInt()).isEqualTo(5);
        }
    }
}
```
> 执行说明:首次运行会经代理下载 GraalJS 依赖。若 `js.node-require` 需额外实验开关,以实际报错为准调整(记录在返回里)。

- [ ] **步骤 3:运行** `cd /d/code/dsh-java && ./gradlew test --tests "dev.dsh.cordis.js.GraalJsSmokeTest"` — 预期 PASSED
- [ ] **步骤 4:提交** `chore: add graaljs dependencies + smoke test`

---

### 任务 1:JsHost(Java)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/js/JsHost.java`
- 创建:`src/test/java/dev/dsh/cordis/js/JsHostTest.java`

**参考源(只读):** `D:\code\deepseek-harness\vendor\cordis\src\index.ts`(无直接对应,参照设计 §3.2)

**关键实现:**

`JsHost.java`:
```java
package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.nio.file.Path;

/** Manages the shared GraalJS context for JS plugins (design §3.2). */
public final class JsHost implements AutoCloseable {
    private final Context context;

    public JsHost() {
        this.context = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .option("js.node-require", "true")
                .build();
    }

    public JsHost(Path nodeModulesDir) {
        this.context = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .option("js.node-require", "true")
                .option("js.npm-resolve", nodeModulesDir.toString())
                .build();
    }

    /** Evaluate a JS expression and return the resulting value. */
    public Value eval(String script) {
        return context.eval("js", script);
    }

    /** Load a CommonJS module by specifier and return its exports. */
    public Value require(String specifier) {
        return context.eval("js", "require(" + quoted(specifier) + ")");
    }

    /** Load a module file and return its default export / exports value. */
    public Value loadModule(Path file) {
        return context.eval("js", "require(" + quoted(file.toAbsolutePath().toString()) + ")");
    }

    public Context graalContext() { return context; }

    @Override
    public void close() { context.close(); }

    private static String quoted(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
```
> 移植说明:`js.npm-resolve` 为 GraalJS node-compat 的 npm 解析根(任务 5 echo 用)。若该选项名不对,以实际报错调整。`require` 由 `js.node-require` 提供。

**测试:** `JsHostTest.java` 验证 eval + require 基础(冒烟测试已覆盖 eval,这里补 require 一个内联 CJS):
```java
package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JsHostTest {
    @Test
    void requireCommonJsModule() throws Exception {
        try (JsHost host = new JsHost()) {
            Value exports = host.eval("module.exports = { greet: (n) => 'hi ' + n }");
            Value greet = exports.getMember("greet");
            assertThat(greet.canExecute()).isTrue();
            assertThat(greet.execute("bob").asString()).isEqualTo("hi bob");
        }
    }
}
```
> 注:`host.eval("module.exports = ...")` 在同 context 内,CJS `module` 由 node-compat 提供。

- [ ] **步骤 1:创建 `JsHost.java` + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.js.JsHostTest"` — 预期 PASSED(若 `module.exports` 在 eval 中不可用,改用 `host.require` 或调整,记录)
- [ ] **步骤 3:提交** `feat: js host (graaljs wrapper)`

---

### 任务 2:JS ctx shim + JsCtxBridge(基础:on/emit/get/provide/effect)

**文件:**
- 创建:`src/main/resources/js/ctx.js`(JS ctx shim)
- 创建:`src/main/java/dev/dsh/cordis/js/JsCtxBridge.java`
- 创建:`src/test/java/dev/dsh/cordis/js/JsCtxBridgeTest.java`

**参考源(只读):** `vendor/cordis/src/context.ts`、`registry.ts`、`events.ts`(契约)

**关键实现:**

`src/main/resources/js/ctx.js`(JS ctx shim,经 polyglot 转发到 Java bridge):
```js
// cordis ctx 契约子集 shim。bridge 为 Java JsCtxBridge 对象。
module.exports = function createCtx(bridge) {
  const ctx = {
    on: (name, listener, opts) => bridge.on(name, listener, opts == null ? {} : opts),
    once: (name, listener, opts) => bridge.once(name, listener, opts == null ? {} : opts),
    emit: (name, ...args) => bridge.emit(name, args),
    provide: (name, value) => bridge.provide(name, value),
    get: (name) => bridge.get(name),
    inject: (deps, cb) => bridge.inject(deps, cb),
    effect: (disposer) => bridge.effect(disposer),
  }
  return ctx
}
```

`JsCtxBridge.java`(Java 侧,方法暴露给 JS):
```java
package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Events;
import dev.dsh.cordis.util.Disposable;
import org.graalvm.polyglot.Value;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bridges a JS ctx shim to the Java cordis Context (design §3.3). */
public final class JsCtxBridge {
    private final JsHost host;
    private final Context ctx;
    private Value shim;

    public JsCtxBridge(JsHost host, Context ctx) {
        this.host = host;
        this.ctx = ctx;
    }

    /** Create (once) and return the JS ctx shim bound to this bridge. */
    public Value ctxShim() {
        if (shim == null) {
            Value createCtx = host.eval(loadCtxJs());
            shim = createCtx.execute(host.graalContext().asValue(this));
        }
        return shim;
    }

    // ---- methods callable from JS ----

    public Object on(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        Events.Listener l = (c, args) -> listener.execute(args);
        return ctx.on(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    public Object once(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        Events.Listener l = (c, args) -> listener.execute(args);
        return ctx.once(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    public void emit(String name, Value argsArray) {
        Object[] args = toArgs(argsArray);
        ctx.emit(name, args);
    }

    public Object get(String name) {
        return ctx.get(name);
    }

    public Object provide(String name, Value value) {
        Object javaValue = value.isProxyObject() ? value.asProxyObject() : value;
        return ctx.provide(name, javaValue);
    }

    public Object effect(Value disposer) {
        Disposable d = () -> {
            disposer.execute();
            return CompletableFuture.completedFuture(null);
        };
        return ctx.effect(() -> d, "js-effect");
    }

    public Object inject(Object deps, Value callback) {
        // M2 简化:deps 为字符串数组;回调待注入满足后调用
        List<?> list = deps instanceof List<?> l ? l : List.of();
        String[] names = list.stream().map(String::valueOf).toArray(String[]::new);
        Events.Listener trigger = (c, args) -> { callback.execute(); return null; };
        return ctx.inject(dev.dsh.cordis.Inject.of(names), (c, cfg) -> callback.execute());
    }

    private Object[] toArgs(Value argsArray) {
        if (!argsArray.hasArrayElements()) return new Object[0];
        long len = argsArray.getArraySize();
        Object[] out = new Object[(int) len];
        for (int i = 0; i < len; i++) out[i] = argsArray.getArrayElement(i);
        return out;
    }

    private String loadCtxJs() {
        try (var is = getClass().getClassLoader().getResourceAsStream("js/ctx.js")) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load ctx.js", e);
        }
    }
}
```
> 移植说明:
> - `on` 的 JS listener 用 `listener.execute(args)` 调用,args 为 Java `Object[]`(GraalJS 自动映射)。
> - `provide` 存 `Value`(JS 对象暴露给 Java 为 Value,方法可 invoke);或经 `asProxyObject` 转代理(取决于插件用法)。
> - `inject` M2 简化:直接回调(不做依赖延迟),记为已知简化。
> - 跨语言服务契约 = 接口 + JSON 序列化签名;M2 用 Value 互操作起步。

**测试:** `JsCtxBridgeTest.java` 验证 on/emit 双向 + get:
```java
package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.PluginSpec;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class JsCtxBridgeTest {
    @Test
    void jsPluginEmitsBackToJava() throws Exception {
        Context root = new Context();
        try (JsHost host = new JsHost()) {
            AtomicReference<String> got = new AtomicReference<>();
            root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

            Value fn = host.eval("(ctx) => { ctx.on('app/ready', (msg) => { ctx.emit('done', 'hi ' + msg); }); }");
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            fn.execute(bridge.ctxShim());

            root.emit("app/ready", "bob");
            assertThat(got.get()).isEqualTo("hi bob");
        }
        root.fiber.dispose().join();
    }
}
```
> 注:此测试直接调 JS 函数 + shim,不经过 JsPluginAdapter(任务 3);`root.emit` 触发 JS listener → JS `ctx.emit('done', ...)` → bridge.emit → Java `root.emit('done', ...)` → Java listener 收到。

- [ ] **步骤 1:创建 ctx.js + JsCtxBridge.java + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.js.JsCtxBridgeTest"` — 预期 PASSED(若 GraalJS 互操作细节差异,按实际报错调整,记录)
- [ ] **步骤 3:提交** `feat: js ctx shim + bridge (on/emit/get/provide/effect)`

---

### 任务 3:JsPluginAdapter

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/js/JsPluginAdapter.java`

**参考源(只读):** `vendor/cordis/src/registry.ts:92-146`(Plugin 三形态归一)

**关键实现:**

`JsPluginAdapter.java`:
```java
package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;
import dev.dsh.cordis.util.Disposable;
import org.graalvm.polyglot.Value;

import java.util.concurrent.CompletableFuture;

/** Wraps a JS plugin (function or { apply } object) as a Java Plugin (design §3.4). */
public final class JsPluginAdapter implements Plugin<Object> {
    private final JsHost host;
    private final Value pluginValue;   // JS default export: function OR { apply, inject, provide, name }
    private final String name;
    private final String[] inject;
    private final String[] provide;

    public JsPluginAdapter(JsHost host, Value pluginValue) {
        this.host = host;
        this.pluginValue = pluginValue;
        this.name = memberString("name");
        this.inject = memberStringArray("inject");
        this.provide = memberStringArray("provide");
    }

    @Override public String name() { return name; }
    @Override public String[] inject() { return inject; }
    @Override public String[] provide() { return provide; }

    @Override
    public void apply(Context ctx, Object config) {
        JsCtxBridge bridge = new JsCtxBridge(host, ctx);
        Value jsCtx = bridge.ctxShim();
        Value applyFn = resolveApply();
        Object result = applyFn.execute(jsCtx, config);
        // 若 JS 插件返回 disposer 函数,注册为 fiber effect
        if (result instanceof Value v && v.canExecute()) {
            Value disposer = v;
            ctx.effect(() -> (Disposable) () -> {
                disposer.execute();
                return CompletableFuture.completedFuture(null);
            }, "js-plugin-disposer");
        }
    }

    private Value resolveApply() {
        if (pluginValue.canExecute()) return pluginValue;
        Value apply = pluginValue.getMember("apply");
        if (apply != null && apply.canExecute()) return apply;
        throw new IllegalArgumentException("JS plugin is neither a function nor { apply }");
    }

    private String memberString(String key) {
        Value v = pluginValue.getMember(key);
        return v != null && v.isString() ? v.asString() : null;
    }

    private String[] memberStringArray(String key) {
        Value v = pluginValue.getMember(key);
        if (v == null || !v.hasArrayElements()) return new String[0];
        long len = v.getArraySize();
        String[] out = new String[(int) len];
        for (int i = 0; i < len; i++) out[i] = v.getArrayElement(i).asString();
        return out;
    }
}
```
> 移植说明:echo 是对象-with-apply(design §3.6),`resolveApply` 取 `plugin.apply`。JS 插件附加元数据 `module.exports.inject/provide/name` 被读取为 adapter 的 `inject()/provide()/name()`。

- [ ] **步骤 1:创建 `JsPluginAdapter.java`**
- [ ] **步骤 2:提交** `feat: js plugin adapter (wrap js plugin as java plugin)`
  > 注:编译依赖任务 2 的 JsCtxBridge;若 `ctx.effect` 的 EffectBody 签名需调整,以编译报错为准,记录。

---

### 任务 4:跨语言 spike 测试(counter 链)

**文件:**
- 创建:`src/test/java/dev/dsh/cordis/js/CrossLanguageSpikeTest.java`

**关键实现:**

`CrossLanguageSpikeTest.java`:
```java
package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 设计 §4.1:Java provide counter → JS 插件 inject 并调用 → JS emit 回 Java。 */
class CrossLanguageSpikeTest {
    static final class Counter {
        int value = 0;
        int next() { return ++value; }
    }

    @Test
    void javaProvidesJsCallsAndEmitsBack() throws Exception {
        Context root = new Context();
        try (JsHost host = new JsHost()) {
            root.provide("counter", new Counter());

            AtomicReference<String> done = new AtomicReference<>();
            root.on("done", (c, args) -> { done.set(args[0] + "#" + args[1]); return null; });

            // JS 插件:inject counter,在 app/ready 时调用 counter.next() 并 emit 回 Java
            org.graalvm.polyglot.Value fn = host.eval(
                "(ctx) => { ctx.on('app/ready', () => { " +
                "  const c = ctx.get('counter'); ctx.emit('done', 'n', c.next()); " +
                "}); }");
            root.plugin(new JsPluginAdapter(host, fn), null);

            root.emit("app/ready", "started");
            assertThat(done.get()).isEqualTo("n#1");
        }
        root.fiber.dispose().join();
    }
}
```
> 说明:JS 插件经 `ctx.get('counter')` 拿 Java 服务(Value 互操作,`c.next()` 调 Java 方法)→ `ctx.emit('done', 'n', n)` → Java 收到。统一 registry(`root.plugin(adapter)`)。

- [ ] **步骤 1:创建 `CrossLanguageSpikeTest.java`**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.js.CrossLanguageSpikeTest"` — 预期 PASSED。若 `c.next()` 的 Value 方法调用、或 `ctx.emit` 多参映射有差异,按实际调整 bridge(记录)
- [ ] **步骤 3:提交** `test: cross-language spike (java provides, js calls, emits back)`

---

### 任务 5:koishi 模块 shim + command DSL(支撑 echo)

**文件:**
- 创建:`src/main/resources/js/koishi.js`(koishi 模块 shim:Schema/h/Random/Time)
- 创建:`src/main/resources/js/command.js`(command DSL 子集)
- 修改:`src/main/resources/js/ctx.js`(注入 ctx.command、ctx.i18n、ctx.bots 桩)
- 修改:`src/main/java/dev/dsh/cordis/js/JsCtxBridge.java`(暴露 command 注册)
- 创建:`src/test/java/dev/dsh/cordis/js/CommandDslTest.java`

**参考源(只读):** echo 源码经代理拉取:`curl -x http://127.0.0.1:7897 -s https://unpkg.com/@koishijs/plugin-echo@2.2.5/lib/index.js`(任务 5 步骤 1 执行)

**关键实现:**

`src/main/resources/js/koishi.js`(最小 shim,只实现 echo 用到的面):
```js
// 最小 koishi 模块 shim(design §3.6):Schema / h / Random / Time
function Schema(value) {
  return { __schema: true, value }
}
Schema.object = (shape) => { const s = Schema(shape); s.object = true; return s }
Schema.function = () => Schema(undefined)
Schema.array = (item) => Schema(item)
Schema.string = () => Schema('string')
Schema.number = () => Schema('number')
Schema.boolean = () => Schema('boolean')
Schema.transform = (fn) => { const s = Schema(undefined); s.transform = fn; return s }

const h = {
  parse: (text) => ({ text: String(text) }),
  escape: (text) => String(text).replace(/[<>]/g, (c) => c === '<' ? '&lt;' : '&gt;'),
}

const Random = {
  id: (len = 8) => Array.from({ length: len }, () => 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)]).join(''),
}

const Time = {
  minute: 60 * 1000,
  second: 1000,
}

module.exports = { Schema, h, Random, Time, Context: class Context {} }
```
> 移植说明:echo 只用 `Schema.object({})`、`h.parse`、`h.escape`。其余按需扩展(记录)。

`src/main/resources/js/command.js`(command DSL 子集,注入到 ctx):
```js
// 最小 command DSL:ctx.command('echo <message:text>').option(...).action(...)
module.exports = function installCommand(ctx, bridge) {
  const registry = bridge.commandRegistry()   // Java 侧命令注册表(桥)
  ctx.command = (def) => {
    const parsed = parseCommandDef(def)
    const builder = {
      _options: [],
      option(name, alias, opts) { this._options.push({ name, alias, opts: opts || {} }); return this },
      userFields() { return this },
      alias() { return this },
      action(fn) { registry.register(parsed.name, parsed.argDef, this._options, fn); return this },
    }
    return builder
  }
}

function parseCommandDef(def) {
  // 'echo <message:text>' → name 'echo', arg template 'message:text'
  const m = def.trim().split(/\s+/)
  return { name: m[0], argDef: m.slice(1).join(' ') }
}
```
> 说明:命令触发时 Java 侧调用 registry 的 action,构造 session 对象(`{ text, guildId, ... }`)与 options 传入。最小实现:action 的返回值作为 bot 回复。

**修改 ctx.js**:加 `ctx.command`(经 command.js)、`ctx.i18n.define`、`ctx.bots.find` 桩、`ctx.logger`:
```js
module.exports = function createCtx(bridge) {
  const ctx = {
    on: (name, listener, opts) => bridge.on(name, listener, opts == null ? {} : opts),
    once: (name, listener, opts) => bridge.once(name, listener, opts == null ? {} : opts),
    emit: (name, ...args) => bridge.emit(name, args),
    provide: (name, value) => bridge.provide(name, value),
    get: (name) => bridge.get(name),
    inject: (deps, cb) => bridge.inject(deps, cb),
    effect: (disposer) => bridge.effect(disposer),
    logger: (name) => ({ error: () => {}, info: () => {}, warn: () => {}, debug: () => {} }),
    i18n: { define: (locale, dict) => {}, },
    bots: { find: () => null },
  }
  require('./command.js')(ctx, bridge)
  return ctx
}
```
> `require('./command.js')` 在 node-compat 下相对解析;若受限,改用 `bridge.eval` 注入(command.js 内容内联到 ctx.js 或经桥注入)。

**JsCtxBridge.java** 加 `commandRegistry()`:
```java
    private final Map<String, CommandEntry> commands = new java.util.LinkedHashMap<>();

    public Object commandRegistry() {
        return commands;   // JS 侧 registry.register(name, argDef, options, fn)
    }
```
> 命令触发(Java 模拟消息):`JsCtxBridge.dispatchCommand(String message)` — 解析首个词匹配命令,构造 session `{text, options}`,调 action,返回回复。此方法由 echo spike 测试调用(设计 §4.2)。

**测试:** `CommandDslTest.java`(echo 核心语义:命令注册 + action 触发):
```java
package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommandDslTest {
    @Test
    void commandActionRunsAndReturnsReply() throws Exception {
        try (JsHost host = new JsHost()) {
            JsCtxBridge bridge = new JsCtxBridge(host, null);   // 命令 DSL 测试不需完整 ctx
            Value fn = host.eval("(ctx) => { ctx.command('echo <message:text>').action(({session, options}, message) => 'echo: ' + message); }");
            fn.execute(bridge.ctxShim());
            // bridge 需暴露一个 dispatchCommand 用于测试触发(见 JsCtxBridge)
            Object reply = bridge.dispatchCommand("echo hello");
            assertThat(String.valueOf(reply)).isEqualTo("echo: hello");
        }
    }
}
```
> 注:`JsCtxBridge` 的构造在命令 DSL 测试中用 null ctx,但 dispatchCommand 不需 ctx。实现时若 `ctxShim()` 依赖 ctx,调整测试构造(或用最小 Context)。

- [ ] **步骤 1:拉取 echo 源码** `curl -x http://127.0.0.1:7897 -s https://unpkg.com/@koishijs/plugin-echo@2.2.5/lib/index.js` 保存到 `/tmp/echo.js`,读它确认精确的 ctx API 面(option 名、action 签名、i18n/bots 用法),据此微调 koishi.js 与 command.js
- [ ] **步骤 2:创建 koishi.js + command.js + 修改 ctx.js + JsCtxBridge(命令注册 + dispatchCommand)**
- [ ] **步骤 3:运行** `./gradlew test --tests "dev.dsh.cordis.js.CommandDslTest"` — 预期 PASSED
- [ ] **步骤 4:提交** `feat: koishi module shim + command DSL`

---

### 任务 6:echo 插件 spike 测试

**文件:**
- 创建:`src/test/java/dev/dsh/cordis/js/EchoPluginTest.java`
- 创建:`src/test/resources/echo/`(echo 插件本体 + node_modules/koishi → 指向 koishi.js shim,若 node-compat 需要)

**关键实现:**

`EchoPluginTest.java`(设计 §4.2:加载 echo → 模拟消息 → action 返回 → 捕获 bot 回复):
```java
package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 设计 §4.2:加载真实 @koishijs/plugin-echo,Java 触发命令,捕获回复。 */
class EchoPluginTest {
    @Test
    void loadRealKoishiPluginAndTrigger() throws Exception {
        Context root = new Context();
        try (JsHost host = new JsHost(nodeModulesRoot())) {
            // 加载 echo(对象-with-apply)
            Value echo = host.loadModule(echoLibFile());
            root.plugin(new JsPluginAdapter(host, echo), null);

            // Java 模拟 session 消息触发命令
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            Object reply = bridge.dispatchCommand("echo hello world");
            assertThat(String.valueOf(reply)).contains("hello world");
        }
        root.fiber.dispose().join();
    }

    private java.nio.file.Path echoLibFile() {
        return java.nio.file.Path.of("src/test/resources/echo/node_modules/@koishijs/plugin-echo/lib/index.js").toAbsolutePath();
    }
    private java.nio.file.Path nodeModulesRoot() {
        return java.nio.file.Path.of("src/test/resources/echo/node_modules").toAbsolutePath();
    }
}
```
> 说明:
> - `src/test/resources/echo/node_modules/@koishijs/plugin-echo/` 放 echo 的 package.json + lib/index.js(经代理 curl 拉取,任务 5 已存);`node_modules/koishi/index.js` 指向 koishi.js shim(或经 `js.npm-resolve` 根解析)。
> - `dispatchCommand` 是任务 5 JsCtxBridge 的方法:解析首词 → 匹配命令 → 构造 session → 调 action → 返回回复。echo 的 action 返回消息数组/字符串,bot 捕获。
> - 若 echo 的 command 面过重(option 解析、session 面等),按设计 §4 回退本地插件(`dsh-repeat-tool-reminder` 或 `dsh-session-stats`),本测试改为对应语义;回退时在返回中记录。

- [ ] **步骤 1:布置 echo 资源**(curl 拉 lib/index.js + package.json 到 `src/test/resources/echo/node_modules/@koishijs/plugin-echo/`;koishi shim 链接)
- [ ] **步骤 2:创建 `EchoPluginTest.java`**
- [ ] **步骤 3:运行** `./gradlew test --tests "dev.dsh.cordis.js.EchoPluginTest"` — 预期 PASSED。若 echo 需要更多 koishi 面(Schema 校验、session 属性、option 解析),逐步补 koishi.js/command.js(记录每个补的 API)
- [ ] **步骤 4:提交** `test: real koishi echo plugin loaded and triggered`

---

### 任务 7:构建验证 + 收尾

- [ ] **步骤 1:`./gradlew clean build` — 预期 BUILD SUCCESSFUL,全部测试通过**
- [ ] **步骤 2:确认 `git status` 干净**
- [ ] **步骤 3:提交** `chore: milestone 2 complete (graaljs js-plugin-host)`

---

## 自我审查(执行前读一遍)

**1. 规范覆盖:** 设计 §3.2(JsHost)→任务1;§3.3(shim)→任务2;§3.4(adapter)→任务3;§4.1(跨语言链)→任务4;§3.6+§4.2(echo)→任务5-6;§3.7(构建)→任务0。缺口:设计 §3.5 的 `ctx.on` next 链 + `{prepend, global}` 语义——任务 5 的 echo 只用 `ctx.command`,next 链在回退插件(任务 6 回退时)才需要;已记录,若 echo 主路径未用则任务 6 主路径不阻塞。设计 §3.3 的 `ctx.plugin`(嵌套)未入 M2 任务——YAGNI(echo 不用),记为 M3。

**2. 占位符扫描:** 无 TODO/TBD。`JsHost` 的 `js.npm-resolve` 选项名、`module.exports` 在 eval 中的可用性、echo 的具体 API 面均标注"以实际报错调整",属执行时确认,非占位。

**3. 类型一致性:** `JsCtxBridge.on/once` 返回 `ctx.on(...)`(Disposable);`emit` 的 `Value argsArray` 转 `Object[]`;`inject` 用 `Inject.of`;`JsPluginAdapter` 的 `resolveApply` 处理函数与对象两形态;`ctx.effect` 的 EffectBody 返回 Disposable。各任务签名前后一致。

**4. 执行顺序:** 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7(依赖序)。任务 3 编译依赖任务 2 的 JsCtxBridge;任务 5 依赖任务 2。
