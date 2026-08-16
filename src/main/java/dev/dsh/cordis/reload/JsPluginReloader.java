package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.GraalJsHost;
import dev.dsh.cordis.js.JsPluginAdapter;
import dev.dsh.cordis.js.PluginModule;

import java.nio.file.Path;

/** 编排 JS 插件热重载:文件变更 → 关旧 JsHost → 新 JsHost 加载 → registry 换。 */
public final class JsPluginReloader {
    private final Context ctx;

    public JsPluginReloader(Context ctx) { this.ctx = ctx; }

    public JsPluginAdapter reload(Path jsFile, JsPluginAdapter oldAdapter, Object config) throws Exception {
        JsHost oldHost = oldAdapter.host();
        if (oldHost != null) oldHost.close();
        JsHost newHost = new GraalJsHost(jsFile.getParent());
        PluginModule exports = newHost.loadModule(jsFile.toAbsolutePath());
        JsPluginAdapter newAdapter = new JsPluginAdapter(newHost, exports);
        ctx.registry.delete(oldAdapter);
        ctx.registry.plugin(ctx, newAdapter, config);
        return newAdapter;
    }
}
