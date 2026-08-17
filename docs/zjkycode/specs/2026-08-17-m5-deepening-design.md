# dsh-java:里程碑 5 深化设计 —— worker 并行 + Async worker 事件循环

- 日期:2026-08-17
- 状态:已批准(用户审核通过,"先进行M5深化 再去进行M6")
- 范围:① 多 worker 并行(每插件独立 worker);② Async worker 事件循环(macrotask 支持)

## 1. 背景与目标

M5 + NEEDS 完成(176 测试)。M5 深化解决两个并发问题:

1. **多 worker 并行**:独立插件(不同 worker)的 ctx 调用并行——零风险。
2. **Async worker 事件循环**:解锁 macrotask——dsh 插件真做 `await fetch/setTimeout/异步库`,子进程卸载不通用,**零移植承诺要求宿主支持 macrotask**。

## 2. ① 多 worker 并行(零风险,先做)

- 确认/补强:`PluginRuntimeResolver` 每插件独立 `NodeWorkerJsHost`(或按隔离组)。
- 独立插件的 ctx 调用跨 worker 并行,互不阻塞。
- worker 本就隔离,并行安全。

## 3. ② Async worker 事件循环(架构级)

### 3.1 现状(同步宿主)与问题

- worker 主循环 `fs.readSync` 同步阻塞读 stdin,一次处理一个请求。
- 同步 ctx 调用(嵌套)用同一把读锁阻塞等回复。
- 代价:macrotask(timer/I/O/TLA)永远无法同步 settle → "macrotask await unsupported"。

### 3.2 目标设计

**worker 侧**:`fs.readSync` 阻塞读 → **async `readline` 事件循环**。

**桥 ctx 方法返回 Promise(异步)**:
```js
// node-bridge.js
ctx.get = async (name) => bridgeCall({op:'get', name})   // await 插件代码自然工作
ctx.emit = async (name, args) => bridgeCall({op:'emit', ...})
```

**同步 ctx 访问(sync-over-async 泵)**:
- cordis 的同步 `ctx.get` 仍是同步调用(`const x = ctx.get('llm')`)。
- 同步调用阻塞等回复时,**泵事件循环**(处理 readline 到达的消息)。
- **重入控制**:泵期间收到的重入消息**排队**,不内联执行——避免监听器在等待中途触发导致状态错乱。

### 3.3 重入队列(关键机制)

```
同步 ctx 调用
  → 发送请求 → 阻塞等回复
  → 泵事件循环处理 readline 消息
  → 重入消息(如 worker 发起的 ctxCall/事件)→ 入队,不内联
  → 回复到达 → 停止泵 → 返回
  → 泵期间入队的消息,在调用返回后按序处理
```

### 3.4 Java 侧

- 在途请求:CompletableFuture(已有 pending map)——现在每 worker 允许多个在途。
- 虚拟线程(JDK 25):驱动多个在途 future,便宜。
- reader 线程:路由回复 + 处理 worker 发起的 ctxCall/invokeService(不阻塞)。

### 3.5 解锁的能力

- async 插件:`await ctx.llm.stream(...)`、`await setTimeout`、`await 任意异步库` 在宿主内真跑。
- 真 I/O 不再需要子进程 runner(可以,但宿主内也可)。

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| 同步 ctx vs 异步循环的冲突 | 异步 Promise 桥方法(await 自然)+ 同步 ctx 用 sync-over-async 泵 + 重入排队 |
| 重入导致状态错乱 | 泵期间重入消息排队,不内联执行 |
| 存量测试/插件回归 | 全量回归(176+),已验证 dsh 插件重跑 |
| Windows 平台(fd/异步 I/O) | readline 跨平台;双通道若需,按平台处理 |
| 事件循环饥饿 | 泵预算/调度,防 sync ctx 长时间占住 |

## 5. 验证

1. async 插件:`await setTimeout` 在宿主内真跑(不抛 "macrotask unsupported");
2. 同步 ctx 仍工作(兼容 cordis 同步 `ctx.get`);
3. 重入安全:泵期间事件入队,不中途触发;
4. 多 worker 并行:独立插件 ctx 调用并行(断言时序/并发);
5. 全量回归:176+ 测试 + dsh 插件(agent/agent-loop/tools/settings/llm)。
