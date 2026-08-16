# dsh-java

在 JDK 25 上复刻 cordis 核心,构建"一套机制、两套运行时"的插件框架:Java 插件与 JS 插件(dsh/koishi 社区)融合于统一注册表。

## 项目理念(2026-08-16 用户定调,不可违背)

**融合 Java 生态与 dsh 庞大生态。** 核心(Java 复刻的 cordis,即 `dev.dsh.cordis`)是唯一不可替换的组件;**除此之外,每个插件都可以随时替换**——既可用 Java 实现,也可用 dsh/koishi 生态的 JS 插件,两者并存、互不冲突。

- 核心 = 我们的 Java cordis(唯一不可替换)。
- **插件 = 优先全部用 dsh 的**:除核心外尽量直接用 dsh/koishi 生态,Java 化是渐进替换。
- **做不到的必须说明**:凡 dsh 组件无法经桥直接使用,必须明确记录"要移植什么、为什么不能"(产出边界报告,见 `docs/ecosystem-boundary.md`)。
- 桥是"保生态 + 迁移"的工具:每一步迁移,桥都让"还没替换的部分"继续跑——替换零风险、渐进。

## 三宿主动态分辨

插件经 `PluginRuntimeResolver` 按需动态选宿主(静态检测 + 运行时兜底,每插件独立选):

| 宿主 | 跑什么 | 加载方式 | 状态 |
|---|---|---|---|
| ① Java 原生 | Java 插件 | ClassLoader(M3 热重载) | ✅ |
| ② JS 进程内 GraalJS | 纯 JS 轻量插件 | GraalJsHost | ✅ |
| ③ JS 进程外 Node worker | ESM / native / 重 Node 插件 | NodeWorkerJsHost | ⬜ M4 |

## 架构演进(M1-M3 完成,43 测试全绿)

- **M1**:核心 `dev.dsh.cordis`(cordis 忠实复刻,响应式重载)。
- **M2**:GraalJS 桥 `dev.dsh.cordis.js`(JsHost/JsCtxBridge/JsPluginAdapter;真实 `@koishijs/plugin-echo` 加载响应)。
- **M2 深化**:async apply、JS next 链、command option、ServiceProxy 跨语言 RPC、`JsHost` 接口 seam。
- **M3**:热重载 `dev.dsh.cordis.reload`(Java ClassLoader + JS 上下文重启 + FileWatcher + 回滚)。
- **M4(规划)**:NodeWorkerJsHost + 三宿主 PluginRuntimeResolver + 融合证明 spike。
- **之后**:一步一步把 dsh harness 组件替换成 Java(重量 OS 件 shell/sandbox/code-runtime 留 Node worker,Java 化部分 llm/mcp/编排逐步替换)。

## 参考

- 设计文档:`docs/zjkycode/specs/`
- 计划文档:`docs/zjkycode/plans/`
- 参考源(只读):`D:\code\deepseek-harness\vendor\cordis\src\`(cordis TS 源码)
- 构建:JDK 25 + Gradle 9.7.0(代理 `127.0.0.1:7897`,gradle.properties 已配)
