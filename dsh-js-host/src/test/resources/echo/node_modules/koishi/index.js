// 最小 koishi 模块 shim(design §3.6):Schema / h / Random / Time
// 按 @koishijs/plugin-echo@2.2.5 实际用法裁剪(仅 Schema.object / h.parse / h.escape)。
function Schema(value) {
  return { __schema: true, value }
}
Schema.object = (shape) => { const s = Schema(shape); s.object = true; return s }
Schema.function = () => Schema(undefined)
Schema.array = (item) => Schema(item)
Schema.string = () => Schema('string')
Schema.number = () => Schema('number')
Schema.boolean = () => Schema('boolean')
Schema.transform = (fn) => { const s = Schema(undefined); s.transform = fn; return s }

const h = {
  parse: (text) => ({ text: String(text) }),
  escape: (text) => String(text).replace(/[<>]/g, (c) => c === '<' ? '&lt;' : '&gt;'),
}

const Random = {
  id: (len = 8) => Array.from({ length: len }, () => 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)]).join(''),
}

const Time = {
  minute: 60 * 1000,
  second: 1000,
}

module.exports = { Schema, h, Random, Time, Context: class Context {} }
