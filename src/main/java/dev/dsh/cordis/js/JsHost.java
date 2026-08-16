package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.nio.file.Path;

/** Manages the shared GraalJS context for JS plugins (design §3.2). */
public final class JsHost implements AutoCloseable {
    private final Context context;

    public JsHost() {
        this.context = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .allowIO(true)
                .option("js.commonjs-require", "true")
                .build();
    }

    public JsHost(Path requireCwd) {
        this.context = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .allowIO(true)
                .option("js.commonjs-require", "true")
                .option("js.commonjs-require-cwd", requireCwd.toAbsolutePath().toString())
                .build();
    }

    /** Evaluate a JS expression and return the resulting value. */
    public Value eval(String script) {
        return context.eval("js", script);
    }

    /** Load a CommonJS module by specifier relative to the require cwd. */
    public Value require(String specifier) {
        return context.eval("js", "require(" + quoted(specifier) + ")");
    }

    /** Load a CommonJS module file by absolute path. */
    public Value loadModule(Path file) {
        return context.eval("js", "require(" + quoted(file.toAbsolutePath().toString()) + ")");
    }

    public Context graalContext() { return context; }

    @Override
    public void close() { context.close(); }

    private static String quoted(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
