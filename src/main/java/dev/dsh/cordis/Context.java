package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Root and child dependency containers for plugins (context.ts). */
public final class Context {
    /** Parent context; null for root. */
    public final Context parent;
    /** The root context (every child shares it). */
    public final Context root;
    /** Base URL for resolving relative plugin specifiers. */
    public String baseUrl;

    /** Isolation map: name → scope label. */
    public final Map<String, String> isolate = new HashMap<>();
    /** Intercept map: name → config merged into that service's per-plugin config. */
    public final Map<String, Object> intercept = new HashMap<>();

    /** Listener filter consulted on event dispatch (reflect.ts proxy filter). */
    public Predicate<Context> filter;

    /** The fiber owning this context (rebound to the plugin fiber by the Fiber ctor). */
    public Fiber fiber;
    public final Reflect reflect;
    public final Registry registry;
    public final Events events;
    public final LoggerService logger;

    /** Tracing shadow (JS symbols.shadow); M1 保留字段,不实现完整追踪。 */
    Context shadow;
    Object receiver;

    /** Create the root context and install built-in services (context.ts:71-84). */
    public Context() {
        this.parent = null;
        this.root = this;
        this.fiber = new Fiber(this, null, Map.of(), null);
        this.reflect = new Reflect(this);
        this.registry = new Registry(this);
        this.events = new Events(this);
        this.logger = new LoggerService(this);
    }

    private Context(Context parent, Map<String, String> isolate, Map<String, Object> intercept) {
        this.parent = parent;
        this.root = parent.root;
        this.baseUrl = parent.baseUrl;
        if (isolate != null) this.isolate.putAll(isolate);
        if (intercept != null) this.intercept.putAll(intercept);
        this.reflect = parent.reflect;    // shared, root-level
        this.registry = parent.registry;  // shared
        this.events = parent.events;      // shared
        this.logger = parent.logger;      // shared
        this.fiber = parent.fiber;        // replaced when plugin creates child (see Registry.plugin)
        this.filter = parent.filter;
    }

    /** Create a child context inheriting from this one (context.ts:99-107). */
    public Context extend() {
        return new Context(this, null, null);
    }

    /** Create a child with an independent service scope for `name` (context.ts:121-125). */
    public Context isolate(String name) {
        Map<String, String> iso = new HashMap<>();
        iso.put(name, name + "@" + System.identityHashCode(new Object()));
        return new Context(this, iso, null);
    }

    /** Add service-specific intercept config for plugins below (context.ts:139-145). */
    public Context intercept(String name, Object config) {
        Map<String, Object> ic = new HashMap<>();
        ic.put(name, config);
        return new Context(this, null, ic);
    }

    // ---- service resolution (reflect.ts:135-206 proxy handler, Java-ized) ----

    /** Read a service by name (proxy-get equivalent). */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        Reflect.Property prop = this.reflect.props.get(name);
        if (prop instanceof Reflect.Property.Accessor acc) {
            return (T) acc.get.apply(this, this.receiver);
        }
        if (this.fiber.runtime == null) {
            return this.reflect.get(this, name, false);
        }
        Context ctx = this.shadow != null ? this.shadow : this;
        Fiber f = ctx.fiber;
        String key = Reflect.effectiveIsolate(this, name);
        while (true) {
            Reflect.Impl impl = f.store == null ? null : f.store.get(name);
            if (impl != null) return (T) impl.value;
            if (f.inject.containsKey(name)) {
                throw new CordisError(CordisError.Code.INACTIVE_EFFECT,
                        "cannot get required service \"" + name + "\" in inactive context");
            }
            if (f.runtime == null) break;
            if (!Objects.equals(Reflect.effectiveIsolate(f.parent, name), key)) break;
            f = f.parent.fiber;
        }
        return this.reflect.get(this, name, false);
    }

    /** Overwrite a provided service's value. */
    public void set(String name, Object value) {
        this.reflect.set(this, name, value);
    }

    /** Register a service implementation owned by the current fiber (reflect.ts:277). */
    public Disposable provide(String name, Object value) {
        return provide(name, value, null);
    }

    public Disposable provide(String name, Object value, Predicate<Object> check) {
        return this.reflect.provide(this, name, value, check);
    }

    // ---- events (mixins made static; events.ts mixed onto ctx) ----

    public Disposable on(String name, Events.Listener listener) {
        return this.events.on(this, name, listener, new Events.EventOptions());
    }

    public Disposable on(String name, Events.Listener listener, Events.EventOptions opts) {
        return this.events.on(this, name, listener, opts);
    }

    public Disposable once(String name, Events.Listener listener, Events.EventOptions opts) {
        return this.events.once(this, name, listener, opts);
    }

    public void emit(String name, Object... args) {
        this.events.emit(name, args);
    }

    public CompletableFuture<Void> parallel(String name, Object... args) {
        return this.events.parallel(name, args);
    }

    public CompletableFuture<Object> serial(String name, Object... args) {
        return this.events.serial(name, args);
    }

    public Object bail(String name, Object... args) {
        return this.events.bail(name, args);
    }

    public Object waterfall(String name, Object... args) {
        return this.events.waterfall(name, args);
    }

    // ---- registry (mixins made static) ----

    public Fiber plugin(Plugin<?> plugin, Object config) {
        return this.registry.plugin(this, plugin, config);
    }

    public Fiber inject(Inject deps, PluginSpec.PluginApply<Void> callback) {
        return this.registry.inject(this, deps, callback);
    }

    // ---- effects ----

    public Disposable effect(Fiber.EffectBody body, String label) {
        return this.fiber.effect(body, label);
    }

    // ---- typed convenience ----

    public Logger logger(String name) {
        return this.logger.get(name);
    }

    public Logger logger() {
        return this.logger.current();
    }
}
