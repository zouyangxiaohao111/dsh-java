# dsh 生态边界报告(能直接用 / 必须移植 / 怎么做到)

- 日期:2026-08-16
- 依据:三组探查子代理对 `deepseek-harness/packages/`(39+ 包)与 `core/` 的只读扫描 + 实测(echo/session-stats/repeat-tool-reminder 经桥跑通)
- 目标:回答"哪些 dsh 组件能直接用(怎么用)、哪些必须移植(移植什么、为什么不能)、要'做到'得怎么做"

## 结论框架:三层判定

| 层 | 含义 | 占比 |
|---|---|---|
| **A 直接用** | 抽象 Service 契约 / 纯逻辑 / 空宿主面 / 纯事件插件,Java 核心基础 ctx 面即可跑 | 多数 |
| **B 扩展 shim** | 纯 TS 逻辑 + 依赖一组 Java 核心必须提供的 ctx 服务 seam(桥接即用,不移植业务) | 主要工作 |
| **C 留 Node worker / Java 重写** | 运行时代码求值(node:vm/worker_threads)/ 原生 OS 集成(koffi/沙箱)/ Node 专有后端(node:sqlite/zstd) | 少数 |

**关键洞察:真 Node worker 几乎什么都能跑。"必须移植"不是"不能跑",而是"它跑在 Node 里,不是 Java 原生"。** 真正的边界是融合深度(跨桥 RPC/状态在 Node 侧)与是否要 Java 原生。

## 一、C 类清单(真边界——留 Node worker 独占 或 Java 重写)

| 包 | 为什么 C | 要"做到"怎么做 |
|---|---|---|
| `sandbox-local` + `sandbox-windows-acl` | OS 内核沙箱(bwrap/landlock/Win32 受限令牌)+ N-API 原生绑定(koffi、node-addon-landlock-run) | **留 Node worker 独占**:`ctx.sandbox.confine()` 经桥 RPC 暴露给 Java。Java 重写需 JNA/JNI 重建 Landlock/Win32 面;bwrap/sandbox-exec 是外部二进制,Java ProcessBuilder 可替换 |
| `code-runtime-worker-thread` | `node:worker_threads` 隔离求值模型 TS + Node 内建类型剥离 | **留 Node worker 独占**;或 Java 另起 Java/Python 执行器实现 `CodeRuntime.run` 契约 |
| `cordis-host-runner` | `node:vm` 沙箱求值模型写的宿主代码 | **留 Node worker 独占**;RPC 动词(define/run/stop/…)桥出 |
| `directory-picker-native` | koffi FFI + 子进程 IPC + OS 对话框(COM/AppleScript) | **留 Node worker 独占**;Java 重写用 JNA/Swing |
| `session-persistence-jsonl` | node:zlib zstd + koffi FFI(win32 fsync)+ crash-durable fs | **留 Node worker 独占**(桥暴露 `SessionPersistence` 接口);Java 重写需移植 zstd 帧 + 平台 fsync |
| `session-persistence-sqlite` / `session-query-sqlite` | `node:sqlite`(FTS5,随 Node 原生库) | **留 Node worker 独占**;Java 重写用 JDBC/org.sqlite 复刻 schema + FTS 面 |
| `apiproxy` | 整个 Web 宿主网关,聚合 ~20 ctx 服务 + Node fs/crypto + 原生进程 + ZIP | **Java 重写**:复用其 `api/` 契约与 `fetch/client.ts`(A 级),对 Java 侧服务重实现网关 |
| `webserver` | 真实监听 TCP 套接字的 `node:http` 服务器 + upgrade 处理 | **Java 重写**(推荐,自包含):JDK HttpServer/Netty 重实现 exact/prefix 路由 + fallback + index taps |
| `agent-loop` | **B 附 C 风险**:fiber/effect/initiator 三重语义若桥承载不了,则 turn/step 机器需 Java 重写 | 先让 Java 核心把 effect/fiber/initiator 做对(P1/P2 对齐);不行则 Java 重写 turn/step,TS 只留 `AgentFactory` 契约 |

## 二、B 类:Java 核心需提供的 ctx 服务 seam(桥接即用,不移植业务)

B 类包共性 = 纯 TS 逻辑 + 一组 ctx 服务 seam。seam 全集(Java 核心逐一提供):

```
ctx.fs / ctx.shell / ctx.commands / ctx.tools / ctx.llm / ctx.sessions(+flush)
ctx.sessionProjections / ctx.sessionQuery / ctx.sessionPersistence / ctx.settings
ctx.userQuestions / ctx.approval / ctx.jobs / ctx.agents / ctx.loader(运行时插件装载)
ctx.logger / ctx.tokenMeter / ctx.systemPrompt / ctx.permissionPresets / ctx.launchEnvironment
ctx.sessionTelemetry / ctx.storageDomain / ctx.compaction / ctx.codeRuntime / ctx.webServer
ctx.directoryPicker / ctx.typert(+connection.rpc.intercept、internal/service 事件、@Remote 载体)
```
另需 JS 运行时支持:`crypto.randomUUID`/`Buffer.byteLength` 垫片、ES2024 `using`/`Symbol.dispose`、`AbortSignal.any`、计时器。纯 JS 助手包(`dsh-scope`、`dsh-timeout`)直接桥。

**A 类示例**:llm 全组、system-prompt、persona、repeat-tool-reminder、抽象 Service 契约(code-runtime/compaction/credentials/jobs/sandbox 定义面/fs 定义面/lsp 定义面)。

## 三、桥的硬门槛(决定成败的三件事)

1. **fiber/effect/initiator 三重语义** —— `ctx.effect` 的 generator + 精确 disposer 身份、`ctx.fiber` 状态机、`AsyncLocalStorage` initiator 因果链。**这关联我们的 P1/P2 核心对齐**,是 agent 组上层全部成立的前提。
2. **Node worker 桥(非纯 GraalJS)** —— agent-loop 用 ES2024(`using`/`Symbol.dispose`/`AbortSignal.any`),纯 GraalJS 风险高;且 C 类后端本就是 Node 专属。
3. **Typert Remote 协议** —— `api/gateway`+`api/remotes` 的 `ctx.typert`/`@Remote`/`connection.rpc.intercept` 是 RPC 公共底座,应优先 Java 落地。

## 四、外部依赖(全部可解析/可 shim)

原生 addon 仅 `koffi`(directory-picker-native、sandbox-windows-acl、fs-local win32);外部 npm 仅 fflate/zod/chokidar/yaml/react。无其它阻塞。

## 五、可执行结论

1. **首选 M4 Node worker 桥** —— 同时覆盖 B 的 Node API 面与 C 的原生面;GraalJS 留给轻量插件。
2. **Java 核心先做对 effect/fiber/initiator(P1/P2 对齐)** —— 这是 agent 组的地基。
3. **B 类按 seam 清单逐组接通** —— 每接通一批 seam,dsh 的一组插件即可用,不需移植业务。
4. **C 类两条路**:留 Node worker 独占(推荐,零移植)或 Java 重写(apiproxy/webserver 值得;沙箱/代码执行不值得)。
5. **"移植"的真实含义收敛** —— 绝大多数不是移植业务逻辑,而是 Java 核心补齐 ctx 服务 seam。
