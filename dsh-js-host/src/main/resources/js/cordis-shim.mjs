/**
 * @deepseek-ai/cordis overlay shim — the value surface the agent-fusion dsh
 * packages import from the real package (vendor/cordis), re-pointed at OUR
 * Java cordis core running on the other end of the NodeWorkerJsHost pipe.
 *
 * The only two value imports the overlay's packages make are
 * `{ Context, Service }` (dsh-session-projection, dsh-llm index). The
 * load-bearing deviation from the real package:
 *
 *   `Service` extends the Java core Service — not literally (it is JS), but
 *   semantically: `super(ctx, name)` registers the instance into the Java
 *   core through the node-bridge RPC. The instance carries `ctx`, `name`,
 *   and the `[Service.check]` predicate as own properties, and every public
 *   prototype method is bound onto the instance so it survives the bridge's
 *   serialization (which only walks own enumerable keys). A later
 *   `ctx.get(name)` in the worker (or a Java service fetch) round-trips the
 *   registered value; its method handles deserialize back to the original
 *   callable closures.
 *
 * Deviation notes (documented, not silent):
 * - The bridge's `ctx.provide(name, value)` forwards no separate predicate
 *   argument to `Reflect.provide`, so Java-side dependency gating consults
 *   the default (always-true) predicate; the JS `check` predicate still
 *   crosses the bridge as a fn handle on the registered value, ready for a
 *   future bridge enhancement. The Java core remains the single authority.
 * - `Context` is only the static surface + brand (`Context.is`) the
 *   packages need; the instance face is the worker's ctx shim proxy.
 *
 * @module @deepseek-ai/cordis
 */

// Mirror vendor/cordis/src/utils.ts `symbols` (the subset the packages touch)
// keyed by global symbols so they stay stable across realms / shim copies.
export const symbols = {
  init: Symbol.for('cordis.init'),
  check: Symbol.for('cordis.check'),
  config: Symbol.for('cordis.config'),
  invoke: Symbol.for('cordis.invoke'),
  extend: Symbol.for('cordis.extend'),
  tracker: Symbol.for('cordis.tracker'),
  resolveConfig: Symbol.for('cordis.resolveConfig'),
  filter: Symbol.for('cordis.filter'),
  isolate: Symbol.for('cordis.isolate'),
  intercept: Symbol.for('cordis.intercept'),
  effect: Symbol.for('cordis.effect'),
  original: Symbol.for('cordis.original'),
  shadow: Symbol.for('cordis.shadow'),
  checkProto: Symbol.for('cordis.checkProto'),
}

/**
 * Dependency-injection decorator (registry.ts:37-60). M8:real dsh web bundles
 * import {@code Inject} (mostly as a type; a few class plugins decorate with
 * it). Minimal faithful surface: the class-decorator path records the injected
 * service name on the class's `inject` map (inherited chain + checkProto
 * marker, mirroring registry.ts:39-44); the method path is out of scope for
 * the host shim and fails loud so a real usage surfaces instead of silently
 * losing a dependency.
 */
export function Inject(name, config) {
  return function (value, decorator) {
    if (decorator?.kind === 'class') {
      if (!Object.hasOwn(value, 'inject')) {
        Object.defineProperty(value, 'inject', {
          value: Object.create(Object.getPrototypeOf(value).inject ?? null),
          enumerable: false,
          writable: true,
          configurable: true,
        })
        value.inject[symbols.checkProto] = true
      }
      value.inject[name] = config
    } else {
      throw new Error('@Inject() can only be used on class or class methods (cordis-shim)')
    }
  }
}

/**
 * Lifecycle states of a Cordis fiber (fiber.ts:147-153), numeric-valued to
 * mirror the `export const enum` the real package compiles to. Used by
 * `@deepseek-ai/dsh-agent` to test `fiber.state === FiberState.UNLOADING`.
 */
export const FiberState = {
  PENDING: 0,
  LOADING: 1,
  ACTIVE: 2,
  FAILED: 3,
  DISPOSED: 4,
  UNLOADING: 5,
}

/**
 * Wrap a service value so method calls see the caller's active context
 * (utils.ts:117-123). Overlay approximation: shadow-wrapped values unwrap to
 * their prototype, and values carrying a `tracker` (Cordis service tracing)
 * are returned unchanged — the bridge already reconstructs bound methods, so
 * no extra proxy layer is needed for the fused AgentRegistry.
 */
export function getTraceable(ctx, value) {
  if (value === null || typeof value !== 'object') return value
  if (Object.hasOwn(value, symbols.shadow)) {
    return Object.getPrototypeOf(value)
  }
  return value
}

/**
 * Run a callback and splice outer call-site frames into thrown async errors
 * (utils.ts:268-282). M8:the vendored cordis-plugin-loader imports it to wrap
 * plugin application; the Java core already captures the root error message,
 * so a faithful-enough shim rethrows the original reason (stack intact).
 */
export function composeError(callback, getOuterStack) {
  const info = { offset: 1, error: new Error() }
  try {
    const result = callback(info)
    if (result && typeof result === 'object' && typeof result.then === 'function') {
      return Promise.resolve(result).then(undefined, (reason) => { throw reason })
    }
    return result
  } catch (reason) {
    throw reason
  }
}

/**
 * Logger static formatting surface (logger.ts) — the subset the vendored
 * logger-console exporter reads ({@code color}/{@code code}/{@code format}).
 * The console exporter itself is not part of the Java-hosted row tree, so the
 * instance face is a minimal no-op that still accepts the exporter contract.
 */
export class Logger {
  static color(exporter, code, value, decoration = '') {
    if (!exporter?.colors) return '' + value
    const prefix = `[3${code < 8 ? code : '8;5;' + code}${exporter.colors >= 2 ? decoration : ''}m`
    return `${prefix}${value}[0m`
  }

  static code(name, level) {
    let hash = 0
    for (let i = 0; i < name.length; i++) {
      hash = ((hash << 3) - hash) + name.charCodeAt(i) + 13
      hash |= 0
    }
    return Math.abs(hash) % 256
  }

  static format(exporter, message) {
    const args = message.args.slice()
    const head = args.shift()
    return `${message.type}: [${message.name}] ${head}${args.length ? ' ' + args.join(' ') : ''}`
  }

  constructor(options, service) {
    this.name = options?.name ?? 'default'
    this.error = () => {}
    this.info = () => {}
    this.warn = () => {}
    this.debug = () => {}
  }
}

/**
 * Cordis context marker / static face (context.ts:42-74). The instance face
 * is the worker's ctx shim proxy passed into `apply`; packages mostly use
 * `Context` as a type, and `Context.is` / the filter symbols as values.
 */
export class Context {
  static effect = symbols.effect
  static filter = symbols.filter
  static isolate = symbols.isolate
  static intercept = symbols.intercept

  /** Cross-realm brand test: `true` for any ctx the shim or Java handed us. */
  static is(value) {
    return !!value?.[Context.is]
  }

  static {
    Context.is[Symbol.toPrimitive] = () => Symbol.for('cordis.is')
    Context.prototype[Context.is] = true
  }
}

/**
 * Base class for services exposing a named API on `ctx` (service.ts:11-115).
 *
 * Subclasses call `super(ctx, name)`. Registration is a bridge call into the
 * Java core, so the service is unregistered automatically when the owning
 * fiber unloads (Java `Reflect.provide` rides the fiber, exactly like the
 * real `ctx.reflect.provide`).
 */
export class Service {
  /** Symbol key of an instance method run after construction (class plugins). */
  static init = symbols.init
  /** Symbol key of the availability predicate passed to the Java provide. */
  static check = symbols.check
  /** Symbol key of the phantom intercept-config type parameter. */
  static config = symbols.config
  /** Symbol key of the call body making a service callable. */
  static invoke = symbols.invoke
  /** Symbol key of the helper deriving an extended service instance. */
  static extend = symbols.extend
  /** Symbol key of the tracker metadata used for context tracing. */
  static tracker = symbols.tracker
  /** Symbol key of the intercept-config resolution helper. */
  static resolveConfig = symbols.resolveConfig

  /** The service name this instance is registered under. */
  name

  /**
   * Register this instance as `name` in the Java core.
   *
   * Sets `this.ctx` / `this.name`, binds every public prototype method as an
   * own property (so the whole callable surface crosses the bridge as fn
   * handles), then calls `ctx.provide(name, self)` through the node-bridge
   * RPC. The `[Service.check]` predicate is carried as an own property on
   * the registered value.
   *
   * @param ctx - the context to register in (stored as `this.ctx`).
   * @param name - the service name; defaults to the static `provide` field.
   */
  constructor(ctx, name) {
    name = name ?? this.constructor.provide
    const self = this
    self.ctx = ctx
    self.name = name

    // Bridge round-trip: serializeValue walks own enumerable keys only, so
    // the class's public methods (prototype) must become own properties to
    // survive `provide` → Java → `get`. Bind them so `this` stays the
    // original instance even when called off the reconstructed object.
    // Accessors (getters/setters) are deliberately skipped, never evaluated:
    // `typeof self[key]` would invoke a getter during construction — subclass
    // getters may depend on fields the subclass assigns only AFTER super()
    // returns (pwsh-local's `get config()` calls `this.source()`, assigned in
    // the subclass constructor) → "this.source is not a function". Getters
    // resolve through the prototype chain on their own and are not own
    // enumerable, so they neither need binding nor cross the bridge.
    for (let proto = Object.getPrototypeOf(self); proto !== null && proto !== Object.prototype; proto = Object.getPrototypeOf(proto)) {
      for (const key of Object.getOwnPropertyNames(proto)) {
        if (key === 'constructor' || key === 'name') continue
        const desc = Object.getOwnPropertyDescriptor(proto, key)
        if (!desc || typeof desc.value !== 'function') continue
        self[key] = self[key].bind(self)
      }
    }

    // Carry the check predicate on the instance: symbol-keyed (shim-internal,
    // mirrors the real package) AND string-keyed so it crosses the bridge as
    // a fn handle on the registered value. Java-side dependency gating still
    // uses the default predicate (the bridge forwards no separate predicate
    // argument) — documented deviation; the check itself is registered.
    self[symbols.check] = typeof self[symbols.check] === 'function'
      ? self[symbols.check].bind(self)
      : undefined
    if (typeof self[symbols.check] === 'function') self.$check = self[symbols.check]

    // Register into the Java core (fiber-scoped, auto-unregistered on unload).
    ctx.provide(name, self, self[symbols.check])
    return self
  }

  /** Default availability predicate: always available. */
  [symbols.check]() {
    return true
  }

  /** Isolate filter: same-isolate only (mirrors service.ts:79-83). */
  [symbols.filter](ctx) {
    const isolate = this.ctx?.[symbols.isolate]
    return isolate === undefined || ctx?.[symbols.isolate] === isolate
  }

  /** Derive an extended instance copying this one (service.ts:84-90). */
  [symbols.extend](props) {
    return Object.assign(Object.create(this), props)
  }

  /** Merge intercept config from ancestors with optional base/head (service.ts:104-118). */
  [symbols.resolveConfig](base, head) {
    const configs = []
    let intercept = this.ctx?.[Context.intercept]
    while (intercept && typeof intercept === 'object') {
      if (Object.hasOwn(intercept, this.name)) configs.unshift(intercept[this.name])
      intercept = Object.getPrototypeOf(intercept)
    }
    if (base) configs.unshift(base)
    if (head) configs.push(head)
    if (this.Config && typeof this.Config.merge === 'function') {
      return this.Config.merge(...configs)
    }
    return Object.assign({}, ...configs)
  }

  /** Structural `instanceof` across realms (service.ts:120-130). */
  static [Symbol.hasInstance](instance) {
    if (!instance) return false
    let constructor = instance.constructor
    while (constructor) {
      constructor = constructor.prototype?.constructor
      if (constructor === this) return true
      constructor = constructor && Object.getPrototypeOf(constructor)
    }
    return false
  }
}
