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
    /** Same as {@link #ctx}; the fiber.ts `context` alias used by core hooks. */
    public final Context context;
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

    /** Per-fiber listeners for the `internal/update` waterfall (fiber.ts:202). */
    public final Map<String, DisposableList<Events.Listener>> _hooks = new HashMap<>();
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
        this.context = this.ctx;
        this.ctx.fiber = this;   // rebind: plugin context's fiber is this fiber (fiber.ts:236)

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

    /** Set the lifecycle state and emit `internal/status` on any change (fiber.ts:587-595).
     *  During root-context construction the events bus is not yet wired, so the
     *  emission is skipped there. */
    private void setState(FiberState newState) {
        FiberState oldState = this.state;
        if (oldState == newState) return;
        this.state = newState;
        if (this.ctx.events != null) this.context.emit("internal/status", this, oldState);
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
        if (this.state == FiberState.UNLOADING) {
            throw new CordisError(CordisError.Code.INACTIVE_EFFECT);
        }
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
        Reflect.Impl impl = this.ctx.reflect.getImpl(this.ctx, name, true);
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
            setState(FiberState.LOADING);
            this.reload();   // reload() 自己管理 this.inertia
        } else {
            setState(FiberState.UNLOADING);
            this.unload();   // unload() 自己管理 this.inertia
        }
    }

    /** Load the plugin: resolve config, run apply (fiber.ts:646-673). */
    @SuppressWarnings("unchecked")
    CompletableFuture<Void> reload() {
        this.store = storeSnapshot();
        String oldEpoch = this.epoch;
        try {
            this.config = resolveConfig(this._config);
            Object result = ((Plugin<Object>) this.runtime.callback).apply(this.ctx, this.config);
            if (result instanceof CompletableFuture<?> cf) {
                ((CompletableFuture<Void>) cf).join();   // 等待异步 apply 完成(同步模型下 join 务实)
            }
            this._error = null;
        } catch (Throwable t) {
            this.ctx.logger().error(t);
            this._error = t;
            this.epoch = INACTIVE;
        }
        if (Objects.equals(this.epoch, oldEpoch)) {
            setState(FiberState.ACTIVE);
            this.inertia = null;
            // fiber.ts:_updateState — notify dependents of services this fiber provides
            if (this.store != null) {
                List<String> provided = new ArrayList<>();
                for (Map.Entry<String, Reflect.Impl> e : this.store.entrySet()) {
                    if (e.getValue() != null && e.getValue().fiber == this) provided.add(e.getKey());
                }
                if (!provided.isEmpty()) this.ctx.reflect.notify(provided);
            }
        } else {
            setState(FiberState.UNLOADING);
            this.unload();   // unload() 自己管理 this.inertia
        }
        return this.inertia;
    }

    /** Unload: run disposers in reverse order (fiber.ts:675-696). */
    CompletableFuture<Void> unload() {
        List<Disposable> toRun = this._disposables.clear();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Disposable d : toRun) {
            chain = chain.thenCompose(v -> safeDispose(d));
        }
        this.inertia = chain;   // 同步标记在飞(基链),覆盖异步窗口;终态 continuation 再置 null 或调 reload
        return chain.thenCompose(v -> {
            this.store = null;
            if (Objects.equals(this.epoch, INACTIVE)) {
                this.inertia = null;
                // fiber.ts:_getState — no pending reload: FAILED if errored, else PENDING
                setState(this._error != null ? FiberState.FAILED : FiberState.PENDING);
                return CompletableFuture.completedFuture(null);
            } else {
                setState(FiberState.LOADING);
                this.reload();   // reload() 自己管理 this.inertia
                return this.inertia != null ? this.inertia : CompletableFuture.completedFuture(null);
            }
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

    /** Resolve raw config through the `internal/config` waterfall, then validate
     *  against the runtime's Config validator (fiber.ts:50-62, 641-644). */
    Object resolveConfig(Object rawConfig) {
        Object config = this.context.waterfall(this.ctx, "internal/config", rawConfig,
                (Events.Listener) (ctx, args) -> rawConfig);
        if (runtime == null || runtime.config() == null) return config;
        if (config instanceof JsonNode node) {
            return runtime.config().validate(node);
        }
        return config;
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
        setEpoch(INACTIVE);  // 若 ACTIVE,先卸载旧 effects
        refresh();           // 重算依赖,可触发 reload
        return awaitInternal().thenApply(v -> null);
    }

    /** Validate and apply new config, then restart through the `internal/update`
     *  waterfall (fiber.ts:736-753); update hooks may veto the restart by not
     *  calling `next`.
     *
     *  <p>返回契约镜像 fiber.ts:753:waterfall 的结果<b>原样透出</b>——默认路径返回
     *  {@code restart()} 的 {@link CompletableFuture};veto 路径返回否决值本身(非
     *  future,如 {@code false} / 自定义标记),不包一层空 CF(调用方据此区分"已重启"
     *  与"被否决")。fiber 非 ACTIVE 时直接返回 {@code null}(fiber.ts:739-745)。 */
    public Object update(Object config, boolean noSave) {
        assertActive();
        this._config = config;
        if (this.state != FiberState.ACTIVE) {
            this._error = null;
            this.epoch = INACTIVE;
            this.refresh();
            return null;
        }
        config = resolveConfig(config);
        final Object resolved = config;
        return this.context.waterfall(this.ctx, "internal/update", resolved, noSave,
                (Events.Listener) (ctx, args) -> {
                    this.config = resolved;
                    this._error = null;
                    return restart();
                });
    }

    /** Dispose this fiber: unload, then settle once cleanup finished. */
    public CompletableFuture<Void> dispose() {
        if (this.state == FiberState.DISPOSED) return CompletableFuture.completedFuture(null);
        this.epoch = INACTIVE;   // unload 不得重载 ACTIVE fiber
        CompletableFuture<Void> done = unload();
        return done.thenAccept(v -> {
            setState(FiberState.DISPOSED);
            this._error = null;
            if (this.runtime != null) {
                this.runtime.fibers.delete(this);   // 除名,防止 notify 复活(fiber.ts:266-275)
            }
        });
    }

    private Map<String, Reflect.Impl> storeSnapshot() {
        return this.store == null ? new HashMap<>() : new HashMap<>(this.store);
    }
    private Reflect.Impl storeGet(String n) { return this.store == null ? null : this.store.get(n); }
    private void storePut(String n, Reflect.Impl i) { if (this.store == null) this.store = new HashMap<>(); this.store.put(n, i); }
    private void storeRemove(String n) { if (this.store != null) this.store.remove(n); }
}
