# M10-1 — dev 模式:`./dshj web --dev`(host + tsdown watch + vite 前端壳)

里程碑:M10。日期:2026-08-19。实测记录(非臆测)。前置:`./setup.sh`(host lib + client lib +
apps/web dist + web profile junctions)。

## 结论

`./dshj web --dev` 起完整 dev 管线,且**所有 dev 差异都是"改 yml"**,核心/桥零特判:

1. **dev patch 层** `profiles/web/cordis.patch.dev.yml`:解 pin 用户层钉住的 `client-hmr`
   (disbled: false)+ 并入 `group: web`(与 webserver/modules 同 worker,M9-1 已证同组共享
   worker 消除跨 worker 路由死锁;client-hmr 的 `/plugins/events` SSE 路由经 webServer.register
   注册,必须同组)。非 dev boot 完全不受影响。
2. **双 watcher 子进程**(通用进程机制,DevPipeline):tsdown watch(dev-web,重建 client
   bundle `lib/client.js`)+ vite build --watch(前端壳 `apps/web/dist`)。host 的 frontend-static
   fallback 每请求读 dist,"host serve 壳" 即 dev 前端循环。
3. **HMR 链**(镜像真实 dsh):改客户端插件源码 → tsdown watch 重建 → host client-hmr
   stat-poll 检测 rev 变更 → `/plugins/events` SSE 广播 `rebuilt` 帧 → 浏览器热换插件。

## 1. 启动证据(boot log 摘要)

```
dshj: profile 'web' is running on the Java harness. Ctrl+C to stop.
dshj: dev mode ON (--dev): dev patch layer applied + tsdown/vite watchers started.
dshj: dsh web UI at http://127.0.0.1:3080/
dshj: [dev] tsdown watch (client bundles) + vite build --watch (frontend shell dist) started — edit sources to see HMR/refresh.
  - client-hmr  [NODE]  @deepseek-ai/dsh-client-hmr        ← dev patch 层解 pin
[dev-web (tsdown watch, client bundles)] dev-web: watching 39 dsh.client plugin packages (polling 500ms):
[vite build --watch (frontend shell dist)] watching for file changes...
[vite build --watch (frontend shell dist)] built in 6010ms.
```

## 2. 端点证据(curl)

| 端点 | 结果 | 说明 |
|---|---|---|
| `GET /` | HTTP 200 + `window.__DSH_BOOT__` | 真实 DeepSeek Harness shell(boot manifest 注入) |
| `GET /plugins/events` | HTTP 200 + SSE 帧 | client-hmr SSE 通道(非 dev 是 404 —— client-hmr 钉住) |
| `GET /plugins/@deepseek-ai/dsh-client-ui-conversation/client.js` | HTTP 200 | 客户端插件 bundle |
| `GET /api/events.host` | HTTP 426(gateway 级响应) | /api 前缀由 api-gateway 处理(非 SPA fallback) |
| `GET /api/respond` | HTTP 404(gateway 的 not-found) | 同上;对照非 /api 路径 `/nonexistent-route` → 200 SPA fallback |

SSE 首帧(连接即下推 graph):

```
: connected

data: {"type":"graph","graph":{"rev":"...","entries":[...]}}
```

## 3. HMR 链实测(编辑源码 → SSE rebuilt 帧)

编辑 `vendor/dsh/packages/client/ui-theme/src/client/settings-store.ts`(真实代码变更,
`preference: 'system'` → `'light'`),观察:

1. tsdown watch 检测变更并重建:
   ```
   [dev-web (tsdown watch, client bundles)] ℹ [@deepseek-ai/dsh-client-ui-theme/client] [CJS] lib\client.js ...
   [dev-web (tsdown watch, client bundles)] ✔ [@deepseek-ai/dsh-client-ui-theme/client] Rebuilt in 40ms.
   ```
2. host client-hmr stat-poll 检测 `lib/client.js` rev 变更 → boot manifest 中 ui-theme rev
   `0fe99bc82e0c` → `d882b8e14811`(graph 重组合)。
3. `/plugins/events` SSE 广播 rebuilt 帧:
   ```
   data: {"type":"rebuilt","id":"@deepseek-ai/dsh-client-ui-theme","rev":"d882b8e14811"}
   ```
4. 浏览器按该帧热换 ui-theme 插件(客户端 HMR 链完整)。

> 注:仅注释/空白变更会被 bundler 剥离 → 产物 byte-identical → rev 不变 → 无 rebuilt 帧
> (正确语义:内容没变就无需热换)。实测需改真实代码。

## 4. 纯库(clientLibrary)确认 —— 天然解,无需 serve 端修

任务预期的"32 个 ui-* pending"(clientLibrary 包无 `lib/client.js`,clientBundle external
require 它们 → `__ModuleLoader__` 模块表 resolve 不到):

**实测为已解决**:clientLibrary 包是 **平台模块**,经 shell 种子进冻结模块表 ——
`packages/client/web/src/platform.ts` 的 `PLATFORM_MODULES` + `seed.ts` 的 `getStaticModules()`
把 5 个 clientLibrary 包(ui-slots/ui-primitives/ui-attachment/schema-form/web-react)静态注册
为模块表种子(`@deepseek-ai/cordis` 同)。tsdown client 面 `CLIENT_EXTERNALS = PLATFORM_MODULES`,
clientBundle 包对它们的 require 一律 external → 运行时 `__ModuleLoader__.makeRequire` 从 seed
命中。`web` 包是 shell 自身(vite 打进壳,不是插件行)。

验证:
- client bundle 的全部 `@deepseek-ai` external require = 6 个,cursor 一一落在种子表:
  `@deepseek-ai/cordis`、`dsh-client-schema-form`、`dsh-client-ui-attachment`、
  `dsh-client-ui-primitives`、`dsh-client-ui-slots`、`dsh-client-web-react`。
- boot manifest 27 个 ui-* 行全部带 `url` + rev,`/plugins/<id>/client.js` 全部 200
  (无 missing bundle)。
- 该边界已固化为自动回归:`DevClientBundleResolutionTest`(dsh-host)直接扫描 dev 浏览器
  fetch 的全部 client bundle 产物,断言每个 static external require 都落在
  `__ModuleLoader__` 可 resolve 集合(种子词 ∪ 图行 ∪ `/client` 规范化),且 5 个
  clientLibrary 包都有 lib/index.js 无 lib/client.js(纯种子,无 bundle 行)。
  反证:把 `ui-slots` 移出种子词 → 测试红("missed the module table" 路径)。

## 5. 边界(诚实记录)

| 边界 | 说明 |
|---|---|
| **vite serve(裸 dev server)不可用** | `apps/web` vite.config 的 `rejectStandaloneServe` 拒绝 `serve` 命令 —— 与真实 dsh 一致:`__DSH_BOOT__` 必须由 host 注入(index tap),裸 vite 无法注入。dev 前端循环 = `vite build --watch` + host serve dist("host serve 壳",任务二选一里的后者)。 |
| **前端壳源码变更需手动刷新** | tsdown watch 管客户端插件 bundle(HMR 链自动热换);vite build --watch 重建的是整壳 dist,浏览器需手动刷新(壳无 SSE 订阅;真实 dsh 同)。 |
| **watcher 子进程生命周期** | 优雅退出(Ctrl+C / SIGTERM)→ JVM shutdown hook 调 `DevPipeline.close()` 回收两个 watcher。Windows 强制杀 JVM(`taskkill /F` / kill -9)不跑 shutdown hook → watcher 子进程会遗留,需手动清理(标准 JVM 语义)。 |
| **clientLibrary 包不在 tsdown watch 重建范围** | dev-web 按 `dsh.client` 声明发现包;clientLibrary 包无该声明,不被 watch —— 但它们被 vite 打进壳,改它们的源码走 vite build --watch 重建壳。 |
| **dev patch 层仅作用于 dsh profile 路径** | Java harness `cordis.yml` profile 无 patch 层概念;`--dev` 对它们仅打印 dev 提示(web profile 才有 watcher)。 |
| **`--dev` 是启动器 flag** | 被 CLI 消费,不传给 booted app(镜像 `--profile`;web-startup 的 commander 不会看到未知 flag)。 |

## 6. 测试

- `DshCliTest` 新增 6 例:`web --dev` / `--profile web --dev` / `--dev=true|false` /
  `--dev` 保留 app args / `--dev=invalid` 拒绝 / 无 --dev 时 dev=false。
- `DshProfileReaderTest` 新增 2 例:dev patch 层仅在 dev boot 最后应用(last-write-wins,
  解 pin + group 透传)/ dev patch 层缺失是 no-op。
- `DevPipelineTest` 3 例:prereqs 缺前置 → false + 提示 / 前置齐全 → true / 空管线 close 幂等。
- `DevClientBundleResolutionTest` 2 例:clientLibrary 纯库=平台种子(有 lib/index.js 无
  lib/client.js)/ 全部 client bundle 的 static external require 都落在模块表可 resolve
  集合(含 ui-slots 种子命中的 4 个 bundle)。全量回归 349 测试(见提交)。

## 7. 浏览器路径

1. `./dshj web --dev`(等 `dshj: dsh web UI at http://127.0.0.1:3080/` + 两个 watcher 就绪)。
2. 浏览器打开 `http://127.0.0.1:3080/` → 真实 dsh agent UI(DeepSeek Harness shell)。
3. 改 `vendor/dsh/packages/client/<pkg>/src/...` 客户端插件源码 → 保存 → tsdown watch 重建
   bundle(日志 `[dev-web ...] Rebuilt in xx ms`)→ SSE rebuilt 帧 → 浏览器热换该插件。
4. 改 `vendor/dsh/packages/client/web/src/...` / `apps/web/src/...` 壳源码 → vite build --watch
   重建 dist → 浏览器手动刷新。
