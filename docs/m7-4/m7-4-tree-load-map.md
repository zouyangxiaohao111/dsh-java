# M7-4 — dsh-base 全量核心树加载地图(实证"配置即用"边界)

里程碑:M7-4 + M7-5 复核。日期:2026-08-18。实证记录(非臆测)。

结论:**"核心 = Java cordis + 其他全是 dsh + 配置即用"成立到 51/78 行;M7-5 配置通道修补后,51 行
全部为「零用户 config」** —— 原 12 行 B 组(需用户层 config 补丁)已全部降为 A 组,补丁删除后整树
照常 boot 到 HTTP 状态页(证据:`docs/m7-4/m7-4-full-boot.log`,round-m7-5-01)。剩余 22 行需要桥面/
核心工作,5 行被 base 层 disable(其中 bash-sandbox/tool-bash/skill-badge 正确禁用,pwsh-sandbox/
tool-pwsh 因 **disabled 通道的 `!!js` 未求值**而保守跳过——M7-5 只覆盖 config 通道,disabled 通道
是独立 Java 侧判定,未解)。

## 1. 实验设置(与 M7-1 同形,反向:全量不 disable)

- **profile**:`build/m7-4/home/profiles/full`(DSH_HOME=`build/m7-4/home`),`package.json`
  声明 `dsh.profile.bundles = ["@deepseek-ai/dsh-base"]`;**用户层初始 `[]`,逐步按失败钉行**。
- **M7-5 复核**:应用配置通道修补(3288b6a)后重跑同一 profile,**删除 12 行 B 组的用户层 config
  补丁**(回到零配置),只保留 22 行 C 组 `disabled: true` 钉行。结果:51 行一次 boot 成功
  (round-m7-5-01),B 组 12 行全部零配置加载 —— 补丁确实不再需要。
- **workspace 链接**:`vendor/dsh/node_modules/@deepseek-ai/dsh-base` 链接已在(M7-1 恢复);
  profile 自身 `node_modules/@deepseek-ai/*` 为 76 个 workspace 包目录的 junction(镜像真实
  `pnpm add @deepseek-ai/dsh-base` + install 的扁平 node_modules 布局)。
- **boot**:`DSH_HOME=<home> ./dshj --profile full boot`。加载器语义 = 任一条目失败 → 整批回滚;
  每轮只暴露**第一个**失败行 → 钉掉 → 重跑。37 轮后稳定(见 §6 轮次日志)。
- **边界断言**:round-m7-5-01 `./dshj --profile full boot` 完整 boot,51 行列出 + `profile 'full' is
  running on the Java harness` + `web status page at http://127.0.0.1:8080/`(120s 内未退出 =
  整树加载成功)。证据日志:`docs/m7-4/m7-4-full-boot.log`(round-m7-5-01 原样,替换原 round-37)。
- 用户层 `cordis.patch.yml`(每行注释记录原因):`build/m7-4/home/profiles/full/cordis.patch.yml`
  (build/,gitignored)。**M7-5 复核后只剩 22 行 C 组钉行;12 行 B 组条目已删除(零配置)。**

## 2. 最终统计(78 行)

| 分组 | 行数 | 说明 |
|---|---|---|
| A 真能加载(零 config,含原 12 行 B 组) | **51** | 只需 profile + workspace 链接;M7-5 后 51 行全零配置 |
| B 配置+可解(用户层 config 补丁) | **0** | M7-5 配置通道修补后不再需要(M7-4 为 12) |
| C 要桥面/核心工作 | **22** | 钉掉;见 §4 分类 |
| D base 层 disabled(平台/表达式) | **5** | 见 §5(3 正确禁用 + 2 保守跳过) |

**78 行里:51 零配置即用 / 22 要桥面或核心工作 / 5 base 禁用(含 2 保守跳过)。**

> **为什么是 51 不是 63?** M7-4 的 §7 曾按"12 行 B 组降为 A 组 → 51 → 63"预估。复核显示:12 行 B 组
> 本就包含在 51 里(39 A + 12 B),配置通道修补让它们从「带补丁」变「零配置」,**行数不变**;
> D 组的 pwsh-sandbox/tool-pwsh 属 **disabled 通道**(独立于 config 通道),Java 侧仍保守跳过,
> 也不在本次修补范围。故实测边界 = 51 零配置,而非 63。把 disabled 通道也接到 worker 求值可 +2 → 53。

## 3. A:51 行加载成功,全部零配置(证据:round-m7-5-01 日志)

> M7-4 曾把 12 行归为 B 组(需用户层 config 补丁):把 schemastery 的 `static Config` 默认值显式写进
> 用户层(loader 不跑 schemastery 校验,config=null 直传 → 插件构造读 `config.x` 崩),或把 base 层
> 的 `!!js` 表达式求值成字面量。**根因是 loader 的 config 通道与真实 cordis 的 schemastery 默认化
> 不同**,不是插件本身不能用。
>
> **M7-5 修补(3288b6a)**:worker 侧 apply 前先求值 config 里的 `!!js` 标记对象(`{$dshJs: expr}`,scope =
> `process` + `dshHomePath`),再按插件 `static Config` 默认化 —— schemastery(可调用函数)直接
> `Config(raw ?? {})`,zod 走 `.parse(raw ?? {})`;失败一律回退原 config 不挂加载。**这 12 行补丁
> 全部删除后,51 行一次 boot 成功**,证明配置通道已镜像真实 cordis。下表把原 B 行标注为 A(零配置)。

| 行 | 插件 | 状态 |
|---|---|---|
| llm | @deepseek-ai/dsh-llm | A |
| session | @deepseek-ai/dsh-session | A |
| typert-loader | @deepseek-ai/dsh-typert-loader | A |
| session-title-llm | @deepseek-ai/dsh-session-title-first-prompt-llm | A |
| user-questions | @deepseek-ai/dsh-user-questions | A |
| agent | @deepseek-ai/dsh-agent | A |
| agent-default-model | @deepseek-ai/dsh-agent-default-model | A |
| jobs | @deepseek-ai/dsh-jobs-local | A(零配置;schemastery 默认 `maxConcurrentJobsPerOwner: 10`) |
| llm-retry | @deepseek-ai/dsh-llm-retry | A(零配置;zod `z.object({})` parse) |
| credentials | @deepseek-ai/dsh-credentials-local | A(零配置;`resolveSpec` → `$DSH_HOME/.credentials.yaml`) |
| attachment-local | @deepseek-ai/dsh-attachment-local | A(零配置;`resolveDshHome` → `<home>/attachments/v1`) |
| session-projection | @deepseek-ai/dsh-session-projection | A |
| session-telemetry-otel | @deepseek-ai/dsh-session-telemetry-otel | A(零配置;base `!!js` 由桥求值 → `mode: DISABLED` + exporter url) |
| subprocess | @deepseek-ai/dsh-subprocess-local | A |
| sandbox | @deepseek-ai/dsh-sandbox-local | A(零配置;schemastery 默认 `runnerCommand/runnerFailureSignatures/probeTimeoutMs`) |
| sandbox-policy | @deepseek-ai/dsh-sandbox-policy | A |
| approval | @deepseek-ai/dsh-user-approval | A |
| shell-env | @deepseek-ai/dsh-shell-env | A(零配置;`resolveDshHome` → `<home>`) |
| tool-jobs | @deepseek-ai/dsh-tool-jobs | A |
| fs-observation-policy | @deepseek-ai/dsh-fs-observation-policy | A |
| tool-fs | @deepseek-ai/dsh-tool-fs | A |
| tool-fs-search | @deepseek-ai/dsh-tool-fs-search | A |
| agent-instructions | @deepseek-ai/dsh-agent-instructions | A |
| skill | @deepseek-ai/dsh-skill | A(零配置;`collectCacheMaxEntries ??` 默认) |
| tool-skill | @deepseek-ai/dsh-tool-skill | A |
| command-feedback | @deepseek-ai/dsh-command-feedback | A |
| goal-round-driver | @deepseek-ai/dsh-goal-round-driver | A |
| command-goal | @deepseek-ai/dsh-command-goal | A |
| token-meter | @deepseek-ai/dsh-token-meter | A(零配置;zod `z.object({})` parse) |
| compaction-basic | @deepseek-ai/dsh-compaction-basic | A(零配置;`resolveConfig` 填默认) |
| command-compact | @deepseek-ai/dsh-command-compact | A |
| subagent | @deepseek-ai/dsh-subagent | A |
| tool-subagent-control | @deepseek-ai/dsh-tool-subagent-control | A |
| tool-subagent | @deepseek-ai/dsh-tool-subagent | A |
| tool-subagent-fork | @deepseek-ai/dsh-tool-subagent | A |
| tool-subagent-report | @deepseek-ai/dsh-tool-subagent-report | A |
| workflow-worker-thread | @deepseek-ai/dsh-workflow-worker-thread | A |
| tool-workflow | @deepseek-ai/dsh-tool-workflow | A |
| timeout-policy | @deepseek-ai/dsh-tool-call-timeout-policy | A |
| spill-local | @deepseek-ai/dsh-spill-local | A(零配置;`root` undefined → OS-temp private root) |
| spill-policy | @deepseek-ai/dsh-spill-policy | A |
| session-checkpoint-policy | @deepseek-ai/dsh-session-checkpoint-policy | A |
| tool-result-pruner | @deepseek-ai/dsh-compaction-tool-result-pruner | A |
| tool-todo | @deepseek-ai/dsh-tool-todo | A |
| tool-goal | @deepseek-ai/dsh-tool-goal | A |
| tool-ralph | @deepseek-ai/dsh-tool-ralph | A |
| tool-str-replace-editor | @deepseek-ai/dsh-tool-str-replace-editor | A |
| repeat-tool-reminder | @deepseek-ai/dsh-repeat-tool-reminder | A(零配置;schemastery 默认 `include/exclude: []`) |
| web | @deepseek-ai/dsh-web | A |
| tool-web | @deepseek-ai/dsh-tool-web | A |
| system-prompt | @deepseek-ai/dsh-system-prompt | A |

**M7-5 落地**:loader 的 config 通道已镜像真实 cordis —— worker 侧在 apply 前求值 config 里的
`!!js`(scope = `process` + `dshHomePath`)并跑插件 `static Config` 默认化(schemastery 可调用 /
zod `.parse`)。**12 行 B 组已降为 A 组,51 行全部是「零 config」**,不再是 M7-4 的"纯配置可达"。

## 4. C:22 行失败,按原因分类(每行 = 一行原因 + 缺什么)

| 失败行 | 原因类别 | 具体原因 / 缺什么 |
|---|---|---|
| typert | 客户端专属 | `dsh.client.platform: web, immediately: true`;**无 host lib 构建**(lib/ 仅 types/),loader 把所有行当 Node 插件、不跳过 client-only 行 |
| typert-gateway | 客户端专属 | 同上(@deepseek-ai/dsh-api-gateway,web 平台) |
| tool-subagent-list-agents | 桥形状 | 模块说明符是 **export-map 子路径** `.../list-agents`;`HostSelector.resolveFromNodeModules` 只解析包根,不解析 exports 子路径 → path not found |
| timer | 桥形状 | `ctx.mixin()` 是 cordis 框架方法,**Java 桥 ctx shim 未暴露**(Proxy 把 `ctx.mixin` 当服务 get → `Context.get('mixin')` 抛 "without inject") |
| hmr | 缺 ctx 服务 | 缺 **`ctx.loader`**(cordis 热重载 loader 服务),Java 核心未提供 |
| session-title | 桥形状 | Service 实例含 `this.ctx` 回环,**node-bridge serializeValue 拒绝循环** → "cannot serialize cyclic value" |
| session-query-sqlite | 桥形状 | 实例含 own 可枚举 `_persistenceBinding = { identity: Symbol() }`;serializeValue **拒绝 Symbol** |
| llm-pi-ai | 桥形状 | config 空为正确 dormant 态,但注册 PiAiAdapter 时 **fn handle 失效** → "unknown fn handle" |
| session-persistence-jsonl | **核心: fiber 隔离** | 读 `ctx.sessions`(dsh-session 已提供)仍 "without inject";**PluginLoaderService 每插件一个独立子 fiber,兄弟 fiber 服务不可见** |
| settings | 桥形状(shim bug) | cordis-shim Service 构造用 `typeof self[key]` 绑定原型成员,**求值了 getter**;`documentPath` getter 在子类 `this.spec` 赋值前被读 → 崩 |
| permission | 桥形状(shim bug)+核心 | 同上 getter bug(`get names`);且 `static inject = ['shell','approval','sessions']` |
| commands | 桥形状 | Service 实例循环 → serializeValue 拒绝 |
| goal | 桥形状 | config 补丁过了 null,但 GoalService 实例循环 → serializeValue 拒绝 |
| plan-mode | 核心: fiber 隔离 | 读 `ctx.systemPrompt`(system-prompt 是**更靠后**的行,即便无隔离也按顺序不可见) |
| skill-filesystem | 桥形状 | fn handle 失效 → "unknown fn handle" |
| subagent-spawn-in-process | 桥形状 | fn handle 失效 |
| subagent-fork-in-process | 桥形状 | fn handle 失效 |
| web-search-deepseek | 桥形状 | fn handle 失效 |
| tools | 核心: fiber 隔离 | config.mode 可补(null),但构造读 `ctx.systemPrompt.tools(...)`(system-prompt 更靠后) |
| agent-loop | 核心: fiber 隔离 | 读 `ctx.configuredAgentIdentities` |
| fs-sandbox | 核心: fiber 隔离 | config.diffBasisMaxBytes 可补,null,但构造读 `ctx.sandboxPolicy.defaultMode`(sibling fiber) |
| llm-deepseek | 核心: fiber 隔离 | 读 `ctx.launchEnvironment` |

**类别汇总**:
- **缺 ctx 服务 / fiber 隔离(核心工作)**:6 行 —— session-persistence-jsonl、plan-mode、tools、
  agent-loop、fs-sandbox、llm-deepseek。根因:**每插件独立子 fiber,服务不跨兄弟可见**;真实 dsh
  按"服务可用性驱动"激活(cordis patch 注释原话),loader 按严格顺序 + 逐插件 fiber 实现。
- **桥形状(跨桥值/句柄)**:13 行 —— typert、typert-gateway(client-only 无 host lib)、
  tool-subagent-list-agents(exports 子路径)、timer(ctx.mixin 未暴露)、session-title/commands/goal
  (循环)、session-query-sqlite(Symbol)、llm-pi-ai/skill-filesystem/subagent-{spawn,fork}-
  in-process/web-search-deepseek(fn handle 失效)、settings/permission(shim getter 求值 bug)。
- **核心: cordis-shim 与真实 cordis 语义差异**):settings、permission 的 getter 求值 bug 属这一类。

## 5. D:5 行 base 层 disabled(M7-5 复核:仅 config 通道的 `!!js` 被求值,disabled 通道未解)

> **M7-5 复核结论**:配置通道修补只求值 **config 值**里的 `!!js`(worker 侧 apply 前);`disabled`
> 字段是 **Java 侧独立判定**(`DshProfileReader.isDisabled`),对 `!!js` 标记对象(`{$dshJs: ...}`)
> 一律保守返回「已禁用」—— `DshProfileReaderTest` 显式断言「带 disabled 表达式(!!js)的行被保守
> 跳过」。因此 **pwsh-sandbox / tool-pwsh 在 Windows 上仍不加载**(round-m7-5-01 日志无此二行),
> 与修补前一致。「!!js 相关的 D 组在 Windows 上应能求值并加载」的预期**未达成**:这是 disabled
> 通道,不是 config 通道 —— 修补范围之外。
>
> **M7-6 落地**:disabled 通道已接到 worker 求值(3288b6a 之后,独立提交)。`DshProfileReader`
> 对 `{$dshJs: expr}` disabled 标记不再保守剔除 —— 行保留,标记经 `Entry.disabled()` 透传给
> loader,loader 在加载前让宿主求值(scope = process + dshHomePath,与 config 通道同函数
> `evalJsExpression`);求值 truthy → 插件不加载,求值失败 → 保守按禁用处理。**pwsh-sandbox /
> tool-pwsh 在 Windows 上不再被保守跳过**(`process.platform !== 'win32'` 求值为 false → 加载),
> D 组 +2 行进入"可加载"面;bash-sandbox / tool-bash 由求值正确禁用(与保守跳过同结果)。
> 注:强制启用后 pwsh 是否还有桥/服务注入问题未在本次实验复核(超出 disabled 通道范围)。

| 行 | base 表达式 | Windows 实际 | Java 处理 | 备注 |
|---|---|---|---|---|
| bash-sandbox | `!!js process.platform === 'win32'` | 应禁用 | 标记对象→跳过 | ✅ 正确(win32 本就该禁用) |
| tool-bash | 同上 | 应禁用 | 跳过 | ✅ 正确 |
| skill-badge | `disabled: true` | 禁用 | 布尔真→跳过 | ✅ 正确 |
| pwsh-sandbox | `!!js process.platform !== 'win32'` | **应启用** | 标记对象→跳过 | ⚠️ **仍保守跳过**:disabled 通道的 `!!js` Java 侧不求值(行被 compose 排除,进不到 worker)。解=把 disabled 判定也接到 worker 求值;未测强制启用后是否还有桥/服务注入问题 |
| tool-pwsh | 同上 | **应启用** | 跳过 | ⚠️ 同上 |

## 6. 迭代轮次日志(M7-4:40 轮上限,37 轮完成;M7-5 复核:1 轮)

`build/m7-4/round-01.log … round-37.log`(gitignored)。每轮 = 第一个失败行 → 钉/补 → 重跑:

```
01 typert         客户端专属无 host lib           → 钉
02 tool-subagent-list-agents  exports 子路径      → 钉
03 timer          ctx.mixin 未暴露               → 钉
04 hmr            缺 ctx.loader                  → 钉
05 session-title  循环 serialize                 → 钉
06 jobs           config null → maxConcurrent   → config 补丁 ✅
07 llm-retry      config null → Object.keys      → config 补丁 ✅
08 settings       shim getter 求值 bug           → 钉
09 credentials    config null → path             → config 补丁 ✅
10 llm-pi-ai      config null → providers        → config 补丁
11 llm-pi-ai      fn handle 失效                 → 钉
12 session-persistence-jsonl  缺 ctx.sessions    → 钉
13 attachment-local config null → dshHome        → config 补丁 ✅
14 session-query-sqlite  Symbol serialize        → 钉
15 session-telemetry-otel  !!js 字面量            → config 补丁 ✅
16 sandbox        config null → runnerCommand    → config 补丁 ✅
17 permission     shim getter bug + inject       → 钉
18 shell-env      config null → dshHome          → config 补丁 ✅
19 skill          config null → collectCache     → config 补丁 ✅
20 skill-filesystem  fn handle 失效              → 钉
21 commands       循环 serialize                 → 钉
22 goal           config null → defaultRounds    → config 补丁
23 goal           循环 serialize                 → 钉
24 plan-mode      缺 ctx.systemPrompt            → 钉
25 token-meter    config null → Object.keys      → config 补丁 ✅
26 compaction-basic config null → resolveConfig  → config 补丁 ✅
27 subagent-spawn-in-process  fn handle 失效      → 钉
28 subagent-fork-in-process   fn handle 失效      → 钉
29 spill-local    config null → root             → config 补丁 ✅
30 repeat-tool-reminder  config 缺 include/exclude → config 补丁 ✅
31 web-search-deepseek  fn handle 失效            → 钉
32 tools          缺 ctx.systemPrompt            → 钉
33 agent-loop     缺 ctx.configuredAgentIdentities→ 钉
34 fs-sandbox     缺 ctx.sandboxPolicy           → 钉
35 llm-deepseek   缺 ctx.launchEnvironment       → 钉
36 llm-deepseek   缺 ctx.launchEnvironment       → 钉
37 稳定:51 行 boot 成功(HTTP 状态页)            ✅
m7-5-01 应用配置通道修补 + 删除 12 行 B 组补丁   → 51 行零配置一次成功 ✅
```

> `round-m7-5-01.log`:DSH_HOME=`build/m7-4/home`,`./dshj --profile full boot`,120s 内未退出 =
> 整树加载成功(evidence 已替换进 `docs/m7-4/m7-4-full-boot.log`)。删除补丁的行:jobs / llm-retry /
> credentials / attachment-local / session-telemetry-otel / sandbox / shell-env / skill / token-meter /
> compaction-basic / spill-local / repeat-tool-reminder。

## 7. 结论与建议(M7-5 复核后)

- **"核心 = Java cordis + 其他全是 dsh + 配置即用"实证成立到 51/78,且 51 行全部零配置**。
  配置通道已镜像真实 cordis(schemastery/zod 默认化 + `!!js` 求值),12 行 B 组补丁删除后一次 boot
  成功 —— **config 通道的缺口已关闭**(M7-4 §7 预估的"51 → 63"未达,因为 12 行 B 组本就计入 51,
  见 §2 说明)。
- **剩余 22 行 C 组里,没有一个**是"版本标签"或"原生 OS"问题 —— **全部是桥面/核心工作**
  (跨桥值序列化、fn handle 生命周期、ctx 框架方法未暴露、exports 子路径解析、client-only 行建模、
  fiber 隔离)。**5 行 D 组**:3 行正确禁用;2 行(pwsh-sandbox/tool-pwsh)是 **disabled 通道的 `!!js`
  未求值**导致的保守跳过(独立于 config 通道)。
- **最高杠杆的 Java 侧工作**(按行数):
  1. **共享插件 fiber / 服务可用性驱动激活**(6 行):session-persistence-jsonl、plan-mode、tools、
     agent-loop、fs-sandbox、llm-deepseek。镜像真实 dsh 的 activation 语义是解锁最大块的核心工作。
  2. **node-bridge 值序列化 + fn handle 生命周期加固**(13 行):循环/Symbol 容忍、服务 provide 改
     句柄、client-only 行跳过、exports 子路径解析、ctx.mixin 等框架方法暴露。
  3. **cordis-shim Service 构造**(2 行):settings、permission —— 原型绑定循环用
     `getOwnPropertyDescriptor` 跳过 getter。
  4. **disabled 通道的 `!!js` 求值**(2 行):pwsh-sandbox、tool-pwsh —— 把 `disabled` 判定也接到
     worker 侧求值(与 config 通道同 mechanism),可再 +2。
- **已闭合**:config 通道(schema 默认化 + config `!!js` 求值)→ 12 行降为 A 组。

## 8. 提交回归锁

- 实验产物(profile、round 日志、脚本)在 `build/m7-4/`(gitignored),不入库。
- 入库:`docs/m7-4/m7-4-tree-load-map.md` + `docs/m7-4/m7-4-full-boot.log`(round-m7-5-01 证据,
  替换原 round-37)。
- 配置通道修补(M7-5)已单独提交(3288b6a,含 ConfigChannelTest ×5 + DshProfileReaderTest 更新);
  全量回归 285 测试全绿。
- 本次复核未改任何核心代码/loader 语义;只更新 docs + 实验产物。
