/**
 * M4 agent-fusion proof driver.
 *
 * Runs inside the NodeWorkerJsHost worker and fuses the four overlay packages
 * with the Java cordis core:
 *
 *   1. @deepseek-ai/dsh-session-projection — a real dsh `Service` subclass.
 *      `super(ctx, 'sessionProjections')` (via the @deepseek-ai/cordis shim)
 *      registers the registry into the JAVA core over the node-bridge RPC:
 *      the instance carries ctx, name, and check; public methods are bound to
 *      own properties so they cross as fn handles and round-trip callable.
 *   2. @deepseek-ai/dsh-session-stats — the real session-stats plugin
 *      (projection.ts imports zod + @deepseek-ai/dsh-llm/message, the two
 *      other overlay packages) injects `ctx.sessionProjections`: the Java-held
 *      registry value deserializes back with callable `register`, and the real
 *      register runs (its effect body setup is deferred to fiber unload — a
 *      documented bridge simplification).
 *
 * Java asserts afterwards that `sessionProjections` is registered in the core
 * (carrying name, bound-method handles, and the check predicate) and that the
 * fusion probe reports a clean mount.
 *
 * @module @dsh-java/fusion-spike
 */

import { Context } from '@deepseek-ai/cordis'
import { SessionProjectionRegistry } from '@deepseek-ai/dsh-session-projection'
import { apply as mountSessionStats } from '@deepseek-ai/dsh-session-stats'

/** Cordis plugin name. */
export const name = 'dsh-fusion-spike'
/** The registry this plugin installs is visible to dependents. */
export const provide = ['sessionProjections']
export const inject = []

/**
 * Fuse the real dsh projection service with the Java core, then mount the
 * real session-stats plugin on the same ctx.
 * @param ctx - registrant context (bridge proxy back to the Java core).
 */
export function apply(ctx) {
  // 1. Service extends the Java core Service via the cordis shim: registers
  //    ctx.sessionProjections into the Java core (fiber-scoped).
  const registry = new SessionProjectionRegistry(ctx)
  // Context static surface (the shim's value surface) is present; the bridged
  // ctx is a proxy so `Context.is(ctx)` correctly reads false — the brand
  // applies to real cordis Context instances.
  const contextIsFn = typeof Context.is === 'function'

  // 2. Mount the real session-stats plugin: injects ctx.sessionProjections
  //    (Java-held registry → callable register round-trips) and invokes the
  //    real register. Note: this host's effect model defers JS effect bodies to
  //    fiber unload (both Node and GraalJS bridges), so the unit's
  //    registration table setup runs at unload, not synchronously — a
  //    documented bridge simplification, not asserted here.
  mountSessionStats(ctx)

  // Java-observable probe: a serializable snapshot of what fused.
  const probe = {
    registryName: registry.name,
    contextIsFn,
    statsMounted: true,
  }
  // Register the probe on the Java core (plain value, crosses the bridge).
  ctx.provide('fusionProbe', probe)
  return probe
}
