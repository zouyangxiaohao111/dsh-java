# M11-1 — web UI 可交互修复(service 链全通)

日期:2026-08-20。实测(Chrome headless + 真实实例)。

## 现象
M10-4 后 UI 渲染成功但**无法交互**:工作区选择器 inert、点击对话无效、无 agent 模式列表、
console 满屏 `/api/... HTTP 404`。server 端 99 插件 + api-gateway 全 ACTIVE(Java 侧),
但浏览器端 remote 全失败。

## 根因(三层,逐层定位)
1. **ctx.inject 子 ctx 桥缺口**。storage-domain 等插件用 `ctx.inject(backendServices, (domainCtx) =>
   { domainCtx.provide('storageDomain', ...) })`。桥的 `doInject` 只 `invokeListener(cb, [])` ——
   cb 的 `domainCtx` = undefined → provide 静默失败 → **storageDomain 服务从未注册**(主 fiber
   ACTIVE 但子 fiber 的 apply 没生效)。workspace/message-feedback/session-projection-cache 全
   PENDING(等 storageDomain)。
2. **directory-picker 服务缺失**。web-app bundle 把 `directory-picker` 指向 auto(其 apply 用
   `ctx.loader.create` 挂 native backend —— Java `LoaderService.create` 是 M8 的 no-op,挂不上)
   → `directoryPicker` 服务缺失。
3. **级联** → api-gateway(等 workspace/storageDomain/directoryPicker)PENDING → `apiProxy`
   未提供 → connection 的 `/api` fallback `ctx.get('apiProxy')===undefined` → 404 → client
   remote 全失败。另:DshCli 不 settle,后台加载完成前功能不可用。

## 修复
1. **桥 ctx.inject 子 ctx**(dsh-js-host):
   - `node-bridge.js` `makeCtx.inject`:包装 cb,把 Java 传来的子 ctxId 转 `makeCtx`(domainCtx)。
   - `NodeWorkerBridge.doInject`:子 ctx 建独立 bridge,ctxId 传 cb。
   - `NodeWorkerJsHost.registerChildCtx`:子 ctx 独立 ctxId 空间(100M 起,< 2^53 JS 精度)。
     *不* 用 `createCtx`(reader 线程阻塞会与 worker 单线程 apply 互等死锁)。
2. **patch**(profiles/web/cordis.patch.yml):`directory-picker` 指向 auto → disabled;
   `insert` directory-picker-native(Service 子类,加载即 provide directoryPicker)。
3. **DshCli**:boot 后 `loader.settle()` 再提示 URL —— 后台加载完成才功能就绪。
4. **测试**:RealDshWebProfileTest 断言 apiProxy 非空。

## 验证(实测)
| 项 | 结果 |
|---|---|
| 全量回归 | ✅ 702 测试 green |
| console | ✅ **HTTP 404 = 0**(M10-4 时 3 个) |
| `/api/workspaces/list`(POST) | ✅ 400(apiProxy 命中,参数校验——不再是 404) |
| UI DOM | ✅ 新建会话 / 选择工作区 / 发送消息按钮齐全 |
| 后台加载 | ✅ settle 后 100 插件,api-gateway ACTIVE,apiProxy present |

工作区选择器 inert = 新环境无数据(正常初始态,需创建工作区),非故障。

## 遗留
- 后台 88 个 deferred 顺序 `registerAll`(apply)需 ~68s —— 启动 URL 提示前等 settle。后续可并行 apply 提速。
- curl `GET /api` 裸路径返回 "not found" 是 apiProxy 对无匹配端点的正常 404,非故障。
- directory-picker native 的 pick 打开 OS 对话框是深度功能,未触发不影响基本交互。
