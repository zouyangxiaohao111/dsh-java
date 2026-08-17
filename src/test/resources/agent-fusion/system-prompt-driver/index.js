/**
 * M4 agent-fusion driver for @deepseek-ai/dsh-system-prompt.
 *
 * Runs inside the NodeWorkerJsHost worker:
 *
 *   1. Instantiates the real SystemPrompt service (overlay of the real dsh
 *      package). `super(ctx, 'systemPrompt')` (via the @deepseek-ai/cordis
 *      shim) registers the service into the JAVA core over the node-bridge
 *      RPC; the constructor's own sections (harness:identity, deployment
 *      persona) register through the worker's ctx.effect seam, which steps
 *      generator effect bodies synchronously so the ScopedLayers tables are
 *      actually populated.
 *   2. Registers an extra prompt section + a prompt variable from the driver
 *      (the fusion assertion targets).
 *   3. Optionally registers a JS listener on `system-prompt/assemble`
 *      (config.waterfallListener) to prove the ctx.waterfall seam folds a JS
 *      listener chain synchronously.
 *   4. Registers a Java-observable probe.
 *
 * Java then triggers `assemble` by invoking the registered service's
 * `assemble` fn handle and asserts the merged result comes back into Java.
 *
 * @module @dsh-java/system-prompt-driver
 */

import { SystemPrompt } from '@deepseek-ai/dsh-system-prompt'

/** Cordis plugin name. */
export const name = 'system-prompt-driver'
/** The systemPrompt service this plugin installs is visible to dependents. */
export const provide = ['systemPrompt']
export const inject = []

/**
 * Fuse the real system-prompt registry with the Java core, register a section
 * and a variable, and (optionally) a waterfall listener.
 * @param ctx - registrant context (bridge proxy back to the Java core).
 * @param config - driver config: `{ waterfallListener?: boolean }`.
 */
export function apply(ctx, config = {}) {
  config = config ?? {}
  const systemPrompt = new SystemPrompt(ctx, {
    includeHarnessIdentity: true,
    includeRuntimeContext: true,
    persona: 'You are the deployment persona.',
  })

  // The fusion assertion target: a driver-registered section and variable.
  systemPrompt.section({
    name: 'tools:guidance',
    order: 100,
    text: 'Prefer deterministic tools over free-form reasoning.',
  })
  systemPrompt.variable('deployment_name', () => 'Acme')

  // Optional: prove the ctx.waterfall seam folds a JS listener chain.
  if (config.waterfallListener === true) {
    ctx.on('system-prompt/assemble', async (assembly, context, next) => {
      const merged = await next()
      merged.sections.push({ name: 'waterfall:post', text: 'appended by waterfall listener' })
      return merged
    })
  }

  // Java-observable probe: a serializable snapshot of what fused.
  const probe = {
    mounted: true,
    serviceName: systemPrompt.name,
    hasSectionFn: typeof systemPrompt.section === 'function',
    hasAssembleFn: typeof systemPrompt.assemble === 'function',
  }
  ctx.provide('systemPromptProbe', probe)
  return probe
}
