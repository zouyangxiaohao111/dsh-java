/**
 * M4 agent-fusion driver for @deepseek-ai/dsh-agent (core/agent).
 *
 * Runs inside the NodeWorkerJsHost worker:
 *
 *   1. Instantiates the real {@link AgentRegistry} service. `super(ctx,
 *      'agents')` (via the @deepseek-ai/cordis shim) registers `ctx.agents`
 *      into the JAVA core; the constructor's own seams
 *      (`ctx.inject(['typert'], ...)` — inert without typert; `ctx.accessor('agent')`;
 *      `ctx.on('internal/status')`; the `agents.initiatorLifecycle()` generator
 *      effect) run through the worker ctx shim bridge.
 *   2. Registers a minimal live Agent stub through `ctx.agents.register(agent)`.
 *      The enhanced node-bridge `ctx.effect` seam runs the register generator to
 *      completion, so `announce()` fires `agent/created` through the worker
 *      `ctx.events.dispatch` seam into the JAVA core immediately (cordis
 *      semantics).
 *   3. Exercises the process-local initiator scope (real `node:async_hooks`
 *      AsyncLocalStorage inside the worker): a synchronous `withInitiator`
 *      boundary, the clearing boundary, and an async (microtask) hop exposed as
 *      a probe fn that Java invokes through the bridge (top-level pump).
 *   4. Emits an agent-subject event via the fused `agentEvents` dispatcher
 *      (`agent/status`), which routes through `ctx.events.dispatch`, and folds a
 *      `ctx.serial` listener chain synchronously.
 *   5. Provides a Java-observable probe snapshot.
 *
 * <p>The apply itself is SYNCHRONOUS: the sync NodeWorkerJsHost runner cannot
 * re-enter `syncWaitPromise`'s `process._tickCallback()` pump from inside an
 * async continuation (nested pumps never settle), so anything that needs a
 * microtask hop is exposed as a fn handle for Java to invoke (top-level pump).
 *
 * Java triggers disposal by disposing the plugin fiber: the `agents.register()`
 * effect disposer (detach) runs the worker-side detach, which emits
 * `agent/disposed` back into the Java core.
 *
 * @module @dsh-java/agent-driver
 */

import { Context } from '@deepseek-ai/cordis'
import AgentRegistry, { agentCarrier, agentEvents } from '@deepseek-ai/dsh-agent'

/** Cordis plugin name. */
export const name = 'agent-driver'
/** The agents service this plugin installs is visible to dependents. */
export const provide = ['agents']
export const inject = []

/** Build a minimal live Agent (id/session identity + agent-scoped ctx). */
function stubAgent(agentId) {
  return {
    id: agentId,
    options: {},
    session: { id: agentId },
    inbox: { hasPending: false },
    status: 'idle',
    ctx: new Context(),
    send: () => {},
    followup: () => {},
    steer: () => {},
    inject: () => {},
    cancel() {},
    runMaintenance: task => task(new AbortController().signal),
    whenIdle: () => Promise.resolve(),
  }
}

/**
 * Fuse the real agent registry with the Java core, register an agent, probe the
 * initiator scope, and emit an agent event.
 * @param ctx - registrant context (bridge proxy back to the Java core).
 * @param config - driver config: `{ agentId?: string }`.
 * @returns a Java-observable probe snapshot.
 */
export function apply(ctx, config = {}) {
  config = config ?? {}
  const agentId = config.agentId ?? 'a1'

  // 1. Real AgentRegistry into the JAVA core (super provides 'agents').
  const agents = new AgentRegistry(ctx)

  // 2. Register a live agent → effect generator runs enter() + announce() →
  //    'agent/created' fires into the Java core immediately.
  const agent = stubAgent(agentId)
  const detach = agents.register(agent)

  // 3. Initiator (AsyncLocalStorage) probes — native ALS inside the worker.
  const initiatorInside = agents.withInitiator(agent, () => {
    const cur = agents.currentInitiator()
    return cur === undefined ? null : cur.id
  })
  const initiatorOutside = (() => {
    const cur = agents.currentInitiator()
    return cur === undefined ? null : cur.id
  })()
  let requireThrows = null
  try {
    agents.requireInitiator()
  } catch (error) {
    requireThrows = error && error.message ? error.message : String(error)
  }
  // ALS survives an async (microtask) hop inside the boundary. The apply cannot
  // await this (nested pump), so it is exposed for Java to invoke (top-level
  // pump through the worker's invokeFn handler).
  const probeAsyncInitiator = () => agents.withInitiator(agent, async () => {
    await Promise.resolve()
    const cur = agents.requireInitiator()
    return cur.id
  })

  // 4. Fused agentEvents dispatcher → 'agent/status' through the carrier into
  //    the Java core (worker's events.dispatch seam).
  const statusHeard = []
  const stopListening = ctx.on('agent/status', (payload) => {
    statusHeard.push(payload && payload.status)
  })
  const dispatcher = agentEvents(ctx, agent)
  dispatcher.emit('agent/status', { status: 'running' })

  // 4b. serial dispatch through the worker's ctx.serial seam (synchronous fold;
  //     agentEvents.serial is async, so call the seam directly — a nested pump
  //     can never settle on the sync host).
  const stopSerial = ctx.on('agent/turn-stopping', async (payload) => {
    await Promise.resolve()
    return undefined
  })
  let serialResult = null
  try {
    serialResult = ctx.serial(agentCarrier(agent), 'agent/turn-stopping', { turn: 1, agent })
  } catch (error) {
    serialResult = error && error.message ? String(error.message) : String(error)
  }

  // 5. Java-observable probe snapshot.
  const probe = {
    serviceName: agents.name,
    agentId: agents.get(agentId) !== undefined ? agentId : null,
    listLength: agents.list().length,
    rootCount: agents.roots().length,
    initiatorInside,
    initiatorOutside,
    requireThrows,
    statusHeard: [...statusHeard],
    serialResult,
    detachFn: typeof detach === 'function' ? true : !!detach,
  }
  ctx.provide('agentDriverProbe', probe)
  ctx.provide('probeAsyncInitiator', probeAsyncInitiator)
  return probe
}
