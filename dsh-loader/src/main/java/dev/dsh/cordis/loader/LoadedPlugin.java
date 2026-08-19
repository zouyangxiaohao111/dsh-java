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
 * <p>{@code source} 为可监听的热更新源文件(JS 入口 / 编译型 .java 源 / jar 插件文件),
 * 类名型 Java 插件为 null(无源文件可监听)。注册/卸载都经 {@link Context#registry},复用 M3
 * 的 Fiber/回滚语义。
 *
 * <p>{@code classLoader} 为 Java 插件的隔离 ClassLoader(源码编译 / .class / jar / 类名各路径
 * 均有),卸载/替换时关闭释放资源;JS 插件为 null。
 */
public final class LoadedPlugin implements AutoCloseable {
    private final Entry entry;
    private final HostKind kind;
    private final String ref;
    private final Plugin<?> plugin;
    private final JsHost host;           // JS 插件宿主,null for Java
    private final ClassLoader classLoader; // Java 插件隔离 CL,null for JS
    private final Path source;           // 热更新监听源文件,null for 类名型 Java
    private final FileWatcher watcher;
    private final boolean sharedHost;    // M9-1:进程组共享宿主(close 不关 host,由组生命周期回收)

    LoadedPlugin(Entry entry, HostKind kind, String ref, Plugin<?> plugin, JsHost host, Path source,
                 ClassLoader classLoader) {
        this(entry, kind, ref, plugin, host, source, classLoader, false);
    }

    /** 8 参构造:共享宿主标记(M9-1 进程组)。共享宿主由 {@link PluginLoaderService} 的组回收,不随插件 close。 */
    LoadedPlugin(Entry entry, HostKind kind, String ref, Plugin<?> plugin, JsHost host, Path source,
                 ClassLoader classLoader, boolean sharedHost) {
        this.entry = entry;
        this.kind = kind;
        this.ref = ref;
        this.plugin = plugin;
        this.host = host;
        this.classLoader = classLoader;
        this.source = source;
        this.watcher = source != null ? new FileWatcher(source) : null;
        this.sharedHost = sharedHost;
    }

    public Entry entry() { return entry; }
    public HostKind kind() { return kind; }
    public String ref() { return ref; }
    public Plugin<?> plugin() { return plugin; }
    public JsHost host() { return host; }
    public ClassLoader classLoader() { return classLoader; }
    public Path source() { return source; }

    /** 源文件是否已变更(M3 FileWatcher 轮询;类名型 Java 恒 false)。 */
    public boolean sourceChanged() {
        return watcher != null && watcher.changed();
    }

    /** 注册进 registry(开始 fiber),透传条目的 {@code config}(dsh profile 组合行 M6-6)。 */
    public void register(Context ctx) {
        ctx.registry.plugin(ctx, plugin, entry.config());
    }

    /** 从 registry 卸载(dispose fiber)并释放底层资源(JS 宿主 / Java 隔离 ClassLoader)。 */
    public void unregister(Context ctx) {
        ctx.registry.delete(plugin);
        releaseResources();
    }

    /** 仅从 registry 解除 runtime(dispose fiber),保留底层宿主 —— 替换/回滚提交前先腾出
     *  服务名;新实现校验失败时旧宿主仍可用,可重新 {@link #register(Context)} 恢复旧实现。 */
    public void unregisterRuntime(Context ctx) {
        ctx.registry.delete(plugin);
    }

    /** 仅释放底层资源(用于已加载未注册的句柄回滚清理)。JS 宿主 close 抛错不阻断 CL 释放。 */
    @Override
    public void close() {
        releaseResources();
    }

    private void releaseResources() {
        // M9-1:共享宿主(进程组)不随插件 close——由 PluginLoaderService 的组回收统一关闭,
        // 避免一个组成员卸载时把整组 worker 关掉。
        if (host != null && !sharedHost) host.close();
        if (classLoader instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
                // URLClassLoader.close() 释放已加载 jar 的文件句柄;失败仅泄漏句柄,不影响卸载
            }
        }
    }

    /** 是否进程组共享宿主(M9-1):host 归 {@link PluginLoaderService} 组回收,不随本插件 close。 */
    public boolean sharedHost() { return sharedHost; }
}
