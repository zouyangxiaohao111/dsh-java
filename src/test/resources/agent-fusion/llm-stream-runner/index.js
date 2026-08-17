/**
 * M5 NEEDS — llm-stream-runner: the real fetch+SSE transport subprocess.
 *
 * The NodeWorkerJsHost bridge is a SYNCHRONOUS host (its runner main loop
 * blocks on stdin reads), so real network I/O (macrotask `fetch`) can never
 * settle inside it — verified at M5-NEEDS-llm design time. This subprocess is
 * the async side of the seam: a standalone Node process that runs the REAL
 * @deepseek-ai/dsh-llm LlmRuntime + REAL @deepseek-ai/dsh-llm-deepseek
 * DeepSeekAdapter against a configured endpoint, with REAL fetch and REAL
 * SSE parsing (eventsource-parser). Chunks stream back to the caller over
 * stdout NDJSON.
 *
 * Protocol (stdin NDJSON → stdout NDJSON):
 *   in  {"type":"init","id":n,"config":{baseURL,apiKeyEnv,apiKey,...}}
 *   out {"type":"ok","id":n,"providers":["deepseek-official"],...}
 *   in  {"type":"stream","id":n,"options":{provider,model,messages,...}}
 *   out {"type":"chunk","id":n,"chunk":{...}}   (one per StreamChunk)
 *   out {"type":"done","id":n}                    (stream completed)
 *   out {"type":"error","id":n,"message":...}     (stream failed)
 *   in  {"type":"close"}
 *
 * <p>Local ctx: this process is NOT bridged to Java — it builds a minimal
 * in-process cordis-like ctx covering exactly the seams the real llm packages
 * touch (provide/get/effect/events.dispatch/waterfall/on/once/inject/logger),
 * mounts a REAL @deepseek-ai/dsh-settings SettingsProvider subclass over a
 * document and a REAL @deepseek-ai/dsh-credentials CredentialProvider subclass
 * (env-backed), then calls the REAL llm-deepseek {@code apply()} — so the
 * settings section, the credential seam, and the adapter registration are all
 * the real code. The adapter's {@code stream()} then runs REAL
 * {@code fetch(`${baseURL}/chat/completions`)} + {@code parseSse}
 * (eventsource-parser) + {@code translate}.
 *
 * @module @dsh-java/llm-stream-runner
 */

import { readSync } from 'node:fs'
import LlmRuntime, {
  createUserMessage,
  LlmError,
} from '@deepseek-ai/dsh-llm'
import { apply as applyDeepSeek } from '@deepseek-ai/dsh-llm-deepseek'
import { CredentialProvider } from '@deepseek-ai/dsh-credentials'
import { SettingsProvider, settingsNamespace } from '@deepseek-ai/dsh-settings'

// ---------------------------------------------------------------------------
// Minimal in-process cordis-like ctx (seam surface only)
// ---------------------------------------------------------------------------

/**
 * Build the local context the real llm packages run against. This is NOT a
 * cordis core — it is the subset the packages touch, with the same observable
 * contracts (Service.provide/get registration, generator effects collecting
 * disposers, events.dispatch folding, waterfall chaining).
 */
function createRunnerContext() {
  const services = new Map()
  const hooks = new Map() // event name → listener list
  const disposers = []

  const subscribe = (name, listener, opts = {}) => {
    const list = hooks.get(name) ?? []
    if (opts.prepend) list.unshift(listener)
    else list.push(listener)
    hooks.set(name, list)
    let disposed = false
    const dispose = () => {
      if (disposed) return
      disposed = true
      const current = hooks.get(name)
      if (current !== undefined) {
        const idx = current.indexOf(listener)
        if (idx >= 0) current.splice(idx, 1)
      }
    }
    disposers.push(dispose)
    return dispose
  }

  const logger = () => ({ error: () => {}, warn: () => {}, info: () => {}, debug: () => {} })
  logger.error = logger().error
  logger.warn = logger().warn
  logger.info = logger().info
  logger.debug = logger().debug

  const ctx = {
    provide(name, value) {
      services.set(name, value)
    },
    get(name) {
      if (services.has(name)) return services.get(name)
      // The real plugin reads optional services as undefined (ctx.get is
      // callable without a declared inject in worker-local hosts).
      return undefined
    },
    // cordis effect semantics: run the body now, collect disposers (generator
    // or function), register them for teardown.
    effect(body, _label) {
      const stepped = typeof body === 'function' ? body() : body
      const collected = []
      if (stepped && typeof stepped.next === 'function' && typeof stepped[Symbol.iterator] === 'function') {
        const iterator = stepped
        while (true) {
          const result = iterator.next()
          if (typeof result.value === 'function') collected.push(result.value)
          if (result.done) break
        }
      } else if (typeof stepped === 'function') {
        collected.push(stepped)
      }
      const dispose = () => {
        for (let i = collected.length - 1; i >= 0; i--) {
          try { collected[i]() } catch (_e) { /* contained */ }
        }
      }
      disposers.push(dispose)
      return dispose
    },
    events: {
      dispatch(mode, args) {
        const local = Array.from(args)
        let thisArg = local.length > 0
          && (typeof local[0] === 'object' || typeof local[0] === 'function')
          ? local.shift() : null
        const name = local.shift()
        const list = hooks.get(name) ?? []
        const callbacks = list.map(hook => hook.callback.bind(thisArg))
        if (mode === 'emit') return callbacks
        // serial: fold in order, first defined result wins.
        let result
        for (const cb of callbacks) {
          result = cb(...local)
          if (result != null && result !== false) break
        }
        return result
      },
    },
    on(name, listener, opts) {
      return subscribe(name, listener, opts)
    },
    once(name, listener, opts) {
      let disposed = false
      const wrapper = (...args) => {
        dispose()
        return listener(...args)
      }
      const dispose = subscribe(name, wrapper, opts)
      return dispose
    },
    waterfall(target, name, ...args) {
      const next = args[args.length - 1]
      const payload = args.slice(0, -1)
      const list = hooks.get(name) ?? []
      const call = (idx) => {
        if (idx >= list.length) return next(...payload)
        return list[idx].callback.call(target, ...payload, () => call(idx + 1))
      }
      return call(0)
    },
    inject(deps, callback) {
      const names = Array.isArray(deps) ? deps : Object.keys(deps || {})
      const sctx = {}
      let allPresent = true
      for (const name of names) {
        const value = services.has(name) ? services.get(name) : undefined
        if (value === undefined) { allPresent = false; break }
        sctx[name] = value
      }
      if (!allPresent) return undefined
      sctx.effect = (...args) => ctx.effect(...args)
      sctx.get = (name) => ctx.get(name)
      return callback(sctx)
    },
    logger,
  }
  // Service-name reads resolve through the local store (`ctx.llm`,
  // `ctx.settings`, `ctx.credentials`), mirroring cordis' proxy context.
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
  return { ctx: proxy, disposers }
}

// ---------------------------------------------------------------------------
// Real providers mounted on the local ctx
// ---------------------------------------------------------------------------

/** Env-backed credential provider: resolves the configured apiKey for any ref. */
class EnvCredentialProvider extends CredentialProvider {
  constructor(ctx, apiKey) {
    super(ctx)
    this.apiKey = apiKey
  }

  resolve(ref) {
    return Promise.resolve(this.apiKey ? { value: this.apiKey, source: 'test-env' } : undefined)
  }

  describe(ref) {
    return Promise.resolve({ configured: !!this.apiKey, writable: false })
  }

  set() { return Promise.reject(new Error('llm-stream-runner: read-only credentials')) }
  unset() { return Promise.resolve() }
}

/** In-memory document settings provider (real dsh-settings provider). */
class DocSettingsProvider extends SettingsProvider {
  constructor(ctx, doc) {
    super(ctx)
    this.doc = structuredClone(doc)
  }

  get writable() { return true }
  load() { return Promise.resolve(structuredClone(this.doc)) }
  persist(ns, section) { this.doc[ns] = structuredClone(section); return Promise.resolve() }
}

// ---------------------------------------------------------------------------
// Runner main loop
// ---------------------------------------------------------------------------

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

function lineReader() {
  let buf = Buffer.alloc(0)
  return () => {
    let idx
    while ((idx = buf.indexOf(10)) < 0) {
      const chunk = Buffer.alloc(1 << 16)
      const n = readSync(0, chunk, 0, chunk.length, null)
      if (n === 0) return undefined
      buf = Buffer.concat([buf, chunk.subarray(0, n)])
    }
    const line = buf.subarray(0, idx).toString('utf8').replace(/\r$/, '')
    buf = buf.subarray(idx + 1)
    return line
  }
}

/** The real messages array for one stream request. */
function buildMessages(text) {
  return [
    createUserMessage({
      content: [{ type: 'text', text }],
      source: { kind: 'plugin', plugin: 'llm-stream-runner' },
    }),
  ]
}

async function main() {
  const { ctx, disposers } = createRunnerContext()
  const readLine = lineReader()
  let llm
  let initialized = false

  while (true) {
    const line = readLine()
    if (line === undefined) break // stdin EOF
    let msg
    try {
      msg = JSON.parse(line)
    } catch (_e) {
      send({ type: 'error', message: 'llm-stream-runner: malformed json' })
      continue
    }
    const id = typeof msg.id === 'number' ? msg.id : undefined

    try {
      if (msg.type === 'init') {
        if (initialized) throw new Error('llm-stream-runner already initialized')
        const config = msg.config ?? {}

        // Real credentials + real settings providers, mounted before the
        // llm-deepseek apply so its ctx.get('credentials') / ctx.inject
        // (['settings']) both resolve.
        const credentials = new EnvCredentialProvider(ctx, config.apiKey)
        ctx.provide('credentials', credentials)
        const settings = new DocSettingsProvider(ctx, {})
        ctx.provide('settings', settings)
        // The adapter's baseURL is explicit config; launch environment falls
        // back to the inherited process env (no 'launchEnvironment' slot).
        settings.publish({})

        // REAL LlmRuntime: Service super() provides 'llm' into the local ctx.
        llm = new LlmRuntime(ctx)
        // REAL llm-deepseek apply: resolveAdapterOptions + registerAdapter +
        // installSettingsSection all run here.
        applyDeepSeek(ctx, config)
        initialized = true
        send({
          type: 'ok',
          id,
          providers: llm.listProviders().map(p => p.id),
          llmRegistered: ctx.get('llm') === llm,
        })
        continue
      }

      if (msg.type === 'stream') {
        if (!initialized) throw new Error('llm-stream-runner: stream before init')
        const options = msg.options ?? {}
        const request = {
          provider: options.provider ?? 'deepseek-official',
          model: options.model ?? 'deepseek-v4-flash',
          messages: options.messages ?? buildMessages(options.prompt ?? 'ping'),
          ...options.system !== undefined ? { system: options.system } : {},
          ...options.maxTokens !== undefined ? { maxTokens: options.maxTokens } : {},
        }
        let chunks = 0
        try {
          const stream = llm.stream(request)
          for await (const chunk of stream) {
            send({ type: 'chunk', id, chunk })
            chunks++
          }
          send({ type: 'done', id, chunks })
        } catch (error) {
          send({ type: 'error', id, message: errorChainMessage(error) })
        }
        continue
      }

      if (msg.type === 'probe') {
        // Introspection surface the driver/Java asserts on.
        send({
          type: 'ok',
          id,
          providers: llm ? llm.listProviders().map(p => p.id) : [],
          listModels: llm ? await llm.listModels('deepseek-official') : [],
        })
        continue
      }

      if (msg.type === 'close') {
        for (let i = disposers.length - 1; i >= 0; i--) { try { disposers[i]() } catch (_e) {} }
        send({ type: 'ok', id })
        process.exit(0)
      }

      throw new Error('llm-stream-runner: unknown message type ' + msg.type)
    } catch (error) {
      send({ type: 'error', id, message: errorChainMessage(error) })
    }
  }
}

/** Render the thrown value with its cause chain (diagnostic only). */
function errorChainMessage(error) {
  if (error instanceof LlmError) return `${error.name}[${error.code}]: ${error.message}`
  return String(error && error.stack ? error.stack : error)
}

main().catch((error) => {
  send({ type: 'error', message: errorChainMessage(error) })
})
