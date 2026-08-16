package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;

/** Plugin registry installed as ctx.registry (registry.ts:195-337). */
public final class Registry {
    private int _counter = 0;
    private final Map<Plugin<?>, Plugin.Runtime> _internal = new IdentityHashMap<>();

    public final Context ctx;

    public Registry(Context ctx) { this.ctx = ctx; }

    /** Allocate the next fiber uid (registry.ts:207-209). */
    int counter() { return ++_counter; }

    public int size() { return _internal.size(); }

    /** Look up the runtime record for a plugin (registry.ts:236-239). */
    public Plugin.Runtime get(Plugin<?> plugin) { return _internal.get(plugin); }

    public boolean has(Plugin<?> plugin) { return _internal.containsKey(plugin); }

    /** Dispose every running fiber for a plugin and remove its runtime (registry.ts:258-267). */
    public void delete(Plugin<?> plugin) {
        Plugin.Runtime runtime = _internal.remove(plugin);
        if (runtime == null) return;
        for (Fiber fiber : runtime.fibers) {
            fiber.dispose();
        }
    }

    public Collection<Plugin.Runtime> values() { return _internal.values(); }

    /** Iterate the registered plugin callbacks (registry.ts:270-272). */
    public Set<Plugin<?>> keys() { return _internal.keySet(); }

    /** Iterate {@code [callback, runtime]} pairs (registry.ts:280-282). */
    public Set<Map.Entry<Plugin<?>, Plugin.Runtime>> entries() { return _internal.entrySet(); }

    /** Visit every registered runtime, receiving (runtime, plugin) like registry.ts:289-291. */
    public void forEach(java.util.function.BiConsumer<Plugin.Runtime, Plugin<?>> action) {
        _internal.forEach((plugin, runtime) -> action.accept(runtime, plugin));
    }

    /** Start a callback once requested dependencies are available (registry.ts:300-302). */
    public Fiber inject(Context caller, Inject deps, PluginSpec.PluginApply<Void> callback) {
        PluginSpec<Void> spec = PluginSpec.of(callback);
        spec.inject(deps.entries.keySet().toArray(String[]::new));
        for (var e : deps.entries.entrySet()) {
            if (e.getValue() != null) spec.injectConfig(e.getKey(), e.getValue());
        }
        return plugin(caller, spec, null);
    }

    /** Start a plugin in the current context and return its fiber (registry.ts:316-336). */
    public Fiber plugin(Context caller, Plugin<?> plugin, Object config) {
        caller.fiber.assertActive();

        Plugin.Runtime runtime = _internal.get(plugin);
        if (runtime == null) {
            runtime = new Plugin.Runtime(plugin.name(), plugin, plugin.config());
            _internal.put(plugin, runtime);
        }

        // @Inject class annotations + inject()/injectConfig() overrides (registry.ts:71-89, 330)
        Map<String, Object> injectMap = Inject.resolve(plugin);

        Fiber fiber = new Fiber(caller, config, injectMap, runtime);
        runtime.fibers.push(fiber);

        // 级联:父 fiber 卸载时 dispose 本插件 fiber(fiber.ts:265)
        caller.fiber.effect(() -> (Disposable) () -> fiber.dispose(), "ctx.plugin()");

        // Publish only after the parent owns a fully assigned disposer (fiber.ts:299-303);
        // a synchronous observer may dispose this fiber, so rethrow on failure after cleanup.
        try {
            fiber.context.emit("internal/plugin", fiber);
        } catch (Throwable t) {
            fiber.dispose();
            throw t;
        }

        // publication + dependency resolution (fiber.ts:299-319)
        if (fiber.uid != 0 && caller.fiber.state != FiberState.UNLOADING) {
            for (String name : injectMap.keySet()) {
                fiber.checkImpl(name);
            }
            fiber.refresh();
        }
        return fiber;
    }
}
