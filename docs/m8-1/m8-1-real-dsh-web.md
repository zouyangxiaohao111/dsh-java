# M8-1 — 真实 dsh web UI:Java 核心宿主 dsh-base + dsh-web-app bundle

里程碑:M8。日期:2026-08-19。实证记录(非臆测)。前置:`./setup.sh`(host lib + client lib +
apps/web dist)+ `node scripts/link-web-profile.mjs`(web profile node_modules junctions)。

结论:`./dshj web boot` 现在打开**真实 dsh agent UI**(不再是 M6-5a 状态页)。web profile =
`profiles/web/package.json` 声明 `dsh.profile.bundles = [@deepseek-ai/dsh-base, @deepseek-ai/dsh-web-app]`,
Java 核心(dev.dsh.cordis)作唯一运行时,经 Node 桥逐插件宿主真实 dsh 插件:
**webserver**(`@deepseek-ai/dsh-host-webserver`,node:http 监听,默认 :3080,`--port` 覆盖)serve 前端
dist(apps/web vite build,经 web-runtime 的 frontend-static fallback)+ **api-gateway**
(`@deepseek-ai/dsh-host-apiproxy`,聚合 sessions/agents/llm/tools 等 ctx 服务,经 M7-8 provide
句柄化跨 worker 可调)+ storage/workspace/session-stats/plugin-inventory + client 模块系统
(dsh-client-modules 注入 `window.__DSH_BOOT__` + serve `/plugins/<id>/client.js`)。

## 1. 边界(用户层 `profiles/web/cordis.patch.yml` 钉住)

| id | 包 | 钉住原因 |
|---|---|---|
| `code-runtime` | `@deepseek-ai/dsh-code-runtime-worker-thread` | Code Mode worker-thread 代码执行沙箱(OS 级 landlock)。深度功能边界 —— 需 worker 沙箱 seam;前端无它仍渲染(native 工具)。 |
| `client-hmr` | `@deepseek-ai/dsh-client-hmr` | 客户端插件 HMR 重载链:需 `pnpm run dev:web` 重建 watcher 才激活;Java 宿主 boot 无 dev watcher,链恒 idle。 |

浏览器 UI 本身(browser 插件 ui-* / client-* / modules / connection)全部加载 —— 它们的 node 半是
host-safe 的轻量注册,经 dsh-client-modules 扫描进 `window.__DSH_BOOT__`,浏览器从
`/plugins/<id>/client.js` 拉真实 UI 插件。

## 2. 新 seam(全部通用机制,非 per-plugin 定制)

- **启动器事实 `ctx.cmdlineArgs` / `ctx.appExit`**(ProfileBoot,dsh-host):Java 核心 = launcher,
  在树挂载前 provide —— 镜像真实 dsh `provideCmdline`。dsh-web-app/startup 的 `parseCmdline` 两者
  都读(缺任一 → apply 抛错)。`--port` 经此传给 web-startup → webserver config。
- **`LoaderService.await()`**(dsh-cordis):web-runtime 读 `ctx.get("loader")?.await()`,返回
  `Context.NO_SERVICE` → 桥映射 JS undefined → 按"已 settle"处理(Java 核心同步驱动加载)。
- **cordis-shim 补齐导出**:`Inject`(类装饰器注入元数据)、`composeError`(loader 导入)、`Logger`
  (logger-console 导入的静态格式化面)。都是类型/工具面,插件运行时不依赖其行为。
- **`node:process` 内置 import 死锁 → resolve 钩子 shim**(node-resolve-hook.cjs +
  node-process-shim.mjs):实测 Node 24 Windows —— 桥侧 stdin 活跃读(reader 线程阻塞读 FD 0 / 主线程
  异步读 process.stdin)时,`import process from 'node:process'`(commander 15 的 ESM 图经
  web-startup → dsh-cmdline 触发)死锁(内置 process 模块初始化与活跃 stdin 读冲突)。resolve 钩子把
  `node:process` 拦到进程 shim(惰性 re-export globalThis.process),绕过内置初始化路径 —— 通用机制,
  同 cordis shim。
- **bridge `takeLineAsync` 弃 `Atomics.waitAsync` 改 setTimeout 轮询**(node-bridge.js):主线程挂起的
  `Atomics.waitAsync` 与 ESM loader 对同一 agent 的 Atomics.wait/notify 抢醒,dynamic `import()`
  加载含 `node:process` 的 ESM 图死锁。轮询(Atomics.load 只读、不注册 waiter)不干扰。
- **bridge load 走 `await import()` 而非同步 `require(file)`**(node-bridge.js):`node:process` 的 ESM
  图在同步 require(esm) 路径同样死锁;异步 import 在事件循环自由时执行,不冲突。CJS/ESM 都经
  `resolvePlugin`/`pluginMeta` 的 default 解包处理。

## 3. 5 low 修复(随 M8 落地)

| # | 修复 | 位置 |
|---|---|---|
| ① | `isIteratorLike` 收紧:带 `next()` 的纯数据对象(原型 = Object.prototype)不再句柄化 | node-bridge.js |
| ② | `forwardRemoteAsync` 弃每次 `new Thread`,改虚拟线程执行器(JDK 25) | NodeWorkerJsHost |
| ③ | `tools` 激活语义级证据:M7-8 复核的 fiber 状态审计(65 ACTIVE) | docs/m7-4(记录) |
| ④ | `JsIterable` release 后守卫:`hasNext/next` 不再跨桥调用已释放句柄 | JsIterable |
| ⑤ | `Reflect.notify` 快照含移除条目:刷新期间已卸载的 runtime 跳过 | Reflect |

## 4. 实测证据

- `./dshj web boot` 完整 boot(99 插件),插件列表见 `m8-1-boot.log`(逐插件 `[NODE]` 宿主)。
- `dshj: dsh web UI at http://127.0.0.1:3080/` —— DshCli 检测 webServer 服务存在,跳过状态页,
  打印真实 UI URL。
- `curl http://127.0.0.1:3080/` 返回**真实前端 HTML**(DeepSeek Harness shell,非状态页),且
  `window.__DSH_BOOT__` 被 dsh-client-modules 注入**完整客户端插件花名册**(ui-conversation /
  ui-tool / ui-sidebar / client-modules / client-connection … 全部带 rev 与 inject 边)。
- 深度边界:**/plugins/<id>/client.js 与 /api 传输跨 worker 路由死锁**(诚实记录,见 §5)。

## 5. 边界:HTTP 路由处理器跨 worker 死锁

webserver(独立 worker)serve 路由时,route handler(modules 的 /plugins、connection 的 /api)
是**其它 worker 注册的 JS 函数** —— webserver 经跨 worker RPC 调用它,且把 node:http 的
`req`/`res` 跨桥传给 handler。实测:`/`(frontend-static fallback,同样跨 worker)能 serve(证明
跨 worker 调用链通),但 `/plugins` 的 serveBundle 处理 req/res 时死锁(`sync call invokeService
timed out`),冻结整个 webserver worker。

**根因**:"每插件一个 Node worker"下,route handler 与 node:http req/res 对象跨 worker 边界
(webserver 等 handler 完成,handler 调 res 方法又回调 webserver 的 res)—— 跨 worker RPC 链在该
路径死锁。真实 dsh 中 webserver 与路由注册者在同一宿主进程,req/res 本地直传。

**下一跳(不在 M8 范围)**:让 webserver / modules / connection / web-runtime 共享一个 worker(或
route handler 在 webserver worker 本地执行、经桥读远端文件),消除 req/res 跨 worker 传递。这是
"核心 web 体验(聊天/会话/工具)完整呈现"的前置 seam。

> 注意:全量 boot 逐插件 spawn Node worker(每插件一个进程),分钟级 —— 这是"Java 核心 + 桥"架构的
> 实测成本;M8 交付"真实 dsh web 后端全树宿主 + 首页真实 UI + 完整 boot manifest",HTTP 传输的
> 跨 worker 死锁诚实记录(§5),Code Mode / client HMR 等深度功能诚实钉住。
