#!/usr/bin/env bash
# setup.sh — dsh-java 一次性初始化(M6-4):链接 dsh 子模块 + 安装其依赖,使 dsh 插件可经桥解析。
#
# 目标:clone → setup → run。
#   1. git submodule update --init --depth 1     拉取 vendor/dsh(deepseek-harness 真源);
#   2. pnpm install(经 corepack 锁 pnpm@11.7.0)  安装 dsh workspace 依赖 → vendor/dsh/node_modules;
#   3. build:lib:host(tsc -b + tsdown)            构建 dsh 包 lib/(exports/main → lib/,src/ 不能直接跑)。
#      M6-5c 实测:pnpm 跑 script 前的 install 预检会被 lefthook postinstall 阻断,用
#      --config.verify-deps-before-run=false 跳过;失败时兜底 scripts/strip-dsh-libs.mjs。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "[dshj setup] 1/4 拉取 vendor/dsh 子模块(deepseek-harness)..."
git submodule update --init --depth 1 --recursive

# pnpm 选择:优先 corepack(按 vendor/dsh 的 packageManager: pnpm@11.7.0 锁定),
# 全局 pnpm 太旧(workspace 配置用 pnpm 10+ 语法)时必须有 corepack。
echo "[dshj setup] 2/4 检查 pnpm..."
run_pnpm() {
  if command -v corepack >/dev/null 2>&1; then
    (cd vendor/dsh && corepack pnpm "$@")
  else
    if ! pnpm --version 2>/dev/null | grep -qE '^1[01]\.'; then
      echo "[dshj setup] 错误: 需要 pnpm 11(dsh 声明 pnpm@11.7.0),当前 $(pnpm --version 2>/dev/null || echo '无 pnpm')。"
      echo "  安装 corepack:  npm install -g corepack && corepack enable"
      echo "  或升级 pnpm:     npm install -g pnpm@11"
      exit 1
    fi
    (cd vendor/dsh && pnpm "$@")
  fi
}
run_pnpm --version >/dev/null

echo "[dshj setup] 3/4 安装 dsh workspace 依赖(vendor/dsh/node_modules)..."
if ! run_pnpm install; then
  # 根 postinstall(lefthook git-hook 安装)在子模块 worktree 配置下必失败 —— 非阻塞:
  # deps 已全部装好(node_modules 就位),仅 git-hook 没挂上,不影响构建与运行。
  # M6-5c 实测:lockfile 校验通过、deps "Already up to date",唯根 postinstall 报错。
  if [ -d vendor/dsh/node_modules/@deepseek-ai ]; then
    echo "[dshj setup] 注意: pnpm install 根 postinstall(lefthook)失败 —— 非阻塞,继续。"
    echo "  (子模块无法 enable extensions.worktreeConfig;deps 已装好,git-hook 非运行依赖)"
  else
    echo "[dshj setup] 错误: pnpm install 失败且 node_modules 未就位。"
    exit 1
  fi
fi

echo "[dshj setup] 4/4 构建 dsh host lib(pnpm build:lib:host)..."
# dsh 包按 exports/main → lib/ 解析(构建产物),src/ 不能直接跑。走 dsh 原生完整构建
# `tsc -b tsconfig.host.json && tsdown`(M6-5c 实测 exit 0,lib/ 全量产物;*tsbuildinfo
# 与 lib/ 都被 vendor/dsh/.gitignore 忽略,子模块不被弄脏)。
# 注:pnpm 默认跑 script 前会重跑一次 install 预检(deps status check),被 lefthook
# postinstall 阻断 → 用 --config.verify-deps-before-run=false 跳过预检(deps 上一步已就位)。
if ! run_pnpm --config.verify-deps-before-run=false build:lib:host; then
  echo "[dshj setup] build:lib:host 失败 —— 回退 scripts/strip-dsh-libs.mjs(type-strip 最小闭包)..."
  node "$DIR/scripts/strip-dsh-libs.mjs" || {
    echo "[dshj setup] 错误: strip-dsh-libs.mjs 失败。"
    exit 1
  }
fi
SYS_LIB="vendor/dsh/packages/core/system-prompt/lib/index.js"
if [ -f "$SYS_LIB" ]; then
  echo "[dshj setup] system-prompt lib 就位,web profile 可经桥加载。"
else
  echo "[dshj setup] 错误: 构建后 system-prompt lib 仍缺失($SYS_LIB)。"
  exit 1
fi

echo "[dshj setup] 5/6 构建 dsh client lib + web 前端 dist(M8 真实 dsh UI)..."
# M8:真实 web UI 需要 client 包 lib/(浏览器模块系统 + node 半)与 apps/web 的 vite dist。
# build:lib:client(tsc -b tsconfig.client.json + tsdown client face)build 全部 client 包;
# build:web 对 apps/web 跑 vite build → dist/。两者任一失败 → 真实 UI 不可用(诚实报错)。
if ! run_pnpm --config.verify-deps-before-run=false build:lib:client; then
  echo "[dshj setup] 错误: build:lib:client 失败(真实 web UI 需要 client 包 lib/)。"
  echo "  修复后重跑;headless/cli profile 不受影响。"
  exit 1
fi
if ! run_pnpm --config.verify-deps-before-run=false build:web; then
  echo "[dshj setup] 错误: build:web 失败(真实 web UI 需要 apps/web dist/)。"
  echo "  修复后重跑;headless/cli profile 不受影响。"
  exit 1
fi
WEB_DIST="vendor/dsh/apps/web/dist/index.html"
if [ -f "$WEB_DIST" ]; then
  echo "[dshj setup] web 前端 dist 就位($WEB_DIST)。"
else
  echo "[dshj setup] 错误: build:web 后 dist/index.html 仍缺失。"
  exit 1
fi

echo "[dshj setup] 6/6 链接 web profile node_modules junctions(M8 裸模块解析)..."
node "$DIR/scripts/link-web-profile.mjs" || exit 1

echo "[dshj setup] 7/7 构建 ./dshj 快速启动(installDist,免 gradle,M9-2)..."
if "$DIR/gradlew" -p "$DIR" -q :dsh-host:installDist > /dev/null 2>&1; then
  echo "[dshj setup] installDist 就位(./dshj 秒起)。"
else
  echo "[dshj setup] 警告: installDist 失败(./dshj 回退 gradle run,启动慢)。可重跑本步。"
fi

echo
echo "[dshj setup] 完成。接下来:"
echo "  ./dshj --help                        # CLI 帮助(web/headless/cli 任意 profile)"
echo "  ./dshj web boot                      # boot 真实 dsh web UI(dsh-base+dsh-web-app,默认 :3080,"
echo "                                          --port <n> 覆盖),浏览器打开真实 agent 界面"
echo "  ./dshj plugin --profile web add <spec>  # 插件 add(安装 M6-7)"
