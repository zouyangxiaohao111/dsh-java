package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.JsPluginAdapter;
import dev.dsh.cordis.js.PluginRuntimeResolver;
import dev.dsh.cordis.js.ResolvedJsPlugin;

import java.nio.file.Path;

/**
 * 编排 JS 插件热重载:文件变更 → 关旧 JsHost → resolver 选宿主(② GraalJS / ③ Node
 * worker,静态检测 + 运行时兜底)加载新模块 → registry 换。
 *
 * <p>M4 起经 {@link PluginRuntimeResolver} 选宿主:纯 JS 插件仍走进程内 GraalJS,ESM/
 * native/重 Node 插件自动落到真 Node worker。
 */
public final class JsPluginReloader {
    private final Context ctx;
    private final PluginRuntimeResolver resolver;

    public JsPluginReloader(Context ctx) {
        this(ctx, new PluginRuntimeResolver());
    }

    public JsPluginReloader(Context ctx, PluginRuntimeResolver resolver) {
        this.ctx = ctx;
        this.resolver = resolver;
    }

    public JsPluginAdapter reload(Path jsFile, JsPluginAdapter oldAdapter, Object config) throws Exception {
        JsHost oldHost = oldAdapter.host();
        if (oldHost != null) oldHost.close();
        // 文件内容可能已变(CJS→ESM 等)→ 静态判定缓存失效,重选宿主
        resolver.invalidate(jsFile);
        ResolvedJsPlugin resolved = resolver.loadJs(jsFile);
        ctx.registry.delete(oldAdapter);
        ctx.registry.plugin(ctx, resolved.adapter(), config);
        return resolved.adapter();
    }
}
