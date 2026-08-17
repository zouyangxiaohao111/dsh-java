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

echo "[dshj setup] 4/4 核查 dsh 插件解析方式(读 package.json main/bin + 实际探测)..."
# dsh 包的 exports/main 指向 lib/(构建产物)。若代表性包(lib 目录)缺失 → 需要构建。
NEED_BUILD=0
SAMPLE="packages/boot/app-boot"
if [ -f "vendor/dsh/$SAMPLE/package.json" ]; then
  MAIN=$(node -e "const p=require('$DIR/vendor/dsh/$SAMPLE/package.json'); process.stdout.write(String(p.main||''))" 2>/dev/null || true)
  if [ -n "$MAIN" ] && [ ! -f "vendor/dsh/$SAMPLE/$MAIN" ]; then
    NEED_BUILD=1
  fi
fi
if [ "$NEED_BUILD" = "1" ]; then
  echo "[dshj setup] dsh 包按 exports→lib 发布产物解析,lib/ 尚未构建 —— 运行 pnpm build:lib:host..."
  run_pnpm build:lib:host
else
  echo "[dshj setup] dsh 插件可直接解析(lib 已存在或源码可跑),无需额外构建。"
fi

echo
echo "[dshj setup] 完成。接下来:"
echo "  ./dshj --help                        # CLI 帮助(web/headless/cli 任意 profile)"
echo "  ./dshj web boot                      # boot 默认 web profile(Java harness;dsh 完整 web 是 M6-5)"
echo "  ./dshj plugin --profile web add <spec>  # 插件 add 骨架(真正安装是 M6-7)"
