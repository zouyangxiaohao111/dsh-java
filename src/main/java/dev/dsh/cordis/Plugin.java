package dev.dsh.cordis;

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
}
