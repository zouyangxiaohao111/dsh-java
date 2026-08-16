// 最小 command DSL:ctx.command('echo <message:text>').option(...).action(...)
// 经 bridge.commandRegistry() 把命令注册进 Java 侧(JsCtxBridge.commands)。
// 注:ctx.js 目前将本文件内容内联加载(资源流 eval 下 require 相对路径不可靠),
// 修改 DSL 时两处需同步。
module.exports = function installCommand(ctx, bridge) {
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
