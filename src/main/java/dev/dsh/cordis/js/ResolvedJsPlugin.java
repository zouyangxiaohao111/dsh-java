package dev.dsh.cordis.js;

/**
 * {@link PluginRuntimeResolver} 的产出:一个已解析并加载好的 JS 插件 + 其宿主 + 决策
 * (design §2.2 架构图)。调用方持有该记录,并把 {@link #adapter()} 交给
 * {@code ctx.registry.plugin(...)};宿主生命周期随 {@link JsPluginAdapter} 走
 * (热重载关闭旧宿主),或直接 {@link #close()} 释放。
 */
public record ResolvedJsPlugin(HostKind kind, JsHost host, JsPluginAdapter adapter) implements AutoCloseable {

    @Override
    public void close() {
        host.close();
    }
}
