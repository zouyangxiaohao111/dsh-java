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
const nodePath = require('node:path')
const nodeOs = require('node:os')
const { Worker, isMainThread, workerData } = require('node:worker_threads')

// ---- cordis → Java 桥 shim 解析拦截(M6-5b)----
// 真实 dsh 插件(vendor/dsh 子模块)的 node_modules 里 @deepseek-ai/cordis 是 dsh 自己的
// cordis,不是我们的 Java 桥 shim。NODE_PATH 是 fallback 不是 override,压不住本地 node_modules;
// ESM 裸 import 更完全不走 NODE_PATH。这里注册 Node 内置 ESM loader 的 resolve 钩子:
// 顶层裸 specifier '@deepseek-ai/cordis' → 我们的桥 shim(Service extends Java 核心注册),
// 其余 dsh 包照常从 vendor/dsh 的 node_modules(pnpm workspace 符号链接)解析。
// 另补 Module._resolveFilename 拦截,覆盖纯 CJS require('@deepseek-ai/cordis') 的路径。
// 钩子文件与 shim 由 Java 侧(NodeWorkerJsHost)抽取到 bridge 同目录并经环境变量传入。
const { register } = require('node:module')
const { pathToFileURL } = require('node:url')

const CORDIS_SPECIFIER = '@deepseek-ai/cordis'
const cordisShimPath = process.env.DSH_CORDIS_SHIM

if (cordisShimPath && cordisShimPath.length > 0) {
  // 1) ESM loader resolve 钩子(import + require() 加载 ESM 的内部解析都经过它)。
  //    shortCircuit:true → 优先级高于 vendor/dsh 树内本地 node_modules。
  try {
    register('./node-resolve-hook.cjs', pathToFileURL(__filename).href)
  } catch (e) {
    // 个别 Node 版本 register 失败时降级:进程内 resolve 钩子缺失 = shim 拦截失效,
    // 但 worker 主循环仍可跑 —— 记到 stderr,Java 侧在插件加载失败时可诊断。
    process.stderr.write('node-bridge: module.register failed (cordis shim interception disabled): '
      + (e && e.message ? e.message : e) + '\n')
  }
  // 2) CJS require 路径的兜底拦截(纯 CJS 插件代码里 require('@deepseek-ai/cordis'))。
  const Module = require('node:module')
  const origResolveFilename = Module._resolveFilename
  Module._resolveFilename = function (request, ...rest) {
    if (request === CORDIS_SPECIFIER) return cordisShimPath
    return origResolveFilename.call(this, request, ...rest)
  }
}

// ---- 裸模块解析基址 seam(M6-4,bareModuleBaseUrl 等价物)----
// 宿主(Java 侧 NodeWorkerJsHost)在 spawn 时把 dsh 子模块 node_modules + profile
// node_modules 等基址经 NODE_PATH / DSH_MODULE_BASES 环境变量传给本进程。CJS require
// 在启动时把 NODE_PATH 读进 Module.globalPaths,使 worker 内任意 require('裸包')
// (含插件代码内部、load 用的 require)都能经基址解析。基址必须是 **node_modules 目录
// 本身**(模块根),不是其父目录 —— require 对每条 NODE_PATH 做 <base>/<specifier>
// 的路径拼接(实测 Node 24:NODE_PATH=<dir> → require('pkg') 找 <dir>/pkg)。
// 注:运行时再 push Module.globalPaths 无效(Node 24 只在启动 _initPaths 读一次),故不在此做。
// ESM 裸 import 走 Node 原生解析(从模块文件目录向上找 node_modules),NODE_PATH 不生效:
// ESM 插件应位于 profile/dsh 树内,让原生向上解析命中其 node_modules(这是 dsh 的 pnpm 布局)。

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
  const sab = new SharedArrayBuffer(3 * 4 + SLOT_SIZE)
  return {
    state: new Int32Array(sab, 0, 1),
    len: new Int32Array(sab, 4, 1),
    lock: new Int32Array(sab, 8, 1),   // 0=空闲 1=消费者/reader 占用(读-改-写互斥)
    data: new Uint8Array(sab, 12),
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
      // 等消费者锁释放(上一行可能正被 takeSlotLine 拷贝),避免覆盖正在读取的数据。
      for (;;) {
        if (Atomics.compareExchange(slot.lock, 0, 0, 1) === 0) break
        Atomics.wait(slot.lock, 0, 1, 2)
      }
      try {
        const line = buf.subarray(0, idx)
        buf = buf.subarray(idx + 1)
        const n = Math.min(line.length, SLOT_SIZE)
        slot.data.set(line.subarray(0, n))
        Atomics.store(slot.len, 0, n)
        Atomics.store(slot.state, 0, n < line.length ? OVERSIZE : READY)
        Atomics.notify(slot.state, 0)
      } finally {
        Atomics.store(slot.lock, 0, 0)
        Atomics.notify(slot.lock, 0)
      }
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
      // M8:主线程不在槽上挂 Atomics.waitAsync —— 挂起的 waitAsync 会与 Node ESM loader
      // 对同一 agent 的 Atomics.wait/notify 抢醒:loader 的同步 wait 被 notify 误醒(空转
      // 重挂)而 loader 内部等待永不满足 → dynamic import() 死锁(实测 Node 24 Windows:
      // 加载 `import process from 'node:process'` 的 ESM 图时挂起,直到有其它 stdin 行到来
      // 才解;minimal worker 复现:reader+slot+Atomics.waitAsync+import 挂,换成 setTimeout
      // 轮询(Atomics.load 只读、不注册 waiter)即通)。每行轮询延迟 2ms,对 NDJSON 消息流
      // 可忽略。
      await new Promise(r => setTimeout(r, 2))
      continue
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
  // CAS 抢锁:事件循环的 takeLineAsync 与同步泵的 takeLineSync 并发消费同一槽 ——
  // 无锁时两者可同时读到同一行(takeSlotLine 读-改-写非原子)并各自处理 → 同一行被
  // 双处理;更糟的是泵内嵌套分发把同一行重新入队 → 无限重复处理同一 id(实测
  // invokeFn/rehandleFn 死循环)。锁保证一行只被一个消费者读走。
  for (;;) {
    if (Atomics.compareExchange(slot.lock, 0, 0, 1) === 0) break
    Atomics.wait(slot.lock, 0, 1, 2)
  }
  try {
    const s = Atomics.load(slot.state, 0)
    if (s === EMPTY || s === EOF) return null   // 另一消费者已取走/EOF:调用方继续轮询
    const n = Atomics.load(slot.len, 0)
    const line = Buffer.from(slot.data.subarray(0, n)).toString('utf8')
    Atomics.store(slot.state, 0, EMPTY)
    Atomics.notify(slot.state, 0)
    return s === OVERSIZE ? null : line
  } finally {
    Atomics.store(slot.lock, 0, 0)
    Atomics.notify(slot.lock, 0)
  }
}

// ---- 同步行读取:worker 主循环与嵌套同步桥调用(syncBridgeCall)共用同一把 stdin 读锁 ----
// (旧实现 fs.readSync 阻塞读;现由 reader 线程 + Atomics 提供,见上)

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

// ---- 句柄注册表 ----
const fnById = new Map()  // id → JS function
const modById = new Map() // id → 已加载模块(CJS exports / ESM namespace)
const ctxById = new Map() // id → ctx shim
const objById = new Map() // id → live 对象 / iterable 视图(M7-7,经 invokeObj RPC 调用)
// M11-6:每 worker 独立句柄 id 区间(Java 传 DSH_HANDLE_BASE,1M 起每 worker 1M):fn/obj/ctx
// 本地 id 跨 worker 不冲突 → 读方 worker 的 objById/ctxById 不会误命中本地对象(实测
// agentCtx 跨 worker 时 web 拿到本地 makeCtx,setup 读 .agent 得 undefined)。单测无 env → 1。
let nextHandle = parseInt(process.env.DSH_HANDLE_BASE || '1', 10)

// M7-8:全局句柄 id 基址(与 NodeWorkerJsHost GLOBAL_OBJ_ID_BASE / GLOBAL_FN_ID_BASE 对齐)。
// Java 给跨 worker 句柄分配的 id 在此之上,远大于 worker 本地 nextHandle 计数;worker 据此
// 分辨"本地句柄(监听器回调,泵内延迟)"与"跨 worker 句柄(服务方法/getter/回调,泵内内联
// 处理防互等死锁)"。
const GLOBAL_OBJ_ID_BASE = 100_000_000
const GLOBAL_FN_ID_BASE = 200_000_000
// M11-6:makeCtx 句柄标记 —— 序列化时识别"桥 ctx 代理"为 live 句柄({$kind:'obj'} 而非普通
// 对象快照)。快照会丢失 ctx 的动态成员(Agent.ctx 的 .agent 是经 ctx.get 动态读的,不在
// target 上)→ setup 跨 worker 读 agentCtx.agent 得 undefined。句柄化后 web 侧经 makeServiceProxy
// 的 invokeGet 路由回属主(core)读 .agent。
const CTX_MARK = Symbol.for('dsh.ctxId')

// ---- 值序列化(worker → Java)----
// 函数 → {$kind:'fn',id,length};普通值走 JSON。服务句柄({$kind:'svc',id} 与
// {$kind:'ctx'/'module'} 标记)是普通 JSON 对象,原样透传回 Java。
// 实数语义:NaN/±Infinity → null(与 JSON.stringify 一致)。
// M7-6 容忍性(真实 dsh 插件 apply 结果 / service provide 值里出现):
//   - 循环引用 → {$kind:'cycle'} 标记,不无限递归(Service 实例 this.ctx / this.ownerFiber
//     的自引用链;真实 dsh 里这些回环是实例结构,跨桥复制应丢环而不是挂加载);
//   - Symbol 值 → {$kind:'symbol'} 标记(不可序列化,保留键位;如 session-query-sqlite 的
//     _persistenceBinding.identity);
//   - 其余不可序列化类型仍抛明确错误。
// M7-7(通用句柄机制核心扩展):
//   - 非数组 iterable/iterator → {$kind:'iter',id}:Java 侧得 Iterable/Iterator 代理,
//     经 invokeObj RPC next(),遍历终结自动释放句柄(见 JsIterable);
//   - live 对象(带原型方法的类实例等,opts.liveHandles 时)→ {$kind:'obj',id}:Java 侧得
//     RemoteObject 代理,方法调用 RPC 回 worker(递归:方法返回的嵌套 live 对象也句柄化)。
//   - 仅 fn 型 own 成员的对象(probe 类)仍是普通 JSON(方法 → fn 句柄,数据原样)——M5-NEEDS
//     语义不变;类实例才走 live 句柄(带原型方法 = 需方法调用)。

/** 是否可迭代对象(非数组):有 Symbol.iterator 可调(Set/Map/generator/...),或是一个
 *  真正的迭代器对象(经 iterable 的 [Symbol.iterator]() 产生的原型非 Object.prototype)。
 *  收紧(M8 low ①):纯数据对象(原型 = Object.prototype / null)即使带 next()/Symbol.iterator
 *  成员(如 { next: fn, [Symbol.iterator]: fn } 配置/数据容器)也不再被句柄化 —— 一律按 JSON
 *  跨桥,否则它们会被当作迭代器跨桥为 JsIterable,丢失数据面。真迭代器/可迭代(Set/Map/生成器、
 *  自定义可迭代类实例)的原型链都非普通 Object.prototype,不受影响。 */
function isIteratorLike(v) {
  if (v === null || typeof v !== 'object') return false
  if (Array.isArray(v)) return false   // 数组仍是数据(JSON array),Java 侧 List
  const proto = Object.getPrototypeOf(v)
  // 普通对象(原型 = Object.prototype / null)是数据容器:next()/Symbol.iterator 都是普通
  // 数据成员,不是迭代器协议证据 —— 按 JSON 跨桥。
  if (proto === null || proto === Object.prototype) return false
  // 真迭代器协议(原型非普通):next() 为函数(迭代器对象)或 Symbol.iterator 可调(Set/Map/
  // 生成器/custom 可迭代类)。
  if (typeof v.next === 'function') return true
  return typeof v[Symbol.iterator] === 'function'
}

/** 是否 live 对象(类实例 / 带原型方法):原型链上有非 Object.prototype 的 function 成员。 */
function isLiveObject(v) {
  if (v === null || typeof v !== 'object') return false
  if (Array.isArray(v)) return false
  for (let proto = Object.getPrototypeOf(v); proto !== null && proto !== Object.prototype; proto = Object.getPrototypeOf(proto)) {
    for (const n of Object.getOwnPropertyNames(proto)) {
      if (n === 'constructor') continue
      const d = Object.getOwnPropertyDescriptor(proto, n)
      if (d && (typeof d.value === 'function' || typeof d.get === 'function' || typeof d.set === 'function')) return true
    }
  }
  return false
}

/**
 * 是否 shim Service 实例(cordis-shim Service 构造提供:name + ctx + $check)。
 * M11-3:Service 的原型方法多用 symbol key(isLiveObject 的 getOwnPropertyNames 只查 string
 * 名),ApiProxyService 等"字段承载业务面"的子类原型无 string 方法 → isLiveObject false →
 * 走普通对象快照;而 provide 发生在 super()(子类字段如 apiProxy.host 赋值前)→ 快照丢失
 * 字段 → 读方(connection)拿到的 apiProxy 无 host → host.listDirectory/describe 报
 * "api.host undefined"(host.describe 失败 → connection lost)。识别为 live 句柄后,读方经桥
 * 读实例,子类构造后期赋值的字段可见。
 */
function isServiceLike(v) {
  return v !== null && typeof v === 'object'
    && typeof v.name === 'string'
    && typeof v.ctx === 'object'
    && v.$check !== undefined
}

/**
 * 是否"纯迭代器/生成器":原型链成员只有迭代器协议(next/return/throw)。M7-8 用它区分
 * 两类"既是 iterable 又是 live 对象"的值:
 *   - 纯生成器/迭代器(gen()、iterator view)→ 仍是 {$kind:'iter'}(Java 侧 JsIterable 遍历);
 *   - 带业务方法(如 Service 服务的 list()/get()/Symbol.iterator)→ {$kind:'obj'}(方法可调 +
 *     成员可读),避免 iter 分支吞掉方法面。
 */
function isPureIterator(v) {
  // 标准集合(Set/Map)是数据容器,不是带业务方法的 live 服务对象 → 仍走 iter 分支(可遍历,
  // Java 侧 JsIterable),不把数据容器当服务句柄。
  const ctor = v && v.constructor
  if (ctor === Set || ctor === Map) return true
  if (typeof v.next !== 'function') return false
  const proto = Object.getPrototypeOf(v)
  if (proto === null || proto === Object.prototype) return false
  const names = Object.getOwnPropertyNames(proto)
  for (const n of names) {
    if (n === 'constructor' || n === 'next' || n === 'return' || n === 'throw') continue
    return false
  }
  return true
}

/** 把 iterable/iterator 归一成"只有 next()"的视图(Java 侧只调 next())。 */
function makeIterableView(v) {
  const iter = (typeof v.next === 'function') ? v : v[Symbol.iterator]()
  const view = { next: () => iter.next() }
  view[Symbol.iterator] = () => view
  return view
}

function serializeValue(v, seen, opts) {
  const liveHandles = !!(opts && opts.liveHandles === true)
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
    // M11-4:schemastery schema 是可调用函数(live 对象)。跨 worker 传参时,function 分支
    // 会把它当普通回调(fn 句柄)→ 接收方拿 NodeRef(无方法)→ settings.register 的 schema
    // 在 core worker 无 toJSON → settings.describe 挂(welcome 确认 / 凭据引导全卡)。
    // 带 toJSON 的"函数即 schema"(live)作为 obj 句柄(live 对象),方法经桥可调。
    if (liveHandles && typeof v.toJSON === 'function') {
      const id = nextHandle++
      objById.set(id, v)
      return { $kind: 'obj', id }
    }
    const id = nextHandle++
    fnById.set(id, v)
    const out = { $kind: 'fn', id }
    const len = v.length
    if (Number.isInteger(len)) out.length = len
    // M11-6:async 标记 —— async fn 回调(如 agents.create 的 setup,内部 fs.stat/动态 import
    // 依赖 macrotask)跨 worker 作为参数时,调用方方法必须异步(防同步泵饿死 macrotask →
    // 互等死锁);同步 fn(registerProvider 的 create,纯逻辑)保持同步方法调用。
    if (v.constructor && v.constructor.name === 'AsyncFunction') out.async = true
    return out
  }
  if (t === 'symbol') return { $kind: 'symbol' }
  if (t === 'object') {
    // M11-6:桥 ctx 代理(makeCtx)→ ctx 句柄(读方经桥路由回属主 ctx,on/emit/get 等方法
    // 与动态成员(agent 等)语义保持)。不用 obj 句柄 —— makeServiceProxy 的 $members 遍历
    // 原型链,而 makeCtx 方法在 target(非原型)→ 枚举不到 → 成员全按方法处理,破坏 ctx 语义
    // (实测 web-runtime/api-gateway route 未注册)。
    if (v !== null && typeof v === 'object' && v[CTX_MARK] !== undefined) {
      return { $kind: 'ctx', id: v[CTX_MARK] }
    }
    // M7-8:live 对象优先于纯 iterable —— 带业务方法的可迭代服务(如 Service 的
    // list()/get() + Symbol.iterator)必须保留方法面({$kind:'obj'}),否则 iter 分支会吞掉
    // 方法(Java 侧只得 JsIterable,方法不可调)。纯生成器/迭代器(isPureIterator)仍走 iter
    // 分支,保持 M7-7 语义(Java 侧 JsIterable 遍历)。
    if (liveHandles && (isLiveObject(v) || isServiceLike(v)) && !isPureIterator(v)) {
      const id = nextHandle++
      objById.set(id, v)
      return { $kind: 'obj', id }
    }
    // 非数组 iterable/iterator:一律跨桥为句柄(Java 可遍历;数组仍是数据)。
    if (isIteratorLike(v)) {
      const id = nextHandle++
      objById.set(id, makeIterableView(v))
      return { $kind: 'iter', id }
    }
    // live 对象:仅方法返回上下文(liveHandles)句柄化 —— 纯数据 provide/emit 等仍可 JSON
    // (不强转),不破坏 Java 侧读纯数据提供值(Map)的既有语义。
    const active = seen || new WeakSet()
    if (active.has(v)) return { $kind: 'cycle' }   // 循环引用:降级为标记(此前抛错挂加载)
    active.add(v)
    let out
    if (Array.isArray(v)) {
      out = v.map(x => serializeValue(x, active, opts))
    } else {
      out = {}
      for (const k of Object.keys(v)) out[k] = serializeValue(v[k], active, opts)
      // M11-7:symbol key(如 dsh-scope 的 kScope = Symbol('dsh.scope'),createScope 经
      // ctx.extend({[kScope]: key}) 设 scope key)序列化为 "$symbol$<desc>" 键 ——
      // Java doExtend 存到 child.symbolProps,跨 worker 读方(makeCtx proxy 对 symbol 属性
      // 经 ctxCall symbolGet)路由回属主取回(scopeOf(agentCtx) 同形)。排除内置 symbol
      // (iterator/toStringTag 等,不应跨桥传输)。
      for (const s of Object.getOwnPropertySymbols(v)) {
        const desc = s.description || String(s)
        if (desc === 'Symbol.iterator' || desc === 'Symbol.toStringTag'
            || desc === 'Symbol.asyncIterator' || desc === 'Symbol.hasInstance') continue
        out['$symbol$' + desc] = serializeValue(v[s], active, opts)
      }
    }
    active.delete(v)
    return out
  }
  throw new Error('cannot serialize value across bridge: ' + t)
}

// ---- 值反序列化(Java → worker 的 config / invokeFn args)----
// M7-6 容忍性:fn 句柄失效(已 release 或属其它 worker 进程)不再挂加载 —— 降级为
// 记录性 no-op stub(日志提示),让跨 worker 服务值读取不崩(真实 dsh 每插件独立
// worker,兄弟插件读到的服务值含对方 worker 的 fn 句柄;深层语义修复属共享 fiber/
// 服务句柄化工作,这里先保证加载不挂)。
function makeStaleFn(id) {
  const stub = function () {
    if (stub._warned) return undefined
    stub._warned = true
    process.stderr.write('node-bridge: stale fn handle ' + id + ' invoked (no-op stub; '
      + 'owned by a released/disposed or foreign worker handle)\n')
    return undefined
  }
  return stub
}

function deserializeValue(v) {
  if (v === null || typeof v !== 'object') return v
  if (v.$kind === 'fn') {
    const fn = fnById.get(v.id)
    if (typeof fn === 'function') return fn
    // M7-8:跨 worker 的 fn 句柄(指向其它 worker 的 JS 函数,如 skill 服务方法收到的回调参数)
    // → 经 Java 路由到属主 worker 执行(invokeService '$call' → FUNCTION_OWNERS → 属主宿主
    // invokeFn)。Java 侧无该句柄路由(真正陈旧/已 release)→ 抛错 → 降级为 no-op stub
    // (保持 M7-6 容忍语义,不挂加载)。
    // M11-7:按 async 标记分流 —— async fn(agents.create 的 setup,内部 fs.stat/动态 import
    // 依赖 macrotask)跨 worker 调用必须异步(asyncBridgeCall 返回 Promise):调用方 worker
    // (core)的 await 挂起但事件循环自由,能响应回调执行期间的跨 worker ctxCall(如 setup
    // 读 agentCtx.agent → core ctx.get → accessor getter 经桥回 core worker)→ 互等解除。
    // 同步 fn(registerProvider 的 create)保持同步 stub(既有同步回调语义)。
    if (v.async === true) {
      const remoteAsync = function () {
        const args = Array.prototype.slice.call(arguments)
        // liveHandles:回调参数里的 live 对象(如 service 传的 AbortSignal)以句柄跨桥,属主
        // worker 之外仍可调方法(skill FileSystemSkillProvider 的 control.signal.addEventListener
        // 同形);纯数据参数不受影响。
        return asyncBridgeCall('invokeService', {
          handle: v.id, method: '$call',
          args: args.map(x => serializeValue(x, undefined, { liveHandles: true })),
        }).catch((e) => {
          if (remoteAsync._warned) return undefined
          remoteAsync._warned = true
          process.stderr.write('node-bridge: remote fn handle ' + v.id + ' invocation failed ('
            + (e && e.message ? e.message : e) + '); no-op\n')
          return undefined
        })
      }
      return remoteAsync
    }
    const remote = function () {
      const args = Array.prototype.slice.call(arguments)
      const serialized = args.map(x => serializeValue(x, undefined, { liveHandles: true }))
      if (serialized.some(a => hasSerializedRemoteHandle(a, 3))) {
        return asyncBridgeCall('invokeService', { handle: v.id, method: '$call', args: serialized })
          .catch((e) => {
            if (remote._warned) return undefined
            remote._warned = true
            process.stderr.write('node-bridge: remote fn handle ' + v.id + ' invocation failed ('
              + (e && e.stack ? e.stack : e) + '); no-op\n')
            return undefined
          })
      }
      try {
        return syncBridgeCall('invokeService', { handle: v.id, method: '$call', args: serialized })
      } catch (e) {
        if (remote._warned) return undefined
        remote._warned = true
        process.stderr.write('node-bridge: remote fn handle ' + v.id + ' invocation failed ('
          + (e && e.message ? e.message : e) + '); no-op\n')
        return undefined
      }
    }
    return remote
  }
  if (v.$kind === 'undefined') return undefined
  if (v.$kind === 'module') {
    const m = modById.get(v.id)
    if (m === undefined) throw new Error('unknown module handle ' + v.id)
    return m
  }
  if (v.$kind === 'ctx') {
    const c = ctxById.get(v.id)
    if (c === undefined) {
      // M11-6:外部 worker 的 ctx(本地无该 ctxId)→ 创建远程 ctx 代理(makeCtx 经桥路由回
      // 属主 ctx,on/emit/get 与动态成员(agent 等)经 ctxCall 由 Java 核心路由到属主)。
      // ctxId 用每 worker 独立区间(DSH_HANDLE_BASE),跨 worker 不冲突,不会误命中本地 ctx。
      // M12:v.__snap(Java toJsonNode 附的 kScope 只读快照)传入 makeCtx —— scopeOf(ctx)
      // 首次读 ctx[kScope] 即本地命中,零 symbolGet park。
      return makeCtx(v.id, v.__snap)
    }
    return c
  }
  if (v.$kind === 'svc') return makeServiceProxy(v.id, v.__snap)
  if (v.$kind === 'obj' || v.$kind === 'iter') {
    // 自持句柄(本 worker 创建的 live 对象/iterable 视图)读回 → 返回本地对象,免跨桥往返,
    // 也避免"同步泵中调用自身句柄 → Java 转发回本 worker → 泵内延迟 → 死锁"的循环。
    // 外部 worker 的句柄 → Proxy(经 invokeService → Java 转发 invokeObj 回属主 worker)。
    const local = objById.get(v.id)
    if (local !== undefined) return local
    return makeServiceProxy(v.id, v.__snap)
  }
  if (Array.isArray(v)) return v.map(deserializeValue)
  const out = {}
  for (const k of Object.keys(v)) out[k] = deserializeValue(v[k])
  return out
}

// ---- 服务句柄 → JS 可调 Proxy ----
// 直接调用 svc(...) → invokeService {$call};成员访问 svc.method(...) →
// invokeService {method}(method 走 Java 反射 / JS 侧 invokeObj)。
// M7-8 扩展:成员值读(svc.getter 属性访问)与普通方法调用区分 —— proxy 首次访问某成员时
// 经 $members 拉取成员描述符(缓存):方法 → 可调用函数;getter/数据字段 → $get 直接读值
// (触发 worker 侧 getter)。这样 `ctx.shell.sandboxMode`(permission 同形)读到真实值
// 而不是一个函数;`ctx.agents.list()` 仍是方法调用。
// then 返回 undefined,避免 JS Promise 把服务对象误当 thenable 吸收。
// ---- M11-11:跨 worker live 对象标记与序列化后检测(死锁防护)----
// 跨 worker fn 回调(remote stub)的参数若含"经桥 live 对象"(makeCtx 代理 / makeServiceProxy /
// 序列化后的 {$kind:'obj'|'ctx'|'svc'|'iter'} 句柄),fn 内部几乎必然反向调用属主 worker
// (读属性、调方法),而调用方若用 syncBridgeCall 占住事件循环 → 属主 worker 的反向调用无人处理
// → 互等死锁(invokeFn 120s 超时)。检测到参数含远程句柄时,即使 fn 未标 async 也走 asyncBridgeCall:
// 调用方事件循环自由,能响应反向调用。
const REMOTE_MARK = Symbol.for('dsh.remoteHandle')
let dbgLateCount = 0
// 泵内 dispatch 深度:泵内分发 invokeObj/invokeFn 等时,direct 处理的消息的 JS 函数若反向
// syncBridgeCall(嵌套泵),嵌套泵内不得再次 dispatch(会无限嵌套,回复永远读不到)——嵌套泵
// 把新到消息 deferred,泵只等自己的回复。
let pumpDispatchDepth = 0
function isRemoteHandle(v) {
  return !!v && (typeof v === 'function' || typeof v === 'object') && v[REMOTE_MARK] === true
}
/** 递归检测参数(含浅层嵌套)是否含跨 worker live 对象。 */
function hasRemoteHandle(v, depth) {
  if (v === null || v === undefined) return false
  if (isRemoteHandle(v)) return true
  if (depth <= 0 || typeof v !== 'object') return false
  for (const k of Object.keys(v)) {
    if (hasRemoteHandle(v[k], depth - 1)) return true
  }
  return false
}
/** 序列化后检测 —— 本地 live 对象(如 Session 实例)经 serializeValue liveHandles 变
 *  {$kind:'obj'} 句柄,读方(跨 worker)经桥路由回属主。参数序列化结果含此类句柄 → fn 内部
 *  访问它必然反向调用属主 → 必须异步(防互等死锁)。 */
function hasSerializedRemoteHandle(v, depth) {
  if (v === null || v === undefined) return false
  if (typeof v === 'object') {
    const k = v.$kind
    if (k === 'obj' || k === 'ctx' || k === 'svc' || k === 'iter') return true
  }
  if (depth <= 0 || typeof v !== 'object') return false
  for (const k of Object.keys(v)) {
    if (hasSerializedRemoteHandle(v[k], depth - 1)) return true
  }
  return false
}

function makeServiceProxy(handle, snap) {
  // M7-8:参数带 liveHandles 序列化 —— 方法参数里的 live 对象(如 llm.registerAdapter 的
  // adapter 类实例)以句柄跨桥,读方可路由回属主调用其方法;纯数据参数不受影响(不强转)。
  // M11-6:同步/异步分流 —— proxy 元操作($members/$get/iterator)必须同步(JS proxy trap
  // 与 for...of 要求同步返回);普通服务方法**异步**(asyncBridgeCall):真实 dsh 服务方法
  // 都是 async(Promise),插件总是 await,异步不阻塞事件循环 → 解除跨 worker 回调链死锁。
  // M12(snap 参数):Java 侧 toJsonNode 把事件参数里 live 对象(Fiber)的只读字段(state/uid/entry)
  // 作为 __snap 附带在 {$kind:'svc'} 句柄上。get trap 先查本地快照,命中即返回快照值(零
  // syncBridgeCall park)—— 这是跨 worker 互等 + 事件风暴池耗尽的根因解(回调读 fiber.state/
  // entry?.options.name 不再 park 事件循环)。快照 miss(方法/未快照字段)走原 bridge 路径,
  // 保持 live 语义(值化会破坏 fiber.ctx.get/fiber.parent 等方法面,故保留 live 代理)。
  const invokeSync = (method, args) =>
    syncBridgeCall('invokeService', { handle, method, args: args.map(x => serializeValue(x, undefined, { liveHandles: true })) })
  // M11-6:参数带 async fn 回调(如 agents.create 的 setup)→ 异步(防跨 worker 同步回调链
  // 死锁);同步 fn/纯数据 → 同步(既有语义)。
  const invoke = (method, args) => {
    const serialized = args.map(x => serializeValue(x, undefined, { liveHandles: true }))
    // M11-8:async 方法标记($members 标记的 async JS/Java 方法)→ 异步调用(不 park 事件循环,
    // 防跨 worker 同步互等死锁 —— agentPresets.resolve/mount 等 async 方法跨 worker 同步调
    // 用会 park 本 worker,而属主 worker 的反向调用(如 agents.create 的 setup 回调)需要本
    // worker 响应 → 互等)。参数带 async fn 回调同理。同步 fn/纯数据/本地 svc 保持同步(既有
    // 语义:parseCmdline 等需要同步返回值)。
    const mk = kinds().get(method)
    // apply 期间(加载期 registerAll):参数含 async fn 的调用保持同步(异步会让 registerAll 等
    // 不到注册完成)。运行时(apply 外)才 async(破对话互等)。
    if (applyDepth === 0 && (hasAsyncFnHandle(serialized) || (mk && mk.fn && mk.async))) {
      return asyncBridgeCall('invokeService', { handle, method, args: serialized })
    }
    return syncBridgeCall('invokeService', { handle, method, args: serialized })
  }
  const callable = (...args) => invoke('$call', args)
  // 成员描述符缓存:name → 'function' | 'value'(getter/数据字段)。$members 失败(旧宿主 /
  // 不支持)时退化为"全部可调用",保持 M7-7 行为。
  let memberKinds = null
  const kinds = () => {
    if (memberKinds === null) {
      memberKinds = new Map()
      try {
        const list = invokeSync('$members', [])
        if (Array.isArray(list)) {
          for (const it of list) {
            if (it && typeof it === 'object' && typeof it.name === 'string') {
              memberKinds.set(it.name, { fn: it.type === 'function', async: !!it.async })
            }
          }
        }
      } catch (e) {
        // $members 不可用:退化为全部方法可调用(不抛,不破坏既有服务代理)
      }
    }
    return memberKinds
  }
  return new Proxy(callable, {
    get(target, prop, receiver) {
      if (prop === 'then') return undefined
      if (prop === REMOTE_MARK) return true
      if (prop === Symbol.iterator) {
        // Java Iterable/Iterator 服务 → JS 可 for...of(经桥 RPC hasNext/next 适配)。
        // 先尝试经 iterator() 拿到 Java 侧 java.util.Iterator 句柄;若对象本身是
        // Iterator(ServiceInvoker 对 iterator() 返回自身),直接用原句柄。
        return function () {
          let itProxy
          try {
            itProxy = invokeSync('iterator', [])   // 返回 svc 句柄代理(deserializeValue)
          } catch (e) {
            itProxy = callable                  // 非 Iterable:视原句柄为迭代器
          }
          const iterator = {
            next() {
              // itProxy.next() → invokeService('next') → ServiceInvoker 返回 {done,value}
              const step = itProxy.next()
              if (step && typeof step === 'object' && 'done' in step) return step
              return { done: true, value: undefined }
            },
          }
          iterator[Symbol.iterator] = () => iterator
          return iterator
        }
      }
      if (typeof prop === 'symbol') return undefined
      const name = String(prop)
      // M12:本地值快照 —— 事件参数里 live 对象(Fiber)的只读字段(state/uid/entry)由 Java 侧
      // toJsonNode 作为 __snap 附带。get trap 必须先查快照:命中即返回快照值(本地,零 park)。
      // 快照值再经 deserializeValue 展开(嵌套 live 对象仍句柄化,方法与成员面保留)。快照 miss
      // (方法/未快照字段)走原 bridge 路径,保持 live 语义。
      if (snap !== undefined && Object.prototype.hasOwnProperty.call(snap, name)) {
        return deserializeValue(snap[name])
      }
      // getter/数据字段成员 → 直接读值(invokeService '$get' → invokeGet 触发 getter)。
      const k = kinds().get(name)
      if (k && !k.fn) return invokeSync('$get', [name])
      return (...args) => invoke(name, args)
    },
  })
}

/** M7-8:消息携带的 fn 句柄是否为跨 worker 全局句柄(id ≥ GLOBAL_FN_ID_BASE)。 */
function isGlobalFnHandle(h) {
  return !!h && typeof h === 'object' && h.$kind === 'fn' && typeof h.id === 'number' && h.id >= GLOBAL_FN_ID_BASE
}

/**
 * 序列化参数里是否含 **async** fn 句柄(递归)。M11-6:方法参数带 async fn 回调(如
 * agents.create 的 setup)意味着该方法执行期间会**回调回来**,且回调内部需要 macrotask
 * (fs.stat/动态 import)→ 调用方若同步泵等待,会饿死回调所在 worker 的事件循环 → 互等
 * 死锁。此类调用必须异步(await 挂起不阻塞事件循环);同步 fn(registerProvider 的 create)
 * 或纯数据参数的方法保持同步(既有语义)。
 */
function hasAsyncFnHandle(v) {
  if (v === null || typeof v !== 'object') return false
  if (v.$kind === 'fn') return v.async === true
  if (Array.isArray(v)) return v.some(hasAsyncFnHandle)
  for (const k of Object.keys(v)) if (hasAsyncFnHandle(v[k])) return true
  return false
}

/** 泵动 microtask 链(限次):让 dispatchRequest 的 .then(发送回复)在同步泵内真正发出。
 *  仅驱动 microtask(不碰 macrotask/事件循环);超限静默放弃,由事件循环稍后补发。 */
function pumpMicrotasks() {
  for (let i = 0; i < 256 && process._tickCallback && !closing; i++) {
    process._tickCallback()
  }
}

// ---- 嵌套 RPC:ctx 方法 / 服务方法 需同步等 Java 回复 ----
// 同步泵(sync-over-async):发送请求 → 阻塞(Atomics.wait)取行 → 匹配到自身 ctxResult
// 即返回;其余行(Java 并发发来的请求 / 其它回复)入队(deferred),事件循环稍后按序消化。
// 泵期间收到的重入消息**不内联执行**(防监听器在等待中途触发导致状态错乱)。
const deferred = []   // 泵期间入队的已解析消息对象(事件循环按序处理)
let bridgeSeq = 0

// ---- 异步桥调用(service 方法 / 跨 worker fn 回调):不阻塞事件循环 ----
// 死锁根因修复:同步泵(syncBridgeCall)等待跨 worker 回复时**占住主事件循环**,饿死
// 同 worker 内的 macrotask(动态 import / setTimeout / ESM TLA)→ setup 回调链
// (presets.mount 的 import)永不 settle → web↔core 互等死锁。异步桥让方法调用返回
// Promise:await 挂起但事件循环自由,macrotask 照常跑,回复到达经主循环 resolve。
// 同步语义保留给 ctx 方法(ctx.get 等)与 proxy 元操作($members/$get/iterator ——
// JS proxy trap 必须同步返回)。asyncWaiters 的回复由 routeMessage(主循环)或同步泵
// (泵内顺带)resolve,两者都查同一张表。
const asyncWaiters = new Map()
function asyncBridgeCall(type, payload) {
  const id = ++bridgeSeq
  return new Promise((resolve, reject) => {
    try {
      send(Object.assign({ type, id }, payload))
      asyncWaiters.set(id, { resolve, reject })
    } catch (e) {
      reject(e)
    }
  })
}
function resolveAsyncWaiter(msg) {
  const w = asyncWaiters.get(msg.id)
  if (!w) return false
  asyncWaiters.delete(msg.id)
  if (msg.error) w.reject(new Error('java bridge error: ' + msg.error))
  else w.resolve(deserializeValue(msg.result))
  return true
}

function syncBridgeCall(type, payload) {
  const id = ++bridgeSeq
  send(Object.assign({ type, id }, payload))
  const deadline = Date.now() + SYNC_PUMP_TIMEOUT_MS
  if (process.uptime() > 20 && dbgLateCount++ < 40) {
    const h = payload && (payload.handle ?? payload.ctx ?? payload.method ?? payload.name ?? '')
    process.stderr.write('[DG-LATE] ' + type + ' h=' + JSON.stringify(h) + '\n' + new Error().stack.split('\n').slice(1, 5).join('\n') + '\n')
  }
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
    if (msg.type === 'ctxResult' && resolveAsyncWaiter(msg)) {
      // M11-6:泵内顺带消费异步桥调用的回复(主循环被泵占住时异步回复也必须及时 resolve)。
      continue
    }
    // M7-8:rehandleObj 是纯注册表操作(把 objById 条目迁到全局 id),**泵内同步处理** ——
    // 提供方 worker 在 provide 后紧接着自读自己的服务(ctx.get → {$kind:'obj', id:全局id})
    // 时必须已把条目迁好,否则自读返回代理 → 代理首次调用($members)经 Java helper 线程
    // 回发 → 泵内再次延迟 → helper 超时死锁。同步迁移无监听器副作用,不违反"泵内不内联
    // 执行重入消息"的设计约束(那条针对监听器触发的状态变更)。
    if (msg.type === 'rehandleObj') {
      if (typeof msg.from === 'number' && typeof msg.to === 'number') {
        const v = objById.get(msg.from)
        if (v !== undefined) {
          objById.delete(msg.from)
          objById.set(msg.to, v)
        }
      }
      continue
    }
    if (msg.type === 'rehandleFn') {
      if (typeof msg.from === 'number' && typeof msg.to === 'number') {
        const fn = fnById.get(msg.from)
        if (typeof fn === 'function') {
          fnById.delete(msg.from)
          fnById.set(msg.to, fn)
        }
      }
      continue
    }
    // M7-8:跨 worker RPC 请求(invokeObj / invokeGet / invokeMembers / 全局 fn 回调)泵内
    // **内联分发 + 泵动 microtask 让回复发出**。否则调用方 worker 同步等回复、本 worker 泵内
    // 延迟该请求 → 跨 worker 互等死锁(skill 的 registerProvider(cb) 同形:worker B 泵等
    // registerProvider 回复,而回复依赖 worker A 调用的 cb,cb 恰是发给 worker B 的 invokeFn)。
    // 本地 fn 的 invokeFn(监听器回调)保持延迟入队,防监听器在等待中途触发导致状态错乱。
    // 泵内不得分发 'apply'/'load' 等重型请求(它们会再进泵,语义不受益)。
    if (msg.type === 'invokeObj' || msg.type === 'invokeGet' || msg.type === 'invokeMembers'
        || (msg.type === 'invokeFn' && isGlobalFnHandle(msg.handle))) {
      // 嵌套泵(dispatch 上下文内):不再 dispatch —— 被处理消息的 JS 函数反向 syncBridgeCall
      // 进入本泵,若再 dispatch 新消息 → 无限嵌套,本泵的回复永远读不到(agent 创建的
      // internal/status 投影死锁)。deferred 入队,泵只等自己的回复;泵返回后事件循环 drain。
      if (pumpDispatchDepth > 0) {
        deferred.push(msg)
        scheduleDeferredDrain()
        continue
      }
      pumpDispatchDepth++
      try {
        dispatchRequest(msg)
        pumpMicrotasks()
      } finally {
        pumpDispatchDepth--
      }
      continue
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

// ---- ctx.fiber live 代理(M7-8 B:agent-loop 同形)----
// worker 侧 ctx.fiber 是可调 live 代理:assertActive() / state 经 ctxCall 桥到 Java 核心的
// fiber 状态(不再是非可枚举的静态 { state:'active' } 桩)。parent 保持自终止链
// (fiber.parent.fiber === fiber),使 hasLifecycleAncestor 的 identity 比较首次即返回 false。
// 数值 state 与 shim 的 FiberState 对应(PENDING=0, LOADING=1, ACTIVE=2, FAILED=3,
// DISPOSED=4, UNLOADING=5)—— 真实 dsh 插件读数值(ctx.fiber.state === FiberState.ACTIVE)。
function makeFiberProxy(ctxId) {
  const invoke = (method, args) =>
    syncBridgeCall('ctxCall', { ctx: ctxId, method, args: (args || []).map(x => serializeValue(x)) })
  const callable = (...args) => invoke('fiberCall', args)
  const parentChain = {}
  const proxy = new Proxy(callable, {
    get(target, prop, receiver) {
      if (prop === 'then') return undefined
      if (prop === 'parent') return parentChain
      if (prop === 'assertActive') {
        // 存活不抛、返回 undefined(Java 侧 assertActive 为 void)。dispose 后 Java 抛
        // INACTIVE_EFFECT → 桥 error → 此处抛 Error,调用方可 catch(cordis 语义)。
        return function () { invoke('fiberAssertActive', []); return undefined }
      }
      if (prop === 'state') return invoke('fiberState', [])
      return undefined
    },
  })
  parentChain.fiber = proxy
  return proxy
}

// ---- ctx shim(与 ctx.js 同一契约面)----
function makeCtx(ctxId, snap) {
  // M12:跨 worker ctx 只读本地化。symbolCache 缓存首次 symbolGet 的只读 symbol(kScope);
  // nameCache 缓存稳定句柄形服务(ctx.get 返回跨 worker live 代理)。两缓存使 boot 期
  // scopeOf(ctx)=ctx[kScope] 与运行期 ctx.get('sessions') 重复读零 syncBridgeCall park,
  // 并固定对象身份(scoped Map 键一致,消除重复 symbolGet 新对象身份失配/静默重放)。
  // snap 为 Java toJsonNode 附的 __snap 只读快照(首次读即本地,零往返)。
  const symbolCache = new Map()
  const nameCache = new Map()
  // logger 既可当函数调用(ctx.logger('agents') → 命名 logger),也带方法属性
  // (AgentRegistry 直接读 this.ctx.logger.warn(...) 的无 name 调用)。两者都经桥
  // 转发到 Java Logger 格式化层(见 makeLogger)。
  const defaultLogger = makeLogger(ctxId, undefined)
  const logger = function (name) { return name === undefined ? defaultLogger : makeLogger(ctxId, name) }
  logger.error = defaultLogger.error
  logger.info = defaultLogger.info
  logger.warn = defaultLogger.warn
  logger.debug = defaultLogger.debug
  // M7-8 B:ctx.fiber 是可调 live 代理(assertActive/state 经桥到 Java 核心);parent 终止链
  // (fiber.parent.fiber === fiber)使 AgentRegistry.hasLifecycleAncestor 的 identity 比较
  // 首次即返回 false。非可枚举:代理是函数,若被 serializeValue(Service 提供 self 时遍历
  // self.ctx 的 own enumerable keys)扫到会报循环引用或破坏句柄 id 分配。
  const fiberProxy = makeFiberProxy(ctxId)
  const ctx = {
    on: (name, listener, opts) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'on', args: [serializeValue(listener), name, opts || {}] }),
    once: (name, listener, opts) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'once', args: [serializeValue(listener), name, opts || {}] }),
    emit: (name, ...args) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'emit', args: [name, args.map(x => serializeValue(x))] }),
    // M7-8:provide 的服务值带 liveHandles 序列化 —— live 对象(Service 子类实例等)跨桥为
    // {$kind:'obj'} 句柄(提供方注册进 objById),Java 核心记下句柄 + 属主 worker,兄弟 worker
    // ctx.get 得到指向属主 worker 的 live 代理(方法/getter 经桥路由回属主执行)。纯数据值
    // 不受影响(仍 JSON),与 M7-7 兼容(方法返回的句柄化机制不变)。
    provide: (name, value) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'provide', args: [name, serializeValue(value, undefined, { liveHandles: true })] }),
    get: (name) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'get', args: [name] }),
    inject: (deps, cb) => {
      // M11:ctx.inject(deps, cb) 的 cb 必须收到子 ctx(domainCtx) —— 真实 cordis 语义。
      // Java doInject 给子 ctx 分配独立 ctxId 并作为 cb 参数传来;这里把 ctxId 转成
      // makeCtx(子 ctx proxy)。此前桥只 invokeListener 空参数 → cb 的 domainCtx=undefined
      // → storage-domain 等插件的 ctx.inject 回调里 provide 静默失败 → storageDomain 等
      // 服务缺失 → workspace/api-gateway 级联 PENDING → /api 404 → web UI 无法交互。
      const wrapped = (subCtxId) => cb(subCtxId === undefined ? undefined : makeCtx(subCtxId))
      return syncBridgeCall('ctxCall', { ctx: ctxId, method: 'inject', args: [deps.map(x => serializeValue(x)), serializeValue(wrapped)] })
    },
    extend: (meta) => makeCtx(syncBridgeCall('ctxCall', { ctx: ctxId, method: 'extend', args: [serializeValue(meta, undefined, { liveHandles: true })] })),
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
    // M8:ctx.plugin(plugin, config) —— 挂一个 JS 子插件(web-runtime 用它挂 frontend-static
    // 的 { apply } 插件到 fallback seat)。函数 / { apply } / Service 子类三种形状都支持:
    //   - 函数 → apply(ctx, config)(普通插件函数);
    //   - { apply, inject } → apply(ctx, config)(frontend-static 同形);
    //   - Service 子类 → new Plugin(ctx, config)(cordis 类插件语义,与 loader apply 同形)。
    // apply 经 ctx.effect 登记的 disposer 生命周期归 Java fiber(与 loader 插件一致)。返回
    // 子插件的 apply 结果(函数 → 可作为 disposer;普通值 → undefined)。
    plugin: function (plugin, config) {
      // 必须把 THIS(代理)传给子插件的 apply —— makeCtx 里的闭包 ctx 是未代理的原对象,
      // 子插件读 ctx.webServer 等服务属性会得 undefined(服务属性经 Proxy get 路由)。
      const self = this
      let apply
      if (typeof plugin === 'function') {
        if (plugin.prototype && typeof plugin.prototype[Symbol.for('cordis.init')] !== 'undefined') {
          // Service 子类:构造即经 ctx.provide 注册进 Java 核心
          return new plugin(self, config)
        }
        apply = plugin
      } else if (plugin && typeof plugin.apply === 'function') {
        apply = plugin.apply
      } else {
        throw new Error('ctx.plugin: unsupported plugin shape')
      }
      const result = apply(self, config)
      // M11-6:ctx.plugin 必须返回 fiber(带 ctx + dispose)——createScope 用
      // fiber.ctx.extend + quiesceFiber(fiber)。此前只返回 apply 结果(no-op 插件 →
      // undefined)→ createScope 的 fiber.ctx.extend 崩溃(agent 创建失败 → 会话失败 →
      // UI 退回选择工作区)。返回 fiber-like:ctx = self(可 extend),dispose = apply 结果
      // 或 no-op。真实 fiber 的 full 语义(Java Registry.plugin)是后续改进。
      return {
        ctx: self,
        dispose: typeof result === 'function' ? result : () => {},
        inertia: undefined,
      }
    },
    effect: (body, label) => {
      const stepped = body()
      const disposers = []
      // generator body 检测:真 generator 是对象(有 next + Symbol.iterator)。排除函数 ——
      // 服务代理(makeServiceProxy,可调用函数)的 get trap 对任意属性都返回函数,
      // Symbol.iterator 现已可调用(Java Iterable 遍历);若把代理误判成 generator 会去调
      // .next() → Java 侧 "no public method 'next'"(M7-7 回归:agent-loop setFactory 的
      // effect body 返回 Java disposer 句柄)。真 generator 恒为 object,不受影响。
      if (stepped && typeof stepped !== 'function'
          && typeof stepped.next === 'function' && typeof stepped[Symbol.iterator] === 'function') {
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
  // ctx.fiber live 代理(非可枚举,serializeValue 扫不到;经 ctx Proxy get 仍可达)。
  Object.defineProperty(ctx, 'fiber', { value: fiberProxy, enumerable: false, writable: true, configurable: true })
  // ctx.baseUrl 经桥到 Java 核心 root.baseUrl(hmr 的 new URL(config.base||'.', ctx.baseUrl)
  // 需要;未设置 → undefined)。非可枚举:进 target 使 Proxy get 走 Reflect.get,不经 ctx.get()。
  // baseUrl:本地可写(preset 组合的 Include 构造设 ctx.baseUrl 做相对 URL 解析 —— 经桥 setter
  // 在加载期触发 syncBridgeCall 会破坏 registerAll,故本地存),读优先本地,fallback 到 Java。
  let localBaseUrl
  Object.defineProperty(ctx, 'baseUrl', {
    get: () => {
      if (localBaseUrl !== undefined) return localBaseUrl
      const v = syncBridgeCall('ctxCall', { ctx: ctxId, method: 'baseUrl', args: [] })
      return v === undefined ? undefined : v
    },
    set: (v) => { localBaseUrl = String(v) },
    enumerable: false, configurable: true,
  })
  // M11-8:ctx.reflect(cordis-plugin-loader 的 EntryTree.await() 读 ctx.reflect.notify;挂载
  // preset 的组合树 await 时反射 undefined → "reading notify")。桥代理暴露 reflect 面,
  // notify/provide/get 经 ctxCall 回 Java(loader 的刷新通知 no-op 记录,不触发重载)。
  Object.defineProperty(ctx, 'reflect', {
    value: {
      store: Object.create(null),   // 挂载组合的服务注册表(本地;loader 读 reflect.store[symbol])
      notify: (names = []) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'reflectNotify', args: [names] }),
      provide: (name, value) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'reflectProvide', args: [name, serializeValue(value)] }),
      get: (name) => syncBridgeCall('ctxCall', { ctx: ctxId, method: 'reflectGet', args: [name] }),
    },
    enumerable: false, writable: true, configurable: true,
  })
  // M11-8:cordis 的 Context.isolate/intercept Symbol —— loader 挂载组合时
  // (Entry._start 前)用 Object.create/setPrototypeOf/swap 操作 ctx[Symbol('cordis.isolate')],
  // 桥代理若经 symbolGet 返回 undefined 会崩("reading Symbol(cordis.isolate)")。暴露本地
  // 空对象(loader 的 Object.create 链在本地建立,挂载插件的服务隔离语义本地成立)。
  Object.defineProperty(ctx, Symbol.for('cordis.isolate'), {
    value: Object.create(null),
    enumerable: false, writable: true, configurable: true,
  })
  Object.defineProperty(ctx, Symbol.for('cordis.intercept'), {
    value: Object.create(null),
    enumerable: false, writable: true, configurable: true,
  })
  // cordis 框架方法(M7-6):ctx.mixin(source, keys|renamed) 把服务成员直接暴露到 ctx
  // (reflect.ts:364-390)。Java Context.mixin 已有(accessor 转发),这里暴露给 shim。
  // keys 可为字符串数组(同键暴露)或映射(重命名);原样过桥,Java 侧按 List/Map 分发。
  // 非可枚举定义:仅供 ctx 的 Proxy get 可达,不进 Object.keys —— 否则每次序列化 ctx 就
  // 多注册一个 fn 句柄,扰动全桥句柄 id 分配(实测破坏 M5Profile 组合场景)。
  Object.defineProperty(ctx, 'mixin', {
    value: (source, keys) => syncBridgeCall('ctxCall', {
      ctx: ctxId, method: 'mixin', args: [source, serializeValue(keys)],
    }),
    enumerable: false, writable: true, configurable: true,
  })
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
      if (prop === CTX_MARK) return ctxId
      if (prop === REMOTE_MARK) return true
      // M11-9:ctx.root —— 真实 cordis Context 构造器把 root 指向自身 proxy;agent-presets 的
      // leakedServices 读 ctx.root[Symbol.for('cordis.isolate')] 判泄漏。桥代理此前无 root →
      // ctx.get('root') 回 Java 返回 undefined → "reading Symbol(cordis.isolate)"。这里 root
      // 指向自身:本地 isolate/intercept map 恒空,泄漏检查保守(永不误报),挂载不崩。
      if (prop === 'root') return proxy
      if (prop in target) return Reflect.get(target, prop, receiver)
      if (typeof prop === 'symbol') {
        // M11-7:symbol 属性(如 dsh-scope 的 kScope —— scopeOf(agentCtx) 读 ctx[kScope])
        // 经 ctxCall symbolGet 路由回属主 ctx 的 symbolProps(createScope 经
        // extend({[kScope]: key}) 存入)。跨 worker 的 agentCtx 远程代理由此读到 scope key。
        // M12:先查本地快照(Java toJsonNode 附的 __snap,首次读零 park),再查 symbolCache
        // (首次 symbolGet 后本地,固定对象身份 —— 每次 symbolGet 反序列化新对象会使
        // ScopedLayers 的 scoped Map 键失配 → createLayer 恒真 + 每 effect 泄漏)。kScope
        // 是 createScope 时写入的静态值,缓存安全。symbolGet 跨 worker 同步 pump park 是
        // boot 期 web↔core 互等根因之一,缓存后重复读零 pump。
        const desc = String(prop.description || prop)
        if (snap !== undefined && Object.prototype.hasOwnProperty.call(snap, desc)) {
          return deserializeValue(snap[desc])
        }
        if (symbolCache.has(desc)) return symbolCache.get(desc)
        const sv = syncBridgeCall('ctxCall', { ctx: ctxId, method: 'symbolGet', args: [desc] })
        symbolCache.set(desc, sv)
        return sv
      }
      const nm = String(prop)
      if (nameCache.has(nm)) return nameCache.get(nm)
      const gv = ctx.get(nm)
      // M12:稳定句柄形服务(ctx.get 返回跨 worker live 代理,如 workspace 的 'sessions')
      // 缓存 —— 运行期重复读(sessionKnown/readSessionHeader/attach/archive)本地命中,零
      // pump。只缓存句柄形(REMOTE_MARK 远程代理),绝不缓存 undefined/纯量(服务可能稍后
      // 才 provide;纯数据属性动态可变)。
      if (gv !== null && gv !== undefined && isRemoteHandle(gv)) nameCache.set(nm, gv)
      return gv
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

// ---- Java 桥 shim 的 Service 基类判断(M6-5b 类插件语义)----
// 懒加载 shim(经 DSH_CORDIS_SHIM 指向的 ESM 模块),用 prototype 链判断候选是不是
// shim.Service 的子类。加载的 shim 与插件代码 import 到的是同一文件 → 同一模块实例,
// 因此 `SystemPrompt.prototype instanceof Service` 能成立。
let cordisShimPromise = null
function loadCordisShim() {
  if (!cordisShimPromise) {
    if (!cordisShimPath || cordisShimPath.length === 0) {
      cordisShimPromise = Promise.reject(new Error('DSH_CORDIS_SHIM not set; cannot resolve cordis shim for class plugins'))
    } else {
      cordisShimPromise = import(pathToFileURL(cordisShimPath).href)
    }
  }
  return cordisShimPromise
}

/** 候选是否为 shim.Service 的子类(cordis 类插件:new Plugin(ctx, config) 实例化)。 */
async function isServiceClass(candidate) {
  if (typeof candidate !== 'function' || !candidate.prototype) return false
  try {
    const shim = await loadCordisShim()
    const Service = shim && (shim.Service || shim.default && shim.default.Service)
    if (typeof Service !== 'function') return false
    return candidate.prototype instanceof Service
  } catch (e) {
    // shim 加载失败:不是类插件,落回普通 apply(让原错误路径报出更具体的插件错误)
    return false
  }
}

function pluginMeta(mod) {
  const source = resolvePlugin(mod).source
  // Note: a class plugin (Service subclass, e.g. dsh-plan-mode / dsh-tools /
  // dsh-agent-loop) is `typeof 'function'`, so `static inject`/`static provide`
  // must be read from the class too — otherwise its fiber never gates activation
  // on the declared deps and loads out of order (reads an unavailable sibling
  // service as undefined). M7-6 fiber isolation: accept functions as well.
  const get = (key) => {
    for (const c of [mod, source, mod && mod.default]) {
      if (c && (typeof c === 'object' || typeof c === 'function') && c[key] !== undefined) return c[key]
    }
    return undefined
  }
  return {
    name: get('name'),
    inject: Array.isArray(get('inject')) ? get('inject') : [],
    provide: Array.isArray(get('provide')) ? get('provide') : [],
  }
}

// ---- M7-5 配置通道:!!js 求值 + schemastery/zod Config 默认化 ----
// 根因(M7-4 实证):Java loader 把 config 原样传(不跑插件 static Config 默认化,
// 也不求值 !!js),真实 dsh 插件的 Config 用 schemastery(可调用 schema)或 zod(.parse)。
// 这里在 worker 侧 apply 之前补齐;失败一律回退原 config —— 绝不因默认化失败挂掉加载。

/** 取插件模块的 static Config(schemastery schema / zod schema / 普通 transform)。 */
function getPluginConfig(mod) {
  const source = resolvePlugin(mod).source
  for (const c of [mod, source, mod && mod.default]) {
    if (c != null && c.Config !== undefined) return c.Config
  }
  return undefined
}

/**
 * 按 schema 默认化 config:
 *   - schemastery 形状(可调用函数)→ Config(raw ?? {});
 *   - zod 形状(有 .parse)→ Config.parse(raw ?? {});
 *   - 两者都不是 / 调用抛错 → 回退原 config(记日志),绝不因默认化失败挂掉加载。
 */
function defaultConfig(Config, raw) {
  if (Config == null) return raw
  const input = raw == null ? {} : raw
  try {
    if (typeof Config === 'function') return Config(input)                              // schemastery
    if (typeof Config === 'object' && typeof Config.parse === 'function') {
      return Config.parse(input)                                                        // zod
    }
    return raw
  } catch (e) {
    process.stderr.write('node-bridge: config defaulting failed, using raw config: '
      + (e && e.message ? e.message : e) + '\n')
    return raw
  }
}

// ---- !!js 求值 scope(镜像 dsh Loader 的求值 scope:process + dshHomePath)----

/** dsh-home-paths 的 resolveDshHome 等价物:$DSH_HOME(非空)否则 ~/.dsh。 */
function resolveDshHome() {
  const env = process.env.DSH_HOME
  let selected = (env !== undefined && String(env).trim().length > 0)
    ? env
    : nodePath.join(nodeOs.homedir(), '.dsh')
  if (selected === '~') selected = nodeOs.homedir()
  else if (selected.startsWith('~/') || selected.startsWith('~\\')) {
    selected = nodePath.join(nodeOs.homedir(), selected.slice(2))
  }
  return nodePath.resolve(selected)
}

/** dshHomePath(...segments):DSH_HOME(或 ~/.dsh)根下的路径拼接(@deepseek-ai/dsh-home-paths 等价物)。 */
function dshHomePath(...segments) {
  return nodePath.join(resolveDshHome(), ...segments)
}

/** 求值一个 !!js 表达式;scope = process + dshHomePath(与真实 dsh Loader 的求值面一致)。 */
function evalJsExpression(expr, ctx) {
  // M8:scope 加 ctx(镜像 dsh Loader 的求值面) —— web-app bundle 的 config 表达式读
  // ctx.webStartup.host/port/trustedHosts(web-startup 先 apply 提供该服务)。无 ctx 的
  // 调用(disabled 通道)保持原 scope(process + dshHomePath)。
  if (ctx !== undefined) {
    const fn = new Function('process', 'dshHomePath', 'ctx', '"use strict"; return (' + expr + ')\n')
    return fn(process, dshHomePath, ctx)
  }
  const fn = new Function('process', 'dshHomePath', '"use strict"; return (' + expr + ')\n')
  return fn(process, dshHomePath)
}

/**
 * 递归求值 config 里的 !!js 标记值({$dshJs: expr} → 求值结果)。Java 侧(DshProfileReader)
 * 把 YAML 的 {@code !!js} 标量解析成显式标记对象(不裸传字符串)。求值失败 → 保守处理:
 * 记日志 + 该值按表达式原文保留(插件读到字符串,不挂加载)。
 */
function evalJsMarkers(value, ctx) {
  if (Array.isArray(value)) return value.map((v) => evalJsMarkers(v, ctx))
  if (value !== null && typeof value === 'object') {
    const keys = Object.keys(value)
    if (keys.length === 1 && keys[0] === '$dshJs' && typeof value.$dshJs === 'string') {
      try {
        return evalJsExpression(value.$dshJs, ctx)
      } catch (e) {
        process.stderr.write('node-bridge: !!js eval failed for "' + value.$dshJs + '": '
          + (e && e.message ? e.message : e) + '; keeping expression as-is\n')
        return value.$dshJs
      }
    }
    const out = {}
    for (const k of keys) out[k] = evalJsMarkers(value[k], ctx)
    return out
  }
  return value
}

// ---- Java → worker 请求处理(异步:await 插件结果,解锁 macrotask)----
async function handleRequest(msg) {
  switch (msg.type) {
    case 'load':
    case 'require': {
      const file = msg.file || msg.specifier
      if (typeof file !== 'string' || !file) throw new Error('bad module specifier')
      // M8 fix:统一走异步 import() 而非同步 require(file)。实测(Windows,Node 24)同步
      // require(esm) 在 worker 里加载 ESM 图会因 `import process from 'node:process'`
      // 卡死 —— 主线程被同步 require 占住,ESM loader 对 process 模块的初始化要等事件循环,
      // 而事件循环被占 → 死锁(直到有其它 stdin 行/Java 超时关 stdin 才解)。异步 import()
      // 在 await 时事件循环自由(桥的异步泵),不会死锁。ESM → namespace,CJS → { default },
      // resolvePlugin/pluginMeta 已处理 default 解包(apply 时 resolvePlugin(mod))。
      let mod
      try {
        mod = await import(pathToFileURL(file).href)
      } catch (e) {
        // import() 失败(如非 ESM 的 .cjs 老插件经 pathToFileURL 加载异常)→ 落回 require。
        // 仅兜底;require(esm) 的 process 死锁路径不再触碰(它发生在 import 成功时,不落这里)。
        mod = require(file)
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
    case 'evalJs': {
      if (typeof msg.expr !== 'string') throw new Error('bad evalJs expr')
      // M7-6 disabled 通道:!!js 表达式求值(scope = process + dshHomePath,与 config 通道
      // 的 evalJsExpression 同函数)。结果跨桥返回;求值失败由 worker 侧以 error 消息上报。
      return { type: 'result', id: msg.id, value: serializeValue(evalJsExpression(msg.expr)) }
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
      const { apply, source } = resolvePlugin(mod)
      // M7-5 配置通道:先求值 config 里的 !!js 标记值,再按插件 static Config 默认化。
      // M8:求值 scope 带 ctx —— web-app bundle 的 config 表达式读 ctx.webStartup.*
      // (web-startup 先 apply 提供该服务;跨 worker 经 M7-8 provide 句柄化可读)。
      // 两者失败都回退原 config(带日志),不因默认化失败挂掉加载。
      let config = evalJsMarkers(deserializeValue(msg.config), ctx)
      config = defaultConfig(getPluginConfig(mod), config)
      // cordis 类插件语义(M6-5b):Service 子类(如 @deepseek-ai/dsh-system-prompt 的
      // SystemPrompt 默认导出)经 `new Plugin(ctx, config)` 实例化 —— 构造器里
      // super(ctx, name) 把服务注册进 Java 核心。普通函数/ {apply} 插件照旧 apply(ctx, config)。
      let result
      if (apply === source && await isServiceClass(source)) {
        result = new source(ctx, config)
        // M8:Service.init 生命周期 —— cordis 在 apply 后跑插件的 [Service.init](symbol
        // init,如 webserver 的 listen 绑定端口)。不跑则 Service 的服务值(如 webServer.port)
        // 永不就绪。await 其 async 结果。
        const init = result && typeof result === 'object' && result[Symbol.for('cordis.init')]
        if (typeof init === 'function') {
          const initResult = init.call(result)
          if (initResult && typeof initResult.then === 'function') await initResult
        }
      } else {
        result = apply(ctx, config)
      }
      if (result && typeof result.then === 'function') result = await result   // macrotask 可 settle
      return { type: 'result', id: msg.id, value: serializeValue(result) }
    }
    case 'invokeFn': {
      const fn = deserializeValue(msg.handle)
      if (typeof fn !== 'function') throw new Error('unknown fn handle ' + JSON.stringify(msg.handle))
      const args = (msg.args || []).map(deserializeValue)
      let result = fn(...args)
      if (result && typeof result.then === 'function') result = await result   // macrotask 可 settle
      // M7-7:方法返回上下文 —— 返回值里的 live 对象 / iterable 句柄化(递归)。
      return { type: 'result', id: msg.id, value: serializeValue(result, undefined, { liveHandles: true }) }
    }
    case 'invokeObj': {
      if (typeof msg.handle !== 'number') throw new Error('bad obj handle ' + JSON.stringify(msg.handle))
      const obj = objById.get(msg.handle)
      if (obj === undefined) throw new Error('unknown obj handle ' + msg.handle)
      const method = msg.method
      if (typeof method !== 'string' || method.length === 0) throw new Error('bad obj method')
      const fn = obj[method]
      if (typeof fn !== 'function') throw new Error('no method "' + method + '" on obj handle ' + msg.handle)
      const args = (msg.args || []).map(deserializeValue)
      let result = fn.apply(obj, args)
      if (result && typeof result.then === 'function') result = await result   // macrotask 可 settle
      // M7-7:live 对象方法返回的嵌套 live 对象同样递归句柄化。
      return { type: 'result', id: msg.id, value: serializeValue(result, undefined, { liveHandles: true }) }
    }
    case 'invokeGet': {
      // M7-7 发射器形状补齐:live 对象属性读(含 getter —— JS 属性访问本身触发 accessor)。
      // 补齐方法之外的成员形状:子发射器(session.events)、getter 派生值(config/sandboxMode)、
      // 数据字段(id)。结果递归句柄化:live 子对象 / iterable → 句柄(Java 得 RemoteObject /
      // JsIterable 订阅/遍历),函数 → fn 句柄,普通值 → JSON。
      if (typeof msg.handle !== 'number') throw new Error('bad obj handle ' + JSON.stringify(msg.handle))
      const obj = objById.get(msg.handle)
      if (obj === undefined) throw new Error('unknown obj handle ' + msg.handle)
      const prop = msg.prop
      if (typeof prop !== 'string' || prop.length === 0) throw new Error('bad obj property')
      return { type: 'result', id: msg.id, value: serializeValue(obj[prop], undefined, { liveHandles: true }) }
    }
    case 'invokeMembers': {
      // M7-8:live 对象成员描述符(name → 'function'/'value')。JS 侧服务代理据此分辨
      // 方法(可调用)与 getter/数据字段(直接读值)。遍历原型链收集字符串成员;
      // 原型上的 accessor(get/set)归为 'value',函数归为 'function',数据字段归为 'value'。
      if (typeof msg.handle !== 'number') throw new Error('bad obj handle ' + JSON.stringify(msg.handle))
      const obj = objById.get(msg.handle)
      if (obj === undefined) throw new Error('unknown obj handle ' + msg.handle)
      const seen = new Map()   // name → { type, async }
      for (let o = obj; o !== null && o !== Object.prototype; o = Object.getPrototypeOf(o)) {
        for (const n of Object.getOwnPropertyNames(o)) {
          if (n === 'constructor' || seen.has(n)) continue
          const d = Object.getOwnPropertyDescriptor(o, n)
          let type = 'function'   // 未知成员默认方法(兼容 M7-7:一切可调用)
          let asyncFlag = false
          if (d) {
            if (typeof d.value === 'function') {
              type = 'function'
              // M11-8:async 方法标记 —— 读方服务代理据此走 asyncBridgeCall(不 park 事件循环),
              // 防跨 worker 同步互等死锁(agentPresets.resolve/mount 是 async JS 方法)。
              asyncFlag = d.value.constructor && d.value.constructor.name === 'AsyncFunction'
            } else if (typeof d.get === 'function' || typeof d.set === 'function') type = 'value'
            else type = 'value'
          }
          seen.set(n, { type, async: asyncFlag })
        }
      }
      const out = []
      for (const [name, meta] of seen) out.push({ name, type: meta.type, async: meta.async })
      return { type: 'result', id: msg.id, value: out }
    }
    case 'rehandleObj': {
      // M7-8:Java 把本地 obj 句柄迁到全局 id(跨 worker 服务路由的句柄空间)。
      // 提供方 worker 把条目从 from 移到 to,后续跨 worker 调用(经全局 id)路由回本 worker。
      if (typeof msg.from !== 'number' || typeof msg.to !== 'number') throw new Error('bad rehandleObj')
      const v = objById.get(msg.from)
      if (v !== undefined) {
        objById.delete(msg.from)
        objById.set(msg.to, v)
      }
      return { type: 'result', id: msg.id, value: null }
    }
    case 'rehandleFn': {
      // M7-8:跨 worker 回调参数 —— Java 把本地 fn 句柄迁到全局 id(与 rehandleObj 同构),
      // 使属主 worker 的 fnById 在全局 id 下仍能命中,其它 worker 经 Java 路由回来执行。
      if (typeof msg.from !== 'number' || typeof msg.to !== 'number') throw new Error('bad rehandleFn')
      const fn = fnById.get(msg.from)
      if (typeof fn === 'function') {
        fnById.delete(msg.from)
        fnById.set(msg.to, fn)
      }
      return { type: 'result', id: msg.id, value: null }
    }
    case 'release': {
      if (msg.handle && typeof msg.handle === 'object' && msg.handle.$kind === 'fn') fnById.delete(msg.handle.id)
      else if (typeof msg.handle === 'number') fnById.delete(msg.handle)
      return { type: 'result', id: msg.id, value: null }
    }
    case 'releaseObj': {
      if (typeof msg.handle === 'number') objById.delete(msg.handle)
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
    // Java 对某次桥调用的回复。同步泵(syncBridgeCall)在泵内按 id 直接消费当前请求的回复;
    // 主循环到达的 ctxResult 是**异步桥调用(asyncBridgeCall)**的回复 → resolve 对应 waiter;
    // 无 waiter(迟到/多余)忽略。
    resolveAsyncWaiter(msg)
    return
  }
  if (msg.type === 'result' || msg.type === 'error') {
    return // Java 主动发来的 result/error 是 unsolicited(回复应由 worker 发)→ hostile,忽略
  }
  dispatchRequest(msg)
}

/** fire-and-forget 分发一个 Java → worker 请求(处理是 async,完成时发回复)。 */
// apply 深度:插件 apply 期间(加载期),参数含 async fn 的跨 worker 调用(投影 register 的
// apply/init 标 async 后)保持同步 —— 加载期 register 若异步,registerAll(apply)等不到注册
// 完成 → 卡。运行时(apply 外)才走 asyncBridgeCall(破对话互等)。
let applyDepth = 0
function dispatchRequest(msg) {
  if (msg.type === 'apply') applyDepth++
  handleRequest(msg).then(
    (resp) => { if (msg.type === 'apply') applyDepth--; if (resp) send(resp) },
    (e) => { if (msg.type === 'apply') applyDepth--; send({ type: 'error', id: typeof msg.id === 'number' ? msg.id : undefined, message: String(e && e.stack ? e.stack : e) }) },
  )
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
