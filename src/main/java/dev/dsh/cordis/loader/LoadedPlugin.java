package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.JsPluginAdapter;
import dev.dsh.cordis.reload.FileWatcher;

import java.nio.file.Path;

/**
 * 一个已加载的插件句柄:持有 yml 条目、最终宿主、registry 内的 {@link Plugin} 对象(JS 经
 * {@link JsPluginAdapter})与底层 {@link JsHost}(JS 宿主,卸载时关闭)。
 *
 * <p>{@code source} 为可监听的热更新源文件(JS 入口 / 编译型 .java 源),类名型 Java 插件为
 * null(无源文件可监听)。注册/卸载都经 {@link Context#registry},复用 M3 的 Fiber/回滚语义。
 */
public final class LoadedPlugin implements AutoCloseable {
    private final Entry entry;
    private final HostKind kind;
    private final String ref;
    private final Plugin<?> plugin;
    private final JsHost host;        // JS 插件宿主,null for Java
    private final Path source;        // 热更新监听源文件,null for 类名型 Java
    private final FileWatcher watcher;

    LoadedPlugin(Entry entry, HostKind kind, String ref, Plugin<?> plugin, JsHost host, Path source) {
        this.entry = entry;
        this.kind = kind;
        this.ref = ref;
        this.plugin = plugin;
        this.host = host;
        this.source = source;
        this.watcher = source != null ? new FileWatcher(source) : null;
    }

    public Entry entry() { return entry; }
    public HostKind kind() { return kind; }
    public String ref() { return ref; }
    public Plugin<?> plugin() { return plugin; }
    public JsHost host() { return host; }
    public Path source() { return source; }

    /** 源文件是否已变更(M3 FileWatcher 轮询;类名型 Java 恒 false)。 */
    public boolean sourceChanged() {
        return watcher != null && watcher.changed();
    }

    /** 注册进 registry(开始 fiber)。 */
    public void register(Context ctx) {
        ctx.registry.plugin(ctx, plugin, null);
    }

    /** 从 registry 卸载(dispose fiber)并关闭底层 JS 宿主(如有)。 */
    public void unregister(Context ctx) {
        ctx.registry.delete(plugin);
        if (host != null) host.close();
    }

    /** 仅从 registry 解除 runtime(dispose fiber),保留底层宿主 —— 替换/回滚提交前先腾出
     *  服务名;新实现校验失败时旧宿主仍可用,可重新 {@link #register(Context)} 恢复旧实现。 */
    public void unregisterRuntime(Context ctx) {
        ctx.registry.delete(plugin);
    }

    /** 仅释放底层宿主(用于已加载未注册的句柄回滚清理)。 */
    @Override
    public void close() {
        if (host != null) host.close();
    }
}
