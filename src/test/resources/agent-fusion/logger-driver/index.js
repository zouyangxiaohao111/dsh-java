/**
 * M5 logger-bridge driver — exercises the worker→Java ctx.logger bridge.
 *
 * Runs inside the NodeWorkerJsHost worker:
 *
 *   1. Instantiates the real {@code @deepseek-ai/dsh-agent} AgentRegistry
 *      (like agent-driver), then registers a THROWING JS listener on
 *      'agent/disposed' BEFORE registering an agent. When the plugin fiber is
 *      disposed, the register effect disposer runs the registry's
 *      {@code emitDisposed()} — its synchronous throw is caught and it calls
 *      {@code this.ctx.logger.warn(...)} — the real dsh agent logger path
 *      crossing the node-bridge into the Java Logger formatting layer.
 *   2. Named-logger printf calls ({@code ctx.logger('worker-sub').warn/info/
 *      debug/error}) and a no-name call ({@code ctx.logger.info(...)}, fiber
 *      derived) — all forwarded to Java {@code ctx.logger(name).<type>(...)},
 *      proving the P3 printf/color formatting layer is reused.
 *
 * <p>The apply is SYNCHRONOUS (see agent-driver for the nested-pump rationale);
 * disposal of the plugin fiber drives the warn path.
 *
 * @module @dsh-java/logger-driver
 */

import { Context } from '@deepseek-ai/cordis'
import AgentRegistry from '@deepseek-ai/dsh-agent'

/** Cordis plugin name (also the fiber-derived default logger name). */
export const name = 'logger-driver'
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
 * Fuse the real agent registry with the Java core and drive the logger bridge.
 * @param ctx - registrant context (bridge proxy back to the Java core).
 * @param config - driver config: `{ agentId?: string }`.
 * @returns a Java-observable probe snapshot.
 */
export function apply(ctx, config = {}) {
  config = config ?? {}
  const agentId = config.agentId ?? 'a1'

  // 1. Real AgentRegistry into the JAVA core (super provides 'agents').
  const agents = new AgentRegistry(ctx)

  // 2. Throwing listener on agent/disposed — registered BEFORE register so its
  //    disposer runs AFTER the register effect's disposer (fiber unload is in
  //    reverse order) and is still live when emitDisposed dispatches during
  //    disposal. The registry catches the synchronous throw and calls
  //    ctx.logger.warn(...) — the worker→Java logger path.
  ctx.on('agent/disposed', () => { throw new Error('boom-from-listener') })

  // 3. Named-logger printf through the bridge into the Java formatting layer
  //    (format + args cross verbatim; Java expands the placeholders). The %C
  //    placeholder colors the value by logger name when an exporter enables ANSI.
  ctx.logger('worker-sub').warn('warn %s of %d', 'app', 7)
  ctx.logger('worker-sub').info('info %s', 'ok')
  ctx.logger('worker-sub').info('colored %C', 'app')
  ctx.logger('worker-sub').error('error %s', 'bad')
  ctx.logger('worker-sub').debug('debug %s', 'hidden')
  // No-name form (fiber-derived default logger, cordis semantics).
  ctx.logger.info('no-name %s', 'x')

  // 4. Register a live agent → 'agent/created' fires into the Java core.
  const agent = stubAgent(agentId)
  const detach = agents.register(agent)

  return {
    serviceName: agents.name,
    agentId: agents.get(agentId) !== undefined ? agentId : null,
    listLength: agents.list().length,
    detachFn: typeof detach === 'function' ? true : !!detach,
  }
}
