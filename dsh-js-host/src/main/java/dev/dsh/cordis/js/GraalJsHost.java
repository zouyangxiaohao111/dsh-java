package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

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

    /**
     * 求值一个 dsh {@code !!js} 表达式(M7-6 disabled 通道;scope = process + dshHomePath,镜像
     * {@code node-bridge.js evalJsExpression})。Graal 无 Node {@code process} → 注入最小
     * process shim(platform 取自 JVM os.name,env 取自 System.getenv),dshHomePath 为简单拼接。
     */
    @Override
    public Object evalJs(String expr) {
        String js = "(function() {"
                + "  var process = { platform: " + quoted(osPlatform())
                + ", env: " + envLiteral()
                + ", arch: " + quoted(System.getProperty("os.arch", ""))
                + ", version: '' };"
                + "  function dshHomePath() {"
                + "    var home = process.env.DSH_HOME || '';"
                + "    var segs = Array.prototype.slice.call(arguments);"
                + "    return [home].concat(segs).join('/');"
                + "  }"
                + "  return (" + expr + ");"
                + "})()";
        return toJava(context.eval("js", js));
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

    /** JVM os.name → Node 风格 platform 值(process.platform 的求值面)。 */
    private static String osPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "win32";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("linux") || os.contains("unix")) return "linux";
        return os;
    }

    /** System.getenv → JS 对象字面量(process.env 的求值面)。 */
    private static String envLiteral() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quoted(e.getKey())).append(':').append(quoted(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    /** polyglot Value → Java 原始值(boolean/number/string/null),供 {@link #evalJs} 返回。 */
    private static Object toJava(Value v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.fitsInLong() ? v.asLong() : v.asDouble();
        if (v.isString()) return v.asString();
        return v;
    }

    private static String quoted(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
