/**
 * M5 NEEDS — llm-driver: mount the REAL @deepseek-ai/dsh-llm LlmRuntime and
 * REAL @deepseek-ai/dsh-llm-deepseek adapter inside the NodeWorkerJsHost
 * worker, and stream through the REAL fetch+SSE transport subprocess
 * (llm-stream-runner).
 *
 * The bridge worker is a SYNCHRONOUS host: its runner main loop blocks on
 * stdin reads, so a real `fetch` (macrotask) can never settle inside it
 * (verified at M5-NEEDS-llm design time). The honest split:
 *
 *   - REGISTRATION / CONFIG / SETTINGS / CREDENTIALS run IN THE WORKER with
 *     the real package code: the real LlmRuntime (Service provides 'llm'
 *     into the Java core), the real llm-deepseek `apply()` (resolveAdapterOptions
 *     + registerAdapter + installSettingsSection against a real dsh-settings
 *     SettingsProvider subclass), the real credentialRef seam (a real
 *     CredentialProvider subclass) and the real anonymous-user-id.
 *   - THE NETWORK runs in a subprocess: `streamReal` spawns
 *     llm-stream-runner/index.js (real LlmRuntime + real DeepSeekAdapter +
 *     real fetch to the configured endpoint + real SSE parse) and returns the
 *     real StreamChunks to Java.
 *
 * <p>Honest boundaries: calling `ctx.get('llm').stream(...)` directly on the
 * worker would hit the sync-host macrotask limit (fetch); Java drives the
 * stream through this driver's `streamReal` probe instead.
 *
 * @module @dsh-java/llm-driver
 */

import { spawnSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import LlmRuntime from '@deepseek-ai/dsh-llm'
import { apply as applyDeepSeek, resolveAdapterOptions } from '@deepseek-ai/dsh-llm-deepseek'
import { CredentialProvider } from '@deepseek-ai/dsh-credentials'
import { SettingsProvider } from '@deepseek-ai/dsh-settings'

/** Cordis plugin name. */
export const name = 'llm-driver'

/** The real LlmRuntime provides 'llm' into the Java core at construction. */
export const provide = ['llm']

/** A worker-local fiber whose terminating chain never crosses the bridge. */
function makeFiber() {
  const fiber = { state: 'active' }
  Object.defineProperty(fiber, 'parent', { value: { fiber }, enumerable: false })
  return fiber
}

/**
 * Hybrid context: worker-local service slots (credentials / settings / llm)
 * resolve first, everything else delegates to the bridge ctx (Java core).
 */
function makeHybridCtx(ctx) {
  const local = new Map()
  const hybrid = {
    on: (name, listener, opts) => ctx.on(name, listener, opts),
    once: (name, listener, opts) => ctx.once(name, listener, opts),
    emit: (...args) => ctx.emit(...args),
    provide: (name, value) => ctx.provide(name, value),
    get(name) {
      if (local.has(name)) return local.get(name)
      try {
        return ctx.get(name)
      } catch (error) {
        // The Java core's strict get rejects undeclared names; the real code
        // reads optional services (launchEnvironment) as undefined.
        if (String(error && error.message).includes('without inject')) return undefined
        throw error
      }
    },
    effect: (...args) => ctx.effect(...args),
    inject(deps, callback) {
      const names = Array.isArray(deps) ? deps : Object.keys(deps || {})
      const sctx = {}
      let allPresent = true
      for (const name of names) {
        let value
        if (local.has(name)) value = local.get(name)
        else {
          try { value = ctx.get(name) } catch (error) {
            if (String(error && error.message).includes('without inject')) value = undefined
            else throw error
          }
        }
        if (value === undefined) { allPresent = false; break }
        sctx[name] = value
      }
      if (!allPresent) return undefined
      sctx.effect = (...args2) => ctx.effect(...args2)
      sctx.get = (n) => hybrid.get(n)
      return callback(sctx)
    },
    events: ctx.events,
    waterfall: (target, name, ...args) => ctx.waterfall(target, name, ...args),
    serial: (target, name, ...args) => ctx.serial(target, name, ...args),
    logger: ctx.logger,
  }
  const proxy = new Proxy(hybrid, {
    get(target, prop, receiver) {
      if (prop in target) return Reflect.get(target, prop, receiver)
      if (typeof prop === 'symbol') return undefined
      if (local.has(prop)) return local.get(prop)
      try {
        return ctx[String(prop)]
      } catch (error) {
        if (String(error && error.message).includes('without inject')) return undefined
        throw error
      }
    },
    has(target, prop) {
      return typeof prop === 'symbol' ? Reflect.has(target, prop) : true
    },
  })
  return { hybrid: proxy, hide: (name, value) => local.set(name, value) }
}

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

  set() { return Promise.reject(new Error('llm-driver: read-only credentials')) }
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

/**
 * Spawn the real fetch+SSE subprocess for one stream request and collect its
 * real StreamChunks. spawnSync is safe on this sync host: it blocks the worker
 * main loop (the runner is a separate process with its own async event loop).
 */
function runRunner(baseURL, apiKey, options) {
  const driverDir = dirname(fileURLToPath(import.meta.url))
  const runnerFile = join(driverDir, '..', 'llm-stream-runner', 'index.js')
  const overlayRoot = join(driverDir, '..')
  const init = JSON.stringify({ type: 'init', id: 1, config: { baseURL, apiKeyEnv: 'DEEPSEEK_API_KEY', apiKey } })
  const stream = JSON.stringify({ type: 'stream', id: 2, options })
  const input = init + '\n' + stream + '\n' + JSON.stringify({ type: 'close', id: 3 }) + '\n'
  const result = spawnSync('node', [runnerFile], {
    cwd: overlayRoot,
    input,
    encoding: 'utf8',
    timeout: 30_000,
    maxBuffer: 64 * 1024 * 1024,
  })
  if (result.error) throw new Error('llm-stream-runner failed: ' + result.error)
  if (result.status !== 0) throw new Error(`llm-stream-runner exited ${result.status}: ${result.stderr || ''}`)
  const chunks = []
  let completed = false
  for (const line of String(result.stdout).split('\n')) {
    if (!line.trim()) continue
    let msg
    try { msg = JSON.parse(line) } catch (_e) { continue }
    if (msg.id !== 2) continue
    if (msg.type === 'chunk') chunks.push(msg.chunk)
    else if (msg.type === 'done') completed = true
    else if (msg.type === 'error') throw new Error('llm-stream-runner: ' + msg.message)
  }
  if (!completed) throw new Error('llm-stream-runner did not complete the stream')
  return chunks
}

/**
 * Fuse the real llm machinery with the Java core.
 * @param ctx - registrant context (bridge proxy back to the Java core).
 * @param config - driver config: `{ baseURL, apiKeyEnv?, apiKey? }`.
 */
export function apply(ctx, config = {}) {
  config = config ?? {}
  const baseURL = config.baseURL
  const apiKey = config.apiKey

  const { hybrid, hide } = makeHybridCtx(ctx)

  // Worker-local real credential seam + real settings provider, mounted BEFORE
  // the real llm-deepseek apply so ctx.get('credentials') and
  // ctx.inject(['settings']) both resolve locally.
  const credentials = new EnvCredentialProvider(hybrid, apiKey)
  hide('credentials', credentials)
  const settings = new DocSettingsProvider(hybrid, {})
  hide('settings', settings)
  settings.publish({})

  // The REAL LlmRuntime: Service super() provides 'llm' into the Java core;
  // the hybrid ctx also resolves ctx.llm to this instance locally.
  const llm = new LlmRuntime(hybrid)
  hide('llm', llm)

  // The REAL llm-deepseek apply: resolveAdapterOptions + registerAdapter +
  // installSettingsSection (against the local real settings provider).
  applyDeepSeek(hybrid, config)

  const probe = {
    serviceName: llm.name,
    providers: llm.listProviders().map(p => p.id),
    // Real resolveAdapterOptions surface (validated connection facts).
    resolveAdapterOptions: (cfg) => resolveAdapterOptions(cfg ?? {}),
    // Real adapter discovery surface.
    listModels: () => llm.listModels('deepseek-official'),
    // The REAL network path: runner subprocess does fetch+SSE, returns chunks.
    streamReal: (options) => runRunner(baseURL, apiKey, options ?? {}),
    // The real credential seam resolves the test key.
    credentialsResolved: (ref) => credentials.resolve(ref),
    settingsRegistered: settings.registrations.has('llm-deepseek'),
  }
  ctx.provide('llmProbe', probe)
  return probe
}
