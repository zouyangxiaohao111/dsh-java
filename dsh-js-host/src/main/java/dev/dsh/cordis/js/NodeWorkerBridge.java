package dev.dsh.cordis.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.dsh.cordis.Context;
import dev.dsh.cordis.Events;
import dev.dsh.cordis.Inject;
import dev.dsh.cordis.Logger;
import dev.dsh.cordis.Reflect;
import dev.dsh.cordis.util.Disposable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Node worker 版 ctx 桥:处理 worker 经 {@code ctxCall} 发来的 ctx 方法调用,转发到 Java
 * cordis 核心;JS listener 以函数句柄往返(host 侧按需 invokeFn)。
 *
 * <p>与 {@link JsCtxBridge} 同一契约面(on/once/emit/provide/get/inject/effect/command),
 * 但 bridge 是 RPC proxy。所有 handler 在 host 的 reader 线程执行,必须非阻塞
 * (JS listener 经 {@link NodeWorkerJsHost#invokeListener} 在 reader 线程上自动 fire-and-forget,
 * 避免与 worker 嵌套等待死锁)。
 */
public final class NodeWorkerBridge {
    /** 跨 bridge 共享的命令注册表:root Context → 注册表(与 JsCtxBridge 同模式)。 */
    private static final Map<Context, Map<String, CommandEntry>> SHARED_COMMANDS = new java.util.WeakHashMap<>();

    private final NodeWorkerJsHost host;
    private final Context ctx;
    /** 命令注册表:命令名 → 入口。ctx 为 null 时(纯 DSL 测试)退化为实例私有。 */
    private final Map<String, CommandEntry> commands;
    private long ctxId = -1;

    public NodeWorkerBridge(NodeWorkerJsHost host, Context ctx) {
        this.host = host;
        this.ctx = ctx;
        this.commands = resolveCommands(ctx);
    }

    void ctxId(long id) { this.ctxId = id; }

    private static Map<String, CommandEntry> resolveCommands(Context ctx) {
        if (ctx == null) return new LinkedHashMap<>();
        synchronized (SHARED_COMMANDS) {
            return SHARED_COMMANDS.computeIfAbsent(ctx.root, k -> new LinkedHashMap<>());
        }
    }

    /** 处理 worker 发来的 ctxCall(reader 线程,须非阻塞)。 */
    JsonNode handleCtxCall(JsonNode msg) {
        String method = msg.path("method").asText("");
        JsonNode args = msg.path("args");
        switch (method) {
            case "on":
            case "once":
                return registerListener("once".equals(method), args);
            case "emit":
                return doEmit(args);
            case "provide":
                return doProvide(args);
            case "get":
                return doGet(args);
            case "mixin":
                return doMixin(args);
            case "inject":
                return doInject(args);
            case "effect":
                return doEffect(args);
            case "accessor":
                return doAccessor(args);
            case "eventsDispatch":
                return doEventsDispatch(args);
            case "waterfallPlan":
                return doWaterfallPlan(args);
            case "commandRegister":
                return doCommandRegister(args);
            case "logger":
                return doLogger(args);
            default:
                throw new NodeBridgeError("unknown ctx method " + method);
        }
    }

    // ---- ctx 方法实现 ----

    private JsonNode registerListener(boolean once, JsonNode args) {
        NodeRef listener = fnRef(args.get(0));
        String name = args.get(1).asText("");
        JsonNode opts = args.size() > 2 ? args.get(2) : null;
        boolean prepend = opts != null && opts.path("prepend").asBoolean(false);
        boolean global = opts != null && opts.path("global").asBoolean(false);
        // JsListener 携带跨桥 fn 句柄:worker 发起的 waterfall 经 waterfallPlan 按此分辨
        // JS listener(本地折叠)与 Java 原生 listener(同步宿主无法折叠,抛 NEEDS)。
        Events.Listener l = new JsListener(listener, (c, evArgs) -> host.invokeListener(listener, evArgs));
        Disposable disposable = once
                ? ctx.once(name, l, new Events.EventOptions().prepend(prepend).global(global))
                : ctx.on(name, l, new Events.EventOptions().prepend(prepend).global(global));
        // 监听器随 fiber 卸载自动移除(与 cordis 语义一致);移除后释放跨桥 fn 句柄,
        // 防止 worker 侧 fnById 累积(热重载/反复 on 的插件长时间跑会泄漏)。
        ctx.effect(() -> (Disposable) () -> disposable.dispose()
                .thenRun(() -> host.releaseFn(listener)), "node-js-listener");
        return NullNode.instance;
    }

    /**
     * Worker 发起 waterfall 时解析的 JS listener 计划:Java 只回传该事件收纳的 JS fn 句柄
     * (dispatch 顺序)。worker 在本地直接调用这些 JS 函数(worker→JS,无跨桥往返),避免同步
     * 宿主在 bridgeCall 阻塞期间再调 Java 句柄的死锁。
     *
     * <p>Java 原生(non-JS)listener 混入时无法同步折叠(worker 阻塞、native listener 需往返),
     * 抛明确错误(记 NEEDS)。监听器的 context filter 近似为无过滤:worker 侧 target 载体经
     * 序列化后仅余空对象,filter 为 undefined 时 {@code Events.dispatch} 对所有 hook 放行。
     */
    private JsonNode doWaterfallPlan(JsonNode args) {
        String name = args.get(1).asText("");
        List<Events.Listener> listeners = ctx.events.listenersFor(name, null);
        List<NodeRef> refs = new ArrayList<>(listeners.size());
        for (Events.Listener l : listeners) {
            if (l instanceof JsListener js) {
                refs.add(js.ref);
            } else {
                throw new NodeBridgeError("waterfall '" + name + "' has a Java-native listener; "
                        + "the sync NodeWorkerJsHost can only fold JS listener chains synchronously "
                        + "(NEEDS: worker-initiated waterfall over mixed listeners)");
            }
        }
        return host.toJsonArray(refs);
    }

    private JsonNode doEmit(JsonNode args) {
        String name = args.path(0).asText("");
        JsonNode evArgs = args.size() > 1 ? args.get(1) : null;
        int n = evArgs != null && evArgs.isArray() ? evArgs.size() : 0;
        Object[] javaArgs = new Object[n];
        for (int i = 0; i < n; i++) javaArgs[i] = host.fromJsonNode(evArgs.get(i));
        ctx.emit(name, javaArgs);
        return NullNode.instance;
    }

    private JsonNode doProvide(JsonNode args) {
        String name = args.path(0).asText("");
        Object value = host.fromJsonNode(args.get(1));
        ctx.provide(name, value);
        return NullNode.instance;
    }

    private JsonNode doGet(JsonNode args) {
        String name = args.path(0).asText("");
        // getService: cordis ctx.get() semantics — same-scope sibling services visible,
        // unavailable services read as JS undefined (plugins rely on `?? fallback` for
        // launcher slots like launchEnvironment / configuredAgentIdentities and on
        // strict `=== undefined` absence checks; no "without inject" throw).
        Object svc = ctx.getService(name);
        if (svc == Context.NO_SERVICE) return host.toJsonNode(NodeWorkerJsHost.UNDEFINED);
        return host.toJsonNode(exposeService(svc));
    }

    /** {@code ctx.mixin(source, keys|renamed)}:把服务成员直接暴露到 ctx(Java Context.mixin
     *  已实现,accessor 转发;M7-6 桥面补齐)。keys 为字符串数组(同键暴露)或映射(重命名)。
     *  reader 线程非阻塞(纯注册,无往返)。 */
    private JsonNode doMixin(JsonNode args) {
        String source = args.path(0).asText("");
        JsonNode keys = args.get(1);
        if (keys != null && keys.isArray()) {
            List<String> keyList = new ArrayList<>(keys.size());
            for (JsonNode k : keys) keyList.add(k.asText());
            ctx.mixin(source, keyList);
        } else if (keys != null && keys.isObject()) {
            Map<String, String> renamed = new LinkedHashMap<>();
            var it = keys.fields();
            while (it.hasNext()) {
                var e = it.next();
                renamed.put(e.getKey(), e.getValue().asText());
            }
            ctx.mixin(source, renamed);
        }
        return NullNode.instance;
    }

    private JsonNode doInject(JsonNode args) {
        JsonNode deps = args.path(0);
        NodeRef cb = fnRef(args.get(1));
        String[] names = new String[deps.size()];
        for (int i = 0; i < deps.size(); i++) names[i] = deps.get(i).asText("");
        ctx.inject(Inject.of(names), (c, cfg) -> {
            host.invokeListener(cb, new Object[0]);
            return null;
        });
        return NullNode.instance;
    }

    /**
     * 登记 worker 发来的 effect disposer(fn 句柄),返回一个 Java 持有的 disposer 服务句柄:
     * worker 侧 `ctx.effect(...)` 的调用方(如 AgentRegistry.register 的调用方)拿到它后可直接
     * `dispose()` 触发卸载,不必等 Java fiber 回收。disposer 经 {@code invokeListener} 调用 ——
     * 非 reader 线程阻塞等结果(Java fiber 卸载路径),reader 线程 fire-and-forget(worker 发起的
     * dispose 路径,避免与 worker 嵌套等待死锁)。
     */
    private JsonNode doEffect(JsonNode args) {
        NodeRef disposer = fnRef(args.get(0));
        Disposable javaDisposable = ctx.effect(() -> (Disposable) () -> {
            host.invokeListener(disposer, new Object[0]);
            host.releaseFn(disposer);   // disposer 只跑一次,跑完即回收句柄
            return CompletableFuture.completedFuture(null);
        }, "node-js-effect");
        // svc 句柄:worker 经 invokeService 反射调用其 dispose()。
        return host.toJsonNode(javaDisposable);
    }

    /** 注册一个 computed ctx property;get 经 JS 句柄调用(结果丢弃)。读取该属性时返回 JS
     *  {@code undefined} 哨兵(cordis 的 accessor 未定义默认值;AgentRegistry 读
     *  {@code this.ctx.agent} 需得到 undefined,使 {@code enter(agent, undefined)} 的 owner
     *  = undefined → {@code roots()} 命中)。get 返回真实值需同步往返 → 记 NEEDS。 */
    private JsonNode doAccessor(JsonNode args) {
        String name = args.get(0).asText("");
        NodeRef get = args.size() > 1 ? fnOrNull(args.get(1)) : null;
        ctx.accessor(name, new Reflect.Property.Accessor(
                (c, receiver) -> {
                    if (get != null) host.invokeListener(get, new Object[0]);
                    return NodeWorkerJsHost.UNDEFINED;
                },
                null));
        return NullNode.instance;
    }

    /**
     * Worker 发起的 {@code events.dispatch(mode, args)}(AgentRegistry.announce/emitDisposed 与
     * agentEvents.emit 走这里):Java 解析该事件收纳的监听器 —— JS listener 返回 fn 句柄
     * (worker 本地折叠),Java-native listener 在 {@code emit} 模式下就地调用并收纳错误
     * (纯 Java、非阻塞;顺序上 Java 先、JS 后,桥偏差,记 NOTE)。非 emit 模式混入 Java
     * native listener 无法同步折叠 → 明确错误(记 NEEDS,与 waterfallPlan 一致)。
     * 监听器 context filter 近似为无过滤(worker 侧 target 载体序列化后仅余空对象)。
     */
    private JsonNode doEventsDispatch(JsonNode args) {
        String mode = args.get(1).asText("");
        String name = args.get(2).asText("");
        List<Events.Listener> listeners = ctx.events.listenersFor(name, null);
        List<NodeRef> refs = new ArrayList<>(listeners.size());
        for (Events.Listener l : listeners) {
            if (l instanceof JsListener js) {
                refs.add(js.ref);
            } else if ("emit".equals(mode)) {
                Object[] evArgs = toEventArgs(args.get(3));
                try {
                    Object returned = l.call(null, evArgs);
                    if (returned instanceof CompletableFuture<?> cf) {
                        cf.handle((v, t) -> {
                            if (t != null) ctx.logger().warn("agent event \"" + name + "\" listener rejected: " + t);
                            return null;
                        });
                    }
                } catch (Throwable t) {
                    ctx.logger().warn("agent event \"" + name + "\" listener threw: " + t);
                }
            } else {
                throw new NodeBridgeError("event '" + name + "' has a Java-native listener; "
                        + "the sync NodeWorkerJsHost can only fold JS listener chains for '" + mode
                        + "' (NEEDS: worker-initiated " + mode + " over mixed listeners)");
            }
        }
        return host.toJsonArray(refs);
    }

    private JsonNode doCommandRegister(JsonNode args) {
        String name = args.path(0).asText("");
        String argDef = args.path(1).asText("");
        JsonNode options = args.get(2);
        NodeRef action = fnRef(args.get(3));
        List<Map<String, Object>> opts = new ArrayList<>();
        if (options != null && options.isArray()) {
            for (JsonNode o : options) opts.add(toOptionMap(o));
        }
        opts.removeIf(Map::isEmpty);
        commands.put(name, new CommandEntry(argDef, opts, action));
        return NullNode.instance;
    }

    /**
     * Worker 发起的日志调用:{@code ctx.logger(name).<type>(format, ...args)}。把
     * name/type/args 转给 Java {@code ctx.logger} 的对应方法,消息进入 Java LoggerService
     * —— 复用 P3 对齐的 Logger 格式化层(printf 占位符、每行截断、ANSI name 着色),printf
     * 在 Java 侧展开(worker 只传 format + args,不预格式化)。
     *
     * <p>无 name(name 为 JSON null)时按调用 ctx 的 fiber 名解析默认 logger(cordis 语义:
     * {@code ctx.logger.warn(...)} 等价 {@code ctx.logger().warn(...)},对应 LoggerService
     * 的 current(caller))。handler 在 reader 线程执行,exporter 必须非阻塞(与其它 ctxCall
     * 一致,否则与 worker 嵌套等待死锁)。
     */
    private JsonNode doLogger(JsonNode args) {
        JsonNode nameNode = args.get(0);
        String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
        String type = args.get(1).asText("");
        Object[] msgArgs = toEventArgs(args.get(2));
        Object format = msgArgs.length > 0 ? msgArgs[0] : "";
        Object[] rest = msgArgs.length > 1 ? Arrays.copyOfRange(msgArgs, 1, msgArgs.length) : new Object[0];
        Logger logger = name != null ? ctx.logger(name) : ctx.logger();
        switch (type) {
            case "error" -> logger.error(format, rest);
            case "info" -> logger.info(format, rest);
            case "warn" -> logger.warn(format, rest);
            case "debug" -> logger.debug(format, rest);
            default -> throw new NodeBridgeError("unknown logger type '" + type + "'");
        }
        return NullNode.instance;
    }

    // ---- 命令 dispatch(与 JsCtxBridge 同语义;action 经远程句柄调用)----

    /** 模拟一条消息,匹配命令并调其 action,返回回复(design §4.2)。 */
    public Object dispatchCommand(String message) {
        CommandParser.Parsed parsed = CommandParser.parse(message, entryOptions(message));
        if (parsed.name().isEmpty()) return null;
        CommandEntry entry = commands.get(parsed.name());
        if (entry == null) return null;
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("guildId", "test-guild");
        session.put("text", (java.util.function.Function<String, String>) key -> "[missing text: " + key + "]");
        Map<String, Object> jsArg = new LinkedHashMap<>();
        jsArg.put("session", session);
        jsArg.put("options", parsed.options());
        return host.invokeFn(entry.action(), List.of(jsArg, parsed.arg()));
    }

    private List<Map<String, Object>> entryOptions(String message) {
        String[] tokens = message.trim().split("\\s+");
        if (tokens.length == 0) return List.of();
        CommandEntry entry = commands.get(tokens[0]);
        return entry == null ? List.of() : entry.options;
    }

    // ---- 小工具 ----

    private NodeRef fnRef(JsonNode node) {
        Object v = host.fromJsonNode(node);
        if (!(v instanceof NodeRef ref) || !"fn".equals(ref.kind())) {
            throw new NodeBridgeError("expected function handle in ctxCall, got " + node);
        }
        return ref;
    }

    /** 返回 fn 句柄或 null(可选参数位,如 accessor 的 get)。 */
    private NodeRef fnOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        Object v = host.fromJsonNode(node);
        if (v instanceof NodeRef ref && "fn".equals(ref.kind())) return ref;
        return null;
    }

    /** 事件参数数组:worker 序列化的 {@code [arg0, arg1, ...]}(含 fn/svc 句柄反序列化)。 */
    private Object[] toEventArgs(JsonNode arr) {
        int n = arr != null && arr.isArray() ? arr.size() : 0;
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = host.fromJsonNode(arr.get(i));
        return out;
    }

    private static Object exposeService(Object svc) {
        // 序列化决策交给 host.toJsonNode:JSON 可序列化值直接透传,其余对象注册为远程服务句柄
        return svc;
    }

    private static Map<String, Object> toOptionMap(JsonNode o) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (o.isObject()) {
            var it = o.fields();
            while (it.hasNext()) {
                var e = it.next();
                map.put(e.getKey(), scalar(e.getValue()));
            }
        }
        return map;
    }

    private static Object scalar(JsonNode v) {
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isFloatingPointNumber()) return v.asDouble();
        return v;
    }

    /** 一条已注册命令的入口:参数模板、option 定义、action(远程函数句柄)。 */
    public record CommandEntry(String argDef, List<Map<String, Object>> options, NodeRef action) {
    }

    /**
     * JS listener 的桥内驻留形式:携带跨桥 fn 句柄,转发调用到 worker。
     * {@code doWaterfallPlan} 借此识别 JS listener(可本地折叠)与 Java 原生 listener。
     */
    private static final class JsListener implements Events.Listener {
        final NodeRef ref;
        private final java.util.function.BiFunction<Context, Object[], Object> invoke;

        JsListener(NodeRef ref, java.util.function.BiFunction<Context, Object[], Object> invoke) {
            this.ref = ref;
            this.invoke = invoke;
        }

        @Override public Object call(Context ctx, Object... args) {
            return invoke.apply(ctx, args);
        }
    }
}
