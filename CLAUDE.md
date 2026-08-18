# dsh-java

在 JDK 25 上复刻 cordis 核心,构建"一套机制、两套运行时"的插件框架:Java 插件与 JS 插件(dsh/koishi 社区)融合于统一注册表。

## 项目理念(2026-08-16 用户定调,不可违背)

**融合 Java 生态与 dsh 庞大生态。** 核心(Java 复刻的 cordis,即 `dev.dsh.cordis`)是唯一不可替换的组件;**除此之外,每个插件都可以随时替换**——既可用 Java 实现,也可用 dsh/koishi 生态的 JS 插件,两者并存、互不冲突。

- 核心 = 我们的 Java cordis(唯一不可替换)。
- **插件 = 优先全部用 dsh 的**:除核心外尽量直接用 dsh/koishi 生态,Java 化是渐进替换。
- **做不到的必须说明**:凡 dsh 组件无法经桥直接使用,必须明确记录"要移植什么、为什么不能"(产出边界报告,见 `docs/ecosystem-boundary.md`)。
- 桥是"保生态 + 迁移"的工具:每一步迁移,桥都让"还没替换的部分"继续跑——替换零风险、渐进。

## 架构原则(2026-08-18 用户定调,不可违背)

**一个 Java 核心是唯一运行时。** `dev.dsh.cordis`(Java 复刻的 cordis)就是唯一的插件运行时与注册表——JS 插件(经桥)是**注册表里的普通插件**,和 Java 插件完全同级、随意插拔、互相作用、互相组合。

- **绝不在 worker 里再跑一套 cordis**:Node worker / GraalJS 只是"宿主",跑 JS 插件的代码;插件的 ctx、事件、服务、生命周期**全部来自 Java 核心**(经桥)。
- **桥能力 = 通用机制,不做定制**:live 对象句柄 / RPC 代理 / 迭代器 / 事件发射器 / 递归返回,都是对所有插件通用的一次性机制;绝不写 per-plugin 的定制桥。
- **"一套机制、两套运行时"**:机制 = Java cordis(唯一);运行时 = ① Java 原生 ② GraalJS 进程内 ③ Node worker 进程外,三者都是跑 JS/Java 插件的宿主,不是第二个核心。
- 任何"在 worker 里自洽跑一套 cordis"的倾向都违背此原则——那会让 Java 核心失去意义。

## 三宿主动态分辨

插件经 `PluginRuntimeResolver` 按需动态选宿主(静态检测 + 运行时兜底,每插件独立选):

| 宿主 | 跑什么 | 加载方式 | 状态 |
|---|---|---|---|
| ① Java 原生 | Java 插件 | ClassLoader(M3 热重载) | ✅ |
| ② JS 进程内 GraalJS | 纯 JS 轻量插件 | GraalJsHost | ✅ |
| ③ JS 进程外 Node worker | ESM / native / 重 Node 插件 | NodeWorkerJsHost | ⬜ M4 |

## 架构演进(M1-M7 完成,305 测试全绿)

- **M1**:核心 `dev.dsh.cordis`(cordis 忠实复刻,响应式重载)。
- **M2**:GraalJS 桥 `dev.dsh.cordis.js`(JsHost/JsCtxBridge/JsPluginAdapter;真实 `@koishijs/plugin-echo` 加载响应)。
- **M2 深化**:async apply、JS next 链、command option、ServiceProxy 跨语言 RPC、`JsHost` 接口 seam。
- **M3**:热重载 `dev.dsh.cordis.reload`(Java ClassLoader + JS 上下文重启 + FileWatcher + 回滚)。
- **M4**:NodeWorkerJsHost + 三宿主 PluginRuntimeResolver + 核心全量对齐 + dsh agent 融合证明(agent/agent-loop/system-prompt 真实经桥跑通)。
- **M5**:配置驱动加载(PluginLoaderService,cordis.yml 兼容,Java/Node 混排,随时替换/组合/热更新)+ 真实 dsh llm/tools/settings 装进 worker(NEEDS)。
- **M5 深化**:多 worker 并行 + Async worker 事件循环(macrotask 宿主内真跑)。
- **M6**:多模块拆分 + 发布 dsh-cordis/dsh-testkit 到 Maven + jar: 源 + `./dshj` CLI + dsh 子模块 + DshProfileReader + `./dshj web` 融合效果。
- **M7**:dsh 生态全链路(真实 `dsh plugin add` 消费闭环 + GitHub 源码安装)+ 整树加载地图(63/78 注册)+ 配置通道修补(schema 默认化 + `!!js` 求值)+ fiber 隔离 + 桥序列化 + 通用句柄扩展。
- **之后**:逐步 Java 化 harness(agent 编排/llm/mcp 逐步替换);重量 OS 件(shell/sandbox/code-runtime)留 Node worker。

## 参考

- 设计文档:`docs/zjkycode/specs/`
- 计划文档:`docs/zjkycode/plans/`
- 参考源(只读):`D:\code\deepseek-harness\vendor\cordis\src\`(cordis TS 源码)
- 构建:JDK 25 + Gradle 9.7.0(代理 `127.0.0.1:7897`,gradle.properties 已配)
