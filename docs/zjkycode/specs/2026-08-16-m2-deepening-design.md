# dsh-java:里程碑 2 深化设计 —— JS 桥保真度

- 日期:2026-08-16
- 状态:已批准(用户审核通过)
- 范围:M2 深化(JS 桥更完整),在 M2(GraalJS 桥 + echo 插件)之上

## 1. 背景与目标

M2 已验证"Java 触发 → JS 插件响应"(echo 插件加载并返回)。M2 深化让 JS 桥接近 cordis 真实语义,支撑更多社区插件与真实跨语言服务。

## 2. 范围(优先级排序)

| 项 | 内容 | 决策 |
|---|---|---|
| **真实服务 RPC** | 跨语言服务 = 接口 + JSON 可序列化签名(M1 契约落地) | 高,做 |
| **next 链 + prepend/global** | `ctx.on` 补全 cordis 拦截语义 | 高,做 |
| **command option 解析** | echo 的 `-e`/`-E` 等选项 | 中,做 |
| **async apply** | `Plugin.apply` 支持 `CompletableFuture`(设计契约) | 中,做 |
| **Node worker seam** | `JsHost` 接口化,可换进程外 Node | 中,做 |
| `ctx.plugin` 嵌套 | 嵌套插件加载 | 低,defer(M3 后) |

## 3. 设计

### 3.1 真实服务 RPC(契约 = 接口 + JSON 序列化签名)

- JS 侧服务经 `ctx.provide(name, value)` 提供;Java 侧取到 `Value`。
- Java 侧服务经 `ctx.get(name)` 暴露给 JS;JS 调其方法(HostAccess.ALL 已就位)。
- **契约约束**:跨语言服务的参数/返回值为 JSON 可序列化(原始类型、字符串、数组、Map);不传活对象句柄。
- 新增 `ServiceProxy`(Java):把一个 Java 接口的实现经 `Value` 暴露给 JS(方法名→JSON 参数)。`JsCtxBridge.provide` 升级:若 value 是 Java 服务对象,包装为可调 JS 对象。
- 反向:JS 提供的服务在 Java 侧以 `Value` 呈现,调用经 `Value.invokeMember`;若需强类型,用 `ServiceProxy` 反向包装(接口代理)。

### 3.2 next 链 + prepend/global(`ctx.on` 补全)

- cordis listener 签名 `(…args, next) => any`:监听器可调 `next()` 委派后续监听器(serial/waterfall 语义)。
- **M1 的 `Events.on` 现为 `emit`(同步全部调用)**:深化方案——`ctx.on` 注册的监听器在 dispatch 时按序调用,若 listener 收 next 则传一个 continuation 闭包(JS 函数);不调 next 则短路。
- 具体:`JsCtxBridge.on` 包装 listener 时,判断 JS 函数的 `length`(形参数);若比 dispatch 参数多 1,则追加 next 闭包参数(经 `ProxyExecutable` 调后续监听器)。
- `{prepend, global}` 选项已由 M1 `Events.EventOptions` 支持,桥已透传;深化补测试覆盖。

### 3.3 command option 解析

- `ctx.command('echo <message:text>').option('escape','-e',{value:false}).option(...).action(...)`。
- `dispatchCommand` 解析消息中的 `-e`/`--escape` 等选项,填充 `options` 对象传给 action。
- 实现:JS 侧 command builder 收集 option 定义(已做);`dispatchCommand` 增加 token 解析(短选项 `-x`、长选项 `--xxx`、`-x value`),填入 `options`。

### 3.4 async apply

- `Plugin.apply` 改为返回 `Object`(void | `CompletableFuture<?>`);`PluginApply` 同步。
- `Fiber.reload()` 对返回的 CF await(完成后再 ACTIVE + notify);对 `CompletableFuture<Void>` 与 `CompletableFuture<Disposable>` 区分处理。
- M2 桥:`JsPluginAdapter.apply` 若 JS 插件返回 Promise(GraalJS Value),经 `.then` 桥接为 CompletableFuture 返回。
- **契约变更**:改 `Plugin`/`PluginSpec`/`Fiber`/`JsPluginAdapter` 四处,同步设计文档 §3.4(移除"M1 裁剪"标注)。

### 3.5 Node worker seam(`JsHost` 接口化)

- 定义 `JsHost` 接口(load/eval/close),现有 GraalJS 实现改名 `GraalJsHost`。
- `JsPluginAdapter`/`JsCtxBridge` 依赖接口不依赖具体类。
- Node worker 实现(`NodeWorkerJsHost`)留接口占位,M3 后实现(JSON-RPC 协议)。

### 3.6 测试

- `ServiceRpcTest`:Java 服务接口 ↔ JS 调用(JSON 参数/返回);JS 服务 → Java 调用。
- `NextChainTest`:监听器调 next 委派;不调 next 短路;prepend/global。
- `CommandOptionTest`:echo `-e` 走 escape 分支。
- `AsyncApplyTest`:JS 插件 async apply 返回 Promise,await 后才 ACTIVE。

## 4. 构建

无新依赖(GraalJS 已就位)。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| next 链语义与 cordis 细微偏差 | 对照 `events.ts` serial/waterfall;测试锁定 |
| 服务 RPC 的 JSON 序列化边界 | 契约明确"接口 + JSON 签名";非序列化类型报错 |
| async apply 改签名波及面 | 4 处同步改;全量测试回归 |
| Promise→CompletableFuture 桥接时序 | 用 ProxyExecutable `.then` + CF;测试锁定 await 后 ACTIVE |
