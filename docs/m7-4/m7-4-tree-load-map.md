# M7-4 — dsh-base 全量核心树加载地图(实证"配置即用"边界)

里程碑:M7-4 + M7-5 + M7-6 + M7-7 复核。日期:2026-08-18。实证记录(非臆测)。

结论:**"核心 = Java cordis + 其他全是 dsh + 配置即用"成立到 67/78 行注册、65/78 行真正 apply
(round-m7-7-final-clean 复核)**。M7-7 句柄扩展(迭代器 + 递归 live 对象 + 发射器 + cordis-shim getter 修复)
让 4 行 C/D 组解钉(settings、pwsh-sandbox、tool-pwsh 因 shim getter 修复,**tools** 因迭代器/递归
live 对象 + 一次核心 notify 重入修复),且 **`tools` 是整树的枢纽**:它一旦加载,17 行原本 PENDING
(注册但 inject `tools` 未满足)的行批量进入 ACTIVE。整树照常 boot 到 HTTP 状态页(证据:
`docs/m7-4/m7-4-full-boot.log`,round-m7-7-final-clean)。

> **诚实修正(M7-7 复核)**:目标 63→70+,实测边界是 **67/78 注册、65/78 apply**。M7-7 句柄扩展
> 解决了 4 行 + 级联激活 17 行;剩余的 8 行钉里有 4 行(session-persistence-jsonl / goal-round-driver /
> plan-mode / agent-loop)仍需 **provide 通道的服务句柄化**(跨 worker 服务方法调用 —— M7-6 §7 已
> 记为"独立桥面工作项"),M7-7 只完成了方法**返回**的句柄化,未做 provide 值本身。另 2 行
> (typert/typert-gateway)是 web-client-only(loader 建模),hmr 缺 `ctx.loader`,permission 的 shim
> getter 已修但暴露新的功能检查失败(见 §4)。**70+ 未达成的原因逐行记录,不夸大。**

## 1. 实验设置(与 M7-1 同形,反向:全量不 disable)

- **profile**:`build/m7-4/home/profiles/full`(DSH_HOME=`build/m7-4/home`),`package.json`
  声明 `dsh.profile.bundles = ["@deepseek-ai/dsh-base"]`;**用户层初始 `[]`,逐步按失败钉行**。
- **M7-5 复核**:应用配置通道修补后重跑同一 profile,删除 12 行 B 组用户层 config 补丁。
- **M7-6 复核**:fiber 隔离 + 桥序列化 + disabled 通道 `!!js` 求值后,12 行钉。
- **M7-7 复核(本次)**:M7-7 句柄扩展(dac2e63 迭代器/递归 live 对象、04928a1 发射器 + shim
  getter 修复)落地后,从 12 行钉中移除 4 行(settings / pwsh-sandbox / tool-pwsh / tools),其余
  8 行保留。结果:
  - **67 行注册**、65 行真正 apply(ACTIVE)、2 行 PENDING(注册但依赖未满足)。
  - **`tools` 解钉是本次最大的杠杆**:它 inject `systemPrompt` 且 apply 里调
    `ctx.systemPrompt.tools()/section()`(跨 worker 方法),M7-7 迭代器 + 递归 live 对象让这些
    方法可调;而 tools 一旦 ACTIVE,17 行依赖 `tools` 的 PENDING 行批量 ACTIVE(见 §3)。
  - **settings / pwsh-sandbox / tool-pwsh 解钉**:三行同因 M7-6 记的 cordis-shim Service 构造器
    getter bug(pwsh-local `get config()` → `this.source()`;settings `get documentPath` →
    `this.spec.filename`),M7-7 shim 改用属性描述符只绑数据方法、accessor 一律不求值 → 三行 apply
    不再崩。
  - **新增/改因钉**:plan-mode(M7-6 时因 `tools` 被钉而 PENDING 休眠;M7-7 tools 加载后激活,
    apply 调 `ctx.systemPrompt.section(...)` 的 text 求值读 `agent.session.events` 崩)、
    permission(shim getter 修好但 apply 里 `ctx.shell.sandboxMode === undefined` 真功能检查失败)。
- **workspace 链接**:`vendor/dsh/node_modules/@deepseek-ai/dsh-base` 链接已在(M7-1 恢复);
  profile 自身 `node_modules/@deepseek-ai/*` 为 76 个 workspace 包目录的 junction。
- **boot**:`DSH_HOME=<home> ./dshj --profile full boot`。加载器语义 = 任一条目失败 → 整批回滚;
  每轮只暴露第一个失败行 → 钉掉 → 重跑。M7-7 复核 20 轮(见 §6 迭代日志)。
- **边界断言**:round-m7-7-final-clean `./dshj --profile full boot` 完整 boot,67 行列出 +
  `profile 'full' is running on the Java harness` + `web status page at http://127.0.0.1:8080/`
  (120s 内未退出 = 整树加载成功)。证据日志:`docs/m7-4/m7-4-full-boot.log`(round-m7-7-final-clean 原样,
  替换 round-m7-6-final)。fiber 状态审计另见 `build/m7-4/round-m7-7-18-final-audit.log`
  (65 ACTIVE / 2 PENDING)。
- 用户层 `cordis.patch.yml`(每行注释记录原因):`build/m7-4/home/profiles/full/cordis.patch.yml`
  (build/,gitignored)。**M7-7 复核后 8 行钉。**

## 2. 最终统计(78 行;M7-7 复核口径)

| 分组 | 行数 | 说明 |
|---|---|---|
| A 注册且真正 apply(ACTIVE,零 config) | **65** | fiber 状态审计确认 apply 已运行 |
| P 注册但 PENDING(依赖未满足,apply 未跑) | **2** | typert-loader(依赖 typert 钉)、session-checkpoint-policy(依赖 sessionPersistence 钉) |
| C 用户层钉住(未解) | **8** | 见 §4 |
| D base 层 disabled | **3** | bash-sandbox / tool-bash / skill-badge(正确禁用) |

**78 行里:65 真正 apply / 2 注册待依赖 / 8 用户钉 / 3 base 禁用。**

> **M7-6 → M7-7 变化**:注册 63 → **67**(+4:settings + pwsh×2 + tools);真正 apply 44 → **65**
> (+21:上述 4 行 + 17 行 tools 级联激活);PENDING 19 → **2**;钉 12 → **8**。
>
> **`tools` 是枢纽的实证**:M7-6 §2.1 记 19 行 PENDING"几乎全因 `tools` 被钉而滞留,解 `tools`
> 行后这批会批量进入 ACTIVE"——M7-7 兑现了这句预测。tools ACTIVE 后,17 行依赖它的 PENDING
> 行(spill-policy / timeout-policy / tool-fs / tool-fs-search / tool-goal / tool-jobs / tool-pwsh /
> tool-ralph / tool-skill / tool-str-replace-editor / tool-subagent* / tool-todo / tool-web /
> tool-workflow)批量进入 ACTIVE。**这才是 M7-7 句柄扩展的真正价值所在。**

### 2.1 PENDING 2 行(注册成功,apply 未跑)

| 行 | 未满足的 inject 依赖 |
|---|---|
| typert-loader | `typert`(钉)、`loader`(核心未提供) |
| session-checkpoint-policy | `sessionPersistence`(来自钉住的 session-persistence-jsonl) |

## 3. A:65 行注册且真正 apply(证据:round-m7-7-final-clean fiber 状态)

> 65 = 44(M7-6 ACTIVE 全保留)+ 4(M7-7 解钉)+ 17(tools 级联激活)。

- **44 行 M7-6 ACTIVE 全部保留**(见证据日志完整列表)。
- **M7-7 解钉 4 行**:
  - settings(shim getter 修复 → apply 不再崩,ACTIVE)。
  - pwsh-sandbox / tool-pwsh(shim getter 修复;tool-pwsh 还依赖 tools,M7-7 tools 加载后同轮激活)。
  - **tools**(M7-7 迭代器 + 递归 live 对象 + 核心 notify 重入修复 → apply 里的
    `ctx.systemPrompt.tools()/section()` 跨 worker 方法可调,ACTIVE)。
- **tools 级联激活 17 行**(原本 PENDING,依赖 `tools` 现在满足):spill-policy、timeout-policy、
  tool-fs、tool-fs-search、tool-goal、tool-jobs、tool-ralph、tool-skill、
  tool-str-replace-editor、tool-subagent、tool-subagent-control、tool-subagent-fork、
  tool-subagent-list-agents、tool-subagent-report、tool-todo、tool-web、tool-workflow。
  (tool-pwsh 归入上面 4 行解钉——它原在 M7-6 D 组钉住,shim getter 修复 + tools 加载后同轮 ACTIVE,
  不是 17 行级联之一。)

完整 ACTIVE 列表见 `docs/m7-4/m7-4-full-boot.log`(round-m7-7-final-clean;65 行全列出)。

## 4. C:用户层钉住的 8 行(M7-7 复核后),按原因分类

| 失败行 | 原因类别 | 具体原因 / 缺什么 |
|---|---|---|
| typert / typert-gateway | 客户端专属(非桥) | `dsh.client.platform: web, immediately: true`,lib/ 仅有 types/ 无 host 运行时构建;loader 不跳过 client-only 行。M7-7 确认非桥问题,设计如此,需 loader 建模跳过 |
| hmr | 缺 ctx 服务 | 缺 `ctx.loader`(cordis 热重载 loader 服务),Java 核心未提供 |
| session-persistence-jsonl / goal-round-driver | 核心:跨 worker 服务方法(provide 通道) | apply 里 `ctx.sessions.list()` / `ctx.agents.list()` → `for...of` 返回值。M7-7 把方法**返回**句柄化(迭代器/递归 live 对象),但服务值本身经 `provide` 仍以 fn 句柄 JSON 跨桥,另一 worker 读到的是 stale fn 句柄 → no-op stub → 返回值不可迭代。**需 provide 通道服务句柄化**(跨 worker 服务方法调用),仍是独立桥面工作项 |
| plan-mode | **M7-7 改因(原 PENDING 休眠,现激活失败)** | M7-6 时因 `tools` 被钉而 PENDING 休眠;M7-7 tools 加载后激活,apply 调 `ctx.systemPrompt.section({text})` 跨 worker 注册,section 的 text 求值读 `context.agent.session.events`(agent 为 undefined)→ 崩。跨 worker systemPrompt.section 注册仍缺(同属服务句柄化) |
| agent-loop | **M7-7 复核发现(新钉)** | `AgentLoop.prepare` 调 `ownerCtx.fiber.assertActive()`;ctx 的 `fiber` seam 是非可枚举成员,不跨桥 → `assertActive is not a function`。这是 ctx 形状问题(非服务方法),需桥把 ctx.fiber seam 以句柄暴露 |
| permission | **M7-7 改因(shim getter 已修,新功能检查失败)** | M7-6 记的 cordis-shim getter bug 已被 M7-7 修复(构造不再崩),但 apply 里真实功能检查失败:`ctx.shell.sandboxMode === undefined` → "permission: the mounted bash executor does not confine (no sandboxMode)"。shell 服务的 `sandboxMode` getter 不跨桥(provide 无 liveHandles)→ 需服务句柄化 |

## 5. D:3 行 base 层 disabled(M7-7 复核)

| 行 | base 表达式 | Windows 实际 | 处理 | 备注 |
|---|---|---|---|---|
| bash-sandbox | `!!js process.platform === 'win32'` | 应禁用 | worker 求值 → true → 禁用 | ✅ 正确 |
| tool-bash | 同上 | 应禁用 | 同上 | ✅ 正确 |
| skill-badge | `disabled: true` | 禁用 | 布尔真→跳过 | ✅ 正确 |

> pwsh-sandbox / tool-pwsh 原在 D 组(disabled 通道 `!!js process.platform !== 'win32'` 在
> Windows 求值为 false → 启用 → apply 崩 shim getter bug)。M7-7 shim getter 修复后两行
> **启用并 ACTIVE**(见 §3),不再在 D 组。

## 6. 迭代轮次日志(M7-7 复核:20 轮)

`build/m7-4/round-m7-7-*.log`(gitignored):

```
m7-7-00 基线:12 钉 → 63 行注册稳定(boot 124)✅
m7-7-01 移除 settings/session-persistence-jsonl/permission/plan-mode/tools/agent-loop/
        goal-round-driver/pwsh×2 共 9 行钉 → 首个失败 session-persistence-jsonl
        (ctx.sessions.list 不可迭代)→ 钉回
m7-7-02 继续:permission 失败(不再是 shim getter,是 ctx.shell.sandboxMode undefined)→ 钉回
m7-7-03 继续:goal-round-driver 失败(ctx.agents.list 不可迭代)→ 钉回
m7-7-04 继续:null 异常(ConcurrentModificationException 被 BootException 吞成 null)→ 加诊断
m7-7-06 诊断:java.util.ConcurrentModificationException in Reflect.notify(IdentityHashMap
        迭代中注册新插件 → 失败快迭代器)→ 核心 bug,修(Reflect.notify 先快照 registry.values)
m7-7-15 tools 解钉 + CME 修复 → tools ACTIVE,tool-pwsh/plan-mode 激活;plan-mode FAILED
        (systemPrompt.section text 求值 agent.session undefined)→ 钉回 plan-mode
m7-7-16 诊断 plan-mode 具体错误(TypeError Cannot read properties of undefined reading 'events')
m7-7-17 agent-loop 解钉 → 失败(ownerCtx.fiber.assertActive is not a function)→ 钉回
m7-7-final 8 钉:67 行注册,65 ACTIVE + 2 PENDING,boot 稳定 ✅
```

> **M7-7 复核引入的核心修复**(非句柄扩展本身,但被它暴露):
> `Reflect.notify`(dsh-cordis)在 `for (Plugin.Runtime runtime : registry.values())` 迭代中,
> 若某个 fiber.refresh() 触发再入的注册/注销(如 tools 激活时大量依赖行被 refresh),IdentityHashMap
> 的 fail-fast 迭代器抛 ConcurrentModificationException → 整批回滚、boot 挂掉。修复:迭代前先
> `new ArrayList<>(registry.values())` 快照,与真实 cordis(JS Map 迭代容忍并发增删)语义一致。
> 这使 `tools` 行首次能完成注册;此前 M7-6 因 tools 被钉从未走到这条路径。回归:317 测试全绿。

## 7. 结论与建议(M7-7 复核后)

- **"核心 = Java cordis + 其他全是 dsh + 配置即用"实证成立到 67/78 注册、65/78 apply**。
  M7-7 句柄扩展 + 一次 notify 重入核心修复后,**`tools` 枢纽解钉,级联激活 17 行 PENDING**——
  这是本里程碑最大的单次边界移动(apply 44 → 65)。
- **剩余 8 行 C 组钉**分类:
  1. **provide 通道服务句柄化**(4 行):session-persistence-jsonl、goal-round-driver、plan-mode、
     agent-loop。M7-7 完成方法返回句柄化,但服务值经 `provide` 仍以 fn 句柄 JSON 跨桥,跨 worker
     服务方法调用仍降级为 no-op stub。**下一阶段最高杠杆。**
  2. **客户端专属**(2 行):typert、typert-gateway(设计如此,loader 建模跳过 client-only 行)。
  3. **缺 ctx.loader**(1 行):hmr。
  4. **shim getter 已修但新功能检查失败**(1 行):permission(`ctx.shell.sandboxMode` 不跨桥,
     同属服务句柄化)。
- **测量口径不变**:boot 日志列出注册行,fiber 状态(ACTIVE)才是"apply 是否运行"。

## 8. 提交回归锁

- 实验产物(profile、round 日志、脚本)在 `build/m7-4/`(gitignored),不入库。
- 入库:`docs/m7-4/m7-4-tree-load-map.md` + `docs/m7-4/m7-4-full-boot.log`(round-m7-7-final-clean 证据,
  替换 round-m7-6-final)。
- 本次复核**改了一处核心代码**:`dsh-cordis/src/main/java/dev/dsh/cordis/Reflect.java` 的
  `notify()` 迭代前快照 registry.values(),修 ConcurrentModificationException(见 §6 注记)。
  317 测试全绿。其余只更新 docs + 实验产物。M7-7 核心句柄扩展(迭代器 / 递归 live 对象 / 发射器 /
  shim getter 修复)已在先前提交(dac2e63、04928a1)落地。
