package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;

/**
 * 一个已加载的 JS 插件模块,宿主无关(design §2.2 架构图)。
 *
 * <p>{@link JsPluginAdapter} 统一经本抽象驱动插件,不碰具体宿主:
 * GraalJS 实现包装 polyglot {@code Value},Node worker 实现包装远程句柄。
 */
public interface PluginModule {
    /** The plugin's display name (member {@code name}), or null when absent. */
    String name();

    /** Services this plugin requires (member {@code inject}). */
    String[] inject();

    /** Services this plugin provides (member {@code provide}). */
    String[] provide();

    /**
     * Apply this plugin to a Java cordis {@link Context}.
     *
     * @return null, a disposer, or a value; an async apply may return a {@code CompletableFuture}.
     */
    Object apply(Context ctx, Object config);
}
