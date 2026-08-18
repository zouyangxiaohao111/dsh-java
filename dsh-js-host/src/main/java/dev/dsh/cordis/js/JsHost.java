package dev.dsh.cordis.js;

import java.nio.file.Path;

/**
 * JS 插件运行时抽象(M2 深化 §3.5;M4 三宿主)。两个实现:
 *
 * <ul>
 *   <li>{@link GraalJsHost} — 进程内 GraalJS(共享 Context,现有路径);</li>
 *   <li>{@link NodeWorkerJsHost} — 进程外真 Node,stdin/stdout NDJSON RPC。</li>
 * </ul>
 *
 * {@code eval}/{@code require}/{@code loadModule} 返回宿主无关的 {@link PluginModule}
 * (GraalJS 包装 polyglot Value;Node worker 包装远程句柄)。
 */
public interface JsHost extends AutoCloseable {
    /** Evaluate a JS expression and return the resulting plugin-module handle. */
    PluginModule eval(String script);

    /** Load a module by specifier relative to the host's require cwd. */
    PluginModule require(String specifier);

    /** Load a module file by absolute path (CJS or ESM). */
    PluginModule loadModule(Path file);

    /**
     * 求值一个 dsh {@code !!js} 表达式(scope = process + dshHomePath,与 config 通道
     * {@code node-bridge.js evalJsExpression} 同面)。返回求值结果(原始值:Boolean/Number/
     * String/null)。M7-6 disabled 通道用:loader 在加载前求值 {@code disabled} 标记。
     */
    Object evalJs(String expr);

    @Override
    void close();
}
