<p align="center">
  <img src="https://img.shields.io/badge/JDK-25-0080FF?style=flat&logo=openjdk&logoColor=white" alt="JDK 25">
  <img src="https://img.shields.io/badge/构建-Gradle%209.7-02303A?style=flat&logo=gradle&logoColor=white" alt="Gradle 9.7">
  <img src="https://img.shields.io/badge/测试-317%20全绿-2EA44F?style=flat" alt="317 tests green">
  <img src="https://img.shields.io/badge/GraalJS-24.1-3DDC84?style=flat" alt="GraalJS">
  <img src="https://img.shields.io/badge/license-MIT-2EA44F?style=flat" alt="MIT License">
</p>

<h1 align="center">dsh-java</h1>

<h3 align="center">一套机制、两套运行时 —— 融合 Java 生态与 dsh 庞大生态的企业级 Cordis 插件框架</h3>

<p align="center"><sub>中文 · English</sub></p>

<p align="center">
  <b>核心是不可替换的 Java Cordis 复刻;除此之外,每个插件都能随时替换</b>——既可以用 Java 实现,也可以直接复用 dsh / Koishi 社区的海量 JS 插件,两者并存、互不冲突。
</p>

---

## 为什么做 dsh-java

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)(dsh)是一个基于 [Cordis](https://github.com/cordiverse/cordis) 的插件化智能体框架。它最值钱的不是 harness 本身,而是那套**极致的插件语义**:

- **inject 驱动的响应式重载**——一个插件提供了服务,依赖它的所有插件自动卸载并按新依赖图重跑(主流容器如 Spring 都不做);
- **Fiber 生命周期**——插件实例的状态机 + 作用域化反序清理;
- **万物皆插件**——事件、服务、工具、界面都是插件。

dsh-java 用 JDK 25 把这套语义**忠实复刻成 Java**,再架一座 JS 桥,让 dsh / Koishi 社区的海量 JS 插件**零移植直接跑进 Java 核心**。最终你同时拥有:

| Java 生态 | dsh 生态 |
|---|---|
| JVM 性能、类型安全、JDK 25 全特性 | 社区数千个现成 JS 插件 |
| 你自己的 Java harness 组件 | dsh 的 agent / 工具 / 会话基建 |
| Maven 依赖、虚拟线程、JPMS | 活跃的 Koishi 插件作者 |

## 架构原则(2026-08-18 用户定调,不可违背)

**一个 Java 核心是唯一运行时。** `dev.dsh.cordis`(Java 复刻的 cordis)是唯一的插件运行时与注册表——JS 插件(经桥)是**注册表里的普通插件**,和 Java 插件完全同级、随意插拔、互相作用、互相组合。

- **绝不在 worker 里再跑一套 cordis**:Node worker / GraalJS 只是"宿主",跑 JS 插件的代码;插件的 ctx、事件、服务、生命周期**全部来自 Java 核心**(经桥)。
- **桥能力 = 通用机制,不做定制**:live 对象句柄 / RPC 代理 / 迭代器 / 事件发射器 / 递归返回,都是对所有插件通用的一次性机制;绝不写 per-plugin 的定制桥。
- **"一套机制、两套运行时"**:机制 = Java cordis(唯一);运行时 = ① Java 原生 ② GraalJS 进程内 ③ Node worker 进程外,三者都是跑插件的宿主,不是第二个核心。

## 核心特性

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>核心:Cordis 忠实复刻</h3>
      <p><code>dev.dsh.cordis</code> 逐文件移植 <code>vendor/cordis/src/*.ts</code>:Context 服务解析、Fiber 状态机、epoch 响应式重载、5 种事件派发、isolate 隔离、disposer 反序清理——语义逐行对齐,43 项测试锁定。</p>
    </td>
    <td width="50%" valign="top">
      <h3>JS 桥:真实生态插件直接跑</h3>
      <p><code>dev.dsh.cordis.js</code> 进程内 GraalJS 桥。<b>真实 <code>@koishijs/plugin-echo</code> 零移植加载并响应</b>;dsh 的 <code>session-stats</code>、<code>repeat-tool-reminder</code> 源码逻辑零移植跑通。</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>三宿主动态分辨</h3>
      <p>每个插件按需动态选宿主:<b>① Java 原生</b>(ClassLoader)、<b>② JS 进程内</b>(GraalJS,轻量)、<b>③ JS 进程外</b>(Node worker,ESM/native,规划中)。静态检测 + 运行时兜底。</p>
    </td>
    <td width="50%" valign="top">
      <h3>热重载</h3>
      <p><code>dev.dsh.cordis.reload</code> 改源码即生效:Java 插件源码经 <code>javax.tools</code> 编译换 ClassLoader;JS 插件重启上下文。失败自动回滚,旧实现保留。</p>
    </td>
  </tr>
</table>

## 快速开始(clone → setup → run)

```sh
git clone https://github.com/zouyangxiaohao111/dsh-java.git
cd dsh-java
./setup.sh           # ① 拉 vendor/dsh 子模块 → ② pnpm install(deps) → ③ build:lib:host(host lib)
./gradlew test       # 317 测试全绿
./dshj web boot      # boot 默认 web profile → 打开 http://127.0.0.1:8080/
```

`./setup.sh` 三步体验(M6-5c 实测,exit 0):

| 步 | 做什么 | 实测结果 |
|---|---|---|
| ① | `git submodule update --init` 拉 `vendor/dsh`(deepseek-harness 真源,dsh-v0.1.0-rc.7) | ✅ |
| ② | `corepack pnpm install` 装 dsh workspace 依赖(锁 pnpm@11.7.0) | ✅ deps 就位;根 postinstall(lefthook git-hook)在子模块环境失败 = **非阻塞**(不装 git-hook 不影响构建/运行) |
| ③ | `corepack pnpm --config.verify-deps-before-run=false build:lib:host` 构建 host lib(tsc -b + tsdown) | ✅ exit 0,207 行 `Build complete`(tsdown 逐入口输出;非 lib/ 产物文件数);system-prompt lib 就位 |

> **计数口径(③ 的 207)**:`207` = tsdown 的 `Build complete` **输出行数**(每个 host 入口点一行,
> 部分包有多个入口,故 > 包数),<b>不是</b> lib/ 产物文件数。产物口径为另一数(M6-5c 实测
> 177 个 lib/ 目录,其中 174 个含主入口 `lib/index.js`)。详见 `docs/m6-5c/m6-5c-evidence.md` §1.4。

> **前置依赖**:③ Node worker 宿主(ESM / native / 重 Node 插件)需要系统有 `node` 可执行
> (可通过环境变量 `NODE` 指定路径)。无 Node 时依赖它的测试会被 JUnit Assumption 自动
> **skip**(构建保持全绿),③ 宿主对应的插件加载会在运行时报错;①② 宿主与 Java 插件不受影响。

## CLI(`./dshj`)+ 链接 dsh 真源(M6)

```sh
./dshj --help                            # 帮助(web/headless/cli 任意 profile)
./dshj web boot                          # boot 默认 web profile(Java+Node 混排),起 :8080 状态页
./dshj --profile headless boot           # 指定 profile
./dshj plugin --profile web add <spec>   # 插件 add:jar:<maven/路径> 从 MavenLocal/Central 装;
                                        #   java:<目录|github:|git+> 装源码(JS 侧走真实 dsh plugin add)
```

- `profiles/<name>/cordis.yml` 声明插件集;`$DSH_HOME` 可覆盖 profile 根(镜像 dsh)。
- 任意 `./dshj <profile> boot` 都起 HTTP 状态页(`http://127.0.0.1:8080/`,`--port` 可覆盖),
  显示:harness 名 + 已加载插件列表(Java/Node/GraalJS 宿主标签)+ 桥基址 + 启动日志
  (M6-5a)。web profile 混排 Java 插件(`counter[JAVA]`)与真实 dsh 插件
  (`@deepseek-ai/dsh-system-prompt[NODE]`,经桥注册 `ctx.systemPrompt` 进 Java 核心, M6-5b)。
- **dsh 生态全链路(M7-1)**:真实 dsh CLI 装、我们跑 —— `DSH_HOME=<d> dsh plugin --profile demo
  add <包>` 在 `$DSH_HOME/profiles/demo/` 写 manifest(`dsh.profile.bundles`)+ 用户
  `cordis.patch.yml` 层 + `node_modules`;然后 `DSH_HOME=<d> ./dshj --profile demo boot` 经
  `DshProfileReader` 组合 bundle patch 层 → PluginLoaderService 经 Node 桥加载,状态页可见
  新增包。dsh-base 全量核心树(78 行)在 M7-6 后 63/78 注册、44/78 真正 apply(fiber 隔离 +
  桥值序列化 + disabled `!!js` 使 13 行 C 组解钉),剩余边界行用户层按 dsh patch 机制
  (id 定向 `disabled`)钉住,见 `docs/m7-4/m7-4-tree-load-map.md`。
- `./setup.sh` 一次性初始化,目标是 **clone → setup → run**。`pnpm install` 的 lefthook
  postinstall 失败非阻塞;`build:lib:host` 需 `--config.verify-deps-before-run=false`
  跳过 pnpm 的 install 预检(该预检会被 lefthook postinstall 阻断;tsc/tsdown 本身无碍,
  M6-5c 实测 exit 0)。兜底 `scripts/strip-dsh-libs.mjs` 仅构建 system-prompt 最小闭包。
- Node worker 把 `vendor/dsh/node_modules` + profile `node_modules` 作为裸模块解析基址
  (bareModuleBaseUrl 等价物,`NodeWorkerJsHost` 的 `moduleBases` seam);真实 dsh 插件的
  `@deepseek-ai/cordis` import 被 bridge 的 resolve 钩子拦到 Java 桥 shim(M6-5b),其余
  dsh 包从子模块 node_modules 原生解析。web profile 已加载真实
  `@deepseek-ai/dsh-system-prompt`(经桥注册 `ctx.systemPrompt` 进 Java 核心)。

一个最小示例——Java 提供服务,JS 插件调用并回传:

```java
Context root = new Context();
root.provide("counter", new Counter());   // Java 服务

// JS 插件(经 GraalJS 桥):inject counter → 调用 → emit 回 Java
root.plugin(new JsPluginAdapter(host, jsPluginFn), null);
root.emit("app/ready", "started");       // 触发 JS
// Java 侧 ctx.on("done") 收到 "n#1"      —— 两套运行时同一个 Context
```

## 架构:M1 → M4

| 里程碑 | 内容 | 状态 |
|---|---|---|
| **M1** | Cordis 核心 Java 复刻(响应式重载) | ✅ |
| **M2** | GraalJS 桥 + 真实 Koishi 插件 | ✅ |
| **M2 深化** | async apply、next 链、ServiceProxy 跨语言 RPC、JsHost 接口 seam | ✅ |
| **M3** | 热重载(Java ClassLoader + JS 上下文重启 + 回滚) | ✅ |
| **M4** | 三宿主 PluginRuntimeResolver + Node worker + 核心对齐 | ✅ |
| **M5** | PluginLoaderService 配置驱动加载 + 真实 dsh 服务混排 | ✅ |
| **M6** | 多模块拆分 + 核心发布 + 应用层 `./dshj` + dsh profile 兼容层 | ✅ |
| **M7-1** | 真实 dsh plugin add 消费闭环(dsh 装、我们跑) | ✅ |
| **M7-2** | `dshj plugin add` GitHub 源码安装(clone → 编译 → 加载) | ✅ |
| **M7-3** | M7-1/M7-2 集成验证 + 证据 | ✅ |
| **M7-4** | 全量 dsh-base 树加载地图(实证"配置即用"边界) | ✅ |
| **M7-5** | 配置通道修补(schemastery/zod 默认化 + `!!js` 求值)+ 整树零配置复核 | ✅ |
| **M7-6** | fiber 隔离(兄弟服务可见)+ 桥值序列化(循环/Symbol/fn 句柄/mixin/子路径)+ disabled 通道 `!!js` 求值 + 遗留小项清理;整树复核:63/78 注册、44/78 真正 apply(13 行 C 组解钉) | ✅ |

```
Java 核心(dev.dsh.cordis)      ← 唯一不可替换
   ├─ ① Java 插件  (ClassLoader,热重载)
   ├─ ② JS 插件    (GraalJS,进程内)
   └─ ③ JS 插件    (Node worker,进程外真 Node)  ← M4
   PluginRuntimeResolver: 每插件动态选宿主
```

## 与官方项目的关系

基于 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 与 [Cordis](https://github.com/cordiverse/cordis) 构建,是二者的 **Java 生态融合层**:

- 官方 Harness 提供核心智能体能力与 Cordis 插件体系;本项目的核心以 Java 忠实复刻这套语义。
- 本项目的 JS 桥让官方 / Koishi 生态插件**原样复用**,无需移植。
- 本项目的迁移原则:**桥是"保生态 + 迁移"的工具**,每一步迁移,桥都让"还没替换的部分"继续跑——替换零风险、渐进。

## 文档

| 目标 | 入口 |
|---|---|
| 项目理念与三宿主决策 | [`CLAUDE.md`](CLAUDE.md) |
| 设计文档 | [`docs/zjkycode/specs/`](docs/zjkycode/specs/) |
| 实施计划 | [`docs/zjkycode/plans/`](docs/zjkycode/plans/) |
| 参考源(只读) | `deepseek-harness/vendor/cordis/src/` |

## 特别感谢

感谢 [Cordis](https://github.com/cordiverse/cordis) 提供的插件化基础,感谢 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 团队与 [Koishi.js](https://koishi.chat/) 社区长期积累的插件化实践,以及每一个参与讨论、测试、反馈和插件开发的社区成员。

## License

[MIT](LICENSE)
