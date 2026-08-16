# dsh-java:里程碑 3 设计 —— 热重载

- 日期:2026-08-16
- 状态:已批准(用户审核通过)
- 范围:M3 热重载(Java ClassLoader + JS 上下文重启 + WatchService),在 M2(M1 核心 + M2 桥)之上

## 1. 背景与目标

M1 复刻了 cordis 核心(含 inject 驱动的响应式重载),M2 打通了 JS 桥。M3 加**代码热重载**:改插件源码/文件 → 自动换实现(Java 换 ClassLoader,JS 重启上下文),复用 M1 的 fiber dispose/reload 机制。

## 2. 设计

### 2.1 Java 插件热重载(每插件 ClassLoader)

```
WatchService(监听插件源/class 目录)
   → 文件变更 → 触发重载
   → 新 URLClassLoader(parent = 核心 CL)加载新 .class
   → registry.delete(旧 plugin)(dispose 旧 fiber)→ 用新 Class 注册新 plugin(新 fiber)
   → 失败回滚:旧 ClassLoader 保留,新注册失败则恢复旧
```

- **共享接口放父 CL**:`Plugin`/`Context`/`Service` 等由核心 ClassLoader 加载,插件 .class 由每插件子 CL 加载,避免类身份冲突。
- **源码级重载**:用 `javax.tools.JavaCompiler` 把 `.java` 编译为 `.class` 到临时目录,再新 CL 加载(设计默认源码级,因 harness 以源码为准)。
- **文件监听**:`java.nio.file.WatchService`(非递归需手动走树,或用简单轮询)。
- **class 身份注意**:新 CL 加载的 `Plugin` 实现与旧的不共享类身份,但都 `implements Plugin`(父 CL 接口),`identity` 注册表按新对象为准。

### 2.2 JS 插件热重载(上下文重启)

```
WatchService(监听 JS 插件文件)
   → 文件变更 → 触发重载
   → 旧 JsHost 上下文 close → 新 JsHost 加载新插件 → registry.delete(旧)→ 注册新
```

- JS 重启 = 换 JsHost 上下文(JS 无缓存清空负担,重启即重载,比 Node 清缓存还干净)。
- 保留 cordis HMR 的"失败回滚":新上下文加载失败则保留旧。

### 2.3 触发与编排

- 新增 `PluginReloader`(Java):
  - 持有 `WatchService` + 插件文件→plugin 映射。
  - `reload(plugin)` 统一入口:Java 插件走 ClassLoader,JS 插件走上下文重启。
  - 复用 M1 `Registry.delete` + `registry.plugin`(dispose 旧 fiber + 注册新)。
- 事件:`ctx.emit('plugin/reloaded', plugin, oldFiber, newFiber)`。

### 2.4 测试

- `JavaHotReloadTest`:写一个插件 .java → 加载 → 改源码(加日志/改行为)→ 触发重载 → 断言新行为生效、旧 fiber disposed。
- `JsHotReloadTest`:加载 JS 插件(echo 或简化)→ 改 JS 文件 → 触发重载 → 断言新行为、旧上下文关闭。
- `ReloadRollbackTest`:改坏源码 → 重载失败 → 旧实现保留。

## 3. 构建

无新依赖(JDK 内置 WatchService/JavaCompiler)。

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| 类身份冲突(新旧 ClassLoader) | 共享接口放父 CL;插件间不直接互引类型(经 ctx 服务) |
| ClassLoader 泄漏(Metaspace) | 旧 CL 无引用即可 GC;测试验证 |
| 源码编译失败回滚 | 先编译后切换,失败保留旧 |
| WatchService 非递归/平台差异 | 递归目录手动注册子目录;或用简单轮询兜底 |
| JS 上下文重启时序 | 旧 close 先,新加载后注册;失败回滚旧 |

## 5. 与 M1 响应式重载的关系

M1 的"inject 驱动重载"是**依赖变更→依赖方重载**;M3 是**文件变更→插件自身换实现**。两者正交,M3 复用 M1 的 fiber dispose/reload 机制做换血。
