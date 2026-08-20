# M10-4 — 真实 web client 激活修复(32-pending 根因:typert-gateway pin)

日期:2026-08-20。实测(非纸面)。

## 现象
`./dshj web` 后浏览器打开首页显示 **"Failed to load plugins"**:`web boot: 32 entries did
not activate`。32 个 client 插件(dsh-client-* / dsh-client-ui-*)全部 PENDING,等
`slots` / `locale` / `remote` 服务。Server 端完全正常:11 个 priority 插件 ACTIVE,
3080 LISTENING,`__DSH_BOOT__` roster 完整。

## 根因(已定位,非回归)
1. `profiles/web/cordis.patch.yml` 自 M8-1 起把 `typert-gateway`(`@deepseek-ai/dsh-api-gateway`)
   **pin 成 `disabled: true`**,理由是"web UI 用 @deepseek-ai/dsh-host-apiproxy 代替"。
   那是 **server 平面**的判断 —— apiproxy 替代的是 server 端网关,**client 端 remote
   服务的唯一提供者正是 dsh-api-gateway 的 client 半**(`gateway/src/client/index.ts`:
   `super(ctx, 'remote')`)。
2. `ClientModuleRegistry.processOne`(modules 插件,`client/modules/src/index.ts:387`)
   要求 entry `!disabled` 且 fiber 存在才进 client graph → 被 pin 的 gateway **不进
   `__DSH_BOOT__` manifest** → 浏览器端无人 `provide('remote')`。
3. 级联:
   - `dsh-api-remotes`(client 半 `inject=['remote']`)→ PENDING
   - `dsh-client-runtime`(client 半需 remote,且 **provide `slots`**,`client/runtime/
     src/client/slots.ts:105`)→ PENDING → **`slots` 缺失**
   - `dsh-client-locale`(需 remote + slots)→ PENDING → **`locale` 缺失**
   - 32 个 ui-* + app-shell 全 PENDING → boot.tsx `assertEntriesActive` 抛错。

**M10-2 的"32-pending resolved"是 HTTP 层误判**:只验证了 client.js 35/35 全 200 +
`__DSH_BOOT__` roster,从未在真实浏览器里验证 client 插件 ACTIVE。"ui-* 激活 → 完整 UI"
是推断,不是实测。今天首次真实浏览器打开即暴露。

## 修复
`profiles/web/cordis.patch.yml`:
```yaml
- id: typert-gateway
  group: web          # 与 api-remotes 等共享 worker(M9-1)
  priority: true      # 与 modules 同批,进初始 __DSH_BOOT__ manifest
```
取消 disabled。gateway **server 半**仅 `inject=['typert']`(core 组跨 worker 可调,
M7-8 provide 句柄化)→ Java host 上激活无虞;client 半依赖 `typert`+`connection`
(浏览器内均 ACTIVE)。

## 验证(实测)
| 项 | 结果 |
|---|---|
| server boot | ✅ typert-gateway [NODE] 在 boot 列表第一行(priority) |
| `__DSH_BOOT__` | ✅ 36 行,第 2 行 `@deepseek-ai/dsh-api-gateway`(immediately) |
| Chrome headless 真实加载 | ✅ 无 "Failed to load plugins";无 "web boot:" 错误(boot.tsx 任一 pending 即抛) |
| 真实 UI DOM | ✅ 工作区选择器 `placeholder="选择一个工作区开始"` + `sidebarCol` 布局 + button |
| 全量回归 | ✅ 702 测试 green(failures=0 errors=0),含 RealDshWebProfileTest 新增防回归断言 |
| 启动时间 | ✅ ~10s 端口绑(M10-3 懒加载未回退;gateway 并入 web 组共享 worker) |

## 防回归
`RealDshWebProfileTest` 新增断言:web profile boot 后 `loader.loaded()` 必须含
`typert-gateway`。谁再把它 pin 掉 → 测试红。

## 遗留(可选后续)
- 浏览器激活仍无自动化 e2e(vendor/dsh 有 playwright ^1.49 + 系统 Chrome 可跑
  `channel:'chrome'`)。若想把 M10-2 式"HTTP 层验证"升级为"激活验证",可加一条
  smoke e2e:断言 #root 渲染工作区选择器、无 Failed to load plugins。
