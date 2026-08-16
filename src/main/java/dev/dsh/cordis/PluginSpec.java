package dev.dsh.cordis;

import java.util.*;

/** Mutable fluent plugin descriptor; identity is the registry key. */
public final class PluginSpec<T> implements Plugin<T> {
    private final PluginApply<T> entrypoint;
    private String name;
    private final List<String> inject = new ArrayList<>();
    private final List<String> provide = new ArrayList<>();
    private ConfigValidator<T> config;
    private final Map<String, Object> injectConfig = new LinkedHashMap<>();

    @FunctionalInterface
    public interface PluginApply<T> {
        Object apply(Context ctx, T config) throws Exception;
    }

    private PluginSpec(PluginApply<T> apply) { this.entrypoint = apply; }

    public static <T> PluginSpec<T> of(PluginApply<T> apply) { return new PluginSpec<>(apply); }

    public PluginSpec<T> name(String name) { this.name = name; return this; }
    public PluginSpec<T> inject(String... names) { this.inject.addAll(Arrays.asList(names)); return this; }
    public PluginSpec<T> injectConfig(String name, Object config) { this.injectConfig.put(name, config); return this; }
    public PluginSpec<T> provide(String... names) { this.provide.addAll(Arrays.asList(names)); return this; }
    public PluginSpec<T> config(ConfigValidator<T> config) { this.config = config; return this; }

    @Override public String name() { return name; }
    @Override public String[] inject() { return inject.toArray(String[]::new); }
    @Override public String[] provide() { return provide.toArray(String[]::new); }
    @Override public ConfigValidator<T> config() { return config; }
    @Override public Object apply(Context ctx, T config) throws Exception { return entrypoint.apply(ctx, config); }
    @Override public Map<String, Object> injectConfig() {
        return Collections.unmodifiableMap(injectConfig);
    }
}
