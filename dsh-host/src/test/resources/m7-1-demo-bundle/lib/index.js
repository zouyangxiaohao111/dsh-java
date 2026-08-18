/**
 * @dshj/demo-bundle — M7-1 fixture plugin.
 *
 * A minimal real cordis Service plugin that imports `@deepseek-ai/cordis` (the
 * Java-bridge shim intercepts that specifier in the node worker) and registers
 * itself into the Java core via `super(ctx, name)`. Nothing else is imported,
 * so the package loads with no transitive npm dependencies.
 */
import { Service } from '@deepseek-ai/cordis'

const name = 'demo-bundle'
const inject = []

export default class DemoBundle extends Service {
  constructor(ctx, config) {
    super(ctx, name)
    this.message = (config && config.message) || 'hello from demo-bundle'
  }

  /** A public method, bound and carried onto the registered value. */
  greet() {
    return this.message
  }
}

export { name, inject }
