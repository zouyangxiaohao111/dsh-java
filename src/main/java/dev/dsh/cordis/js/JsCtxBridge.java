package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Events;
import dev.dsh.cordis.util.Disposable;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bridges a JS ctx shim to the Java cordis Context (design §3.3). */
public final class JsCtxBridge {
    /** 跨 bridge 共享的命令注册表:root Context → 注册表。
     *  命令在 {@link JsPluginAdapter#apply} 阶段经其内部 bridge 注册;dispatch 时 Java 侧
     *  常另建 bridge。故注册表按 root Context 共享,使同 root 的任意 bridge 都能派发。
     *  WeakHashMap:root Context 无引用后条目可回收。访问统一加锁(WeakHashMap 非线程安全)。 */
    private static final Map<Context, Map<String, CommandEntry>> SHARED_COMMANDS = new java.util.WeakHashMap<>();

    private final JsHost host;
    private final Context ctx;
    private Value shim;
    /** 命令注册表:命令名 → 入口(design §4.2)。ctx 为 null 时(纯 DSL 测试)退化为实例私有。 */
    private final Map<String, CommandEntry> commands;

    public JsCtxBridge(JsHost host, Context ctx) {
        this.host = host;
        this.ctx = ctx;
        this.commands = resolveCommands(ctx);
    }

    private static Map<String, CommandEntry> resolveCommands(Context ctx) {
        if (ctx == null) return new LinkedHashMap<>();
        synchronized (SHARED_COMMANDS) {
            return SHARED_COMMANDS.computeIfAbsent(ctx.root, k -> new LinkedHashMap<>());
        }
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
        // self 用于 next 续延定位当前监听器在同一事件注册序中的位置(不能直接引用 lambda 自身)
        Events.Listener[] self = new Events.Listener[1];
        self[0] = (c, args) -> {
            Object[] callArgs = args;
            // JS listener 若声明的形参数比 dispatch 实参多 1,则期望一个 next 续延(cordis 拦截语义):
            // 调 next(newArgs) 委派到同一事件后续注册的监听器(含 Java 监听器),不重头 emit。
            if (declaresNext(listener, args)) {
                callArgs = new Object[args.length + 1];
                System.arraycopy(args, 0, callArgs, 0, args.length);
                callArgs[args.length] = (ProxyExecutable) nextArgs ->
                        dispatchNext(name, self[0], c, nextArgs);
            }
            return listener.execute(callArgs);
        };
        return ctx.on(name, self[0], new Events.EventOptions().prepend(prepend).global(global));
    }

    @HostAccess.Export
    public Object once(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        Events.Listener l = (c, args) -> listener.execute(args);
        return ctx.once(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    /** JS listener 声明的形参数 == dispatch 实参 + 1 时,判定其期望一个 next 续延。 */
    private static boolean declaresNext(Value listener, Object[] args) {
        if (listener == null || !listener.canExecute()) return false;
        Value length = listener.getMember("length");
        if (length == null || !length.isNumber() || !length.fitsInInt()) return false;
        return length.asInt() == args.length + 1;
    }

    /** next 续延:按同一事件的注册序,直接调用当前监听器之后的那个监听器(不重头 emit)。
     *  该监听器若是 JS 且自身也声明 next,则链可继续;否则链到此为止(与 cordis waterfall 一致)。 */
    private Object dispatchNext(String name, Events.Listener current, Context c, Object[] nextArgs) {
        List<Events.Listener> listeners = ctx.events.listenersFor(name, c);
        int idx = -1;
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i) == current) { idx = i; break; }
        }
        if (idx < 0 || idx + 1 >= listeners.size()) return null;
        return listeners.get(idx + 1).call(c, nextArgs);
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
            // optionsArray 自 JS 侧传入时,GraalJS 会把 JS 数组映射成 PolyglotList(Java List),
            // 而不一定是 Value(空数组/无 option 场景此前未暴露)。两者都处理。
            List<Map<String, Object>> opts = new ArrayList<>();
            if (optionsArray instanceof Value v && v.hasArrayElements()) {
                for (long i = 0; i < v.getArraySize(); i++) opts.add(toOptionMap(v.getArrayElement(i)));
            } else if (optionsArray instanceof List<?> list) {
                for (Object o : list) opts.add(toOptionMap(o));
            }
            opts.removeIf(Map::isEmpty);
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
     * {@code session.text(key)} 与 {@code session.guildId}。
     *
     * <p>任务 3:布尔 flag 解析。按空白切 token,首个为命令名;其后 token 命中某 option 的
     * 别名(alias,如 {@code -e})或长名({@code --escape})时置 {@code options[name]=true},
     * 否则并入参数(多个参数以单个空格拼回消息文本)。值型 option(如 echo 的
     * {@code -u [user:user]})留待后续任务(记 TODO),当前只做布尔 flag。
     *
     * <p>注:异步 action 返回 Promise,需 await 才能拿到最终回复(任务 6 echo 插件 spike 处理)。
     */
    public Object dispatchCommand(String message) {
        String[] tokens = message.trim().split("\\s+");
        if (tokens.length == 0) return null;
        String name = tokens[0];
        CommandEntry entry = commands.get(name);
        if (entry == null) return null;
        // 解析布尔 flag:已知 option 的 alias(-x)或 --name 匹配 → options[name]=true;其余为参数
        Map<String, Object> options = new LinkedHashMap<>();
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            boolean matched = false;
            for (Map<String, Object> o : entry.options) {
                String optName = String.valueOf(o.get("name"));
                String alias = String.valueOf(o.get("alias"));
                if (t.equals(alias) || t.equals("--" + optName)) {
                    options.put(optName, true);
                    matched = true;
                    break;
                }
            }
            if (!matched) args.add(t);
        }
        String arg = String.join(" ", args);
        Value jsArg = host.eval(
                "({ session: { text: (key) => '[missing text: ' + key + ']', guildId: 'test-guild' }, options: " + toJsOptions(options) + " })");
        return entry.action.execute(jsArg, arg);
    }

    /** 把解析出的 option 表序列化为 JS 对象字面量(当前仅布尔 flag,值恒为 true)。 */
    private static String toJsOptions(Map<String, Object> options) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : options.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.append("}").toString();
    }

    /** 把一条 option 定义(JS 对象或宿主 Map)转成 Java 表。元素既可能是 Value 也可能是 Map。 */
    private static Map<String, Object> toOptionMap(Object o) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (o instanceof Value elem && elem.hasMembers()) {
            for (String key : elem.getMemberKeys()) map.put(key, elem.getMember(key));
        } else if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) map.put(String.valueOf(e.getKey()), e.getValue());
        }
        return map;
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
