package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Events;
import dev.dsh.cordis.util.Disposable;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bridges a JS ctx shim to the Java cordis Context (design §3.3). */
public final class JsCtxBridge {
    private final JsHost host;
    private final Context ctx;
    private Value shim;
    /** 命令注册表:命令名 → 入口(design §4.2)。 */
    private final Map<String, CommandEntry> commands = new LinkedHashMap<>();

    public JsCtxBridge(JsHost host, Context ctx) {
        this.host = host;
        this.ctx = ctx;
    }

    /** Create (once) and return the JS ctx shim bound to this bridge. */
    public Value ctxShim() {
        if (shim == null) {
            Value createCtx = host.eval(loadCtxJs());
            shim = createCtx.execute(host.graalContext().asValue(this));
        }
        return shim;
    }

    // ---- methods callable from JS ----

    @HostAccess.Export
    public Object on(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        Events.Listener l = (c, args) -> listener.execute(args);
        return ctx.on(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    @HostAccess.Export
    public Object once(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        Events.Listener l = (c, args) -> listener.execute(args);
        return ctx.once(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    @HostAccess.Export
    public void emit(String name, Value argsArray) {
        Object[] args = toArgs(argsArray);
        ctx.emit(name, args);
    }

    @HostAccess.Export
    public Object get(String name) {
        return ctx.get(name);
    }

    @HostAccess.Export
    public Object provide(String name, Value value) {
        return ctx.provide(name, value);
    }

    @HostAccess.Export
    public Object effect(Value disposer) {
        Disposable d = () -> {
            disposer.execute();
            return CompletableFuture.completedFuture(null);
        };
        return ctx.effect(() -> d, "js-effect");
    }

    @HostAccess.Export
    public Object inject(Object deps, Value callback) {
        List<?> list = deps instanceof List<?> l ? l : List.of();
        String[] names = list.stream().map(String::valueOf).toArray(String[]::new);
        return ctx.inject(dev.dsh.cordis.Inject.of(names), (c, cfg) -> callback.execute());
    }

    // ---- 命令 DSL(design §4.2)----

    /**
     * 返回 JS 可调用的命令注册器。JS 侧经 command DSL 调
     * {@code registry.register(name, argDef, optionsArray, action)} 把命令存入
     * {@code commands} 表;Java 侧后续可经 {@link #dispatchCommand(String)} 模拟消息触发。
     */
    @HostAccess.Export
    public Object commandRegistry() {
        return (CommandRegistrar) (name, argDef, optionsArray, action) -> {
            List<Map<String, Object>> opts = new ArrayList<>();
            if (optionsArray instanceof Value v && v.hasArrayElements()) {
                for (long i = 0; i < v.getArraySize(); i++) {
                    Object o = v.getArrayElement(i);
                    if (o instanceof Value elem && elem.hasMembers()) {
                        Map<String, Object> map = new LinkedHashMap<>();
                        for (String key : elem.getMemberKeys()) map.put(key, elem.getMember(key));
                        opts.add(map);
                    }
                }
            }
            commands.put(name, new CommandEntry(argDef, opts, action));
        };
    }

    /** JS 侧调用的命令注册接口(SAM,由 commandRegistry() 返回的 lambda 实现)。 */
    @FunctionalInterface
    public interface CommandRegistrar {
        void register(String name, String argDef, Object optionsArray, Value action);
    }

    /** 一条已注册命令的入口:参数模板、option 定义、action(JS 函数)。 */
    public static final class CommandEntry {
        public final String argDef;
        public final List<Map<String, Object>> options;
        public final Value action;

        public CommandEntry(String argDef, List<Map<String, Object>> options, Value action) {
            this.argDef = argDef;
            this.options = options;
            this.action = action;
        }
    }

    /**
     * 模拟一条消息,匹配命令并调其 action,返回回复(design §4.2)。
     *
     * <p>echo 的 action 签名是 {@code async ({ options, session }, message) => {...}}:
     * 第一个参数是被解构的 JS 对象 {@code { session, options }},第二个参数是剩余消息文本。
     * 故调用 {@code action.execute(jsArg, arg)}。session 提供 echo 用到的
     * {@code session.text(key)} 与 {@code session.guildId}。options 未解析命令行 flag
     * 时为空对象(无 flag 即无 option 值)。
     *
     * <p>注:异步 action 返回 Promise,需 await 才能拿到最终回复(任务 6 echo 插件 spike 处理)。
     */
    public Object dispatchCommand(String message) {
        String[] parts = message.trim().split("\\s+", 2);
        String name = parts[0];
        CommandEntry entry = commands.get(name);
        if (entry == null) return null;
        String arg = parts.length > 1 ? parts[1] : "";
        Value jsArg = host.eval(
                "({ session: { text: (key) => '[missing text: ' + key + ']', guildId: 'test-guild' }, options: {} })");
        return entry.action.execute(jsArg, arg);
    }

    private Object[] toArgs(Value argsArray) {
        if (!argsArray.hasArrayElements()) return new Object[0];
        long len = argsArray.getArraySize();
        Object[] out = new Object[(int) len];
        for (int i = 0; i < len; i++) out[i] = unwrap(argsArray.getArrayElement(i));
        return out;
    }

    /** Convert a guest value to a host Object for Java listeners (primitives become
     *  their Java boxed types; non-primitives stay as polyglot Values). */
    private static Object unwrap(Value v) {
        if (v.isString()) return v.asString();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) {
            // 注意:不能用 `v.fitsInLong() ? v.asLong() : v.asDouble()` — 三元表达式在
            // long/double 之间做二进制数值提升,结果类型为 double,连整数也会被转成 Double。
            if (v.fitsInLong()) return v.asLong();
            return v.asDouble();
        }
        return v;
    }

    private String loadCtxJs() {
        try (var is = getClass().getClassLoader().getResourceAsStream("js/ctx.js")) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load ctx.js", e);
        }
    }
}
