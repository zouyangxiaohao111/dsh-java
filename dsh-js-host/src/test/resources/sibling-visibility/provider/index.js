/**
 * M7-6 fiber isolation provider: a plain function plugin that provides a
 * service into the shared scope. Loaded in its own Node worker (the loader
 * gives every JS entry its own worker, mirroring real dsh), the provide lands
 * in the Java core's shared store keyed by the isolate label — so a sibling
 * consumer in another worker can resolve it through ctx.get().
 *
 * @module @dsh-java/sibling-provider
 */
export const name = 'sibling-provider'
export function apply(ctx) {
  ctx.provide('svc', { value: 'from-provider' })
}
