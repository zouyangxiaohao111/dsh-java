# M10-2 — 生产 web 收尾(浏览器完整 UI 验证)

日期:2026-08-20。实测(非纸面)。

## 结论
`./dshj web`(生产,非 dev)serve 完整 dsh web 客户端:**99 插件经桥加载 + 前端壳(dist)+ 35 个 client.js 全 200 + `__DSH_BOOT__` roster**。浏览器加载后经**壳的 staticModules(种子词)解析纯库**(ui-slots 等)→ ui-* 激活 → 完整 UI。32-pending 根因(纯库 resolve)由 M10-1 机制定论 + DevClientBundleResolutionTest 证明已解。

## 验证(实测)

| 项 | 结果 |
|---|---|
| `./dshj web` boot | ✅ 99 插件,web UI at :3080 |
| 首页 GET / | HTTP 200,11KB,含 `__DSH_BOOT__`(35 客户端行 url+rev) |
| 全部 client.js | **35/35 HTTP 200,0 非 200**(client-runtime/ui-conversation 等全部可服务) |
| shell assets | `/assets/index-*.js` HTTP 200(dist 壳,含 getStaticModules) |
| SSE /plugins/events | 连接 0 帧 —— **正确**:graph 推送是 client-hmr 链(dev),生产 hmr 钉住无需 |

## 机制(已定论,见 M10-1)
生产 web = **dist 壳 + staticModules + per-plugin 扩展**:
- webserver serve vite 前端 dist(壳)→ 壳 `getStaticModules()` 硬编码注册
  ui-slots/ui-primitives/cordis/react 为静态模块
- per-plugin client.js(35 个)从 statics 解析纯库 → 激活
- DevClientBundleResolutionTest(已跑未跳过)证明每个 bundle 的 static external
  落在 `__ModuleLoader__` 可 resolve 集合

## 架构
一个 Java 核心(dsh-cordis/dsh-js-host/dsh-reload)零改动;webserver/api-gateway/
前端/attachment 全是 dsh 插件经桥;换插件 = 改 yml。
