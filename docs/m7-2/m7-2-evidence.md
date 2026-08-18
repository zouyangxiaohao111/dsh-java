# M7-2 — dshj plugin add GitHub 源码安装(证据)

里程碑:M7-2。日期:2026-08-18。提交前的实证记录(非臆测)。

结论:**GitHub 源码安装链路打通** —— `dshj plugin add java:git+<repo>` 真实 git clone → 落地
`plugins/src/<name>/` → 组合配置写回 → `./dshj boot` 编译并加载 `plugin-repo [JAVA]`;
配置写回可复用(新 loader 从写回配置重载,回归锁测试)。公开 `github:u/r` 的 URL 构造有单测,
真实网络 clone 与本地 git 源共用同一 `gitCloneTo` 路径(设计 §3 明确 de-scope,残余网络风险诚实记录)。

## 1. 命令与 spec

```
./dshj plugin --profile <name> add java:git+<repo-url>     # 源码 git clone 安装
./dshj plugin --profile <name> add java:github:u/r         # 等价 → https://github.com/u/r.git
./dshj plugin --profile <name> add java:<本地源码目录>       # M6-7 已有,目录拷贝 + 编译
```

`java:` 三种 spec 统一落到 `plugins/src/<name>/`,组合配置写 `source: java:...` 条目。

## 2. 实跑回放(命令行)

```
./dshj plugin --profile demo add java:git+<本地 git repo 路径>
```

- **git clone 落地**:`<profile>/plugins/src/plugin-repo/`(git clone 真实走完,含 .git)。
- **组合配置写回**:cordis.yml(或 dsh profile 用户层)追加 `plugin-repo` 条目,
  `source: java:<plugins/src/plugin-repo>`。
- **boot 编译 + 加载**:`./dshj --profile demo boot` → PluginCompiler 编译源码 → 类发现 →
  注册进 registry,状态页显示 `plugin-repo · host: Java`。

## 3. 断言(单测回归锁,全过)

`PluginInstallerTest` 相关用例:

| 测试 | 断言 |
|---|---|
| `installGitSourceClonesCompilesAndLoadsIntoRegistry` | git 源码安装 → clone 落地 → 编译 → 加载进 registry;配置写回可复用(新 loader 从写回配置重载) |
| `installGitPlusClonesLocalRepo` | `git+` spec 真实 clone 本地 git repo |
| `githubUrlConstructsPublicCloneUrl` | `github:u/r` → `https://github.com/u/r.git` 正确构造 |
| `invalidGithubSpecFails` | 非法 spec 明确报错 |

## 4. 诚实边界

- **公开 github:u/r 真实网络 clone 未实测**:与本地 git 源共用 `gitCloneTo`(测试覆盖)+ URL 构造有单测;设计明确 de-scope,残余网络风险(代理/认证)诚实记录。接入代理后可补一次真实 `github:` 的 clone→编译→加载 e2e。
- **源码插件编译依赖**:自包含插件(只依赖核心,核心为 provided)可直接编译;外部依赖需随仓库提供或走 Maven 坐标(不在此范围)。

## 5. 提交回归锁

- `PluginInstallerTest`(M6-7 已 18 例 + M7-2 新增):jar 三路 + java 目录/单文件/类引用 + git clone + 写回可复用全链。
- 全量回归 280 测试 0 失败 0 跳过(M7 后)。
