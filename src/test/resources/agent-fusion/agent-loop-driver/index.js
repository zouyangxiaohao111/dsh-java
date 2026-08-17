/**
 * M4 agent-loop fusion driver for @deepseek-ai/dsh-agent-loop (core/agent-loop).
 *
 * Runs inside the NodeWorkerJsHost worker. It fuses the REAL agent-loop
 * machine (the {@code AgentLoop} factory service + {@code ReactLoopAgent}
 * turn/step driver) with the JAVA core:
 *
 *   1. Instantiates the real services against a HYBRID loopCtx: the
 *      machine-critical seams stay worker-local REAL instances (AgentRegistry /
 *      SessionStore / SystemPrompt / the tools scheduler keyed by the real
 *      {@code TOOL_RUNTIME_SCHEDULER} symbol / the agentLoop service), so the
 *      live Agent/Session never lose their prototype methods. The generic
 *      cordis surface (on/emit/events/serial/waterfall/effect/inject/provide/
 *      get) delegates to the bridge ctx, so events/registrations still land in
 *      the Java core.
 *   2. Event payloads that carry the live Agent / Session are SANITIZED at the
 *      bridge boundary: the worker's events.dispatch / serial overrides send a
 *      lightweight envelope ({id,status,sessionId} / {id,eventCount}) to Java
 *      for listener resolution and Java-native listeners, while JS listeners
 *      folded locally still receive the ORIGINAL live objects. Without this,
 *      the bridge's serializeValue would throw on the Agent/Session object
 *      graph (shared references treated as cycles).
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
 * <p><b>Honest boundaries (NEEDS)</b>: the {@code llm} seam streams canned
 * chunks from Java (no real adapter/streaming transport); the
 * {@code tools[TOOL_RUNTIME_SCHEDULER]} is a stub scheduler (no real executor
 * / registry / code runtime); {@code settings} resolves through an inert
 * {@code ctx.inject} (no real settings backend); the hybrid loopCtx keeps
 * the real services worker-local because the bridge cannot carry live
 * prototype-bearing objects (a real cross-realm ctx with live services is a
 * NEEDS).
 *
 * <p>The apply is SYNCHRONOUS: the sync NodeWorkerJsHost runner cannot
 * re-enter the pump from inside an async continuation (nested pumps never
 * settle), so the machine's turn is driven through the {@code runTurn} fn
 * handle (top-level pump on the worker side).
 *
 * @module @dsh-java/agent-loop-driver
 */

import AgentRegistry from '@deepseek-ai/dsh-agent'
import AgentLoop from '@deepseek-ai/dsh-agent-loop'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { SessionStore } from '@deepseek-ai/dsh-session'
import { SystemPrompt } from '@deepseek-ai/dsh-system-prompt'
import { TOOL_RUNTIME_SCHEDULER } from '@deepseek-ai/dsh-tools'

/** Cordis plugin name. */
export const name = 'agent-loop-driver'
/** The agentLoop service this plugin installs is visible to dependents. */
export const provide = ['agentLoop']
export const inject = []

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
    tools: deps.tools,
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
 * Build the worker-local ctx.tools: a scheduler stub keyed by the real
 * TOOL_RUNTIME_SCHEDULER symbol, delegating decisions to the Java-provided
 * tools seam.
 * @param toolsSvc - Java tools service (executionMode / schedulerPrepare /
 *   schedulerDispatch / schedulerFinish / schedulerFinalize).
 * @returns the symbol-keyed ctx.tools surface the machine drives.
 */
function makeTools(toolsSvc) {
  return {
    executionMode(exec) {
      return toolsSvc.executionMode(exec)
    },
    [TOOL_RUNTIME_SCHEDULER]: {
      prepare: async (exec) => toolsSvc.schedulerPrepare(exec),
      dispatch: async (exec) => toolsSvc.schedulerDispatch(exec),
      finish: (exec, result) => toolsSvc.schedulerFinish(exec, result),
      finalize: (exec, result) => toolsSvc.schedulerFinalize(exec, result),
    },
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

  // Java-provided seams.
  const llmSvc = ctx.get('llm')
  const toolsSvc = ctx.get('tools')

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
    tools: makeTools(toolsSvc),
    fiber,
  })

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

  // The real AgentLoop service (constructor registers 'agentLoop' into Java).
  const agentLoop = new AgentLoop(loopCtx, {
    agents: [],
    maxParallelToolCalls: 2,
  })
  setAgentLoop(agentLoop)

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
    if (event.type === 'tool/result') out.callId = data.message?.source?.callId
    if (event.type === 'turn/end') out.reason = data.reason
    if (event.type === 'request/header') out.model = data.header?.config?.model
    return out
  })
  const getAgentSnapshot = () => ({ id: agent.id, status: agent.status })
  const callResolveModelInfo = () => llmSvc.resolveModelInfo({ provider: 'test', model: 'test-model' })

  const probe = {
    serviceName: agentLoop.name,
    agentId,
    runTurn,
    getSessionLog,
    getAgentSnapshot,
    callResolveModelInfo,
    llmProvided: typeof loopCtx.llm.stream === 'function',
  }
  ctx.provide('agentLoopProbe', probe)
  return probe
}
