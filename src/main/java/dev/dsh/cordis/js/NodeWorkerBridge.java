package dev.dsh.cordis.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.dsh.cordis.Context;
import dev.dsh.cordis.Events;
import dev.dsh.cordis.Inject;
import dev.dsh.cordis.util.Disposable;

import java.util.ArrayList;
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
            case "inject":
                return doInject(args);
            case "effect":
                return doEffect(args);
            case "commandRegister":
                return doCommandRegister(args);
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
        Events.Listener l = (c, evArgs) -> host.invokeListener(listener, evArgs);
        Disposable disposable = once
                ? ctx.once(name, l, new Events.EventOptions().prepend(prepend).global(global))
                : ctx.on(name, l, new Events.EventOptions().prepend(prepend).global(global));
        // 监听器随 fiber 卸载自动移除(与 cordis 语义一致)
        ctx.effect(() -> disposable, "node-js-listener");
        return NullNode.instance;
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
        Object svc = ctx.get(name);
        return host.toJsonNode(exposeService(svc));
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

    private JsonNode doEffect(JsonNode args) {
        NodeRef disposer = fnRef(args.get(0));
        ctx.effect(() -> (Disposable) () -> {
            host.invokeFn(disposer, List.of());
            return CompletableFuture.completedFuture(null);
        }, "node-js-effect");
        return NullNode.instance;
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
}
