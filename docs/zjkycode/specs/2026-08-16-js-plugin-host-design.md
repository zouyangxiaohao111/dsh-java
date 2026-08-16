# dsh-java:里程碑 2 设计 —— JS 插件 host(GraalJS 桥 + JsPluginAdapter)

- 日期:2026-08-16
- 状态:已批准(用户审核通过)
- 里程碑 2 范围:JsPluginAdapter + GraalJS 桥 + 真实 koishi/cordis 插件(函数式优先)

## 1. 背景与目标

里程碑 1(M1)已完成:`D:\code\dsh-java` 中 `dev.dsh.cordis` 核心忠实复刻 cordis(25 测试全绿)。M2 的目标是打通"一套机制、两套运行时"的融合:

- **核心命题**:Java harness 触发事件 → JS 社区插件(koishi/cordis 生态)`ctx.on` 响应。
- **机制**:`JsPluginAdapter` 把 JS 插件包成 Java `Plugin`,注册进 M1 的统一 registry;事件双向、服务跨语言(Value 互操作)。

**用户决策**:进程内 GraalJS(先试,留 seam);范围 = 桥 + 真实 koishi 插件。

## 2. 关键决策

| 决策 | 选择 | 理由 |
|---|---|---|
| JS 运行时 | **进程内 GraalJS**(`org.graalvm.polyglot:js:24.1.1`,已验证经代理可拉取) | 桥最简、单进程、polyglot 直通(函数双向、Promise↔CompletableFuture);native addon 插件跑不了(选插件避开),JS 单线程可接受 |
| 隔离 | 1 共享 Engine + **每插件 1 个 Context** | 插件间隔离;JS 单线程语义与 cordis 一致 |
| 契约子集 | JS ctx shim 实现 `on/once/emit/provide/get/inject/effect` 起步 | 覆盖函数式插件最常见面;类式 Service 插件(需 Service 基类)降级 |
| 真实插件 | 函数式 cordis/koishi 插件(module.exports = function(ctx, config){...}),无 native 依赖、import 图最小 | CJS 在 GraalJS node-compat 最稳;shim 面最小 |
| 桥的 seam | `JsHost` 接口隔离 GraalJS 实现 | 遇 native addon 插件可换 Node worker(进程外) |

## 3. 架构

### 3.1 组件

```
dev.dsh.cordis            (M1 核心,不动)
dev.dsh.cordis.js         (M2 新增)
  ├── JsHost              管理 GraalJS Engine/Context;load 模块;持有 JS 插件函数 Value
  ├── JsPluginAdapter     implements Plugin<Object>;apply(ctx,config) 调 JS 插件函数
  └── JsCtxBridge         创建 JS ctx shim 对象,转发到绑定 Context/Fiber
src/main/resources/js/
  └── ctx.js              cordis ctx 契约 shim(on/once/emit/provide/get/inject/effect)
```

### 3.2 JsHost

- 持有 GraalJS `Engine`(共享)+ 每插件一个 `Context`。
- `Value load(String specifier)`:经 node-compat 解析 JS 模块,取 default export(插件函数)。
- `Value createCtxShim(Context javaCtx)`:创建 JS ctx 对象,绑定到插件 fiber 的 Java Context。

### 3.3 JS ctx shim(ctx.js)

实现 cordis ctx 契约子集,每个方法经 polyglot 转发到 Java:

```js
// 绑定参数:bridge(Java JsCtxBridge)、fiberName
module.exports = (bridge, fiberName) => {
  const ctx = {}
  ctx.on = (name, listener, opts) => bridge.on(name, listener, opts)   // listener 为 JS 函数,Java 端可调用
  ctx.once = (name, listener, opts) => bridge.once(name, listener, opts)
  ctx.emit = (name, ...args) => bridge.emit(name, ...args)
  ctx.provide = (name, value) => bridge.provide(name, value)            // value 为 JS 值,暴露给 Java
  ctx.get = (name) => bridge.get(name)                                  // Java 服务 → JS Value
  ctx.inject = (deps, cb) => bridge.inject(deps, cb)
  ctx.effect = (disposer) => bridge.effect(disposer)                    // disposer 为 JS 函数
  return ctx
}
```

listener 归属:M1 的 `Events.on` 已 caller-aware,shim 经 `JsCtxBridge.on` 传 `javaCtx` 为 caller。

**扩展面(探查子代理 2026-08-16 评估,按优先级)**:
- 高:`ctx.on` 的 next 链语义 + `{prepend, global}` 选项(拦截/顺序类插件依赖);
- 高:`ctx.command` 最小 DSL(`ctx.command('echo <message:text>').option(...).action(...)`,注册到命令注册表,触发时以 session 调用 action);
- 高:`require('koishi')` 模块 shim(`Schema.object`、`h.parse/escape`;必要时 `Random`/`Time`/`Context`);
- 中:`ctx.plugin`(嵌套)、`ctx.logger(name)`、`ctx.i18n.define(locale, dict)`、`ctx.bots.find(pred)` + bot send 桩;
- 中低:`ctx.setTimeout/interval/debounce/throttle`(基于 effect + JS 定时器)、`ctx.root`/`ctx.fiber`。

### 3.6 真实插件目标(探查子代理选定)

**首选:`@koishijs/plugin-echo` v2.2.5**(npm)
- 形态:CJS `module.exports = { Config, apply, name, parsePlatform }`(cordis 对象-with-apply 分支直接命中);
- 依赖:**0 运行时依赖**,仅 peer koishi;
- ctx API:`ctx.i18n.define`、`ctx.command('echo <message:text>').option(...).action(...)`、`ctx.bots.find`;
- 模块加载期 `Schema.object({})`、action 内 `h.parse`/`h.escape`。

**本地回退**(若 echo 的 command 面过重):
- `@deepseek-ai/dsh-repeat-tool-reminder`(函数式,压测 `ctx.on` next/prepend,需 schemastery + dsh-llm 可解析);
- `@deepseek-ai/dsh-session-stats`(29 行,最小 inject 证明,需 zod + dsh-llm + sessionProjections 服务)。

### 3.7 构建

`build.gradle.kts` 加:

```kotlin
implementation("org.graalvm.polyglot:polyglot:24.1.1")
implementation("org.graalvm.polyglot:js:24.1.1")
```

### 3.4 JsPluginAdapter implements Plugin<Object>

- `inject()/provide()/name()`:读 JS 插件附加元数据(`module.exports.inject` / `.provide` / `.name`);未附加则空。
- `apply(ctx, config)`:
  1. 创建 shim(bind 到 `ctx` 的 fiber);
  2. 调 JS 插件函数 `fn(shim, config)`;
  3. 若返回 JS 函数(disposer),经 `ctx.effect` 注册(JS disposer → 反注册)。
- `config()`:M1 裁剪(异步 config M2 后),默认 null。

### 3.5 事件桥与跨语言服务

- **Java → JS**:`JsCtxBridge.emit` 调 Java `ctx.emit`;JS 侧 listener 由 Java 经 `Value.invokeMember` 调用。
- **JS → Java**:`bridge.get(name)` 返回 Java 服务的 polyglot 代理(方法调用直通)。
- **契约**:跨语言服务 = 接口 + JSON 可序列化签名(M1 已定)。

## 4. 里程碑验收

spike 测试(`src/test/java/dev/dsh/cordis/js/`):

1. **跨语言链**:Java 插件 provide `counter` → JS 插件 `inject(['counter'])` 并调用 `counter.next()` → JS `ctx.emit('done', n)` → Java `ctx.on('done')` 收到(统一 registry,同一个 Context)。
2. **真实 koishi 插件(echo)**:加载 `@koishijs/plugin-echo` v2.2.5 → Java 侧模拟 session 触发 `ctx.command`(如消息 "echo hello")→ echo 的 `action` 返回 "hello" → 捕获 bot 消息(证明 Java 触发 → koishi 插件响应)。需 koishi 模块 shim + command DSL + i18n/bots 桩。
3. **统一 registry**:全部走 `root.plugin(new JsPluginAdapter(...))`。

> 若 echo 的 command 面过重,回退本地插件(`dsh-repeat-tool-reminder` 或 `dsh-session-stats`),验收 2 改为对应语义(事件 next/prepend 或最小 inject)。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| native addon 插件跑不了 | 选插件时避开 native 依赖;留 `JsHost` seam 换 Node worker |
| ESM 模块解析受限 | 优先 CJS;ESM 用 GraalJS 实验开关按需启用 |
| JS 单线程 | 事件分发串行,与 cordis 事件循环一致;虚拟线程不跨 JS context |
| shim 面不足(真实插件用到未实现 API) | 按需扩展 shim;跑不通的降级报告(记录缺的 API) |
| GraalJS 内存/冷启动 | 每插件 1 context,插件数少时可控;后续可池化 |
