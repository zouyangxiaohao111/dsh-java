package dev.dsh.cordis;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dsh.cordis.util.Disposable;
import dev.dsh.cordis.util.DisposableList;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Lifecycle state for one plugin fiber (fiber.ts:184-753). */
public final class Fiber {
    static final String INACTIVE = "__INACTIVE__";

    /** Unique id within the registry; 0 for the root fiber. */
    public final int uid;
    /** The context this fiber's plugin runs in. */
    public final Context ctx;
    /** The context the plugin was loaded from. */
    public final Context parent;
    /** Validated plugin config (updated by update()). */
    public Object config;
    /** Raw plugin config, re-resolved before each activation. */
    Object _config;
    /** Resolved dependency map (name → intercept config). */
    public final Map<String, Object> inject;
    /** Shared plugin runtime; null for the root fiber. */
    public final Plugin.Runtime runtime;
    /** Current lifecycle state. */
    public volatile FiberState state = FiberState.PENDING;
    /** Snapshot of required service impls while loaded. */
    public Map<String, Reflect.Impl> store;
    /** In-flight load/unload transition. */
    public CompletableFuture<Void> inertia;

    final DisposableList<Disposable> _disposables = new DisposableList<>();

    Throwable _error;
    String epoch = INACTIVE;

    /** Create a fiber. Root fiber is created by Context; plugin fibers by Registry. */
    public Fiber(Context parent, Object config, Map<String, Object> inject, Plugin.Runtime runtime) {
        this.parent = parent;
        this._config = config;
        this.inject = inject;
        this.runtime = runtime;
        this.uid = runtime == null ? 0 : parent.registry.counter();
        this.ctx = runtime == null ? parent : parent.extend();

        if (runtime != null) {
            // intercept config shadowing (fiber.ts:239-245)
            for (var e : inject.entrySet()) {
                if (e.getValue() != null) this.ctx.intercept.put(e.getKey(), e.getValue());
            }
        } else {
            this.state = FiberState.ACTIVE;
            this.store = new HashMap<>();
        }
        // publication & dep resolution completed in Registry.plugin (task 9)
    }

    /** The plugin's display name, nearest named ancestor, else "root" (fiber.ts:336-343). */
    public String name() {
        Fiber fiber = this;
        do {
            if (fiber.runtime != null && fiber.runtime.name() != null) return fiber.runtime.name();
            fiber = fiber.parent.fiber;
        } while (fiber != fiber.parent.fiber);
        return "root";
    }

    /** Throw if the fiber has already been disposed (fiber.ts:351-354). */
    public void assertActive() {
        if (this.uid == 0 || this.state != FiberState.DISPOSED) return;
        throw new CordisError(CordisError.Code.INACTIVE_EFFECT);
    }

    /** Effect body accepted by Fiber.effect (fiber.ts:74-101). */
    @FunctionalInterface
    public interface EffectBody {
        /** Returns null | Disposable | CompletableFuture<Disposable> | Iterable<Disposable>. */
        Object run() throws Exception;
    }

    /** Register a cleanup-aware effect (fiber.ts:418-561). Simplified: execute runs
     *  now; collected disposers run in reverse order on teardown and are awaited. */
    public Disposable effect(EffectBody body, String label) {
        assertActive();
        List<Disposable> disposables = new ArrayList<>();
        Disposable dispose = () -> {
            CompletableFuture<Void> task = CompletableFuture.completedFuture(null);
            for (int i = disposables.size() - 1; i >= 0; i--) {
                Disposable d = disposables.get(i);
                task = task.thenCompose(v -> d.dispose());
            }
            disposables.clear();
            return task;
        };

        Object result;
        try {
            result = body.run();
        } catch (Throwable t) {
            throw t instanceof RuntimeException re ? re : new RuntimeException(t);
        }
        if (result instanceof Disposable d) {
            disposables.add(d);
        } else if (result instanceof CompletableFuture<?> cf) {
            ((CompletableFuture<Disposable>) cf).thenAccept(d -> {
                if (d != null) disposables.add(d);
            }).exceptionally(t -> { ctx.logger().error(t); return null; });
        } else if (result instanceof Iterable<?> it) {
            for (Object o : it) if (o instanceof Disposable d) disposables.add(d);
        } else if (result != null) {
            throw new IllegalArgumentException("Invalid effect");
        }

        this._disposables.push(dispose);
        return () -> dispose.dispose();
    }

    /** Metadata tree for effect diagnostics (fiber.ts:96-101). */
    public record EffectMeta(String label, List<EffectMeta> children) {}

    /** Return metadata for currently registered effects (fiber.ts:568-572). */
    public List<EffectMeta> getEffects() {
        return List.of();
    }

    // ---- dependency / epoch machinery (fiber.ts:597-696) ----

    void checkImpl(String name) {
        Reflect.Impl impl = this.ctx.reflect.getImpl(name, true);
        if (impl == null) { storeRemove(name); return; }
        try {
            if (impl.check != null && !impl.check.test(ctx)) { storeRemove(name); return; }
        } catch (Exception e) {
            impl.fiber.ctx.logger().error(e);
            storeRemove(name); return;
        }
        storePut(name, impl);
    }

    void refresh() {
        String epoch = "";
        for (String name : inject.keySet()) {
            Reflect.Impl impl = storeGet(name);
            if (impl == null) { epoch = INACTIVE; break; }
            epoch += ":" + impl.fiber.uid;
        }
        setEpoch(epoch);
    }

    private void setEpoch(String newEpoch) {
        String oldEpoch = this.epoch;
        if (Objects.equals(newEpoch, oldEpoch)) return;
        this.epoch = newEpoch;
        if (this.inertia != null) return;
        if (!Objects.equals(newEpoch, INACTIVE) && Objects.equals(oldEpoch, INACTIVE)) {
            this.state = FiberState.LOADING;
            this.inertia = this.reload();
        } else {
            this.state = FiberState.UNLOADING;
            this.inertia = this.unload();
        }
    }

    /** Load the plugin: resolve config, run apply (fiber.ts:646-673). */
    @SuppressWarnings("unchecked")
    CompletableFuture<Void> reload() {
        this.store = storeSnapshot();
        String oldEpoch = this.epoch;
        try {
            this.config = resolveConfig(this._config);
            ((Plugin<Object>) this.runtime.callback).apply(this.ctx, this.config);
            this._error = null;
        } catch (Throwable t) {
            this.ctx.logger().error(t);
            this._error = t;
            this.epoch = INACTIVE;
        }
        if (Objects.equals(this.epoch, oldEpoch)) {
            this.state = FiberState.ACTIVE;
            this.inertia = null;
        } else {
            this.state = FiberState.UNLOADING;
            this.inertia = this.unload();
        }
        return this.inertia == null ? CompletableFuture.completedFuture(null) : this.inertia;
    }

    /** Unload: run disposers in reverse order (fiber.ts:675-696). */
    CompletableFuture<Void> unload() {
        List<Disposable> toRun = this._disposables.clear();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Disposable d : toRun) {
            chain = chain.thenCompose(v -> safeDispose(d));
        }
        return chain.thenCompose(v -> {
            this.store = null;
            if (Objects.equals(this.epoch, INACTIVE)) {
                this.inertia = null;
                // fiber.ts:_getState — no pending reload: FAILED if errored, else PENDING
                this.state = this._error != null ? FiberState.FAILED : FiberState.PENDING;
            } else {
                this.state = FiberState.LOADING;
                this.inertia = this.reload();
                return this.inertia;
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletableFuture<Void> safeDispose(Disposable d) {
        try {
            return d.dispose();
        } catch (Throwable t) {
            ctx.logger().error(t);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Validate config against the runtime's Config validator (fiber.ts:50-62, 641-644). */
    Object resolveConfig(Object rawConfig) {
        if (runtime == null || runtime.config() == null) return rawConfig;
        if (rawConfig instanceof JsonNode node) {
            return runtime.config().validate(node);
        }
        return rawConfig;
    }

    /** Wait for lifecycle work and rethrow startup errors (fiber.ts:704-710). */
    public CompletableFuture<Fiber> await() {
        CompletableFuture<Fiber> future = new CompletableFuture<>();
        awaitInternal().whenComplete((v, t) -> {
            if (this._error != null) future.completeExceptionally(this._error);
            else future.complete(this);
        });
        return future;
    }

    private CompletableFuture<Void> awaitInternal() {
        if (this.inertia != null) return this.inertia;
        return CompletableFuture.completedFuture(null);
    }

    /** Dispose and immediately reload with current config (fiber.ts:718-723). */
    public CompletableFuture<Void> restart() {
        assertActive();
        this.epoch = INACTIVE;
        this.refresh();
        return awaitInternal().thenApply(v -> null);
    }

    /** Validate and apply new config, then restart (fiber.ts:736-753). Simplified:
     *  no internal/update waterfall (deferred). */
    public CompletableFuture<Void> update(Object config, boolean noSave) {
        assertActive();
        this._config = config;
        if (this.state != FiberState.ACTIVE) {
            this._error = null;
            this.epoch = INACTIVE;
            this.refresh();
            return CompletableFuture.completedFuture(null);
        }
        this.config = resolveConfig(config);
        this._error = null;
        return restart();
    }

    /** Dispose this fiber: unload, then settle once cleanup finished. */
    public CompletableFuture<Void> dispose() {
        if (this.state == FiberState.DISPOSED) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> done = unload();
        return done.thenAccept(v -> {
            this.state = FiberState.DISPOSED;
            this._error = null;
        });
    }

    private Map<String, Reflect.Impl> storeSnapshot() {
        return this.store == null ? new HashMap<>() : new HashMap<>(this.store);
    }
    private Reflect.Impl storeGet(String n) { return this.store == null ? null : this.store.get(n); }
    private void storePut(String n, Reflect.Impl i) { if (this.store == null) this.store = new HashMap<>(); this.store.put(n, i); }
    private void storeRemove(String n) { if (this.store != null) this.store.remove(n); }
}
