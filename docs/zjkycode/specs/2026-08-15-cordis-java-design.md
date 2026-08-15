# dsh-java:cordis 核心 Java 复刻设计

- 日期:2026-08-15
- 状态:已批准(用户审核通过)
- 里程碑 1 范围:核心 + 原生 Java 插件(不包含 GraalJS host,其为里程碑 2)

## 1. 背景与目标

`deepseek-harness`(dsh)是一个基于 [cordis](https://github.com/cordiverse/cordis) 的 TS/Node monorepo,核心已 vendored 于 `vendor/cordis`。cordis 的核心价值是:

1. **Context/DI**:按名字解析服务的依赖注入容器(名字驱动,天然跨语言对称)。
2. **Fiber 生命周期**:插件实例的状态机(PENDING/LOADING/ACTIVE/FAILED/UNLOADING/DISPOSED)+ 作用域化 disposer 反序清理。
3. **inject 驱动响应式重载**:一个插件 provide 了服务,依赖它的所有插件自动 unload + 按新依赖图重跑(`fiber.ts:625` 的 epoch 重算)。这是 cordis 的灵魂,主流容器(Spring 等)均不提供。
4. **事件系统**:5 种 dispatch(emit/parallel/serial/bail/waterfall)+ 上下文过滤。

**总目标**:在 JDK 25 上用 Java 忠实复刻 cordis 核心,构建"一套机制、两套运行时":

- 契约层(Plugin/Context/Fiber/Events/inject/provide)为**语言无关的统一契约**;
- Java 插件原生运行于核心内(ClassLoader 热重载,里程碑 3);
- JS 社区插件(koishi/cordis 生态)通过 `JsPluginAdapter` 包成 Java Plugin 对象接入统一注册表(GraalJS host,里程碑 2)。

**里程碑 1(本次)**:核心 + 原生 Java 插件,验证 cordis 语义在 Java 中成立。

## 2. 关键决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 工程位置 | `D:\code\dsh-java`(独立 git 仓库) | 与 dsh TS monorepo 平级、互不污染 |
| 构建工具 | Gradle(Kotlin DSL),wrapper 引导 | 多模块友好,JDK 25 支持好 |
| 代理 | `127.0.0.1:7897`(env + gradle.properties 双保险) | 用户网络环境 |
| 里程碑范围 | 核心 + Java 插件(方案 A 忠实移植) | 先验证地基语义 |
| 实现方向 | **忠实移植 cordis 动态模型** | 统一契约要求两边身份对称(都按名字依赖/提供);Java 类型安全用泛型 + accessor 接口层补充,不牺牲动态性 |
| 异步模型 | `CompletableFuture` | 镜像 JS Promise 语义;GraalJS 桥原生支持 Promise↔CompletableFuture 互转,为里程碑 2 铺路 |

## 3. 架构

### 3.1 包结构(核心库 `dsh-cordis`)

```
dev.dsh.cordis
  ├── Context                  核心容器:get/provide/inject/plugin/effect/on/emit + 子上下文
  ├── Plugin<T>                插件契约:apply(ctx, config) + inject + provide + name
  ├── Service                  抽象基类:构造时自动 provide 进 ctx,随 owner fiber 卸载
  ├── Fiber                    生命周期状态机(含 epoch 重载)
  ├── FiberState               枚举:PENDING/LOADING/ACTIVE/FAILED/UNLOADING/DISPOSED
  ├── Registry                 plugin() 入口 + Plugin.Runtime 注册表
  ├── Events                   事件总线:on/emit/parallel/serial/bail/waterfall
  ├── Reflect                  服务存储:Map<隔离label, Impl> + notify() 唤醒依赖方
  ├── Inject                   依赖声明(名字数组 / name→config 映射)
  ├── Effect                   作用域化 disposer 注册
  └── util/DisposableList      反序清理的 disposer 栈
```

包名 `dev.dsh.cordis` 为提案,可在实施中调整。

### 3.2 契约层(对照 JS)

```java
// JS: Object.assign((ctx, config) => {...}, { inject: ['counter'] })
Plugin<Void> greeter = Plugin.of((ctx, cfg) ->
        ctx.on("app/ready", msg -> ctx.<Counter>get("counter").next()))
        .inject("counter");   // 名字驱动依赖,与 JS 完全对称

// 类插件(对应 JS 的 class + @Inject 装饰器)
@Inject({"counter"})
public class Greeter implements Plugin<Void> { ... }
```

- `Service` 基类构造函数调 `ctx.provide(name, this)`,随 fiber 卸载自动移除(对应 `service.ts:42`)。
- `Plugin<T>` 支持函数式 `Plugin.of(...)`(fluent builder 携带 inject/provide/Config/name)与类式实现两种形态。

### 3.3 Fiber 生命周期 + inject 驱动重载(灵魂,忠实移植)

状态机逐态对照 `fiber.ts:184-753`:

```
plugin() → Fiber(PENDING)
   → 逐依赖 _checkImpl(name):从 Reflect 解析服务,缺失则保持 PENDING
   → 全部就绪 → _refresh():epoch = join(各依赖 impl.fiber.uid)
   → epoch 由 INACTIVE 变有效 → _reload():
         internal/config waterfall 解析 config → apply(ctx, config)
         → 插件内 effect()/on()/provide() 全部注册到本 fiber → ACTIVE

服务变更时(provide/unprovide):
   → Reflect.notify(name) → 遍历注册表里 inject 该名的 fiber
   → _checkImpl + _refresh → epoch 变 → _unload(disposers 反序)→ _reload

dispose():
   → disposers 反序清理(对应 fiber.ts:675)→ DISPOSED
```

异步:插件 `apply` 可返回 `void` 或 `CompletableFuture<?>`;disposer 可返回 `void` 或 `CompletableFuture<Void>`;`fiber.await()/dispose()/update()` 均返回 `CompletableFuture`。

### 3.4 事件系统

5 种 dispatch 逐一对应 `events.ts:183-243`:

```java
ctx.on("message", l)               // 注册,随 fiber 卸载
ctx.emit("message", arg)           // 同步,忽略返回值
ctx.parallel("msg", arg)           // CompletableFuture,等全部
ctx.serial("msg", arg)             // 依序 await,遇 bail 值停
ctx.bail("msg", arg)               // 同步,首个 bail 值返回
ctx.waterfall("msg", next)         // 中间件链,next 穿透(对应 internal/update 等)
```

监听器属于注册它的 fiber(`events.ts:254`),卸载自动移除;isolate 隔离过滤对应 `events.ts:171-174`。

## 4. 里程碑验收(核心 + Java 插件)

示例场景(复刻 cordis README Quick Start):

```java
Plugin<?> counter = Plugin.of((ctx, cfg) -> {}).provide("counter", new Counter());
Plugin<Void> greeter = Plugin.of((ctx, cfg) ->
        ctx.on("app/ready", msg -> log.info("%s #%d", msg, ctx.<Counter>get("counter").next())))
        .inject("counter");

Context root = new Context();
root.plugin(counter);
root.plugin(greeter);
root.emit("app/ready", "started");   // 输出 "started #1"
await root.fiber().dispose();
```

JUnit 5 + AssertJ 测试覆盖("忠实"的验收标准):

1. **inject 延迟启动** — 先注册 greeter 再注册 counter,counter 出现前 greeter 保持 PENDING,出现后自动激活;
2. **依赖变更自动重载** — dispose 掉 counter 提供者,greeter 自动 unload;换实现重新 provide,greeter 用新实现重载;
3. **fiber 反序清理** — 效果注册顺序与清理顺序相反;
4. **事件 5 种模式** — 各 dispatch 语义断言;
5. **isolate 隔离** — 子作用域服务互不污染。

## 5. 后续里程碑(不在本次范围)

- **M2:GraalJS host** — `JsPluginAdapter` 把 JS 社区插件包进统一注册表;事件双向、跨语言服务 RPC(契约 = 接口 + JSON 可序列化签名)。
- **M3:热重载** — Java 插件 ClassLoader 隔离;JS 插件重启 JS 上下文。
- **M4:框架插件移植** — loader/include/group/timer 等 cordis 官方插件的 Java 版(参考 `vendor/` 下源码)。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| epoch 重算与 JS 语义不完全一致 | 以 `fiber.ts` 为规格逐态对照,JUnit 覆盖状态转换 |
| Java 强类型 vs 动态名字契约的张力 | 泛型 `<T> T get(String)` + accessor 接口层,不在契约层引入强类型服务 |
| CompletableFuture 与事件循环语义 | 明确同步/异步边界;emit 同步、parallel/serial 异步 |
