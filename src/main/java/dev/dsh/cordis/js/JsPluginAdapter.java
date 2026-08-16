package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;

/**
 * Wraps a JS plugin module (function or { apply } object) as a Java Plugin (design §3.4)。
 * 自 M4 起驱动 {@link PluginModule} 抽象,不再绑定 GraalJS Value:
 * 同一适配器既可包 GraalPluginModule,也可包 NodePluginModule。
 */
public final class JsPluginAdapter implements Plugin<Object> {
    private final JsHost host;
    private final PluginModule module;
    private final String name;
    private final String[] inject;
    private final String[] provide;

    public JsPluginAdapter(JsHost host, PluginModule module) {
        this.host = host;
        this.module = module;
        this.name = module.name();
        this.inject = module.inject();
        this.provide = module.provide();
    }

    @Override public String name() { return name; }
    @Override public String[] inject() { return inject; }
    @Override public String[] provide() { return provide; }

    /** The underlying JsHost backing this adapter (hot-reload closes it on swap). */
    public JsHost host() { return host; }

    @Override
    public Object apply(Context ctx, Object config) {
        return module.apply(ctx, config);
    }
}
