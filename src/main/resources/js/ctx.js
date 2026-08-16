// cordis ctx 契约子集 shim。bridge 为 Java JsCtxBridge 对象。
// ctx 以 Proxy 包装:未在显式面里声明的字符串属性按 cordis 语义解析为服务
// (经 bridge.get(name) → Java ctx.get → ServiceProxy),使真实 dsh 插件可
// 直接读 ctx.sessionProjections 等服务属性。符号属性返回 undefined,避免
// Symbol.iterator / Symbol.toPrimitive 等内部探针被误路由到 bridge。
module.exports = function createCtx(bridge) {
  const ctx = {
    on: (name, listener, opts) => bridge.on(name, listener, opts == null ? {} : opts),
    once: (name, listener, opts) => bridge.once(name, listener, opts == null ? {} : opts),
    emit: (name, ...args) => bridge.emit(name, args),
    provide: (name, value) => bridge.provide(name, value),
    get: (name) => bridge.get(name),
    inject: (deps, cb) => bridge.inject(deps, cb),
    effect: (disposer) => bridge.effect(disposer),
    logger: (name) => ({ error: () => {}, info: () => {}, warn: () => {}, debug: () => {} }),
    i18n: { define: (locale, dict) => {} },
    bots: { find: () => null },
  }
  installCommand(ctx, bridge)
  return new Proxy(ctx, {
    get(target, prop, receiver) {
      if (prop in target) return Reflect.get(target, prop, receiver)
      if (typeof prop === 'symbol') return undefined
      return bridge.get(String(prop))
    },
    has(target, prop) {
      return typeof prop === 'symbol' ? Reflect.has(target, prop) : true
    },
  })
}

// ---- 最小 command DSL(任务 5)----
// 说明:原计划经 require('./command.js') 注入,但 ctx.js 由 classpath 资源流 eval 加载,
// GraalJS 的相对路径 require 解析到 JVM 工作目录而非 classpath,故直接内联
// (内容与 command.js 保持一致,修改 DSL 时两处需同步)。
function installCommand(ctx, bridge) {
  const registry = bridge.commandRegistry()   // Java 侧命令注册表
  ctx.command = (def) => {
    const parsed = parseCommandDef(def)
    const builder = {
      _options: [],
      option(name, alias, opts) { this._options.push({ name, alias, opts: opts || {} }); return this },
      userFields() { return this },
      alias() { return this },
      action(fn) { registry.register(parsed.name, parsed.argDef, this._options, fn); return this },
    }
    return builder
  }
}

function parseCommandDef(def) {
  // 'echo <message:text>' → name 'echo', argDef 'message:text'
  const m = def.trim().split(/\s+/)
  return { name: m[0], argDef: m.slice(1).join(' ') }
}
