package dev.dsh.cordis;

import java.util.*;

/** Base class for services exposing a named API on ctx (service.ts:11-115). */
public abstract class Service {
    protected final Context ctx;
    public final String name;

    protected Service(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
        ctx.provide(name, this, ignored -> check());
    }

    /** Availability predicate consulted before dependents may load. */
    protected boolean check() { return true; }

    /** Merge intercept config from ancestors with optional base and head (service.ts:86-102). */
    @SuppressWarnings("unchecked")
    protected <T> T resolveConfig(T base, T head) {
        List<Object> configs = new ArrayList<>();
        Context c = this.ctx;
        while (c != null) {
            if (c.intercept.containsKey(this.name)) configs.add(0, c.intercept.get(this.name));
            c = c.parent;
        }
        if (base != null) configs.add(0, base);
        if (head != null) configs.add(head);
        Object merged = null;
        for (Object cfg : configs) {
            if (cfg instanceof Map<?, ?> m) {
                Map<Object, Object> acc = merged instanceof Map<?, ?> am
                        ? new LinkedHashMap<>(am) : new LinkedHashMap<>();
                acc.putAll(m);
                merged = acc;
            } else {
                merged = cfg;
            }
        }
        return (T) merged;
    }
}
