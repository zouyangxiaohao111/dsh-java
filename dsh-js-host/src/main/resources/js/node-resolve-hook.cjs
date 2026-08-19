// node-resolve-hook.cjs — Node 内置 ESM loader 的 resolve 钩子(M6-5b)。
//
// 目的:把顶层裸 specifier '@deepseek-ai/cordis' 拦到 OUR Java 桥 shim,使真实 dsh
// 插件(位于 vendor/dsh 子模块,node_modules 里 @deepseek-ai/cordis 是 dsh 自己的 cordis)
// 在 worker 内 require('@deepseek-ai/cordis') / import ... from '@deepseek-ai/cordis'
// 拿到的是 Java 桥 shim(Service extends Java 核心注册,ctx 双向),而不是 dsh 的原生 cordis。
//
// 为什么必须 hook 而不是 NODE_PATH:
//   NODE_PATH 是 fallback 不是 override —— require 的查找顺序里本地 node_modules 优先,
//   vendor/dsh 顶层插件的 node_modules(@deepseek-ai/cordis)会先命中,压不住。
//   ESM 裸 import 更完全不走 NODE_PATH(见 node-bridge.js 顶部 seam 注释)。
//   这里的 resolve 钩子对所有 ESM 解析(含 require() 加载 ESM 模块的内部解析)生效,
//   且 `shortCircuit: true` 保证优先级高于 vendor/dsh 树内的本地 node_modules。
//
// 钩子通过 node-bridge.js 顶部 `module.register()` 注册(Node ≥20.6;bridge 是 CJS 主
// 脚本,module.register(specifier, parentURL) 可用)。shim 路径经 DSH_CORDIS_SHIM 环境
// 变量传入(Java 侧 NodeWorkerJsHost 在 spawn 时设置)。
'use strict'

const { pathToFileURL } = require('node:url')

/** 拦截目标:真实 dsh 插件 import 的 cordis 包名。 */
const CORDIS_SPECIFIER = '@deepseek-ai/cordis'

/**
 * M8:拦截 Node 内置 `node:process`。实测 Node 24 Windows —— worker 的 stdin 桥(reader
 * 线程阻塞读 FD 0 / 主线程异步读 process.stdin)在桥侧活跃时,`import process from
 * 'node:process'` 会死锁(内置 process 模块初始化与活跃的 stdin 读冲突;commander 15 的
 * ESM 图经 web-startup 触发)。resolve 钩子把它指到进程外 shim(node-process-shim.mjs,
 * 惰性 re-export globalThis.process),绕过内置初始化路径 —— 通用机制(同 cordis shim)。
 */
const PROCESS_SPECIFIER = 'node:process'

module.exports = {
  /**
   * resolve 钩子:裸 specifier '@deepseek-ai/cordis' → 返回 Java 桥 shim 的 URL,
   * `node:process` → 返回进程 shim 的 URL(DSH_PROCESS_SHIM),`shortCircuit: true`
   * 停止后续解析(优先于 vendor/dsh 的本地 node_modules / 内置模块)。
   * 其余 specifier(含其它 @deepseek-ai/* 包)交给原生解析 —— 它们继续从 dsh
   * 包自身的 node_modules(pnpm workspace 符号链接)解析。
   *
   * 注意:必须同步返回(非 async)。bridge 的插件加载主路径是 require(file),Node 的
   * require(esm) 同步路径只应用同步 resolve 钩子 —— async resolve 会让 require(esm)
   * 绕过钩子直接走默认解析(实测 Node 24.14)。dynamic import() 也接受同步返回。
   *
   * @param {string} specifier 待解析的模块标识符。
   * @param {object} context 解析上下文(parentURL/conditions 等)。
   * @param {(specifier, context) => Promise<{url}>} nextResolve 下一个 resolve 钩子。
   */
  resolve(specifier, context, nextResolve) {
    if (specifier === CORDIS_SPECIFIER) {
      const shim = process.env.DSH_CORDIS_SHIM
      if (shim && shim.length > 0) {
        return { url: pathToFileURL(shim).href, shortCircuit: true }
      }
    }
    if (specifier === PROCESS_SPECIFIER) {
      const shim = process.env.DSH_PROCESS_SHIM
      if (shim && shim.length > 0) {
        return { url: pathToFileURL(shim).href, shortCircuit: true }
      }
    }
    return nextResolve(specifier, context)
  },
}
