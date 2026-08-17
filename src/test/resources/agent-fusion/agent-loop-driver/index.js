/**
 * M4 agent-loop fusion driver for @deepseek-ai/dsh-agent-loop (core/agent-loop).
 *
 * Runs inside the NodeWorkerJsHost worker. It fuses the REAL agent-loop
 * machine (the {@code AgentLoop} factory service + {@code ReactLoopAgent}
 * turn/step driver) with the JAVA core:
 *
 *   1. Instantiates the real services against a HYBRID loopCtx: the
 *      machine-critical seams stay worker-local REAL instances (AgentRegistry /
 *      SessionStore / SystemPrompt / the REAL @deepseek-ai/dsh-tools
 *      {@code ToolRuntime} / the agentLoop service), so the live Agent/Session
 *      never lose their prototype methods. The generic cordis surface
 *      (on/emit/events/serial/waterfall/effect/inject/provide/get) delegates to
 *      the bridge ctx, so events/registrations still land in the Java core.
 *   2. Event payloads that carry the live Agent / Session are SANITIZED at the
 *      bridge boundary: the worker's events.dispatch / serial overrides send a
 *      lightweight envelope ({id,status,sessionId} / {id,eventCount}) to Java
 *      for listener resolution and Java-native listeners, while JS listeners
 *      folded locally still receive the ORIGINAL live objects. Without this,
 *      the bridge's serializeValue would throw on the Agent/Session object
 *      graph (shared references treated as cycles). The real ToolRuntime's
 *      execution carries a Symbol correlation token, which is likewise dropped
 *      at this boundary.
 *   3. {@code ctx.llm} is the JAVA-provided seam: {@code stream(request)}
 *      returns a materialized chunk array (the machine's {@code for await}
 *      iterates it); {@code prepareCall} returns a PreparedLlmCall whose
 *      {@code stream} serves Java's chunks (no real adapter registration);
 *      {@code resolveModelInfo} delegates.
 *   4. Constructs {@code new AgentLoop(loopCtx, {agents: []})} and calls
 *      {@code agentLoop.create(id, {provider, model})}, publishing a live
 *      {@code ReactLoopAgent} (session entered/announced, agent announced,
 *      'agent/session-start' emitted) — all observable from Java.
 *   5. Exposes probe fns Java invokes: {@code runTurn(text)} triggers one
 *      agent turn (followup + whenIdle, settled via the sync host's microtask
 *      pump), {@code getSessionLog()} returns a plain per-event summary,
 *      {@code getAgentSnapshot()} returns live status, and
 *      {@code callResolveModelInfo()} exercises the llm seam.
 *
 * <p><b>Honest boundaries</b>: the {@code llm} seam streams canned chunks from
 * Java (no real adapter/streaming transport); {@code settings} is the REAL
 * dsh-settings SettingsProvider when the driver config carries a
 * {@code settingsPath} (M5 NEEDS #3); {@code ctx.tools} is the REAL
 * @deepseek-ai/dsh-tools {@code ToolRuntime} (M5 NEEDS #2) — it provides
 * {@code tools} into the Java core, registers a real {@code echo} tool via
 * {@code register()}, and drives the real pre/guard/around/post/result
 * pipeline in native mode (a {@code ctx.codeRuntime} for Code Mode is a
 * further NEEDS); the hybrid loopCtx keeps the real services worker-local
 * because the bridge cannot carry live prototype-bearing objects (a real
 * cross-realm ctx with live services is a NEEDS).
 *
 * <p>The apply is SYNCHRONOUS: the sync NodeWorkerJsHost runner cannot
 * re-enter the pump from inside an async continuation (nested pumps never
 * settle), so the machine's turn is driven through the {@code runTurn} fn
 * handle (top-level pump on the worker side).
 *
 * @module @dsh-java/agent-loop-driver
 */

import { readFileSync } from 'node:fs'
import AgentRegistry from '@deepseek-ai/dsh-agent'
import AgentLoop from '@deepseek-ai/dsh-agent-loop'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { SessionStore } from '@deepseek-ai/dsh-session'
import { SettingsProvider, settingsNamespace } from '@deepseek-ai/dsh-settings'
import { SystemPrompt } from '@deepseek-ai/dsh-system-prompt'
import { ToolRuntime, defineTool } from '@deepseek-ai/dsh-tools'
import z from '@deepseek-ai/schemastery'

/** Cordis plugin name. */
export const name = 'agent-loop-driver'
/** The agentLoop service this plugin installs is visible to dependents. */
export const provide = ['agentLoop']
/**
 * The Java-provided llm seam this plugin consumes through ctx.get().
 * Declaring it as inject makes the Java core gate this plugin's activation on
 * its availability and populates this fiber's store, so the strict
 * `ctx.get('llm')` in apply() resolves (a sibling plugin's provide is not on
 * this fiber's ancestor chain without inject).
 *
 * `tools` is NOT injected: this driver mounts the REAL `@deepseek-ai/dsh-tools`
 * `ToolRuntime` worker-locally, which provides `ctx.tools` into the Java core
 * itself (M5 NEEDS #2) — the old Java-provided stub is gone.
 */
export const inject = ['llm']

/**
 * Sanitize a value for the bridge boundary: replace live Agent / Session
 * objects with lightweight envelopes, so serializeValue never walks their
 * (shared-reference / self-referential) object graphs.
 * @returns a walker that deep-copies a value with the heavy objects replaced.
 */
function makeSanitizer() {
  const agentLike = (v) => v && typeof v === 'object'
    && typeof v.id === 'string' && typeof v.status === 'string'
    && v.session !== undefined && Array.isArray(v.session?.events)
    && v.ctx !== undefined && v.ctx.agent === v
  const sessionLike = (v) => v && typeof v === 'object'
    && typeof v.id === 'string' && typeof v.append === 'function'
    && Array.isArray(v.events)
  const seen = new WeakSet()
  const walk = (v) => {
    // The real ToolRuntime's execution carries a Symbol correlation token
    // (TOOL_EXECUTION token); serializeValue cannot cross a symbol, so drop it
    // at the bridge boundary (Java never needs the token identity).
    if (typeof v === 'symbol') return undefined
    if (v === null || typeof v !== 'object') return v
    if (agentLike(v)) return { id: v.id, status: v.status, sessionId: v.session?.id }
    if (sessionLike(v)) {
      return {
        id: v.id,
        eventCount: v.events.length,
        eventTypes: v.events.map(e => e.type),
      }
    }
    if (seen.has(v)) return undefined // shared ref: drop (post-sanitization payloads are trees)
    seen.add(v)
    let out
    if (Array.isArray(v)) {
      out = []
      for (const x of v) out.push(walk(x))
    } else {
      out = {}
      for (const k of Object.keys(v)) out[k] = walk(v[k])
    }
    seen.delete(v)
    return out
  }
  return walk
}

/** A worker-local fiber whose terminating chain never crosses the bridge. */
function makeFiber() {
  const fiber = {
    state: 'active',
    assertActive() {
      if (this.state !== 'active') throw new Error('fiber is not active')
    },
  }
  // Self-terminating parent chain (non-enumerable: never serialized).
  Object.defineProperty(fiber, 'parent', { value: { fiber }, enumerable: false })
  return fiber
}

/**
 * Build the hybrid loopCtx the machine runs against.
 * @param ctx - the registrant bridge ctx (events/effect/provide/get).
 * @param deps - { llm, tools, fiber }.
 * @returns { loopCtx, setAgentLoop, hide }.
 */
function makeLoopCtx(ctx, deps) {
  const bridgeEvents = ctx.events
  const bridgeSerial = ctx.serial
  const sanitize = makeSanitizer()
  let agentLoopInstance
  const setAgentLoop = (instance) => { agentLoopInstance = instance }

  const loopCtx = {
    llm: deps.llm,
    fiber: deps.fiber,
    /**
     * Sanitizing emit dispatch. Mirror the bridge seam's caller-visible
     * mutation (strip carrier + name, keep the ORIGINAL payload) so JS
     * listeners folded locally see the live objects; Java resolves listeners
     * and feeds its native listeners the sanitized envelope.
     */
    events: {
      dispatch(mode, args) {
        const original = Array.from(args)
        const sanitized = original.map(sanitize)
        const callbacks = bridgeEvents.dispatch(mode, sanitized)
        args.length = 0
        for (let i = 2; i < original.length; i++) args.push(original[i])
        return callbacks
      },
    },
    /** Sanitizing serial dispatch (the shim serial serializes its args to Java). */
    serial(target, name, ...args) {
      return bridgeSerial.call(null, target, name, ...args.map(sanitize))
    },
    /**
     * Bridge get with real-cordis optional-service semantics: the Java core's
     * strict get rejects undeclared names, but reads like
     * {@code ctx.get(CONFIGURED_AGENT_IDENTITIES_KEY)} expect undefined.
     */
    get(name) {
      try {
        return ctx.get(name)
      } catch (error) {
        if (String(error && error.message).includes('without inject')) return undefined
        throw error
      }
    },
    /**
     * Worker-local ctx.extend: the agent subject is opaque/self-referential
     * and must NOT be enumerable (never serialized directly).
     */
    extend(meta) {
      const child = Object.create(this)
      for (const key of Reflect.ownKeys(meta)) {
        if (key === 'agent') {
          Object.defineProperty(child, key, { value: meta[key], enumerable: false, configurable: true, writable: true })
        } else {
          child[key] = meta[key]
        }
      }
      return child
    },
    /** Minimal Cordis plugin seam backing dsh-scope's createScope. */
    plugin(pluginFn) {
      const child = this.extend({})
      if (typeof pluginFn === 'function') pluginFn(child)
      return { ctx: child, dispose: () => Promise.resolve(), inertia: undefined }
    },
    /**
     * Worker-local {@code ctx.inject}: resolves deps from worker-local (hidden)
     * service slots first, then the bridge get; fires the callback only when
     * every dep is present (cordis gating). The scoped ctx hands the resolved
     * services plus the minimal ctx surface the real dsh-settings
     * {@code installSettingsSection} consumes ({@code effect}, {@code get}).
     * With no settings provider mounted, {@code ['settings']} never resolves and
     * the section wiring stays inert — exactly the pre-M5 behavior.
     */
    inject(deps, callback) {
      const names = Array.isArray(deps) ? deps : Object.keys(deps || {})
      const sctx = {}
      let allPresent = true
      for (const name of names) {
        let value
        if (Object.hasOwn(this, name)) {
          value = this[name]
        } else {
          try { value = ctx.get(name) } catch (error) {
            if (String(error && error.message).includes('without inject')) value = undefined
            else throw error
          }
        }
        if (value === undefined) { allPresent = false; break }
        sctx[name] = value
      }
      if (!allPresent) return undefined
      sctx.effect = (...args) => ctx.effect(...args)
      sctx.get = (name) => this.get(name)
      return callback(sctx)
    },
  }
  /**
   * Hide a worker-local service slot. The services hold `this.ctx === loopCtx`,
   * so an ENUMERABLE `loopCtx.agents === AgentRegistry` would be a
   * serialization cycle the moment `super(ctx, name)` provides the service
   * into Java. Hidden slots stay readable through the proxy.
   */
  const hide = (name, value) => {
    Object.defineProperty(loopCtx, name, { value, enumerable: false, configurable: true, writable: true })
  }
  // tool-calls.ts reads ctx.agentLoop.config.maxParallelToolCalls per group;
  // non-enumerable so serializing the loopCtx (via AgentLoop's provide) never
  // walks back into the AgentLoop instance (a self-reference).
  Object.defineProperty(loopCtx, 'agentLoop', {
    get() { return agentLoopInstance },
    enumerable: false,
    configurable: true,
  })
  const proxy = new Proxy(loopCtx, {
    get(target, prop, receiver) {
      if (prop in target) return Reflect.get(target, prop, receiver)
      if (typeof prop === 'symbol') return undefined
      try {
        return ctx[String(prop)]
      } catch (error) {
        // The Java core's strict get rejects undeclared names; the real cordis
        // reads optional services as undefined. Mirror the optional-read
        // semantics so e.g. ctx.get(CONFIGURED_AGENT_IDENTITIES_KEY) → undefined.
        if (String(error && error.message).includes('without inject')) return undefined
        throw error
      }
    },
    has(target, prop) {
      return typeof prop === 'symbol' ? Reflect.has(target, prop) : true
    },
  })
  return { loopCtx: proxy, setAgentLoop, hide }
}

/**
 * The REAL `echo` tool this driver registers into the real ToolRuntime (M5
 * NEEDS #2). Native mode, synchronous body — the real registry snapshots args,
 * runs the pre-execute/guard gate, dispatches the body, validates the returned
 * value against the declared output schema, renders model content, and routes
 * the result through post-execute — all the real dsh-tools machinery, with no
 * Java stub seam.
 */
function makeEchoTool() {
  return defineTool({
    name: 'echo',
    description: 'Echo the given message back to the caller.',
    parameters: {
      msg: { type: 'string', required: true, description: 'The message to echo.' },
    },
    output: {
      schema: {
        type: 'object',
        // The value schema DSL expresses requiredness per property, not as an
        // object-level `required` array (which the DSL rejects at the root).
        properties: { text: { type: 'string', required: true } },
        additionalProperties: false,
      },
      render: (args, value) => [{ type: 'text', text: String(value.text) }],
    },
    execute: async (args) => ({ text: `echo: ${args.msg}` }),
  })
}

/**
 * Concrete real-settings provider for this host: a JSON document read from the
 * driver-configured test-config path, exposed through the real dsh-settings
 * {@link SettingsProvider} service. Persists back into the in-memory document
 * (reads are the assertion surface; a file-backed provider is a later NEEDS).
 */
class ConfigSettingsProvider extends SettingsProvider {
  constructor(ctx, path, doc) {
    super(ctx)
    this.path = path
    this.doc = structuredClone(doc)
  }

  get writable() {
    return true
  }

  get documentPath() {
    return this.path
  }

  load() {
    return Promise.resolve(structuredClone(this.doc))
  }

  persist(ns, section) {
    this.doc[ns] = structuredClone(section)
    return Promise.resolve()
  }
}

/**
 * Fuse the real agent-loop with the Java core: install the services, build the
 * hybrid loopCtx, create a live ReactLoopAgent, and expose turn/session probes.
 * @param ctx - registrant context (bridge proxy back to the Java core).
 * @param config - driver config: `{ agentId?: string }`.
 */
export function apply(ctx, config = {}) {
  config = config ?? {}
  const agentId = config.agentId ?? 'agent-a'

  // Java-provided seam.
  const llmSvc = ctx.get('llm')

  // Worker-local llm adapter: returns a PreparedLlmCall whose stream serves the
  // Java-provided chunk sequence (the machine's `for await` iterates it).
  const llmAdapter = {
    prepareCall(config, _signal) {
      return { config, stream: (request) => llmSvc.stream(request) }
    },
    stream: (request) => llmSvc.stream(request),
    resolveModelInfo: (provider, model, signal) => llmSvc.resolveModelInfo({ provider, model }),
  }

  // The hybrid loopCtx is built FIRST so the real services construct against it
  // (their own event dispatches then route through the sanitizing seam).
  const fiber = makeFiber()
  const { loopCtx, setAgentLoop, hide } = makeLoopCtx(ctx, {
    llm: llmAdapter,
    fiber,
  })

  // M5 NEEDS #3: real settings backend. When the driver config carries a
  // settingsPath, mount the REAL dsh-settings SettingsProvider subclass over
  // that document and publish it (the cordis shim does not drive
  // Service.init, so load/publish is explicit) BEFORE the AgentLoop is built —
  // the real constructor's installSettingsSection then registers the
  // 'agent-loop' namespace against the live provider and the config value wins.
  let settingsProvider
  if (config.settingsPath) {
    const doc = JSON.parse(readFileSync(config.settingsPath, 'utf8'))
    settingsProvider = new ConfigSettingsProvider(loopCtx, config.settingsPath, doc)
    settingsProvider.publish(structuredClone(doc))
    hide('settings', settingsProvider)
  }

  // Real services (worker-local, bridged to Java for observation).
  const agents = new AgentRegistry(loopCtx)
  const sessions = new SessionStore(loopCtx)
  const systemPrompt = new SystemPrompt(loopCtx, {
    includeHarnessIdentity: true,
    includeRuntimeContext: true,
    persona: 'You are the agent-loop fusion test persona.',
  })
  systemPrompt.section({
    name: 'tools:guidance',
    order: 100,
    text: 'Prefer deterministic tools over free-form reasoning.',
  })
  // Publish the worker-local services on the hybrid (machine reads them live).
  hide('agents', agents)
  hide('sessions', sessions)
  hide('systemPrompt', systemPrompt)

  // M5 NEEDS #2: the REAL @deepseek-ai/dsh-tools ToolRuntime. Constructing it
  // against the hybrid loopCtx provides `ctx.tools` into the Java core (via
  // the cordis shim's super(ctx,'tools') → bridge provide) and wires its
  // schemas into the real SystemPrompt. It is hidden (non-enumerable) so the
  // machine reads it live while the bridge serializer never walks back into it
  // through loopCtx (its `this.ctx === loopCtx` back-reference would cycle).
  const toolRuntime = new ToolRuntime(loopCtx, { mode: 'native' })
  hide('tools', toolRuntime)

  // Register a REAL tool into the real registry (global layer). The returned
  // disposer is intentionally not retained: the bridge effect owns it with the
  // driver fiber, so it is unregistered exactly when this fiber unloads.
  toolRuntime.register(makeEchoTool())
  const registeredToolNames = () => toolRuntime.schemas().map(s => s.name)

  // The real AgentLoop service (constructor registers 'agentLoop' into Java).
  // With settings mounted, the real constructor's installSettingsSection
  // registers the 'agent-loop' namespace against the provider — so
  // config.maxParallelToolCalls now reads through the settings scope.
  const agentLoop = new AgentLoop(loopCtx, {
    agents: [],
    maxParallelToolCalls: 2,
  })
  setAgentLoop(agentLoop)

  // Direct ctx.settings consumption: register a second namespace straight
  // against the live provider (the real dsh-settings register/scope.get), so
  // Java can assert the config document resolved end-to-end.
  let probeSettingsRead = () => ({})
  if (settingsProvider) {
    const probeSchema = z.object({
      temperature: z.number().default(0.5),
      apiKey: z.string().role('secret'),
    })
    const scope = loopCtx.settings.register(settingsNamespace('probe-settings'), probeSchema, {
      base: { temperature: 0.5 },
    })
    probeSettingsRead = () => scope.get()
  }

  // Create + publish a live ReactLoopAgent under a fixed identity.
  const agent = agentLoop.create(agentId, { provider: 'test', model: 'test-model' }, {})

  // Probes.
  const extractText = (message) => {
    const blocks = message && message.content
    if (!Array.isArray(blocks)) return ''
    return blocks.filter(b => b && b.type === 'text').map(b => b.text).join('')
  }
  const runTurn = async (text) => {
    const message = createUserMessage({
      content: [{ type: 'text', text }],
      source: { kind: 'plugin', plugin: 'agent-loop-driver' },
    })
    agent.followup(message)
    await agent.whenIdle()
    return {
      status: agent.status,
      lastTurn: agent.session.events.findLast(e => e.type === 'turn/start')?.data.turn ?? 0,
    }
  }
  const getSessionLog = () => agent.session.events.map((event) => {
    const data = event.data ?? {}
    const out = { type: event.type, seq: event.seq }
    if (data.turn !== undefined) out.turn = data.turn
    if (data.step !== undefined) out.step = data.step
    if (event.type === 'assistant/message') out.text = extractText(data.message)
    if (event.type === 'user/message') out.text = extractText(data)
    if (event.type === 'tool/call') { out.toolName = data.name; out.callId = data.callId }
    if (event.type === 'tool/result') {
      out.callId = data.message?.source?.callId
      // createToolResultMessage wraps the rendered content in a 'tool-result'
      // block: { type:'tool-result', content:[...real blocks...], isError }.
      const block = (data.message?.content ?? []).find(b => b && b.type === 'tool-result')
      const inner = block?.content ?? data.message?.content ?? []
      out.text = (Array.isArray(inner) ? inner : [])
        .filter(b => b && b.type === 'text').map(b => b.text).join('')
      out.isError = block?.isError === true
    }
    if (event.type === 'turn/end') out.reason = data.reason
    if (event.type === 'request/header') out.model = data.header?.config?.model
    return out
  })
  const getAgentSnapshot = () => ({ id: agent.id, status: agent.status })
  const callResolveModelInfo = () => llmSvc.resolveModelInfo({ provider: 'test', model: 'test-model' })

  // Settings probes: the real provider's resolved values cross back to Java.
  // describe() redacts (a wire surface) so the apiKey never leaves the worker
  // verbatim; the raw document read stays available for the assertion surface.
  const getSettingsSnapshot = () => {
    if (!settingsProvider) return { enabled: false }
    const descriptors = settingsProvider.describe({ redactSecrets: true })
    return {
      enabled: true,
      documentPath: settingsProvider.documentPath,
      // The REAL agent-loop wiring: installSettingsSection pointed the
      // maxParallelToolCalls getter at the settings scope, so this reflects the
      // test-config value (4) — not the constructor entry (2) or schema default (10).
      resolvedMaxParallelToolCalls: agentLoop.config.maxParallelToolCalls,
      registeredNamespaces: [...settingsProvider.registrations.keys()],
      describe: descriptors.map((d) => ({
        ns: d.ns,
        value: d.value,
        schema: d.schema,
        revision: d.revision,
        applies: d.applies,
        ...d.base === undefined ? {} : { base: d.base },
        ...d.user === undefined ? {} : { user: d.user },
        ...d.secrets === undefined ? {} : { secrets: d.secrets },
      })),
      document: settingsProvider.document,
    }
  }
  const updateSettings = async (ns, patch) => {
    await settingsProvider.update(ns, patch)
    return settingsProvider.get(ns)
  }

  // M5 NEEDS #2 probes: drive the REAL ctx.tools directly (bypassing the
  // agent-loop). The real pipeline snapshots + freezes args, runs the ordered
  // pre-execute gate + monotonic guards, dispatches the registered body,
  // validates the returned value against the declared output schema, renders
  // model content, runs post-execute, and materializes the final frozen result.
  const executeTool = async (name, args) => {
    const controller = new AbortController()
    const result = await toolRuntime.execute({
      callId: `direct-${name}`,
      name,
      arguments: args ?? {},
      signal: controller.signal,
      agent,
    })
    return {
      isError: result.isError,
      ...result.value !== undefined ? { value: result.value } : {},
      content: result.content,
      ...result.error !== undefined ? { error: result.error.message } : {},
    }
  }

  const probe = {
    serviceName: agentLoop.name,
    agentId,
    runTurn,
    getSessionLog,
    getAgentSnapshot,
    callResolveModelInfo,
    llmProvided: typeof loopCtx.llm.stream === 'function',
    toolsProvided: typeof toolRuntime.execute === 'function',
    toolNames: registeredToolNames(),
    executeTool,
    settingsEnabled: !!settingsProvider,
    getSettingsSnapshot,
    updateSettings,
    probeSettingsRead,
  }
  ctx.provide('agentLoopProbe', probe)
  return probe
}
