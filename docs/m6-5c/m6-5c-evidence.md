# M6-5c — setup 诚实验证 + dshj web 端到端证据

里程碑:M6-5c。日期:2026-08-18。提交前的实证记录(非臆测)。

结论:

1. **setup 链路诚实**:`./setup.sh` 三步全通(clone → setup → run),exit 0。
   `pnpm install` deps 就位、lockfile 校验通过;根 postinstall(lefthook git-hook 安装)
   在子模块 worktree 配置下失败 —— 标注为**非阻塞**,deps 已装好、git-hook 非运行依赖。
   `pnpm build:lib:host`(dsh 原生完整构建,tsc -b + tsdown)实测 **exit 0**,207 个 host 包
   构建完成;pnpm 默认跑 script 前的 install 预检会被 lefthook postinstall 阻断,故用
   `--config.verify-deps-before-run=false` 跳过预检(真实修复,非掩盖)。
2. **端到端证据**:`./dshj web boot` 拉起 web profile,`curl http://127.0.0.1:8080/`
   HTTP 200,页面含 harness 名 + 插件列表(Java+Node 混排)+ 桥基址 + 启动日志,
   10/10 断言通过。页面与启动日志存为 `docs/m6-5c/` 下证据文件。

---

## 1. setup 链路诚实记录

### 1.1 步骤与结果

| 步骤 | 命令 | 结果 |
|---|---|---|
| 1/4 | `git submodule update --init --depth 1 --recursive` | ✅ 子模块就位(`vendor/dsh` = dsh-v0.1.0-rc.7) |
| 2/4 | `corepack pnpm --version` | ✅ pnpm 11.7.0(corepack 锁,匹配 dsh packageManager) |
| 3/4 | `corepack pnpm install` | ✅ deps 就位(lockfile 校验通过、`Already up to date`;31 顶层包)。**非阻塞警告**:根 postinstall(lefthook)失败 |
| 4/4 | `corepack pnpm --config.verify-deps-before-run=false build:lib:host` | ✅ exit 0;207 个 host 包 `Build complete`;system-prompt lib 就位 |

### 1.2 诚实结论:两处与"理想"不同的点

**a) lefthook postinstall 失败 = 非阻塞。**

```
. postinstall$ node scripts/install-lefthook.mjs
. postinstall: [install-lefthook] cannot enable extensions.worktreeConfig while
   core.worktree is in the common config (file:...vendor/dsh/config)
. postinstall: Failed
```

原因:`vendor/dsh` 是 git 子模块,其 `core.worktree` 在公共 config 里,lefthook 想开
`extensions.worktreeConfig` 被 git 拒绝。只影响 git-hook 安装(提交前检查),**不影响
node_modules 与构建/运行**。setup.sh 已把该失败标记为非阻塞并继续,不伪装成成功、
也不让整个 setup 因 git-hook 而失败。

**b) `pnpm build:lib:host` 默认失败,真实原因是 pnpm 预检,不是 tsc/tsdown。**

pnpm 跑任何 script 前会重跑一次 deps 状态预检(`runDepsStatusCheck` → 内部 `pnpm install`),
内部 install 又触发 lefthook postinstall → 预检失败 → script 未执行就退出。**tsc/tsdown
本身没问题**:直接 `node_modules/.bin/tsc -b tsconfig.host.json` 单独跑 exit 0,
加 `--config.verify-deps-before-run=false` 跳过预检后 `build:lib:host` 全量成功。

> 已把该 flag 写进 `setup.sh` 第 4 步(带 `strip-dsh-libs.mjs` 兜底):这是"让 dsh 原生
> 完整构建真正跑通"的实证修复,而不是绕开构建。

### 1.3 README 三步体验(clone → setup → run)

README 已更新为真实三步:

```
git clone <repo> && cd dsh-java
./setup.sh          # ① 拉 vendor/dsh 子模块 → ② pnpm install → ③ build:lib:host
./dshj web boot     # boot 默认 web profile(Java+Node 混排),打开 http://127.0.0.1:8080/
```

---

## 2. dshj web 端到端证据

### 2.1 运行方式

```
./dshj web boot        # 后台启动(日志 → docs/m6-5c/dshj-web-boot.log)
curl http://127.0.0.1:8080/   # 页面 → docs/m6-5c/dshj-web-page.html
```

### 2.2 启动日志(节选,完整见 `dshj-web-boot.log`)

```
dshj: profile 'web' booted (D:\code\dsh-java\profiles\web\cordis.yml):
  - counter  [JAVA]  dev.dsh.demo.CounterPlugin
  - system-prompt  [NODE]  ../../vendor/dsh/packages/core/system-prompt
dshj: node module bases: [D:\code\dsh-java\vendor\dsh\node_modules]

dshj: profile 'web' is running on the Java harness. Ctrl+C to stop.
dshj: web status page at http://127.0.0.1:8080/
```

→ 启动日志打印插件加载(counter = Java,system-prompt = Node,经桥),并打印桥基址。

### 2.3 页面断言(10/10 通过)

| # | 断言 | 结果 |
|---|---|---|
| 1 | 页面含 harness 名 `<h1>dshj</h1>` | ✅ |
| 2 | 插件列表标题 `loaded plugins` | ✅ |
| 3 | Java 插件 `counter · host: Java` | ✅ |
| 4 | Node 插件 `system-prompt · host: Node` | ✅ |
| 5 | 桥基址标题 `bridge base` | ✅ |
| 6 | 桥基址 = `vendor/dsh/node_modules` | ✅ |
| 7 | 启动日志区块 `startup log` | ✅ |
| 8 | 启动日志含 `counter [JAVA]` | ✅ |
| 9 | 启动日志含 `system-prompt [NODE]` | ✅ |
| 10 | HTTP 200 | ✅ |

页面还列出第 3 个条目 `dev.dsh.cordis.js.JsPluginAdapter@...`(host: —):这是 JS 桥
adapter 在 registry 里的运行条目(无 name 字段),非用户插件,属正常展示。

### 2.4 证据文件

- `docs/m6-5c/dshj-web-page.html` — `curl :8080/` 原始页面
- `docs/m6-5c/dshj-web-boot.log` — boot 启动日志(含插件加载 + 桥基址)
- 本文件 `docs/m6-5c/m6-5c-evidence.md` — 断言汇总与 setup 诚实记录
