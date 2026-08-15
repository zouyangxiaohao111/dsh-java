package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;
import java.util.function.Predicate;

/** Service implementations and declared context properties (reflect.ts). */
public final class Reflect {
    /** A concrete service implementation record (reflect.ts:116-125). */
    public static final class Impl {
        public final String name;
        public Object value;
        public final Fiber fiber;
        public final Predicate<Object> check;
        public Impl(String name, Object value, Fiber fiber, Predicate<Object> check) {
            this.name = name; this.value = value; this.fiber = fiber; this.check = check;
        }
    }

    /** A declared context property (reflect.ts:93-113). */
    public abstract static class Property {
        public static final class Service extends Property {}
        public static final class Accessor extends Property {
            public final java.util.function.BiFunction<Context, Object, Object> get;
            public final java.util.function.BiFunction<Context, Object, Boolean> set;
            public Accessor(java.util.function.BiFunction<Context, Object, Object> get,
                            java.util.function.BiFunction<Context, Object, Boolean> set) {
                this.get = get; this.set = set;
            }
        }
    }

    public final Context ctx;

    /** Service impls keyed by isolation label. */
    public final Map<String, Impl> store = new HashMap<>();
    /** Declared context properties by name. */
    public final Map<String, Property> props = new HashMap<>();

    public Reflect(Context ctx) { this.ctx = ctx; }

    /** Effective isolation label for `name` from a caller's scope (JS prototype-chain isolate). */
    static String effectiveIsolate(Context caller, String name) {
        while (caller != null) {
            if (caller.isolate.containsKey(name)) return caller.isolate.get(name);
            caller = caller.parent;
        }
        return null;
    }

    /** Read a service by name without the inject requirement (reflect.ts:233-243). */
    @SuppressWarnings("unchecked")
    public <T> T get(Context caller, String name, boolean strict) {
        Impl impl = getImpl(caller, name, strict);
        return impl == null ? null : (T) impl.value;
    }

    Impl getImpl(Context caller, String name, boolean strict) {
        String key = effectiveIsolate(caller, name);
        Impl impl = key == null ? null : store.get(key);
        if (impl == null) return null;
        if (strict && impl.fiber.state != FiberState.ACTIVE) return null;
        return impl;
    }

    /** Overwrite a provided service's value (reflect.ts:254-265). */
    public void set(Context caller, String name, Object value) {
        String key = effectiveIsolate(caller, name);
        Impl impl = key == null ? null : store.get(key);
        if (impl == null) throw new IllegalStateException("cannot set property \"" + name + "\" without provide");
        if (impl.fiber != caller.fiber) throw new IllegalStateException("cannot set property \"" + name + "\" in multiple fibers");
        impl.value = value;
    }

    /** Register a service impl owned by the current fiber (reflect.ts:277-305). */
    public Disposable provide(Context caller, String name, Object value, Predicate<Object> check) {
        return caller.fiber.effect(() -> {
            Property existing = props.get(name);
            if (existing != null && !(existing instanceof Property.Service)) {
                throw new IllegalStateException("property \"" + name + "\" is already declared as accessor");
            }
            props.putIfAbsent(name, new Property.Service());
            caller.root.isolate.computeIfAbsent(name, k -> "\u0000" + k); // ensure root default label
            String resolved = effectiveIsolate(caller, name);
            String key = resolved != null ? resolved : name;
            Impl impl = new Impl(name, value, caller.fiber, check);
            if (store.containsKey(key)) {
                throw new IllegalStateException("service \"" + name + "\" has been registered at <" + store.get(key).fiber.name() + ">");
            }
            store.put(key, impl);
            if (caller.fiber.store != null) caller.fiber.store.put(name, impl);
            if (caller.fiber.state == FiberState.ACTIVE) notify(List.of(name));
            return Disposable.of(() -> {
                store.remove(key);
                if (caller.fiber.store != null) caller.fiber.store.remove(name);
                this.notify(List.of(name));
            });
        }, "ctx.provide(" + name + ")");
    }

    /** Re-evaluate every fiber that requires one of the given services (reflect.ts:314-336). */
    public void notify(List<String> names) {
        for (Plugin.Runtime runtime : this.ctx.registry.values()) {
            for (Fiber fiber : runtime.fibers) {
                boolean hasUpdate = false;
                for (String name : names) {
                    if (!fiber.inject.containsKey(name)) continue;
                    if (!isolateMatches(fiber.ctx, name)) continue;
                    hasUpdate = true;
                    fiber.checkImpl(name);
                }
                if (!hasUpdate) continue;
                fiber.refresh();
            }
        }
        // internal/service event 推迟到 Events 就绪(任务 10 回填)
    }

    private boolean isolateMatches(Context fiberCtx, String name) {
        return Objects.equals(effectiveIsolate(fiberCtx, name), effectiveIsolate(this.ctx, name));
    }

    /** Define a computed context property (reflect.ts:345-353). */
    public Disposable accessor(String name, Property.Accessor options) {
        return this.ctx.fiber.effect(() -> {
            if (props.containsKey(name)) throw new IllegalStateException("property \"" + name + "\" is already declared as " + (props.get(name) instanceof Property.Service ? "service" : "accessor"));
            props.put(name, options);
            return Disposable.of(() -> props.remove(name));
        }, "ctx.accessor(" + name + ")");
    }

    /** Expose selected members of a service directly on ctx (reflect.ts:364-390). */
    public Disposable mixin(String source, List<String> keys) {
        return this.ctx.fiber.effect(() -> Disposable.none(), "ctx.mixin(" + source + ")");
    }
}
