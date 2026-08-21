package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;
import dev.dsh.cordis.util.Symbols;

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
    public final Map<String, String> isolate = new java.util.concurrent.ConcurrentHashMap<>();
    /** Intercept map: name → config merged into that service's per-plugin config. */
    public final Map<String, Object> intercept = new java.util.concurrent.ConcurrentHashMap<>();

    /** Listener filter consulted on event dispatch (reflect.ts proxy filter). */
    public Predicate<Context> filter;

    /** M11-7:extend(meta) 的普通 meta key = 本 ctx 的 own property(真实 cordis 语义:
     *  {@code ctx.extend({agent})} 使 {@code agentCtx.agent} 可读,own property 遮蔽
     *  accessor/继承)。此前构造器只处理 ISOLATE/INTERCEPT/fiber/baseUrl,agent 被忽略 →
     *  跨 worker setup 读 {@code agentCtx.agent} 走 accessor(默认 getter)得 undefined。 */
    final Map<String, Object> ownProps = new java.util.concurrent.ConcurrentHashMap<>();
    /** M11-7:extend(meta) 的 Symbol key(symbol 描述 → 值),如 dsh-scope 的
     *  {@code createScope(ctx, key) → ctx.extend({[kScope]: key})} —— scope key 经此存下,
     *  跨 worker 读方(makeCtx proxy 对 symbol 属性)经 ctxCall symbolGet 路由回属主读取。 */
    final Map<String, Object> symbolProps = new java.util.concurrent.ConcurrentHashMap<>();

    /** The fiber owning this context (rebound to the plugin fiber by the Fiber ctor). */
    public Fiber fiber;
    public final Reflect reflect;
    public final Registry registry;
    public final Events events;
    public final LoggerService logger;
    /** Built-in loader service ({@code ctx.loader}, @cordisjs/loader surface).
     *  The Java core owns loading/hot-reload; this is the contract facade hmr and
     *  friends read (see {@link LoaderService}). */
    public final LoaderService loader;

    /** Tracing shadow (JS symbols.shadow); M1 保留字段,不实现完整追踪。 */
    Context shadow;
    Object receiver;

    /** Returns true for Cordis contexts (context.ts:61-68). Java contexts are plain
     *  objects (no proxy/weak brand), so the brand check is a type test. */
    public static boolean is(Object value) {
        return value instanceof Context;
    }

    /** Create the root context and install built-in services (context.ts:71-84). */
    public Context() {
        this.parent = null;
        this.root = this;
        this.fiber = new Fiber(this, null, Map.of(), null);
        this.reflect = new Reflect(this);
        this.registry = new Registry(this);
        this.events = new Events(this);
        this.logger = new LoggerService(this);
        this.loader = new LoaderService(this);
        this.fiber.clearRootEffects();   // context.ts:82 — built-in service effects survive root dispose
    }

    /** Create a child context inheriting from this one (context.ts:99-107). */
    public Context extend() {
        return new Context(this, null);
    }

    /** Create a child context with extra metadata on top of the current scope.
     *  Own entries of `meta` shadow the inherited ones (context.ts:99-107).
     *  Supported meta keys: {@link Symbols#ISOLATE} / {@link Symbols#INTERCEPT}
     *  (scope maps), {@code "fiber"}, {@code "baseUrl"}. */
    public Context extend(Map<String, Object> meta) {
        return new Context(this, meta);
    }

    /** Create a child with an independent service scope for `name`, in a fresh label (context.ts:121-125). */
    public Context isolate(String name) {
        return isolate(name, null);
    }

    /** Create a child with an independent service scope for `name` under the given
     *  `label`; passing the same label to two isolates joins their scopes (context.ts:121-125). */
    public Context isolate(String name, String label) {
        if (label == null) label = name + "@" + System.identityHashCode(new Object());
        Map<String, String> iso = new java.util.concurrent.ConcurrentHashMap<>();
        iso.put(name, label);
        return extend(Map.of(Symbols.ISOLATE, iso));
    }

    /** Add service-specific intercept config for plugins below (context.ts:139-145). */
    public Context intercept(String name, Object config) {
        Map<String, Object> ic = new java.util.concurrent.ConcurrentHashMap<>();
        ic.put(name, config);
        return extend(Map.of(Symbols.INTERCEPT, ic));
    }

    private Context(Context parent, Map<String, Object> meta) {
        this.parent = parent;
        this.root = parent.root;
        this.baseUrl = parent.baseUrl;
        this.reflect = parent.reflect;    // shared, root-level
        this.registry = parent.registry;  // shared
        this.events = parent.events;      // shared
        this.logger = parent.logger;      // shared
        this.loader = parent.loader;      // shared
        this.fiber = parent.fiber;        // replaced when plugin creates child (see Registry.plugin)
        this.filter = parent.filter;
        if (meta != null) {
            Object iso = meta.get(Symbols.ISOLATE);
            if (iso instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    this.isolate.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            Object ic = meta.get(Symbols.INTERCEPT);
            if (ic instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    this.intercept.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            if (meta.containsKey("fiber") && meta.get("fiber") instanceof Fiber f) this.fiber = f;
            if (meta.containsKey("baseUrl")) this.baseUrl = String.valueOf(meta.get("baseUrl"));
            // M11-7:meta 普通 key = own property(遮蔽 accessor/继承)。真实 cordis 的
            // ctx.extend({agent}) 使 agentCtx.agent 可读;Java 此前忽略 → 跨 worker setup
            // 读 .agent 得 undefined("agent setup has no scoped agent")。
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                String k = e.getKey();
                if (Symbols.ISOLATE.equals(k) || Symbols.INTERCEPT.equals(k)
                        || "fiber".equals(k) || "baseUrl".equals(k)) continue;
                // M11-7:serializeValue 把 extend meta 的 Symbol key(如 dsh-scope kScope)序列化
                // 为 "$symbol$<desc>" → 存到 symbolProps(跨 worker 读方经 ctxCall symbolGet 取回)。
                if (k.startsWith("$symbol$")) {
                    this.symbolProps.put(k.substring("$symbol$".length()), e.getValue());
                } else {
                    this.ownProps.put(k, e.getValue());
                }
            }
        }
    }

    // ---- service resolution (reflect.ts:135-206 proxy handler, Java-ized) ----

    /** Read a service by name (proxy-get equivalent). */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        // M11-7:own property(extend meta 普通 key)优先于 accessor/服务 —— 真实 cordis 里
        // Agent.ctx 的 own property agent 遮蔽 accessor('agent')。跨 worker setup 经远程 ctx
        // 读 agentCtx.agent 走 ctx.get('agent') 到 core,此处返回 agent 句柄。
        for (Context c = this; c != null; c = c.parent) {
            if (c.ownProps.containsKey(name)) return (T) c.ownProps.get(name);
        }
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
        // fiber isolation (M7-6): sibling fibers are independent child fibers, so the
        // chain above never reaches their stores. cordis resolves ctx.get(name) through
        // the shared store keyed by the isolate label (reflect.ts:233-243) — consult it
        // as a fallback so same-scope siblings (dsh-base tree plugins) see each other.
        Reflect.Impl global = this.reflect.getImpl(this, name, true);
        if (global != null) return (T) global.value;
        // fiber.ts:163-165 — reaching a non-plugin fiber (or an isolation boundary)
        // without the service throws, mirroring reflect.ts:144 "cannot get property
        // \"X\" without inject". The root context read (fiber.runtime == null) is
        // handled by the early return above and stays null for a missing service.
        throw new CordisError(CordisError.Code.INACTIVE_EFFECT,
                "cannot get property \"" + name + "\" without inject");
    }

    /** Sentinel returned by {@link #getService} when the named service is unavailable
     *  (not provided / not yet active) — distinct from a provided {@code null} value.
     *  The JS bridges map it to JS {@code undefined}, mirroring cordis {@code ctx.get()}
     *  which reads {@code undefined} for a service that is not (yet) available. */
    public static final Object NO_SERVICE = new Object();

    /** Read a service with cordis {@code ctx.get()} semantics (reflect.ts:233-243): the
     *  shared store keyed by the isolate label makes same-scope sibling services visible,
     *  and an unavailable service yields {@link #NO_SERVICE} instead of throwing (plugins
     *  use {@code ctx.get(name) ?? fallback}, e.g. dsh-launch-environment). The JS bridges
     *  route every ctx read here — both explicit {@code ctx.get(name)} calls and
     *  {@code ctx[name]} property reads collapse onto this non-throwing path. */
    @SuppressWarnings("unchecked")
    public <T> T getService(String name) {
        if (this.fiber.runtime == null) {
            // non-plugin context: get() reads null for both missing and provided-null,
            // so resolve the impl to distinguish the unavailable case.
            Reflect.Impl impl = this.reflect.getImpl(this, name, false);
            return impl == null ? (T) NO_SERVICE : (T) impl.value;
        }
        try {
            return get(name);
        } catch (CordisError e) {
            return (T) NO_SERVICE;
        }
    }

    /** M11-7:读本 ctx(沿 parent 链)的 symbol 属性(symbol 描述 → 值,如 dsh-scope 的 scope
     *  key kScope)。桥的 doSymbolGet 经 ctxCall 路由跨 worker 读方(scopeOf(agentCtx))。 */
    public Object getSymbol(String desc) {
        for (Context c = this; c != null; c = c.parent) {
            if (c.symbolProps.containsKey(desc)) return c.symbolProps.get(desc);
        }
        return null;
    }

    /** Overwrite a provided service's value; computed accessors route to their setter. */
    public void set(String name, Object value) {
        Reflect.Property prop = this.reflect.props.get(name);
        if (prop instanceof Reflect.Property.Accessor acc) {
            if (acc.set != null) acc.set.apply(this, value);
            return;
        }
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

    /** Waterfall with an explicit dispatching context (used by core internal hooks). */
    public Object waterfall(Context thisArg, String name, Object... args) {
        return this.events.waterfall(thisArg, name, args);
    }

    // ---- computed properties (reflect.ts:345-390) ----

    /** Define a computed context property backed by get/set hooks (reflect.ts:345-353). */
    public Disposable accessor(String name, Reflect.Property.Accessor options) {
        return this.reflect.accessor(name, options);
    }

    /** Expose selected members of a service directly on ctx (reflect.ts:364-390). */
    public Disposable mixin(String source, List<String> keys) {
        return this.reflect.mixin(source, keys);
    }

    /** Expose renamed members of a service: source-key → ctx-key map (reflect.ts:364-390). */
    public Disposable mixin(String source, Map<String, String> renamed) {
        return this.reflect.mixin(source, renamed);
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
        return this.logger.current(this);   // caller ctx → caller fiber name (utils.ts traceable)
    }
}
