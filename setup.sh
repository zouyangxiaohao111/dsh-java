#!/usr/bin/env bash
# setup.sh — dsh-java 一次性初始化(M6-4):链接 dsh 子模块 + 安装其依赖,使 dsh 插件可经桥解析。
#
# 目标:clone → setup → run。
#   1. git submodule update --init --depth 1     拉取 vendor/dsh(deepseek-harness 真源);
#   2. pnpm install(经 corepack 锁 pnpm@11.7.0)  安装 dsh workspace 依赖 → vendor/dsh/node_modules;
#   3. 核查 dsh 插件解析方式并补齐构建:读 package.json main/bin 字段(已核实:dsh 包
#      exports/main → lib/ 构建产物,源码 src/ 不能直接 require)→ 需要一次 lib 构建
#      (pnpm build:lib:host 只构建宿主侧,不含 web 前端)。
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
run_pnpm install

echo "[dshj setup] 4/4 核查 dsh 插件解析方式(lib/ 构建产物)..."
# dsh 包按 exports/main → lib/ 解析(构建产物),src/ 不能直接跑。M6-5b web profile 需要
# system-prompt 闭包(cosmokit/schemastery/dsh-scope/system-prompt)的 lib。缺失时用
# scripts/strip-dsh-libs.mjs 对该最小闭包做 type-strip 构建(复用 M4/M5 验证过的手段;
# 产物写入 vendor/dsh 各包的 lib/,被子模块 .gitignore 忽略,不弄脏 submodule)。
# 注:整仓 `pnpm build:lib:host`(tsc -b + tsdown)是 dsh 原生完整构建,但在本机子模块环境
# 下会被 lefthook postinstall 与 typret lib 依赖阻断 —— strip 脚本是该场景的可靠替代。
SYS_LIB="vendor/dsh/packages/core/system-prompt/lib/index.js"
if [ -f "$SYS_LIB" ]; then
  echo "[dshj setup] dsh 插件 lib 已构建,可直接解析。"
else
  echo "[dshj setup] system-prompt lib 缺失 —— 运行 scripts/strip-dsh-libs.mjs(type-strip 最小闭包)..."
  node "$DIR/scripts/strip-dsh-libs.mjs" || {
    echo "[dshj setup] 错误: strip-dsh-libs.mjs 失败。备选: 在 vendor/dsh 里跑"
    echo "  pnpm build:lib:host(需先解决 lefthook postinstall / typret 前置)。"
    exit 1
  }
fi

echo
echo "[dshj setup] 完成。接下来:"
echo "  ./dshj --help                        # CLI 帮助(web/headless/cli 任意 profile)"
echo "  ./dshj web boot                      # boot 默认 web profile(Java harness;dsh 完整 web 是 M6-5)"
echo "  ./dshj plugin --profile web add <spec>  # 插件 add 骨架(真正安装是 M6-7)"
