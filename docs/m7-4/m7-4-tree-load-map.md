# M7-4 — dsh-base 全量核心树加载地图(实证"配置即用"边界)

里程碑:M7-4 + M7-5 + M7-6 复核。日期:2026-08-18。实证记录(非臆测)。

结论:**"核心 = Java cordis + 其他全是 dsh + 配置即用"成立到 63/78 行注册、44/78 行真正 apply
(round-m7-6-final 复核)**。M7-6 三件套(fiber 隔离、桥值序列化、disabled 通道 `!!js` 求值)让 13 行
C 组不再需要钉住(12 行真正 apply,`tool-subagent-list-agents` 注册成功但因依赖 `tools` 被钉而滞留
PENDING)。整树照常 boot 到 HTTP 状态页(证据:`docs/m7-4/m7-4-full-boot.log`,round-m7-6-final)。

> **诚实修正(M7-6 复核)**:M7-5 的 "51 行 A" 是把「loader 注册成功」误当成「apply 成功」。boot
> 日志列出的是**全部注册行**(`LoaderService.loaded()`),不区分 fiber 是否真正 apply。本次用 fiber
> 状态审计(每行注册后查 `FiberState`)重测 M7-5 钉集(当前代码 + M7-5 22 钉):**28 ACTIVE +
> 23 PENDING**(PENDING = 注册了但 inject 依赖不满足 → apply 从未运行)。M7-6 复核后 **44 ACTIVE
> + 19 PENDING**。这两组里 PENDING 的根因一致:**该行的 inject 依赖(多为 `tools`)被钉住** →
> fiber 保持 PENDING、apply 不跑,但不阻塞 boot(不 FAILED)。这不是 M7-6 引入的回归,而是
> M7-4/M7-5 测量口径的偏差(把「注册」当「加载」);M7-6 复核把口径修正为 apply 是否真正运行。

## 1. 实验设置(与 M7-1 同形,反向:全量不 disable)

- **profile**:`build/m7-4/home/profiles/full`(DSH_HOME=`build/m7-4/home`),`package.json`
  声明 `dsh.profile.bundles = ["@deepseek-ai/dsh-base"]`;**用户层初始 `[]`,逐步按失败钉行**。
- **M7-5 复核**:应用配置通道修补(3288b6a)后重跑同一 profile,**删除 12 行 B 组的用户层 config
  补丁**(回到零配置),只保留 22 行 C 组 `disabled: true` 钉行。结果:51 行注册、无 FAILED。
- **M7-6 复核(本次)**:应用 fiber 隔离(bd206d6)+ 桥值序列化(800869e)+ disabled 通道 `!!js`
  求值(5205c4c)后,把 M7-6 已解的行从用户层钉中移除,只保留真正未解的行。结果:
  - **63 行注册**、44 行真正 apply(ACTIVE)、19 行 PENDING(注册但依赖未满足)。
  - 13 行 C 组不再需要钉(fiber 2 行 + 桥 11 行),其中 12 行 ACTIVE、`tool-subagent-list-agents`
    注册成功但滞留 PENDING(其 inject `tools`/`subagents` 被钉)。
  - **新增 3 行钉**:`goal-round-driver`(M7-5 时因 `goals` 不可用而休眠,现 `goal` 加载后激活并在
    apply 里调跨 worker 的 `ctx.agents.list()` → 失败)、`pwsh-sandbox`/`tool-pwsh`(disabled 通道
    求值后启用,但 apply 触发 cordis-shim getter bug)。
- **workspace 链接**:`vendor/dsh/node_modules/@deepseek-ai/dsh-base` 链接已在(M7-1 恢复);
  profile 自身 `node_modules/@deepseek-ai/*` 为 76 个 workspace 包目录的 junction(镜像真实
  `pnpm add @deepseek-ai/dsh-base` + install 的扁平 node_modules 布局)。
- **boot**:`DSH_HOME=<home> ./dshj --profile full boot`。加载器语义 = 任一条目失败 → 整批回滚;
  每轮只暴露**第一个**失败行 → 钉掉 → 重跑。M7-4 37 轮稳定;M7-5 复核 1 轮;M7-6 复核 4 轮
  (goal 触发 goal-round-driver 激活回归 + pwsh 启用后 shim bug 两处)。
- **边界断言**:round-m7-6-final `./dshj --profile full boot` 完整 boot,63 行列出 + `profile 'full' is
  running on the Java harness` + `web status page at http://127.0.0.1:8080/`(120s 内未退出 =
  整树加载成功)。证据日志:`docs/m7-4/m7-4-full-boot.log`(round-m7-6-final 原样,替换 round-m7-5-01)。
- 用户层 `cordis.patch.yml`(每行注释记录原因):`build/m7-4/home/profiles/full/cordis.patch.yml`
  (build/,gitignored)。**M7-6 复核后 12 行钉:9 行原 C 组未解 + `goal-round-driver` + 2 行 pwsh。**

## 2. 最终统计(78 行;M7-6 复核口径)

| 分组 | 行数 | 说明 |
|---|---|---|
| A 注册且真正 apply(ACTIVE,零 config) | **44** | fiber 状态审计确认 apply 已运行 |
| P 注册但 PENDING(依赖未满足,apply 未跑) | **19** | 见 §2.1;M7-5 的 "51 A" 里就有 23 行如此 |
| C 用户层钉住(未解) | **10** | typert/typert-gateway(客户端)、hmr、settings/permission(shim getter)、session-persistence-jsonl/plan-mode/tools/agent-loop(跨 worker 方法调用)、goal-round-driver(新增) |
| D base 层 disabled | **5** | 3 正确禁用(bash-sandbox/tool-bash/skill-badge)+ 2 已钉住(pwsh-sandbox/tool-pwsh,启用后 shim bug) |

**78 行里:44 真正 apply / 19 注册待依赖 / 10 用户钉 / 5 base 禁用。**

> **为什么 44 不是 63?** 63 是「注册无失败」;fiber 状态审计显示其中 19 行 PENDING(注册了但
> inject 依赖不满足 → apply 从未运行)。PENDING 不 FAILED,所以 boot 日志照样列出、boot 不失败,
> 但插件的 apply 体没有执行 —— 不算"加载"。真实边界 = **44 行 apply**。
>
> **M7-5 的 "51 A" 是什么?** M7-5 用 boot 日志(列出全部注册行)判定 "51 行加载"。本次审计显示
> 那是 **28 ACTIVE + 23 PENDING**。PENDING 行在 M7-5 一样存在(它们的 inject 依赖 `tools`/
> `commands`/`goals` 等当时就被钉),只是 M7-4/M7-5 没按 fiber 状态区分。修正口径后 M7-5 真加载
> = 28,不是 51。

### 2.1 PENDING 19 行(注册成功,apply 未跑)

| 行 | 未满足的 inject 依赖 |
|---|---|
| typert-loader | `typert`(钉)、`loader`(核心未提供) |
| tool-jobs / tool-fs / tool-fs-search / tool-skill / tool-subagent / tool-subagent-control / tool-subagent-fork / tool-subagent-report / tool-subagent-list-agents / tool-todo / tool-web / tool-goal / tool-ralph / tool-workflow / timeout-policy / spill-policy / session-checkpoint-policy | `tools`(钉;其中 tool-fs 还缺 `fs`、tool-jobs 还缺 `jobs`、session-checkpoint-policy 还缺 `sessionPersistence`) |

根因:这些行的 `static inject`/`const inject` 声明了 `tools` 等依赖,而 `tools` 行仍在 C 组钉住 → fiber
保持 PENDING,apply 不运行。**解 `tools` 行后这些行会批量进入 ACTIVE**(下一阶段工作)。

## 3. A:44 行注册且真正 apply(证据:round-m7-6-final fiber 状态)

> M7-4 曾把 12 行归为 B 组(需用户层 config 补丁);M7-5 配置通道修补让它们零配置。
> M7-6 复核用 fiber 状态确认:下面 44 行 apply 真正运行了。

- 28 行 M7-5 基线 ACTIVE 全部保留:agent, agent-default-model, agent-instructions, approval,
  attachment-local, compaction-basic, credentials, fs-observation-policy, jobs, llm, llm-retry,
  repeat-tool-reminder, sandbox, sandbox-policy, session, session-projection, session-telemetry-otel,
  shell-env, skill, spill-local, subagent, subprocess, system-prompt, token-meter, tool-result-pruner,
  user-questions, web, workflow-worker-thread(见证据日志完整列表)。
- **M7-6 新增 16 行 ACTIVE**(相对 M7-5 基线):
  - 12 行 M7-6 解的 C 组:fs-sandbox, llm-deepseek, timer, session-title, session-query-sqlite,
    llm-pi-ai, commands, goal, skill-filesystem, subagent-spawn-in-process, subagent-fork-in-process,
    web-search-deepseek。
  - 4 行原本 PENDING、因依赖行解钉而激活:command-compact, command-feedback, command-goal(依赖
    `commands` 现在加载)、session-title-llm(依赖 `sessionTitle` 现在加载)。

完整 ACTIVE 列表见 `docs/m7-4/m7-4-full-boot.log`(round-m7-6-final;44 行全列出)。

## 4. C:用户层钉住的 10 行(M7-6 复核后),按原因分类

| 失败行 | 原因类别 | 具体原因 / 缺什么 |
|---|---|---|
| typert / typert-gateway | 客户端专属(非桥) | `dsh.client.platform: web, immediately: true`,lib/ 仅有 types/ 无 host 运行时构建;loader 不跳过 client-only 行。M7-6 确认非桥问题,设计如此,需 loader 建模跳过 |
| hmr | 缺 ctx 服务 | 缺 `ctx.loader`(cordis 热重载 loader 服务),Java 核心未提供 |
| settings / permission | 核心:shim getter bug | cordis-shim Service 构造用 `typeof self[key]` 求值 getter,子类构造前读 `this.spec`/`this.presets` 崩。M7-6 未修(核心:cordis-shim 类别,另行处理) |
| session-persistence-jsonl / plan-mode / tools / agent-loop | 核心:跨 worker 服务方法 | fiber 隔离后兄弟服务可见(不再 "without inject"),但 apply 内**调用**兄弟服务方法(`ctx.sessions.list` / `ctx.systemPrompt.section` / `ctx.systemPrompt.tools` / `ctx.agents.setFactory`)→ 每插件独立 worker 的 fn 句柄跨 worker 降级为 no-op stub。需"服务句柄化"(跨 worker 服务方法调用),独立桥面工作项 |
| goal-round-driver | **新增(M7-6 复核发现)** | M7-5 时因 `goals` 不可用(goal 钉住)而休眠 PENDING,被误记为 "A";M7-6 让 `goal` 加载后该行激活,apply 调跨 worker `ctx.agents.list()` → 失败。与上面 4 行同类(跨 worker 服务方法) |

## 5. D:5 行 base 层 disabled(M7-6 复核:disabled 通道已接 worker 求值)

| 行 | base 表达式 | Windows 实际 | 处理 | 备注 |
|---|---|---|---|---|
| bash-sandbox | `!!js process.platform === 'win32'` | 应禁用 | worker 求值 → true → 禁用 | ✅ 正确 |
| tool-bash | 同上 | 应禁用 | 同上 | ✅ 正确 |
| skill-badge | `disabled: true` | 禁用 | 布尔真→跳过 | ✅ 正确 |
| pwsh-sandbox | `!!js process.platform !== 'win32'` | **启用** | worker 求值 → false → 加载 → apply 失败(shim getter bug) | ⚠️ 钉住(新) |
| tool-pwsh | 同上 | **启用** | 同上 | ⚠️ 钉住(新) |

M7-6 的 disabled 通道修复让 pwsh 两行**不再保守跳过**(`process.platform !== 'win32'` 在 Windows
求值为 false → 行保留、尝试加载),但 apply 触发 cordis-shim getter bug(`get config()` 读
`this.source()` 早于赋值)→ 需要钉住。这是"强制启用后还有桥/服务注入问题"的实证,M7-6 §5 当时的
注记被本次复核确认。

## 6. 迭代轮次日志(M7-4:40 轮上限,37 轮完成;M7-5 复核:1 轮;M7-6 复核:4 轮)

`build/m7-4/round-01.log … round-37.log`、`round-m7-5-01.log`、`round-m7-6-*.log`(gitignored):

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
37 稳定:51 行注册(HTTP 状态页)                  ✅
m7-5-01 应用配置通道修补 + 删除 12 行 B 组补丁   → 51 行注册零配置一次成功 ✅
m7-6-01 移除 M7-6 已解行的钉 → 首个失败:goal-round-driver
        (goal 加载后激活,apply 调 ctx.agents.list 跨 worker 失败) → 钉 goal-round-driver
m7-6-02 pwsh-sandbox 启用后 shim getter bug      → 钉 pwsh-sandbox
m7-6-03 tool-pwsh 同                            → 钉 tool-pwsh
m7-6-final 63 行注册,44 ACTIVE + 19 PENDING,boot 稳定 ✅
```

> `round-m7-6-final.log`:DSH_HOME=`build/m7-4/home`,`./dshj --profile full boot`,120s 内未退出 =
> 整树加载成功(evidence 已替换进 `docs/m7-4/m7-4-full-boot.log`)。fiber 状态审计另见
> `build/m7-4/round-m7-6-fiberstate.log`(44 ACTIVE / 19 PENDING)。

## 7. 结论与建议(M7-6 复核后)

- **"核心 = Java cordis + 其他全是 dsh + 配置即用"实证成立到 63/78 注册、44/78 apply**。
  配置通道(M7-5)、fiber 隔离 + 桥序列化 + disabled 通道(M7-6)三处缺口都已关闭。
- **M7-6 让 13 行 C 组解钉**(12 行真正 apply + `tool-subagent-list-agents` 注册成功但依赖 `tools`
  被钉而 PENDING),并让 4 行原本 PENDING 的行因依赖解钉而激活 → ACTIVE 28 → 44(+16)。
- **剩余 10 行 C 组钉**分类:
  1. **跨 worker 服务方法调用**(5 行):session-persistence-jsonl、plan-mode、tools、agent-loop、
     goal-round-driver。fiber 隔离让服务可见,但"调用兄弟服务方法"仍缺服务句柄化 —— 最高杠杆。
  2. **cordis-shim getter bug**(2 行):settings、permission(连同 pwsh 两行共 4 行同因)。
  3. **客户端专属**(2 行):typert、typert-gateway(设计如此,loader 建模跳过 client-only 行)。
  4. **缺 ctx.loader**(1 行):hmr。
- **PENDING 19 行**:几乎全因 `tools` 被钉而滞留。解 `tools` 行(跨 worker 服务方法)后这 19 行会
  批量进入 ACTIVE —— 与 C 组第 1 类是同一把钥匙。
- **测量口径修正**:boot 日志列出的是注册行,不是 apply 行。今后判定"行是否加载"应以 fiber
  状态(ACTIVE)为准,M7-5 的 "51 A" 是 28 ACTIVE + 23 PENDING。

## 8. 提交回归锁

- 实验产物(profile、round 日志、脚本)在 `build/m7-4/`(gitignored),不入库。
- 入库:`docs/m7-4/m7-4-tree-load-map.md` + `docs/m7-4/m7-4-full-boot.log`(round-m7-6-final 证据,
  替换 round-m7-5-01)。
- 本次复核未改任何核心代码/loader 语义(仅临时加过 fiber 状态审计日志,已还原);只更新 docs +
  实验产物。M7-6 核心修复(fiber 隔离 / 桥序列化 / disabled 通道)已在先前提交落地。
