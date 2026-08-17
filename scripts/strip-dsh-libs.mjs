#!/usr/bin/env node
// strip-dsh-libs.mjs — M6-5b:为 vendor/dsh 子模块"需要的包"做最小 lib 构建。
//
// dsh 包按 exports/main → lib/ 解析(构建产物),src/ 不能直接跑。整仓 `pnpm build:lib:host`
// 需要 `tsc -b`(整仓类型检查 + typret generator lib)+ tsdown,在本机子模块环境(且 tsc 前置
// 被 typret 缺失阻断)下不可靠/过慢。本脚本复用 M4/M5 验证过的 type-strip 手段,仅对
// `@deepseek-ai/dsh-system-prompt` 的运行时依赖闭包做最小构建:
//
//   闭包(vendor/dsh 内,递归):cosmokit → schemastery → dsh-scope → system-prompt
//   `@deepseek-ai/cordis` 不入闭包 —— 它被 node-bridge.js 的 resolve 钩子拦到 Java 桥 shim;
//   `@deepseek-ai/dsh-llm`/`@deepseek-ai/dsh-invariants` 全是 `import type`,擦除即无运行时依赖;
//   `@standard-schema/spec` 在 schemastery 里也是 `import type`,擦除。
//
// 用 vendored typescript 的 transpileModule(transform + strip):处理 namespace / 参数属性 /
// enum 等非 erasable 语法(schemastery 用到了 namespace + constructor 参数属性),并把相对
// import 的 `.ts` 扩展改写成 `.js`(dsh 源码用 allowImportingTsExtensions,运行时是 Node ESM,
// 需要显式扩展)。
//
// 产物写入各包的 lib/ —— vendor/dsh 的 .gitignore 第 4 行 `lib/` 已忽略,子模块不被弄脏。
// 用法:node scripts/strip-dsh-libs.mjs   (cwd = dsh-java 仓库根)
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { createRequire } from 'node:module'

const REPO = join(import.meta.dirname, '..')
const DSH = join(REPO, 'vendor', 'dsh')
const require = createRequire(import.meta.url)
const ts = require(join(DSH, 'node_modules', 'typescript'))

/**
 * transpileModule 一次,输出 ESM 文本。相对 import 的 `.ts` 扩展改写为 `.js`
 * (dsh 源码 `from './store.ts'` → 运行时 `./store.js`)。
 */
function transpile(src, file) {
  const out = ts.transpileModule(src, {
    fileName: file,
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      esModuleInterop: true,
      allowSyntheticDefaultImports: true,
      allowImportingTsExtensions: true,
      isolatedModules: true,
      // 默认 false:transform namespace/enum/参数属性,不止 strip
      erasableSyntaxOnly: false,
      verbatimModuleSyntax: false,
    },
  })
  return out.outputText.replace(/(['"])((?:\.\.?\/)[^'"]*?\.)ts(['"])/g, '$1$2js$3')
}

/**
 * 把一个包的 src/*.ts 全部转成 lib/*.js(或指定输出名),保留相对扩展改写。
 * @param pkgRel  包相对 vendor/dsh 的路径(如 'vendor/cosmokit')
 * @param outExt  输出扩展名 js/mjs/cjs;null → 沿用 js
 */
function buildEsm(pkgRel, outExt = 'js') {
  const pkgDir = join(DSH, pkgRel)
  const srcDir = join(pkgDir, 'src')
  const libDir = join(pkgDir, 'lib')
  mkdirSync(libDir, { recursive: true })
  const files = readdirSync(srcDir).filter(f => f.endsWith('.ts'))
  for (const f of files) {
    const js = transpile(readFileSync(join(srcDir, f), 'utf8'), join(srcDir, f))
    const outName = f.replace(/\.ts$/, outExt ? `.${outExt}` : '.js')
    writeFileSync(join(libDir, outName), js)
    console.log(`  lib/${outName}  <-  ${pkgRel}/src/${f}`)
  }
  return files
}

console.log('[strip-dsh-libs] transpiling dsh host libs for the M6-5b closure...')

// cosmokit:纯类型擦除(src/*.ts → lib/*.js,type: module)
console.log('@deepseek-ai/cosmokit:')
buildEsm('vendor/cosmokit')

// schemastery:namespace + 参数属性 → transpile transform;exports import/require 双格式
console.log('@deepseek-ai/schemastery:')
{
  const src = readFileSync(join(DSH, 'vendor/schemastery/src/index.ts'), 'utf8')
  mkdirSync(join(DSH, 'vendor/schemastery/lib'), { recursive: true })
  const mjs = transpile(src, join(DSH, 'vendor/schemastery/src/index.ts'))
  writeFileSync(join(DSH, 'vendor/schemastery/lib/index.mjs'), mjs)
  const cjsOut = ts.transpileModule(src, {
    fileName: join(DSH, 'vendor/schemastery/src/index.ts'),
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText
  writeFileSync(join(DSH, 'vendor/schemastery/lib/index.cjs'), cjsOut)
  console.log('  lib/index.mjs / lib/index.cjs  <-  vendor/schemastery/src/index.ts')
}

// dsh-scope:index/store/invariant/scoped-events.generated(.ts 相对 import 改写)
console.log('@deepseek-ai/dsh-scope:')
buildEsm('packages/core/scope')

// dsh-system-prompt:index/invariant(.ts 相对 import 改写)
console.log('@deepseek-ai/dsh-system-prompt:')
buildEsm('packages/core/system-prompt')

console.log('[strip-dsh-libs] done.')
