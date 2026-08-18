/**
 * M7-6 fiber isolation consumer: reads the sibling-provider's service through
 * ctx.get() and republishes the outcome as a plain probe service for Java.
 *
 * This is the exact failure the fix addresses — before it, the Java core's
 * fiber-chain walk could not reach a sibling fiber's store and the bridge threw
 * "cannot get property svc without inject"; now the shared store (keyed by the
 * isolate label, both workers sharing the root scope) makes the sibling service
 * visible. The bridge also maps an unavailable service to JS undefined (not a
 * throw), so launcher-owned slots read as absent rather than crashing.
 *
 * @module @dsh-java/sibling-consumer
 */
export const name = 'sibling-consumer'
export function apply(ctx) {
  const svc = ctx.get('svc')
  ctx.provide('siblingRead', {
    visible: svc != null && typeof svc === 'object' && svc.value !== undefined,
    value: svc && svc.value,
  })
}
