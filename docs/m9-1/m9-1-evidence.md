# M9-1 — web 插件组共享 worker(解跨 worker 路由死锁)证据

日期:2026-08-19。实测(非纸面)。

## 结论
`./dshj web` 的 web 运行时组(webserver / web-runtime / modules / connection / api-gateway /
api-remotes / cordis-host-runner / web-startup / client-runtime / cordis-client-runner /
agent-presets / locale / 全部 ui-*)**
共享一个 NodeWorkerJsHost**(同一 Node 进程)。route handler 与 node:http req/res 本地直传,
**跨 worker 路由死锁解除**(M8-1 §5)。

## 机制
- Entry 加 `group:` 字段(yml / dsh profile patch 行透传);同组 node 插件共享一个 worker。
- LoadedPlugin `sharedHost` 标记:组宿主由 loader 统一回收,不随插件 close。
- PluginLoaderService:组宿主 map;全量重载关组宿主;applyDiff 脏组整组重载(全新 worker,
  避免 Node 模块缓存返回旧模块);reloadPlugin 组成员变更 → 整组重载。
- **Registry.delete 快照 fibers 修 CME**:共享 host 多插件 dispose 时暴露的既有 bug
  (fiber.dispose 从 DisposableList 删条目 → 活迭代 CME)。
- web profile 用户层给 ~40 行 web 运行时加 `group: web`(配置驱动,核心不动)。

## 实测(web profile boot 后)

| 端点 | M8-1(死锁) | M9-1 |
|---|---|---|
| `/`(首页) | 200(已通) | HTTP 200, 0.029s |
| `/plugins/@deepseek-ai/dsh-client-ui-theme/client.js` | **冻结(sync call timed out)** | **HTTP 200, 0.0037s** |
| `/api/*` | **冻结** | 快响应(404, 0.003-0.018s,路由存在即 200) |

## 测试
`GroupCoLocationTest` 3 例:同组共享一个 host(sharedHost=true)/ 异组独立 host /
组变更整组换新宿主。335 测试全绿(332 + 3)。
