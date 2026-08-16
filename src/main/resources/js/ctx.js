// cordis ctx 契约子集 shim。bridge 为 Java JsCtxBridge 对象。
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
  return ctx
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
