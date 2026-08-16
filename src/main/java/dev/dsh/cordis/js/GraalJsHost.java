package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.nio.file.Path;

/** GraalJS 版 JsHost:管理共享 GraalJS Context(design §3.2)。 */
public final class GraalJsHost implements JsHost {
    private final Context context;

    public GraalJsHost() {
        this.context = Context.newBuilder("js")
                // M2 可信插件假设:允许 JS 调用 Java 服务公共成员,跨语言服务无需每类 @HostAccess.Export 注解
                .allowHostAccess(HostAccess.ALL)
                .allowExperimentalOptions(true)
                .allowIO(true)
                .option("js.commonjs-require", "true")
                .build();
    }

    public GraalJsHost(Path requireCwd) {
        this.context = Context.newBuilder("js")
                // M2 可信插件假设:允许 JS 调用 Java 服务公共成员,跨语言服务无需每类 @HostAccess.Export 注解
                .allowHostAccess(HostAccess.ALL)
                .allowExperimentalOptions(true)
                .allowIO(true)
                .option("js.commonjs-require", "true")
                .option("js.commonjs-require-cwd", requireCwd.toAbsolutePath().toString())
                .build();
    }

    /** Evaluate a JS expression and return the resulting plugin-module handle. */
    @Override public PluginModule eval(String script) {
        return new GraalPluginModule(this, context.eval("js", script));
    }

    /** Load a CommonJS module by specifier relative to the require cwd. */
    @Override public PluginModule require(String specifier) {
        return new GraalPluginModule(this, context.eval("js", "require(" + quoted(specifier) + ")"));
    }

    /** Load a CommonJS module file by absolute path. */
    @Override public PluginModule loadModule(Path file) {
        return new GraalPluginModule(this, context.eval("js", "require(" + quoted(file.toAbsolutePath().toString()) + ")"));
    }

    /** The underlying GraalJS context (bridge interop entry; was {@code graalContext()}). */
    public Context context() { return context; }

    /** GraalJS 专属:直接取 polyglot Value(桥/测试直接互操作用)。 */
    public Value evalValue(String script) { return context.eval("js", script); }

    /** GraalJS 专属:直接取 polyglot Value。 */
    public Value requireValue(String specifier) { return context.eval("js", "require(" + quoted(specifier) + ")"); }

    /** GraalJS 专属:直接取 polyglot Value。 */
    public Value loadModuleValue(Path file) { return context.eval("js", "require(" + quoted(file.toAbsolutePath().toString()) + ")"); }

    /** Wrap a polyglot Value as a plugin module. */
    public GraalPluginModule module(Value v) { return new GraalPluginModule(this, v); }

    @Override
    public void close() { context.close(); }

    private static String quoted(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
