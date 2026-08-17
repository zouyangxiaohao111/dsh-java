// node-bridge.js — NodeWorkerJsHost 的进程外 worker(runner)。
//
// 契约:通过 stdin/stdout 传输 NDJSON(JSON lines)。只依赖 node: 内置模块。
// Java 侧(dev.dsh.cordis.js.NodeWorkerJsHost)是宿主;本脚本信任宿主回复,但把
// 每一条入站消息都当作 hostile peer 校验(仿 dsh code-runtime-worker-thread):
//   - type 必须是白名单内的 Java→worker 请求;未知 → error 响应。
//   - id 必须是非负整数;句柄引用必须已注册。
//   - 畸形 JSON → error 响应,不崩溃。
//
// 事件循环(M5 深化 §3,design 2026-08-17):
//   fs.readSync 阻塞读 → reader 线程 + SharedArrayBuffer 槽 + Atomics。
//   - reader 线程(worker_threads)阻塞读 stdin,把每行写入共享槽并通知主线程;
//   - 主线程事件循环用 Atomics.waitAsync 异步消费(await 时事件循环自由 → macrotask 可 settle);
//   - 同步 ctx 访问(如 cordis 同步 ctx.get)用 Atomics.wait 同步消费(sync-over-async 泵);
//   - 泵期间收到的重入消息入队(deferred),不内联执行 —— 防监听器在等待中途触发导致状态错乱;
//   - apply / invokeFn 等请求处理改为 async(await 插件结果),macrotask(timer/I/O/fetch)真跑。
//
// ctx shim 与 src/main/resources/js/ctx.js 同一契约面(on/once/emit/provide/get/
// inject/effect/logger/i18n/bots/command)。cordis 的 ctx 方法是同步 API
// (const x = ctx.get('llm')),故 ctx 方法经 syncBridgeCall(同步泵)保持同步语义;
// `await ctx.get(...)` 也自然工作(await 非 Promise 值直接返回)。worker 的"异步性"
// 体现在事件循环与请求处理(apply/invokeFn/handler 的 await),这解锁 macrotask。
'use strict'

const fs = require('node:fs')
const { Worker, isMainThread, workerData } = require('node:worker_threads')

// ---- 跨线程共享:stdin 行队列(reader 线程 → 主线程)----
// 单槽乒乓协议。与 Java 侧 MAX_LINE_LENGTH(8MB)对齐;超长行截断并标记 OVERSIZE,
// 主线程按 hostile 丢弃。
const SLOT_SIZE = 8 * 1024 * 1024
const EMPTY = 0
const READY = 1
const EOF = 2
const OVERSIZE = 3

// 槽内控制字布局(与 reader 线程共享):
//   control[0] = state(EMPTY/READY/EOF/OVERSIZE)
//   control[1] = 当前行的字节长度
//   data[0..]  = 当前行的字节
function makeSlot() {
  const sab = new SharedArrayBuffer(2 * 4 + SLOT_SIZE)
  return {
    state: new Int32Array(sab, 0, 1),
    len: new Int32Array(sab, 4, 1),
    data: new Uint8Array(sab, 8),
  }
}

// ---- reader 线程:阻塞 fs.readSync 读 stdin,把行写入共享槽 ----
function readerMain(slot) {
  let buf = Buffer.alloc(0)
  const chunk = Buffer.alloc(1 << 16)
  try {
    for (;;) {
      // 等槽空(主线程消费完上一行)。对**当前非空值**阻塞等待:值变化(主线程消费后设
      // EMPTY 并 notify)即醒;100ms 超时兜底 —— 若 notify 恰好在本线程注册 wait 之前
      // 触发(wait/notify 竞态)会被错过,超时重查绝不永久阻塞(否则 reader 停止读 stdin,
      // 事件循环等不到新行而挂死)。
      for (;;) {
        const s = Atomics.load(slot.state, 0)
        if (s === EMPTY) break
        Atomics.wait(slot.state, 0, s, 100)
      }
      // 阻塞填缓冲,直到凑齐一行(含 '\n')
      let idx
      while ((idx = buf.indexOf(10)) < 0) {
        const n = fs.readSync(0, chunk, 0, chunk.length, null)
        if (n === 0) {
          // stdin EOF:Java 已关闭进程 → 通知主线程 EOF
          Atomics.store(slot.state, 0, EOF)
          Atomics.notify(slot.state, 0)
          return
        }
        buf = Buffer.concat([buf, chunk.subarray(0, n)])
      }
      const line = buf.subarray(0, idx)
      buf = buf.subarray(idx + 1)
      const n = Math.min(line.length, SLOT_SIZE)
      slot.data.set(line.subarray(0, n))
      Atomics.store(slot.len, 0, n)
      Atomics.store(slot.state, 0, n < line.length ? OVERSIZE : READY)
      Atomics.notify(slot.state, 0)
    }
  } catch (e) {
    // reader 异常(stdin 读失败等):视为 EOF,主线程统一收尾
    Atomics.store(slot.state, 0, EOF)
    Atomics.notify(slot.state, 0)
  }
}

// ---- 主线程消费 ----

/** 从槽取一行(同步阻塞;EOF 返回 null;OVERSIZE 丢弃继续取下一行)。 */
function takeLineSync() {
  for (;;) {
    let s = Atomics.load(slot.state, 0)
    if (s === EMPTY) {
      // 短超时轮询:兼容"待通知被并发 waitAsync 抢走"的竞态(5ms 兜底,不丢行)
      Atomics.wait(slot.state, 0, EMPTY, 5)
      s = Atomics.load(slot.state, 0)
    }
    if (s === EOF) return null
    const line = takeSlotLine()
    if (line === null) continue
    return line
  }
}

/** 从槽取一行(异步;EOF 返回 null;OVERSIZE 丢弃继续取下一行)。 */
async function takeLineAsync() {
  for (;;) {
    if (Atomics.load(slot.state, 0) === EMPTY) {
      const r = Atomics.waitAsync(slot.state, 0, EMPTY)
      if (r.async) await r.value
    }
    const s = Atomics.load(slot.state, 0)
    if (s === EOF) return null
    const line = takeSlotLine()
    if (line === null) continue
    return line
  }
}

/** 从槽拷贝一行(必须 state != EMPTY/EOF 时调用),复位槽并唤醒 reader。 */
function takeSlotLine() {
  const s = Atomics.load(slot.state, 0)
  const n = Atomics.load(slot.len, 0)
  const line = Buffer.from(slot.data.subarray(0, n)).toString('utf8')
  Atomics.store(slot.state, 0, EMPTY)
  Atomics.notify(slot.state, 0)
  return s === OVERSIZE ? null : line
}

// ---- 同步行读取:worker 主循环与嵌套 bridgeCall 共用同一把 stdin 读锁 ----
// (旧实现 fs.readSync 阻塞读;现由 reader 线程 + Atomics 提供,见上)

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
    syncBridgeCall('invokeService', { handle, method, args: args.map(x => serializeValue(x)) })
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
// 同步泵(sync-over-async):发送请求 → 阻塞(Atomics.wait)取行 → 匹配到自身 ctxResult
// 即返回;其余行(Java 并发发来的请求 / 其它回复)入队(deferred),事件循环稍后按序消化。
// 泵期间收到的重入消息**不内联执行**(防监听器在等待中途触发导致状态错乱)。
const deferred = []   // 泵期间入队的已解析消息对象(事件循环按序处理)
let bridgeSeq = 0

function syncBridgeCall(type, payload) {
  const id = ++bridgeSeq
  send(Object.assign({ type, id }, payload))
  const deadline = Date.now() + SYNC_PUMP_TIMEOUT_MS
  for (;;) {
    const line = takeLineSync()
    if (line === null) throw new Error('java bridge: stdin closed while awaiting reply')
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
    // 不是本请求的回复:可能是 Java 并发发来的请求(如 invokeFn)或其它异步桥的回复
    // → 入队,**泵期间不内联执行**(防监听器在等待中途触发导致状态错乱)。调度一次
    // setImmediate 消化:泵在 fire-and-forget 分发内跑时事件循环可能正等新行,不调度
    // 会死锁(入队的重入请求永远不被处理)。
    deferred.push(msg)
    scheduleDeferredDrain()
    if (Date.now() >= deadline) {
      throw new Error('java bridge: sync call ' + type + ' (id ' + id + ') timed out after '
        + SYNC_PUMP_TIMEOUT_MS + 'ms (reply never arrived; worker or Java reader stalled)')
    }
  }
}

// 泵超时兜底(防 sync ctx 长时间占住事件循环)。Java 侧 REQUEST_TIMEOUT 同量级。
const SYNC_PUMP_TIMEOUT_MS = 30_000

// ---- 重入队列消化(泵期间入队的消息在调用返回后按序处理)----
let deferredScheduled = false
function scheduleDeferredDrain() {
  if (deferredScheduled) return
  deferredScheduled = true
  setImmediate(() => {
    deferredScheduled = false
    drainDeferred()
  })
}
function drainDeferred() {
  while (deferred.length > 0) {
    const msg = deferred.shift()
    if (msg.type === 'close') { handleInbound(msg); return }
    routeMessage(msg)
  }
}

// ---- logger 桥(ctx.logger(name) / ctx.logger.warn(...))----
// 两个调用形式都经 ctxCall method 'logger' 转发到 Java ctx.logger 的对应方法,消息进入
// Java LoggerService —— 复用 P3 对齐的 Logger 格式化层(printf 占位符 / 每行截断 /
// ANSI name 着色)。name=null 表示"无 name 调用":Java 侧按调用 ctx 的 fiber 名解析默认
// logger(cordis 语义,ctx.logger.warn(...) 等价 ctx.logger().warn(...))。format + args
// 原样过桥,printf 在 Java 侧展开(worker 不预格式化)。
function makeLogger(ctxId, name) {
  const send = (type) => (...args) =>
    syncBridgeCall('ctxCall', {
      ctx: ctxId,
      method: 'logger',
      args: [name == null ? null : name, type, args.map(x => serializeValue(x))],
    })
  const log = function () { return log }
  log.error = send('error')
  log.info = send('info')
  log.warn = send('warn')
  log.debug = send('debug')
  return log
}

// ---- ctx shim(与 ctx.js 同一契约面)----
function makeCtx(ctxId) {
  // logger 既可当函数调用(ctx.logger('agents') → 命名 logger),也带方法属性
  // (AgentRegistry 直接读 this.ctx.logger.warn(...) 的无 name 调用)。两者都经桥
  // 转发到 Java Logger 格式化层(见 makeLogger)。
  const defaultLogger = makeLogger(ctxId, undefined)
  const logger = function (name) { return name === undefined ? defaultLogger : makeLogger(ctxId, name) }
  logger.error = defaultLogger.error
  logger.info = defaultLogger.info
  logger.warn = defaultLogger.warn
  logger.debug = defaultLogger.debug
  // 最小 fiber seam:AgentRegistry.hasLifecycleAncestor 做 identity 比较(fiber === candidate),
  // 跨桥无法成立;提供终止链(fiber.parent.fiber === fiber)使其在首次比较即返回 false。
  // 真实 fiber 状态/父子关系跨桥 → NEEDS。非可枚举:该链自引用,若被 serializeValue
  // (Service 提供 self 时遍历 self.ctx 的 own enumerable keys)扫到会报循环引用。
  const rootFiber = { state: 'active' }
  rootFiber.parent = { fiber: rootFiber }
  const ctx = {
    on: (name, listener, opts) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'on', args: [serializeValue(listener), name, opts || {}] }),
    once: (name, listener, opts) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'once', args: [serializeValue(listener), name, opts || {}] }),
    emit: (name, ...args) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'emit', args: [name, args.map(x => serializeValue(x))] }),
    provide: (name, value) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'provide', args: [name, serializeValue(value)] }),
    get: (name) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'get', args: [name] }),
    inject: (deps, cb) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'inject', args: [deps.map(x => serializeValue(x)), serializeValue(cb)] }),
    accessor: (name, options) => syncBridgeCall('ctxCall', {
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
      return syncBridgeCall('ctxCall', { ctx: ctxId, method: 'effect', args: [registered] })
    },
    // waterfall:Java 只回传该 dispatch 收纳的 JS listener fn 句柄(顺序),worker 本地直接调用,
    // 无往返。Java 原生(non-JS)listener 混入时 Java 侧抛明确错误(记 NEEDS)。
    // 注意:listener/default 返回 Promise 时不在此 syncWait —— 原样返回 Promise,由调用方的
    // `await`(异步宿主的事件循环)驱动 settlement。
    waterfall: (target, name, ...args) => {
      const next = args[args.length - 1]
      const payload = args.slice(0, -1)
      const plan = syncBridgeCall('ctxCall', { ctx: ctxId, method: 'waterfallPlan', args: [serializeValue(target), name] })
      const listeners = Array.isArray(plan) ? plan : []
      const call = (idx) => {
        if (idx >= listeners.length) return next(...payload)
        return listeners[idx](...payload, () => call(idx + 1))
      }
      return call(0)
    },
    // serial(cordis events.serial):按序折叠 JS listener,首个 bail 值停下(dispatch.ts 的
    // agentEvents.serial 用它)。Java 原生 listener 混入 → Java 侧抛明确错误(记 NEEDS)。
    serial: (target, name, ...args) => {
      const plan = syncBridgeCall('ctxCall', { ctx: ctxId, method: 'eventsDispatch', args: [serializeValue(target), 'serial', name, args.map(x => serializeValue(x))] })
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
        const plan = syncBridgeCall('ctxCall', {
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
      syncBridgeCall('ctxCall', { ctx: ctxId, method: 'commandRegister', args: [name, argDef, options, serializeValue(action)] }),
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

// ---- 同步等待一个 worker 本地 Promise(仅 microtask 链;macrotask 无法同步 settle)----
// 用于 serial 折叠的本地 JS listener(worker 内 promise,不涉桥);macrotask 依赖在同步
// 上下文里无法 settle,预算耗尽即抛错(serial 是同步语义,这是固有的同步限制)。
// 墙钟预算兜底:microtask 链经 _tickCallback 泵动可在首轮 settle;预算给足 1s,
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
      throw new Error('async result for ' + what + ' did not settle synchronously (macrotask await in a synchronous '
        + 'serial/waterfall callback is not supported — await it in async context instead)')
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

// ---- Java → worker 请求处理(异步:await 插件结果,解锁 macrotask)----
async function handleRequest(msg) {
  switch (msg.type) {
    case 'load':
    case 'require': {
      const file = msg.file || msg.specifier
      if (typeof file !== 'string' || !file) throw new Error('bad module specifier')
      let mod
      try {
        mod = require(file)
      } catch (e) {
        // ESM top-level await → Node 抛 ERR_REQUIRE_ASYNC_MODULE。异步 worker 现在可以
        // await:退化为动态 import(),等 TLA settle 后取 namespace(原同步宿主限制已解除)。
        if (e && (e.code === 'ERR_REQUIRE_ASYNC_MODULE' || /top-level await/i.test(String(e.message)))) {
          const { pathToFileURL } = require('node:url')
          mod = await import(pathToFileURL(file).href)
        } else {
          throw e
        }
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
      if (result && typeof result.then === 'function') result = await result   // macrotask 可 settle
      return { type: 'result', id: msg.id, value: serializeValue(result) }
    }
    case 'invokeFn': {
      const fn = deserializeValue(msg.handle)
      if (typeof fn !== 'function') throw new Error('unknown fn handle ' + JSON.stringify(msg.handle))
      const args = (msg.args || []).map(deserializeValue)
      let result = fn(...args)
      if (result && typeof result.then === 'function') result = await result   // macrotask 可 settle
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
    default:
      throw new Error('worker: unknown message type ' + msg.type)
  }
}

// ---- 消息路由 ----
function routeMessage(msg) {
  if (msg.type === 'ctxResult') {
    // Java 对某次桥调用的回复。同步桥直接消费;异步桥(未来扩展)经 bridgeWaiters 解析。
    const w = bridgeWaiters.get(msg.id)
    if (w) {
      bridgeWaiters.delete(msg.id)
      if (msg.error) w.reject(new Error('java bridge error: ' + msg.error))
      else w.resolve(deserializeValue(msg.result))
    }
    return
  }
  if (msg.type === 'result' || msg.type === 'error') {
    return // Java 主动发来的 result/error 是 unsolicited(回复应由 worker 发)→ hostile,忽略
  }
  dispatchRequest(msg)
}

/** fire-and-forget 分发一个 Java → worker 请求(处理是 async,完成时发回复)。 */
function dispatchRequest(msg) {
  handleRequest(msg).then(
    (resp) => { if (resp) send(resp) },
    (e) => send({ type: 'error', id: typeof msg.id === 'number' ? msg.id : undefined, message: String(e && e.stack ? e.stack : e) }),
  )
}

// ---- 异步桥调用(返回 Promise;供未来 async ctx 桥方法使用)。当前 ctx 方法用同步泵。----
const bridgeWaiters = new Map()
function bridgeCall(type, payload) {
  const id = ++bridgeSeq
  return new Promise((resolve, reject) => {
    bridgeWaiters.set(id, { resolve, reject })
    send(Object.assign({ type, id }, payload))
  })
}

// ---- 入站消息处理(事件循环新行 / 泵期间入队的重入消息共用)----
let closing = false

// 处理一条 Java → worker 入站消息。close 就地回包并标记关闭(异步分发后循环若继续
// 卡在取行,close 之后 Java 不再发消息,进程会挂住);其余分发(fire-and-forget)。
function handleInbound(msg) {
  if (msg.type === 'close') {
    closing = true
    send({ type: 'result', id: msg.id, value: null })
    return
  }
  routeMessage(msg)
}

// 收尾:终止 reader 线程,让 stdout 缓冲冲刷后退出(兜底定时器防挂起)。
// 注意:Java 侧 close() 会先关闭本进程 stdin → reader 线程 fs.readSync 得 EOF 后自行退出,
// 此时 process.exit 不再被"reader 阻塞在读"拖住(Windows 上会挂起)。
function shutdown() {
  if (reader) reader.terminate().catch(() => {})
  process.exitCode = 0
  setTimeout(() => process.exit(0), 50)
}

/**
 * 主事件循环:setImmediate(macrotask)驱动,不用 async/await 的 microtask continuation 分发。
 *
 * 关键:同步折叠的 {@code serial} 用 {@code process._tickCallback()} 泵动本地 Promise 的
 * microtask;该泵若在 microtask continuation 里调用(嵌套泵)永不 settle(实测 Node 24)。
 * 故每行必须在 macrotask 上下文处理 —— 这里 takeLineAsync 的 await/.then 只负责取行,
 * 实际 handleLine 在 setImmediate 回调里跑(仍是非阻塞:分发是 fire-and-forget,async
 * handler 的 await 让事件循环继续取下一行)。
 */
function eventLoop() {
  const step = () => {
    if (closing) return shutdown()
    takeLineAsync().then(
      (line) => {
        if (line === null) return shutdown()   // stdin EOF(Java 已关闭 / 已 close)
        setImmediate(() => {
          try {
            if (closing) return shutdown()
            handleLine(line)
            if (closing) return shutdown()
            step()
          } catch (e) {
            process.stderr.write('node-bridge event loop error: ' + (e && e.stack ? e.stack : e) + '\n')
            process.exit(1)
          }
        })
      },
      (e) => {
        process.stderr.write('node-bridge event loop error: ' + (e && e.stack ? e.stack : e) + '\n')
        process.exit(1)
      },
    )
  }
  step()
}

/** 解析并处理一行入站 NDJSON(在 macrotask 上下文调用)。 */
function handleLine(line) {
  let msg
  try {
    msg = JSON.parse(line)
  } catch (e) {
    send({ type: 'error', message: 'malformed json' })
    return
  }
  handleInbound(msg)
}

// ---- 启动 ----
const slot = makeSlot()
let reader
if (isMainThread) {
  reader = new Worker(__filename, { workerData: { slot } })
  eventLoop()
} else {
  readerMain(workerData.slot)
}
