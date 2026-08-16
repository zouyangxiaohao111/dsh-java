// cordis ctx 契约子集 shim。bridge 为 Java JsCtxBridge 对象。
module.exports = function createCtx(bridge) {
  const ctx = {
    on: (name, listener, opts) => bridge.on(name, listener, opts == null ? {} : opts),
    once: (name, listener, opts) => bridge.once(name, listener, opts == null ? {} : opts),
    emit: (name, ...args) => bridge.emit(name, args),
    provide: (name, value) => bridge.provide(name, value),
    get: (name) => bridge.get(name),
    inject: (deps, cb) => bridge.inject(deps, cb),
    effect: (disposer) => bridge.effect(disposer),
  }
  return ctx
}
