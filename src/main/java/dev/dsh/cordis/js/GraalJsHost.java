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

    /** Evaluate a JS expression and return the resulting value. */
    @Override public Value eval(String script) {
        return context.eval("js", script);
    }

    /** Load a CommonJS module by specifier relative to the require cwd. */
    @Override public Value require(String specifier) {
        return context.eval("js", "require(" + quoted(specifier) + ")");
    }

    /** Load a CommonJS module file by absolute path. */
    @Override public Value loadModule(Path file) {
        return context.eval("js", "require(" + quoted(file.toAbsolutePath().toString()) + ")");
    }

    @Override public Context graalContext() { return context; }

    @Override
    public void close() { context.close(); }

    private static String quoted(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
