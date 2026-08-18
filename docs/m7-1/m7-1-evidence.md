# M7-1 — 真实 dsh plugin add 消费闭环(证据)

里程碑:M7-1。日期:2026-08-18。提交前的实证记录(非臆测)。

结论:**闭环打通** —— 真实 dsh CLI `dsh plugin --profile demo add <包>` 建 profile 并装包,
我们的 `./dshj --profile demo boot` 经 `DshProfileReader` 消费(manifest `dsh.profile.bundles`
+ bundle patch 组合 + 用户层)→ PluginLoaderService 经 Node 桥加载,HTTP 状态页可见新增包。

## 1. 真实 dsh CLI 建 profile + 装包

```
DSH_HOME=<临时目录> node vendor/dsh/apps/cli/lib/bin.js plugin --profile demo add <包>
```

- **profile 初始化**:`initProfile` 写 `$DSH_HOME/profiles/demo/package.json`
  (`dsh.profile.bundles = ["@deepseek-ai/dsh-base"]`)+ 用户 `cordis.patch.yml` 层(`[]`)+
  `pnpm-workspace.yaml`。
- **pnpm add 真实生态包**(npm 注册表,经代理):`@deepseek-ai/dsh-system-prompt`、
  `@deepseek-ai/dsh-scope`(reconcile 前需把 system-prompt 升到 `@next` —— 裸包名拉到的是
  `latest` 0.0.1-rc.1 旧版;`@next` = 0.1.0-rc.6/7,与 vendor/dsh 验证过的版本线一致)。
- **reconcile**:两者都不声明 `dsh.bundle`,留在 `dependencies`(dsh 打印
  `installed as a plain dependency, not a profile layer`),`bundles` 仍为 `["@deepseek-ai/dsh-base"]`。
- **pnpm 版本边界(实证)**:pnpm 7 `add` 报 `ERR_PNPM_ADDING_TO_ROOT`;pnpm 8/9/10/11 均成功
  (dsh 自身锁 pnpm 11.7.0)。

`demo-profile-package.json`(证据文件)最终:
```json
{ "name": "dsh-profile-demo", "private": true,
  "dependencies": {
    "@deepseek-ai/dsh-scope": "0.1.0-rc.6",
    "@deepseek-ai/dsh-system-prompt": "0.1.0-rc.6"
  },
  "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base"] } } }
```

## 2. 我们的核心消费(dsh 装、我们跑)

`dsh-base` 是全量核心树:78 行 patch(timer/hmr/llm/session/agent/web/… 见
`vendor/dsh/packages/bundle/base/cordis.patch.yml`)。这些行引用的 dsh 包绝大多数需要
**未接的桥 seam**(agent 编排、llm 会话、typert 注册表、沙箱、工具注册等),`PluginLoaderService`
是"任一条目失败 → 整批回滚"语义,因此按设计 §5 风险处置:**记录边界,用已验证可跑的子集
完成闭环**。

用户 patch 层(dsh profile 格式自带的用户覆盖机制,真实 dsh 用户就这样裁剪 profile)把
77 个需 seam 的行按标准 dsh patch 语义钉掉(`id` 定向 `disabled: true`),只留一个已验证可跑
行 —— `system-prompt`(M6-5b 已验证经桥加载),其模块说明符 `@deepseek-ai/dsh-system-prompt`
从 profile 自身 `node_modules`(dsh CLI 装的)解析。用户层见
`demo-profile-cordis-patch.yml`(77 个 disable)。

```
DSH_HOME=<同临时目录> ./dshj --profile demo boot
```

启动日志(`dshj-demo-boot.log`):
```
dshj: profile 'demo' booted (dsh profile D:\code\dsh-java\build\m7-1\home\profiles\demo):
  - system-prompt  [NODE]  @deepseek-ai/dsh-system-prompt
dshj: node module bases: [D:\code\dsh-java\vendor\dsh\node_modules,
                          D:\code\dsh-java\build\m7-1\home\profiles\demo\node_modules]
```

## 3. 断言(全过)

| # | 断言 | 结果 |
|---|---|---|
| 1 | 真实 dsh CLI 可跑,profile 初始化正确(manifest + 用户层 + node_modules 齐) | ✅ |
| 2 | bundle 包 patch 组合正确:DshProfileReader 组合 dsh-base 78 行 + 用户层 77 个 disable | ✅ |
| 3 | 加载的插件列表含 demoPackage 声明的插件:`system-prompt [NODE] @deepseek-ai/dsh-system-prompt`(dsh CLI 装的包) | ✅ |
| 4 | HTTP 状态页(任意 profile boot 都起)可见新增包:title `dshj · demo`,loaded plugins 含 `system-prompt · host: Node`,桥基址含 profile node_modules | ✅ |
| 5 | 桥 shim Service 注册进 Java 核心:`ctx.systemPrompt` 存在(经 `@deepseek-ai/cordis` 拦截) | ✅(M6-5b 断言同形) |

状态页原始 HTML:`dshj-demo-status-page.html`。

## 4. 诚实边界记录

- **dsh-base 全量核心树无法整树经桥加载**:~77/78 行引用的 dsh 包(agent/llm/session/typert/
  tools/… )需要额外 seam。用户层按真实 dsh patch 机制钉掉,闭环用已验证可跑子集完成
  (设计 §5 允许的对策)。
- **system-prompt 安装时需 `@next` 标签**:裸包名拉到 0.0.1-rc.1 旧版(dist-tag `latest`);
  `@next` = 0.1.0-rc.6/7(与 vendor/dsh 验证过的版本线一致)。
- **vendor/dsh 需恢复 `@deepseek-ai/dsh-base` workspace 链接**:`strip-dsh-libs.mjs` 会把子模块
  node_modules 裁到最小,真实 dsh install 里 `vendor/dsh/node_modules/@deepseek-ai/dsh-base` 是
  workspace 链接(packages/bundle/base)。恢复该链接后 install anchor 按真实 dsh 布局解析
  dsh-base bundle 层。
- **pnpm 版本**:pnpm 7 的 `add` 报 ERR_PNPM_ADDING_TO_ROOT;pnpm ≥ 8 可用(dsh 锁 11.7.0)。

## 5. 提交回归锁

- `DemoProfileConsumptionTest`(3 例,hermetic):真实 dsh CLI 产物等价形状
  (manifest + bundle 包 + 用户层)→ ProfileBoot 经 DshProfileReader → Node 桥加载 → Service
  注册进 Java 核心;用户层 id 定向 disable 使重行不产出。前置:node。
- `RealDshCliInstallConsumeTest`(1 例,环境门控):**真实 dsh CLI** `plugin add` 本地路径
  fixture bundle(免网络)→ reconcile 写 `bundles` → 我们的 ProfileBoot 加载。前置:node +
  vendor/dsh CLI 构建产物 + pnpm ≥ 8(PATH 或 `build/pnpm` 本地安装)。
- fixture:`dsh-host/src/test/resources/m7-1-demo-bundle`(最小真实 dsh bundle 包,声明
  `dsh.bundle.patch`,插件入口 `import { Service } from '@deepseek-ai/cordis'`)。
- 全量回归:278 测试全绿(0 fail / 0 skip);M7-2 追加后为 280(M7 终态)。
