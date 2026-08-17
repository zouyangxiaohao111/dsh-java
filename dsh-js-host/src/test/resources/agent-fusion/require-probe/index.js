/**
 * Require-probe: imports every overlay package by bare specifier and reports
 * a serializable snapshot of each one's loaded surface. The worker loads this
 * as a plugin (`apply`), so the mere fact that this module evaluates proves
 * all four packages (plus the zod + dsh-llm/message value deps) resolved and
 * executed inside the worker.
 *
 * @module @dsh-java/require-probe
 */

import * as cordis from '@deepseek-ai/cordis'
import * as llm from '@deepseek-ai/dsh-llm'
import * as llmMessage from '@deepseek-ai/dsh-llm/message'
import * as sessionProjection from '@deepseek-ai/dsh-session-projection'
import * as sessionStats from '@deepseek-ai/dsh-session-stats'
import * as sessionStatsProjection from '@deepseek-ai/dsh-session-stats/projection'

/** Cordis plugin name. */
export const name = 'require-probe'
export const inject = []

/**
 * @param ctx - registrant context (bridge proxy back to the Java core).
 */
export function apply(ctx) {
  const probe = {
    cordis: Object.keys(cordis).sort(),
    llmExports: Object.keys(llm).length,
    llmMessageHasTokenDelta: typeof llmMessage.isTokenDelta === 'function',
    llmMessageProbe: llmMessage.isTokenDelta({ type: 'text-delta', text: 'hi' }),
    sessionProjectionIsClass: typeof sessionProjection.SessionProjectionRegistry === 'function',
    sessionStatsName: sessionStats.name,
    sessionStatsProjectionKey: sessionStatsProjection.sessionStatsProjectionDefinition.key,
  }
  ctx.provide('requireProbe', probe)
  return probe
}
