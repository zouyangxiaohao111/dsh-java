package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        // reflect.ts:330-334 — report each binding on a filter-scoped child context
        for (String name : names) {
            Context self = this.ctx.extend();
            self.filter = target -> isolateMatches(target, name);
            Impl impl = getImpl(this.ctx, name, false);
            this.ctx.events.emit(self, "internal/service", name, impl == null ? null : impl.value);
        }
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

    /** Expose selected members of a service directly on ctx (reflect.ts:364-390).
     *  Data members are forwarded through accessor get/set; method members are
     *  returned bound to the source service (Java {@link MethodHandle}, else the
     *  raw {@link Method}), mirroring JS `value.bind(mixin)`. */
    public Disposable mixin(String source, List<String> keys) {
        List<String[]> entries = new ArrayList<>();
        for (String key : keys) entries.add(new String[]{key, key});
        return mixinEntries(source, entries);
    }

    /** Expose renamed members of a service: source-key → ctx-key map (reflect.ts:364-390). */
    public Disposable mixin(String source, Map<String, String> renamed) {
        List<String[]> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : renamed.entrySet()) entries.add(new String[]{e.getKey(), e.getValue()});
        return mixinEntries(source, entries);
    }

    /** Shared mixin body over (member-key, ctx-key) pairs (reflect.ts:371-389). */
    private Disposable mixinEntries(String source, List<String[]> entries) {
        return this.ctx.fiber.effect(() -> {
            List<Disposable> disposers = new ArrayList<>();
            for (String[] pair : entries) {
                disposers.add(accessor(pair[1], mixinAccessor(source, pair[0])));
            }
            return Disposable.of(() -> {
                for (int i = disposers.size() - 1; i >= 0; i--) disposers.get(i).dispose();
            });
        }, "ctx.mixin(" + source + ")");
    }

    /** Accessor forwarding one `key` of a service named `source` (reflect.ts:373-388). */
    private Property.Accessor mixinAccessor(String source, String key) {
        return new Property.Accessor(
                (ctx, receiver) -> {
                    Object service = ctx.get(source);
                    if (service == null) return null;
                    return readMember(service, key);
                },
                (ctx, value) -> {
                    Object service = ctx.get(source);
                    if (service == null) return false;
                    return writeMember(service, key, value);
                }
        );
    }

    /** Read a member from a service object: map key, bound public method, or public field. */
    static Object readMember(Object service, String key) {
        if (service instanceof Map<?, ?> m) return m.get(key);
        Method method = findPublicMethod(service.getClass(), key);
        if (method != null) return bindMethod(service, method);
        Field field = findPublicField(service.getClass(), key);
        if (field == null) return null;
        try {
            return field.get(service);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Write a member on a service object: map key or public field. Returns false if unsupported. */
    static boolean writeMember(Object service, String key, Object value) {
        if (service instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> target = (Map<Object, Object>) m;
            target.put(key, value);
            return true;
        }
        Field field = findPublicField(service.getClass(), key);
        if (field == null) return false;
        try {
            field.set(service, value);
            return true;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** First public instance method with the given name (any arity), or null. */
    private static Method findPublicMethod(Class<?> cls, String key) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(key)
                        && Modifier.isPublic(m.getModifiers())
                        && !Modifier.isStatic(m.getModifiers())) {
                    return m;
                }
            }
        }
        return null;
    }

    /** First public instance field with the given name, or null. */
    private static Field findPublicField(Class<?> cls, String key) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(key);
                if (Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** Bind a public method to its service instance (JS `value.bind(service)` equivalent).
     *  Falls back to the raw {@link Method} when a bound handle is not obtainable. */
    private static Object bindMethod(Object service, Method method) {
        try {
            return MethodHandles.lookup().unreflect(method).bindTo(service);
        } catch (IllegalAccessException e) {
            return method;
        }
    }
}
