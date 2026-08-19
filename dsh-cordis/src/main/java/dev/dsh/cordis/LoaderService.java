package dev.dsh.cordis;

import java.util.HashMap;
import java.util.Map;

/**
 * Built-in loader service (cordis's {@code @cordisjs/loader}): the bridge-visible
 * {@code ctx.loader} for JS plugins that inject or read it (e.g.
 * {@code cordis-plugin-hmr}). M7-8 B+C: Java core provides the minimal surface
 * the real consumers actually touch.
 *
 * <p>The Java core owns loading and hot-reload (dsh-loader / dsh-reload); this
 * service only exposes the <i>contract</i>. {@link #internal} is present so hmr's
 * constructor can bind it ({@code this.internal = ctx.loader.internal}) without
 * failing its {@code --expose-internals} guard; the deeper internals usage
 * (module-cache manipulation / specifier resolution) is owned by the Java core
 * and is deliberately not routed through this facade — callers get a clear error.
 */
public final class LoaderService extends Service {
    /** Node module-internals surface ({@code --expose-internals} equivalent). */
    public final LoaderInternal internal = new LoaderInternal();

    public LoaderService(Context ctx) {
        super(ctx, "loader");
    }

    /** Node module-loader internals facade (the surface hmr reads). */
    public static final class LoaderInternal {
        /** v1/v2 specifier-resolution protocol (hmr {@code _resolve} switch). */
        public String version = "v1";
        /** Module cache facade keyed by URL. Empty: the Java core drives loading;
         *  worker-side module-cache manipulation is not wired through here. */
        public Map<String, Object> loadCache = new HashMap<>();

        /** Not supported by the Java loader (specifier resolution crosses into the
         *  worker's Node resolver, owned by the core). Callers get a clear error. */
        public Object resolve(String specifier, String parentURL, Object attributes) {
            throw new UnsupportedOperationException(
                    "loader.internal.resolve is not supported by the Java loader");
        }

        /** Not supported by the Java loader (see {@link #resolve}). */
        public Object resolveSync(String parentURL, Object options) {
            throw new UnsupportedOperationException(
                    "loader.internal.resolveSync is not supported by the Java loader");
        }
    }
}
