// node-bridge.js — NodeWorkerJsHost 的进程外 worker(runner)。
//
// 契约:通过 stdin/stdout 传输 NDJSON(JSON lines)。只依赖 node: 内置模块。
// Java 侧(dev.dsh.cordis.js.NodeWorkerJsHost)是宿主;本脚本信任宿主回复,但把
// 每一条入站消息都当作 hostile peer 校验(仿 dsh code-runtime-worker-thread):
//   - type 必须是白名单内的 Java→worker 请求;未知 → error 响应。
//   - id 必须是非负整数;句柄引用必须已注册。
//   - 畸形 JSON → error 响应,不崩溃。
//
// ctx shim 与 src/main/resources/js/ctx.js 同一契约面(on/once/emit/provide/get/
// inject/effect/logger/i18n/bots/command),但 bridge 是 RPC proxy:方法调用发送
// ctxCall 消息过 pipe,阻塞等待 ctxResult。JS listener 以函数句柄往返(序列化为
// {$kind:'fn',id})。
'use strict'

const fs = require('node:fs')

// ---- 同步行读取:worker 主循环与嵌套 bridgeCall 共用同一把 stdin 读锁 ----
let buf = Buffer.alloc(0)
function fill() {
  const chunk = Buffer.alloc(1 << 16)
  const n = fs.readSync(0, chunk, 0, chunk.length, null)
  if (n === 0) process.exit(0) // stdin EOF:Java 已关闭
  buf = Buffer.concat([buf, chunk.subarray(0, n)])
}
function readLine() {
  let idx
  while ((idx = buf.indexOf(10)) < 0) fill()
  const line = buf.subarray(0, idx).toString('utf8').replace(/\r$/, '')
  buf = buf.subarray(idx + 1)
  return line
}
function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

// ---- 句柄注册表 ----
const fnById = new Map()  // id → JS function
const modById = new Map() // id → 已加载模块(CJS exports / ESM namespace)
const ctxById = new Map() // id → ctx shim
let nextHandle = 1

// ---- 值序列化(worker → Java)----
// 函数 → {$kind:'fn',id,length};普通值走 JSON。服务句柄({$kind:'svc',id} 与
// {$kind:'ctx'/'module'} 标记)是普通 JSON 对象,原样透传回 Java。
// 实数语义:NaN/±Infinity → null(与 JSON.stringify 一致);循环引用 → 报错
// (跨桥值必须是树,拒绝栈溢出;真实 dsh 插件的 zod schema 含 maxValue:Infinity)。
function serializeValue(v, seen) {
  if (v === undefined) return { $kind: 'undefined' }
  if (v === null) return null
  const t = typeof v
  if (t === 'string' || t === 'boolean') return v
  if (t === 'number') {
    if (!Number.isFinite(v)) return null
    return v
  }
  if (t === 'bigint') return { $kind: 'bigint', value: v.toString() }
  if (t === 'function') {
    const id = nextHandle++
    fnById.set(id, v)
    const out = { $kind: 'fn', id }
    const len = v.length
    if (Number.isInteger(len)) out.length = len
    return out
  }
  if (t === 'object') {
    const active = seen || new WeakSet()
    if (active.has(v)) throw new Error('cannot serialize cyclic value across bridge')
    active.add(v)
    let out
    if (Array.isArray(v)) {
      out = v.map(x => serializeValue(x, active))
    } else {
      out = {}
      for (const k of Object.keys(v)) out[k] = serializeValue(v[k], active)
    }
    active.delete(v)
    return out
  }
  throw new Error('cannot serialize value across bridge: ' + t)
}

// ---- 值反序列化(Java → worker 的 config / invokeFn args)----
function deserializeValue(v) {
  if (v === null || typeof v !== 'object') return v
  if (v.$kind === 'fn') {
    const fn = fnById.get(v.id)
    if (typeof fn !== 'function') throw new Error('unknown fn handle ' + v.id)
    return fn
  }
  if (v.$kind === 'undefined') return undefined
  if (v.$kind === 'module') {
    const m = modById.get(v.id)
    if (m === undefined) throw new Error('unknown module handle ' + v.id)
    return m
  }
  if (v.$kind === 'ctx') {
    const c = ctxById.get(v.id)
    if (c === undefined) throw new Error('unknown ctx handle ' + v.id)
    return c
  }
  if (v.$kind === 'svc') return makeServiceProxy(v.id)
  if (Array.isArray(v)) return v.map(deserializeValue)
  const out = {}
  for (const k of Object.keys(v)) out[k] = deserializeValue(v[k])
  return out
}

// ---- Java 服务句柄 → JS 可调 Proxy ----
// 直接调用 svc(...) → invokeService {$call};成员访问 svc.method(...) →
// invokeService {method}。method 走 Java 反射。then 返回 undefined,避免 JS
// Promise 把服务对象误当 thenable 吸收。
function makeServiceProxy(handle) {
  const invoke = (method, args) =>
    bridgeCall('invokeService', { handle, method, args: args.map(x => serializeValue(x)) })
  const callable = (...args) => invoke('$call', args)
  return new Proxy(callable, {
    get(target, prop, receiver) {
      if (prop === 'then') return undefined
      if (typeof prop === 'symbol') return undefined
      return (...args) => invoke(String(prop), args)
    },
  })
}

// ---- 嵌套 RPC:ctx 方法 / 服务方法 需同步等 Java 回复 ----
// 阻塞读取 stdin;匹配到自身 ctxResult 返回,其余行入队(deferred),主循环稍后消化。
const deferred = []
let bridgeSeq = 0
function bridgeCall(type, payload) {
  const id = ++bridgeSeq
  send(Object.assign({ type, id }, payload))
  for (;;) {
    const line = readLine()
    let msg
    try {
      msg = JSON.parse(line)
    } catch (e) {
      throw new Error('worker: malformed line from java: ' + line.slice(0, 120))
    }
    if (msg.type === 'ctxResult' && msg.id === id) {
      if (msg.error) throw new Error('java bridge error: ' + msg.error)
      return deserializeValue(msg.result)
    }
    // 不是本请求的回复:可能是 Java 并发发来的请求(如 invokeFn),入队延后处理
    deferred.push(msg)
  }
}

// ---- ctx shim(与 ctx.js 同一契约面)----
function makeCtx(ctxId) {
  // logger 既可当函数调用(ctx.logger('agents').warn),也带方法属性 —— AgentRegistry 直接读
  // this.ctx.logger.warn(...)(无 name 调用),故 logger 必须是"函数 + 方法"对象。
  const noop = () => {}
  const logger = function () { return logger }
  logger.error = logger.info = logger.warn = logger.debug = noop
  // 最小 fiber seam:AgentRegistry.hasLifecycleAncestor 做 identity 比较(fiber === candidate),
  // 跨桥无法成立;提供终止链(fiber.parent.fiber === fiber)使其在首次比较即返回 false。
  // 真实 fiber 状态/父子关系跨桥 → NEEDS。非可枚举:该链自引用,若被 serializeValue
  // (Service 提供 self 时遍历 self.ctx 的 own enumerable keys)扫到会报循环引用。
  const rootFiber = { state: 'active' }
  rootFiber.parent = { fiber: rootFiber }
  const ctx = {
    on: (name, listener, opts) => bridgeCall('ctxCall', { ctx: ctxId, method: 'on', args: [serializeValue(listener), name, opts || {}] }),
    once: (name, listener, opts) => bridgeCall('ctxCall', { ctx: ctxId, method: 'once', args: [serializeValue(listener), name, opts || {}] }),
    emit: (name, ...args) => bridgeCall('ctxCall', { ctx: ctxId, method: 'emit', args: [name, args.map(x => serializeValue(x))] }),
    provide: (name, value) => bridgeCall('ctxCall', { ctx: ctxId, method: 'provide', args: [name, serializeValue(value)] }),
    get: (name) => bridgeCall('ctxCall', { ctx: ctxId, method: 'get', args: [name] }),
    inject: (deps, cb) => bridgeCall('ctxCall', { ctx: ctxId, method: 'inject', args: [deps.map(x => serializeValue(x)), serializeValue(cb)] }),
    accessor: (name, options) => bridgeCall('ctxCall', {
      ctx: ctxId, method: 'accessor',
      args: [name, options && typeof options.get === 'function' ? serializeValue(options.get) : null],
    }),
    // cordis 语义:effect body 立即执行,把产出的 disposer 交给 Java(生命周期归 Java fiber)。
    // 支持 generator body(fiber.ts:60-80,374-381:generator effect 立即跑到完成,逐个 yield
    // 的 disposer 全部登记;disposal 时逆序执行)。这使 AgentRegistry.register() 的
    // `yield enter(...); announce(agent)` 在登记时真正 announce —— 旧桥只步进到首个 yield,
    // announce 被延后到 unload,agent/created 永不按 cordis 语义触发。
    // Java 侧登记后返回一个 Java 持有的 disposer 服务句柄,worker 拿到后可直接 dispose()。
    effect: (body, label) => {
      const stepped = body()
      const disposers = []
      if (stepped && typeof stepped.next === 'function' && typeof stepped[Symbol.iterator] === 'function') {
        const iterator = stepped
        while (true) {
          const result = iterator.next()
          if (typeof result.value === 'function') disposers.push(result.value)
          if (result.done) break
        }
      } else if (typeof stepped === 'function') {
        disposers.push(stepped)
      }
      if (disposers.length === 0) return undefined
      const registered = serializeValue(() => {
        let lastResult
        for (let i = disposers.length - 1; i >= 0; i--) {
          try { lastResult = disposers[i]() } catch (e) { /* 单个 disposer 失败不阻断逆序链 */ }
        }
        return lastResult
      })
      return bridgeCall('ctxCall', { ctx: ctxId, method: 'effect', args: [registered] })
    },
    // waterfall:Java 只回传该 dispatch 收纳的 JS listener fn 句柄(顺序),worker 本地直接调用,
    // 无往返 —— 同步宿主在 bridgeCall 阻塞期间无法再同步调 JS 句柄,故 JS-only 链才能本地折叠。
    // Java 原生(non-JS)listener 混入时 Java 侧抛明确错误(记 NEEDS)。
    waterfall: (target, name, ...args) => {
      const next = args[args.length - 1]
      const payload = args.slice(0, -1)
      const plan = bridgeCall('ctxCall', { ctx: ctxId, method: 'waterfallPlan', args: [serializeValue(target), name] })
      const listeners = Array.isArray(plan) ? plan : []
      const call = (idx) => {
        if (idx >= listeners.length) return next(...payload)
        let result = listeners[idx](...payload, () => call(idx + 1))
        if (result && typeof result.then === 'function') result = syncWaitPromise(result, 'waterfall listener ' + name)
        return result
      }
      let result = call(0)
      if (result && typeof result.then === 'function') result = syncWaitPromise(result, 'waterfall ' + name)
      return result
    },
    // serial(cordis events.serial):按序折叠 JS listener,首个 bail 值停下(dispatch.ts 的
    // agentEvents.serial 用它)。Java 原生 listener 混入 → Java 侧抛明确错误(记 NEEDS)。
    serial: (target, name, ...args) => {
      const plan = bridgeCall('ctxCall', { ctx: ctxId, method: 'eventsDispatch', args: [serializeValue(target), 'serial', name, args.map(x => serializeValue(x))] })
      const listeners = Array.isArray(plan) ? plan : []
      for (const fn of listeners) {
        let result = fn.apply(target, args)
        if (result && typeof result.then === 'function') result = syncWaitPromise(result, 'serial listener ' + name)
        if (result != null && result !== false) return result
      }
      return undefined
    },
    // events.dispatch(type, args):cordis Events.dispatch 的桥面(events.ts:150-163)。首个
    // object/function 元素是 dispatch thisArg(carrier),第二个是事件名,其余是事件参数。
    // Java 解析监听器:JS listener → 返回 fn 句柄(worker 本地折叠);Java-native listener →
    // emit 模式由 Java 就地调用并收纳错误(非 emit 模式混入 Java listener → 明确 NEEDS)。
    // 就地裁剪 caller 的 args(cordis 也 mutate args;AgentRegistry.announce() 用裁剪后的
    // args 调 callback)。
    events: {
      dispatch: (mode, args) => {
        const local = Array.from(args)
        let thisArg = local.length > 0 && (typeof local[0] === 'object' || typeof local[0] === 'function')
          ? local.shift() : null
        const name = local.shift()
        const plan = bridgeCall('ctxCall', {
          ctx: ctxId, method: 'eventsDispatch',
          args: [serializeValue(thisArg), mode, name, local.map(x => serializeValue(x))],
        })
        args.length = 0
        args.push(...local)
        const handles = Array.isArray(plan) ? plan : []
        return handles.map(fn => function () { return fn.apply(thisArg, arguments) })
      },
    },
    logger,
    i18n: { define: () => {} },
    bots: { find: () => null },
  }
  // fiber seam 非可枚举(自引用链不能被 serializeValue 扫到);ctx.fiber 经 Proxy get 仍可达。
  Object.defineProperty(ctx, 'fiber', { value: rootFiber, enumerable: false, writable: true, configurable: true })
  // command DSL(与 ctx.js installCommand 同语义;注册走 ctxCall)
  const registry = {
    register: (name, argDef, options, action) =>
      bridgeCall('ctxCall', { ctx: ctxId, method: 'commandRegister', args: [name, argDef, options, serializeValue(action)] }),
  }
  ctx.command = (def) => {
    const parsed = parseCommandDef(def)
    const builder = {
      _options: [],
      option(name, alias, opts) { this._options.push({ name, alias, opts: opts || {} }); return this },
      userFields() { return this },
      alias() { return this },
      action(fn) { registry.register(parsed.name, parsed.argDef, this._options.map(x => serializeValue(x)), fn); return this },
    }
    return builder
  }
  const proxy = new Proxy(ctx, {
    get(target, prop, receiver) {
      if (prop in target) return Reflect.get(target, prop, receiver)
      if (typeof prop === 'symbol') return undefined
      return ctx.get(String(prop))
    },
    has(target, prop) {
      return typeof prop === 'symbol' ? Reflect.has(target, prop) : true
    },
  })
  ctxById.set(ctxId, proxy)
  return proxy
}

function parseCommandDef(def) {
  const m = def.trim().split(/\s+/)
  return { name: m[0], argDef: m.slice(1).join(' ') }
}

// ---- 同步等待一个 Promise(仅 microtask 链;macrotask 会超时抛错)----
// 墙钟预算兜底:microtask 链经 _tickCallback 泵动可在首轮 settle;macrotask(timer/I/O)
// 永远无法同步 settle,预算耗尽即抛错(失败上报要快,不能烧满 CPU)。预算给足 1s,
// 远大于正常 microtask 链的 settle 时间,只对真正的 macrotask 依赖生效。
const SYNC_WAIT_BUDGET_MS = 1000
function syncWaitPromise(p, what) {
  let done = false
  let value
  let error
  p.then((v) => { value = v; done = true }, (e) => { error = e; done = true })
  const deadline = Date.now() + SYNC_WAIT_BUDGET_MS
  while (!done) {
    process._tickCallback()
    if (Date.now() >= deadline) {
      throw new Error('async result for ' + what + ' did not settle synchronously (macrotask await unsupported on NodeWorkerJsHost)')
    }
  }
  if (error) throw error
  return value
}

// ---- 解析插件模块:function | {apply} | ESM namespace(default)----
function resolvePlugin(mod) {
  const candidates = [mod]
  if (mod && typeof mod === 'object' && mod.default !== undefined) candidates.push(mod.default)
  for (const c of candidates) {
    if (typeof c === 'function') return { apply: c, source: c }
    if (c && typeof c === 'object' && typeof c.apply === 'function') return { apply: c.apply, source: c }
  }
  throw new Error('plugin module is neither a function nor { apply }')
}

function pluginMeta(mod) {
  const source = resolvePlugin(mod).source
  const get = (key) => {
    for (const c of [mod, source, mod && mod.default]) {
      if (c && typeof c === 'object' && c[key] !== undefined) return c[key]
    }
    return undefined
  }
  return {
    name: get('name'),
    inject: Array.isArray(get('inject')) ? get('inject') : [],
    provide: Array.isArray(get('provide')) ? get('provide') : [],
  }
}

// ---- Java → worker 请求处理 ----
function handleRequest(msg) {
  switch (msg.type) {
    case 'load':
    case 'require': {
      const file = msg.file || msg.specifier
      if (typeof file !== 'string' || !file) throw new Error('bad module specifier')
      let mod
      try {
        mod = require(file)
      } catch (e) {
        // ESM top-level await → Node 抛 ERR_REQUIRE_ASYNC_MODULE。同步宿主无法 await,
        // 归类为明确限制(Java 侧 resolver 据此做失败上报,而非当普通加载错误)。
        if (e && (e.code === 'ERR_REQUIRE_ASYNC_MODULE' || /top-level await/i.test(String(e.message)))) {
          throw new Error('plugin module uses top-level await, unsupported on NodeWorkerJsHost (sync host) — restructure without top-level await: ' + file)
        }
        throw e
      }
      const id = nextHandle++
      modById.set(id, mod)
      const meta = pluginMeta(mod)
      return { type: 'result', id: msg.id, value: { $kind: 'module', id, name: meta.name, inject: meta.inject, provide: meta.provide } }
    }
    case 'eval': {
      if (typeof msg.script !== 'string') throw new Error('bad eval script')
      // eval 的返回值需可跨桥:函数转 fn 句柄,普通值直接走 JSON
      const result = (0, eval)(msg.script)
      return { type: 'result', id: msg.id, value: serializeValue(result) }
    }
    case 'createCtx': {
      const id = nextHandle++
      makeCtx(id)
      return { type: 'result', id: msg.id, value: { $kind: 'ctx', id } }
    }
    case 'apply': {
      const mod = deserializeValue(msg.module)
      if (mod === undefined) throw new Error('unknown module handle ' + JSON.stringify(msg.module))
      const ctx = deserializeValue(msg.ctx)
      if (ctx === undefined) throw new Error('unknown ctx handle ' + JSON.stringify(msg.ctx))
      const { apply } = resolvePlugin(mod)
      const config = deserializeValue(msg.config)
      let result = apply(ctx, config)
      if (result && typeof result.then === 'function') result = syncWaitPromise(result, 'plugin apply')
      return { type: 'result', id: msg.id, value: serializeValue(result) }
    }
    case 'invokeFn': {
      const fn = deserializeValue(msg.handle)
      if (typeof fn !== 'function') throw new Error('unknown fn handle ' + JSON.stringify(msg.handle))
      const args = (msg.args || []).map(deserializeValue)
      let result = fn(...args)
      if (result && typeof result.then === 'function') result = syncWaitPromise(result, 'invokeFn')
      return { type: 'result', id: msg.id, value: serializeValue(result) }
    }
    case 'release': {
      if (msg.handle && typeof msg.handle === 'object' && msg.handle.$kind === 'fn') fnById.delete(msg.handle.id)
      else if (typeof msg.handle === 'number') fnById.delete(msg.handle)
      return { type: 'result', id: msg.id, value: null }
    }
    case 'releaseCtx': {
      if (msg.ctx && typeof msg.ctx === 'object' && msg.ctx.$kind === 'ctx') ctxById.delete(msg.ctx.id)
      else if (typeof msg.ctx === 'number') ctxById.delete(msg.ctx)
      return { type: 'result', id: msg.id, value: null }
    }
    case 'close':
      closing = true
      return { type: 'result', id: msg.id, value: null }
    default:
      throw new Error('worker: unknown message type ' + msg.type)
  }
}

// ---- 主循环:同步处理 Java 请求;先消化嵌套期间入队的消息 ----
let closing = false
for (;;) {
  // deferred 队列存的是 bridgeCall 已解析的消息对象(嵌套期间 Java 并发发来的请求);
  // 直接作为 msg 处理,不再 JSON.parse(对对象 parse 会失败 → 'malformed json')。
  let msg
  if (deferred.length > 0) {
    msg = deferred.shift()
  } else {
    const line = readLine()
    try {
      msg = JSON.parse(line)
    } catch (e) {
      send({ type: 'error', message: 'malformed json' })
      continue
    }
  }
  // 防御:迟到/多余的响应(正常不会出现)直接丢弃
  if (msg.type === 'result' || msg.type === 'error' || msg.type === 'ctxResult') continue
  let resp
  try {
    resp = handleRequest(msg)
  } catch (e) {
    resp = { type: 'error', id: typeof msg.id === 'number' ? msg.id : undefined, message: String(e && e.stack ? e.stack : e) }
  }
  send(resp)
  if (closing) {
    process.exitCode = 0
    break // 正常退出,让 stdout 缓冲冲刷后再离开事件循环
  }
}
