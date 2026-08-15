package dev.dsh.cordis;

import dev.dsh.cordis.util.DisposableList;

import java.util.Map;

/** Plugin entrypoint (registry.ts:92-146).
 *  Call `apply(ctx, config)` when all declared deps are available. */
public interface Plugin<T> {
    void apply(Context ctx, T config) throws Exception;

    /** Display name for fiber diagnostics and loggers. */
    default String name() { return null; }

    /** Services this plugin requires; it only loads while all are available. */
    default String[] inject() { return new String[0]; }

    /** Service name → intercept config dependencies (map form). */
    default Map<String, Object> injectConfig() { return Map.of(); }

    /** Service name(s) this plugin provides. */
    default String[] provide() { return new String[0]; }

    /** Config validator applied before activation. */
    default ConfigValidator<T> config() { return null; }

    /** Mutable registry record shared by all fibers of one plugin (registry.ts:136-145). */
    final class Runtime {
        public final String name;
        public final Plugin<?> callback;
        public final DisposableList<Fiber> fibers = new DisposableList<>();
        public final ConfigValidator<?> config;

        Runtime(String name, Plugin<?> callback, ConfigValidator<?> config) {
            this.name = name; this.callback = callback; this.config = config;
        }
        public String name() { return name; }
        public ConfigValidator<?> config() { return config; }
    }
}
