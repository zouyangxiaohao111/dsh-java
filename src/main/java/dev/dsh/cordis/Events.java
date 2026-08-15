package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Event bus with 5 dispatch modes and context filtering (events.ts). */
public final class Events {
    /** Listener callback; `ctx` is the dispatching context (thisArg), null for plain emits. */
    @FunctionalInterface
    public interface Listener {
        Object call(Context ctx, Object... args);
    }

    public record Hook(Context ctx, Listener callback, boolean prepend, boolean global) {}

    final Map<String, List<Hook>> hooks = new HashMap<>();

    public final Context ctx;

    public Events(Context ctx) { this.ctx = ctx; }

    /** Resolve listeners for one dispatch and apply context filtering (events.ts:165-175). */
    List<Listener> dispatch(String type, Context thisArg, Object[] args) {
        String name = (String) args[0];
        List<Hook> list = hooks.get(name);
        if (list == null) return List.of();
        List<Listener> out = new ArrayList<>();
        for (Hook hook : list) {
            if (hook.global() || thisArg == null || thisArg.filter == null || thisArg.filter.test(hook.ctx)) {
                out.add(hook.callback());
            }
        }
        return out;
    }

    /** Run listeners synchronously, ignoring return values (events.ts:194-196). */
    public void emit(String name, Object... args) {
        Object[] full = prepend(name, args);
        for (Listener cb : dispatch("emit", null, full)) cb.call(null, args);
    }

    /** Dispatch with an explicit dispatching context (used by internal events). */
    public void emit(Context thisArg, String name, Object... args) {
        Object[] full = prepend(name, args);
        for (Listener cb : dispatch("emit", thisArg, full)) cb.call(thisArg, args);
    }

    /** Run listeners concurrently and wait for all; aggregate failures (events.ts:183-187). */
    public CompletableFuture<Void> parallel(String name, Object... args) {
        Object[] full = prepend(name, args);
        List<Throwable> errors = new ArrayList<>();
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (Listener cb : dispatch("emit", null, full)) {
            try {
                Object r = cb.call(null, args);
                if (r instanceof CompletableFuture<?> cf) {
                    all.add(cf.handle((v, t) -> {
                        if (t != null) errors.add(t);
                        return null;
                    }));
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    if (!errors.isEmpty()) throw new AggregateError(errors);
                });
    }

    /** Run listeners in order, awaiting each, until one returns a bail value (events.ts:204-209). */
    public CompletableFuture<Object> serial(String name, Object... args) {
        Object[] full = prepend(name, args);
        List<Listener> cbs = dispatch("serial", null, full);
        return serialLoop(cbs, args);
    }

    private CompletableFuture<Object> serialLoop(List<Listener> cbs, Object[] args) {
        if (cbs.isEmpty()) return CompletableFuture.completedFuture(null);
        Listener cb = cbs.get(0);
        try {
            Object r = cb.call(null, args);
            if (r instanceof CompletableFuture<?> cf) {
                return cf.thenCompose(v -> isBailed(v)
                        ? CompletableFuture.completedFuture(v)
                        : serialLoop(cbs.subList(1, cbs.size()), args));
            }
            return isBailed(r) ? CompletableFuture.completedFuture(r) : serialLoop(cbs.subList(1, cbs.size()), args);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    /** Run listeners synchronously until one returns a bail value (events.ts:217-222). */
    public Object bail(String name, Object... args) {
        Object[] full = prepend(name, args);
        for (Listener cb : dispatch("bail", null, full)) {
            Object r = cb.call(null, args);
            if (isBailed(r)) return r;
        }
        return null;
    }

    /** Compose listeners around the final `next` callback (events.ts:234-243).
     *  Listeners receive (args..., next); calling `next` advances to the following
     *  listener, finally the innermost `inner` continuation. */
    public Object waterfall(String name, Object... args) {
        Object[] full = prepend(name, args);
        List<Listener> cbs = dispatch("waterfall", null, full);
        Listener inner = (Listener) args[args.length - 1];
        Object[] callArgs = new Object[args.length];
        System.arraycopy(args, 0, callArgs, 0, args.length - 1);
        java.util.function.Supplier<Object> next = () -> {
            Listener cb = cbs.isEmpty() ? null : cbs.remove(0);
            Listener target = cb != null ? cb : inner;
            return target.call(null, callArgs);
        };
        callArgs[args.length - 1] = next;
        return next.get();
    }

    /** Register a listener owned by the current fiber (events.ts:254-302). */
    public Disposable on(String name, Listener listener, EventOptions opts) {
        if (opts == null) opts = new EventOptions();
        return this.ctx.fiber.effect(() -> {
            List<Hook> list = hooks.computeIfAbsent(name, k -> new ArrayList<>());
            Hook hook = new Hook(this.ctx, listener, opts.prepend, opts.global);
            if (opts.prepend) list.add(0, hook); else list.add(hook);
            return Disposable.of(() -> {
                list.remove(hook);
                if (list.isEmpty()) hooks.remove(name);
            });
        }, "ctx.on(" + name + ")");
    }

    /** Register a listener that disposes itself after the first call (events.ts:312-318). */
    public Disposable once(String name, Listener listener, EventOptions opts) {
        if (opts == null) opts = new EventOptions();
        Disposable[] self = new Disposable[1];
        self[0] = on(name, (ctx, args) -> { self[0].dispose(); return listener.call(ctx, args); }, opts);
        return self[0];
    }

    /** Aggregates multiple listener failures (mirrors JS AggregateError). */
    public static final class AggregateError extends RuntimeException {
        private final List<Throwable> errors;
        public AggregateError(List<Throwable> errors) {
            super("parallel dispatch failed with " + errors.size() + " errors");
            this.errors = List.copyOf(errors);
        }
        public List<Throwable> getErrors() { return errors; }
    }

    public static boolean isBailed(Object value) {
        return value != null && !Boolean.FALSE.equals(value);
    }

    private static Object[] prepend(String name, Object[] args) {
        Object[] full = new Object[args.length + 1];
        full[0] = name;
        System.arraycopy(args, 0, full, 1, args.length);
        return full;
    }

    /** Listener options (events.ts:112-117). */
    public static final class EventOptions {
        public boolean prepend;
        public boolean global;
        public EventOptions prepend(boolean v) { this.prepend = v; return this; }
        public EventOptions global(boolean v) { this.global = v; return this; }
    }
}
