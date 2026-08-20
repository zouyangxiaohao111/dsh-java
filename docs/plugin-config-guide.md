# dsh-java 插件配置组合操作指南(面向 AI)

> 核心原则:**一个 Java 核心(dsh-cordis),插件全是 dsh,改 yml 即换**。
> 本文档教 AI/开发者如何通过配置文件组合、启用、禁用、分组、注入配置插件,
> 以及故障排查。所有操作 = 编辑 yml + 重启(或等热更新)。

---

## 0. 架构一句话

`./dshj web` 读 `profiles/web/` 的插件清单 → Java 核心逐插件加载(Node worker 经桥)
→ 插件按依赖自动激活。**你改 yml,插件组合就变。**

## 1. profiles 目录结构

```
profiles/<name>/
├── package.json          # dsh profile 声明:dsh.profile.bundles = [bundle 包名列表]
├── cordis.patch.yml      # 用户 patch 层(你的主战场,所有自定义在这里)
├── cordis.patch.dev.yml  # dev 模式额外 patch 层(--dev 才应用,通常解 pin dev 相关)
└── node_modules/         # 经 scripts/link-web-profile.mjs 建的 junction(运行用)
```

**不要直接编辑 bundle 的 patch**(`vendor/dsh/packages/bundle/*/cordis.patch.yml`,
那是 dsh 源码)。用户自定义一律写 `profiles/<name>/cordis.patch.yml`。

## 2. patch 组合顺序(理解覆盖)

```
dsh-base 的 bundle patch  →  dsh-web-app 的 bundle patch  →  用户 cordis.patch.yml  →  (dev 时) cordis.patch.dev.yml
```

后层覆盖前层,last-write-wins。**用户层能覆盖 bundle 层的任何字段。**

## 3. cordis.patch.yml 语法(完整字段)

每条目是一个 YAML 列表项,`id` 是匹配 key(对应 bundle 里的插件行):

```yaml
- id: system-prompt          # 插件 id(匹配 bundle 行)
  # 可选字段:
  name: '@deepseek-ai/dsh-system-prompt'   # 包名(一般不写,继承 bundle)
  group: core                # 进程组:同组插件共享一个 Node worker(性能,见 §6)
  priority: true             # 懒加载优先级:true = 先加载(端口早绑),缺省 = 后台加载
  disabled: true             # 禁用:插件不加载(可整个替换 bundle 里被禁用的行)
  config:                    # 配置注入(见 §7)
    key: value
```

### 3.1 覆盖 vs 新增

| 动作 | 写法 | 说明 |
|---|---|---|
| 覆盖 bundle 某行 | `- id: xxx` + 想改的字段 | id 匹配,字段覆盖(disabled/group/priority/config) |
| **新增一行** | `- insert:` + 行 | 追加到根,成为新插件 |
| 禁用 | `- id: xxx` + `disabled: true` | 插件不加载 |
| 解禁 | `- id: xxx`(不带 disabled) | 覆盖 bundle 的 disabled |

### 3.2 insert(新增插件的正确姿势)

```yaml
- insert:
    - id: my-plugin
      name: '@scope/my-plugin'    # 必须,包名决定加载什么
      group: web                  # 可选,分组
      config: {...}               # 可选,配置
```

> 注意:`- id: xxx` 直接写**只覆盖已有行**,新插件必须 `insert`(带 name)。
> 若 bundle 里没有该 id,直接写 `- id: xxx` 是 no-op(不生效,静默)。

## 4. 实战示例

### 4.1 禁用插件(如 code-runtime,不需要 OS 沙箱)

```yaml
- id: code-runtime
  disabled: true
  # 原因:worker-thread 代码沙箱需要 OS-level seam,Java 宿主暂不提供
```

### 4.2 启用被 bundle 禁用的插件

```yaml
- id: client-hmr
  # 不带 disabled,覆盖 bundle 的 disabled: true → 启用
```

### 4.3 插入一个全新插件

```yaml
- insert:
    - id: directory-picker-native
      name: '@deepseek-ai/dsh-host-directory-picker-native'
      group: web
```

### 4.4 调懒加载优先级(加速端口绑定)

```yaml
- id: webserver
  priority: true      # webserver 先加载 → 端口 ~10s 绑
```

### 4.5 分组(共享 worker,消除跨 worker 路由死锁)

```yaml
- id: web-runtime
  group: web          # 与 webserver/modules/connection 同一 Node worker
```

## 5. 服务依赖与激活(理解插件为何 PENDING)

插件 `inject = ['a', 'b']` 声明依赖。依赖服务**未提供** → 插件 PENDING(等待),
服务到位 → 自动激活。排查 PENDING:

1. **找到它等什么**:诊断测试打印 `fiber.inject` 里 `ctx.get()` 为 null 的。
2. **服务提供者是谁**:搜 `super(ctx, 'xxx')` 或 `provide('xxx')`。
3. **提供者为何没提供**:它自己 PENDING(依赖没到)/ FAILED(apply 抛错)/ 被 disabled。

常见坑:
- **`ctx.inject(deps, cb)` 的子服务**:storage-domain 等用子 ctx provide。
  桥已支持(M11-1),但若子 ctx 的依赖缺失,服务仍不提供。
- **directory-picker 类**:auto 用 `ctx.loader.create` 动态挂,而 Java 的
  `LoaderService.create` 是 no-op → 必须直接启用具体后端插件(见 §4.3)。
- **client 插件(ui-*)** 要在浏览器激活,必须进 `__DSH_BOOT__` manifest:
  条件是它是 loader entry + 有 `dsh.client: {platform: web}` + 非 disabled。
  禁用它对应的 server 插件(如 auto)会连它的 client 半一起消失 → 手动 insert。

## 6. 分组(group)与优先级(priority)

- **group**:进程组。同组插件共享一个 NodeWorkerJsHost(同一 Node 进程)。
  - `web` 组:webserver/web-runtime/modules/connection/api-gateway/client-runtime 等,
    路由 handler 与 node:http req/res 本地直传(解跨 worker 死锁)。
  - `core` 组:dsh-base 服务插件(agent/session/llm/tools 等),一个 worker 全装。
  - 纯服务行(storage 等)可并入 web 组。
  - **改动 group 需整组重载**(组变更强制全新 worker)。
- **priority**:懒加载。priority 插件先加载+apply(端口早绑),其余后台。
  - web profile 给 web 运行时 12 行标 priority → 端口 ~10s 绑。
  - **后台加载需 ~68s**(88 个 deferred 顺序 apply)。DshCli 已 settle 后才提示 URL。

## 7. 配置注入(config)

```yaml
- id: llm-deepseek
  config:
    endpoint: https://api.deepseek.com
    apiKey: ${DEEPSEEK_API_KEY}
```

- config 在 apply 前跑插件 schema 默认化(缺省自动填)+ 求值 `!!js` 表达式。
- `!!js` 表达式(scope = process + dshHomePath):
  ```yaml
  config:
    root: !!js "dshHomePath('sessions')"
  ```

## 8. 故障排查清单(按症状)

| 症状 | 排查 |
|---|---|
| 首页 "Failed to load plugins",N 个 pending | 某依赖服务缺失。找 pending 插件等什么服务 → 找提供者 → 为何没提供 |
| UI 渲染但**无法交互**(工作区点不动) | ① client 端 ui-* 没进 manifest(见 §5);② apiProxy 未提供(`/api` 404) |
| `/api` 404 "not found" | connection fallback 的 apiProxy undefined → api-gateway 没 ACTIVE → 查它依赖(workspace/storageDomain/directoryPicker) |
| `GET /api` 裸路径 404 | **正常**!apiProxy 对无匹配端点的 404,非故障 |
| console `HTTP 400` | apiProxy 命中但业务方法失败(如 unknown service handle) |
| console `connection lost` | host.describe 握手失败(实时通道;不影响 HTTP 交互) |
| 改 Java 后 `./dshj` 不生效 | **须 `./gradlew :dsh-host:installDist`**(dshj 用 installDist 产物,不检测源码变更)。改 yml 无需 |

## 9. dshj 命令(插件安装)

```bash
./dshj --help                              # 全部命令
./dshj web                                 # boot 真实 dsh web UI(:3080)
./dshj --profile <name> boot               # boot 任意 profile
./dshj plugin --profile <name> add <spec>  # 安装插件(jar 从 Maven / 源码从 GitHub)
```

`dshj plugin add` 装好后写回 profile 配置,下次 boot 自动加载。

## 10. AI 操作检查表(每次改配置)

1. [ ] 改的是 `profiles/<name>/cordis.patch.yml`(不是 bundle 源码)
2. [ ] 新增插件用 `insert`(带 name);改已有行用 `- id:` 覆盖
3. [ ] 禁用会连带 client 半消失的,补 insert client 插件(§5)
4. [ ] 跨 worker 依赖用同组或依赖已提供者
5. [ ] 改 yml 后 `./dshj web` 直接生效;改 Java 后先 `installDist`
6. [ ] 验证:浏览器 `Chrome --headless=new --dump-dom http://127.0.0.1:3080/`
       抓 console,确认无 404/错误;交互用 playwright(未装)或人工
7. [ ] 全量回归 `./gradlew test` 通过
