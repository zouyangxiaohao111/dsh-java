package dev.dsh.cordis;

import java.util.*;

/** Service dependency declaration (registry.ts:19-20). */
public final class Inject {
    /** name → intercept config (null = plain dependency). */
    public final Map<String, Object> entries;

    private Inject(Map<String, Object> entries) { this.entries = entries; }

    public static Inject of(String... names) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String n : names) m.put(n, null);
        return new Inject(Collections.unmodifiableMap(m));
    }

    public static Inject config(Map<String, Object> nameToConfig) {
        return new Inject(Collections.unmodifiableMap(new LinkedHashMap<>(nameToConfig)));
    }

    /** Merge own entries over inherited ones (registry.ts:71-89). */
    public static Map<String, Object> resolve(Inject inject, Map<String, Object> inherited) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (inherited != null) result.putAll(inherited);
        if (inject != null) result.putAll(inject.entries);
        return result;
    }

    /** Normalize a plugin's dependency declarations (registry.ts:71-89, 330).
     *  <p>Order: {@code @Inject} class annotations (incl. superclasses) first,
     *  then the {@code inject()} override, then {@code injectConfig()} — later
     *  entries override earlier ones, so an explicit override beats an
     *  annotation for the same service name. */
    public static Map<String, Object> resolve(Plugin<?> plugin) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Class<?> c = plugin.getClass(); c != null; c = c.getSuperclass()) {
            for (dev.dsh.cordis.annotation.Inject ann
                    : c.getAnnotationsByType(dev.dsh.cordis.annotation.Inject.class)) {
                for (String name : ann.value()) result.put(name, null);
            }
        }
        for (String name : plugin.inject()) result.put(name, null);
        result.putAll(plugin.injectConfig());
        return result;
    }
}
