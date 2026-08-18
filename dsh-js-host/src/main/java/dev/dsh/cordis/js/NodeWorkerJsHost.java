package dev.dsh.cordis.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Node worker 版 {@link JsHost}:spawn 一个真 Node 进程跑 {@code js/node-bridge.js},
 * stdin/stdout NDJSON(JSON lines)通信,hostile-peer 校验(仿 dsh code-runtime-worker-thread)。
 *
 * <p>{@code eval}/{@code require}/{@code loadModule} 返回远程句柄({@link NodeRef} 的
 * {@link NodePluginModule});{@code close()} 终止进程。无 GraalJS context(语义上是
 * {@code graalContext() == null})。
 *
 * <p>线程模型:调用线程发请求、阻塞等响应;reader 线程读 stdout——把 {@code result/error}
 * 路由回对应 pending future,把 worker 发起的 {@code ctxCall}/{@code invokeService} 就地
 * 处理并回包(处理逻辑必须非阻塞,否则与 worker 嵌套等待死锁)。
 */
public final class NodeWorkerJsHost implements JsHost {
    /** 单请求超时(worker 阻塞在 macrotask await 时同步等待会自爆,此处兜底)。 */
    static final long REQUEST_TIMEOUT_MS = 30_000;
    /** 入站行长度上限(hostile-peer:拒绝超大行)。 */
    private static final int MAX_LINE_LENGTH = 8 * 1024 * 1024;

    /** JS {@code undefined} 的跨桥哨兵(经 {@code {$kind:'undefined'}} 表示)。 */
    static final Object UNDEFINED = new Object();

    private final Process process;
    private final BufferedWriter stdin;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path bridgeScript;

    /** Java → worker 请求:correlation id → 未完成 future。 */
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** 暴露给 worker 的 Java 服务:svc handle → 服务对象。 */
    private final Map<Long, Object> services = new ConcurrentHashMap<>();
    /** ctx handle → 桥(路由 ctxCall)。 */
    private final Map<Long, NodeWorkerBridge> bridges = new ConcurrentHashMap<>();

    private final AtomicLong seq = new AtomicLong(1);
    private final AtomicLong serviceSeq = new AtomicLong(1_000_000);

    private final Thread readerThread;
    private final Thread deathWatcher;
    private volatile boolean closed;

    public NodeWorkerJsHost() throws IOException {
        this(Path.of(""), List.of(), Map.of());
    }

    public NodeWorkerJsHost(Path requireCwd) throws IOException {
        this(requireCwd, List.of(), Map.of());
    }

    /**
     * 进程外 Node 宿主,带裸模块解析基址(M6-4 seam,等价 dsh 的 {@code bareModuleBaseUrl})。
     *
     * <p>{@code moduleBases} 为附加的裸模块解析基址,须是 <b>node_modules 目录本身</b>(模块根,
     * 如 {@code vendor/dsh/node_modules}、{@code profiles/web/node_modules}),经
     * {@code NODE_PATH}/{@code DSH_MODULE_BASES} 环境变量在 spawn 时传给 worker —— Node 启动时
     * 把 NODE_PATH 读进 {@code Module.globalPaths},worker 内任意 {@code require('裸包')}(含插件
     * 代码内部)都能经基址解析。注意 NODE_PATH 条目是"模块根"({@code <base>/<specifier>} 拼接),
     * 不是 node_modules 的父目录。ESM 裸 {@code import} 走 Node 原生解析(从模块文件目录向上找
     * node_modules),不受 NODE_PATH 影响 —— ESM 插件应位于 profile/dsh 树内以便原生向上解析
     * 命中其 node_modules(见 node-bridge.js 注释)。
     *
     * @param requireCwd  插件模块的 require 基准目录(可为空)
     * @param moduleBases 附加裸模块解析基址;null/空 = 保持原生解析
     */
    public NodeWorkerJsHost(Path requireCwd, List<Path> moduleBases) throws IOException {
        this(requireCwd, moduleBases, Map.of());
    }

    /**
     * 进程外 Node 宿主,带裸模块解析基址 + 额外传给 worker 的环境变量(M7-5 seam)。
     *
     * <p>{@code extraEnv} 追加到 worker 进程环境(如测试注入 {@code DSH_HOME} 以验证
     * {@code !!js} 表达式的 {@code process.env} 求值;叠加在默认继承的环境之上)。
     *
     * @param requireCwd  插件模块的 require 基准目录(可为空)
     * @param moduleBases 附加裸模块解析基址;null/空 = 保持原生解析
     * @param extraEnv    额外传给 worker 进程的环境变量;null/空 = 无
     */
    public NodeWorkerJsHost(Path requireCwd, List<Path> moduleBases, Map<String, String> extraEnv) throws IOException {
        Path runtimeDir = Files.createTempDirectory("dsh-node-runtime");
        this.bridgeScript = extractResource("js/node-bridge.js", runtimeDir.resolve("node-bridge.js"));
        Path shim = extractResource("js/cordis-shim.mjs", runtimeDir.resolve("cordis-shim.mjs"));
        // M6-5b:node-bridge.js 经 module.register 注册 resolve 钩子(node-resolve-hook.cjs,
        // 与 bridge 同目录),把 @deepseek-ai/cordis 拦到 shim;shim 路径经环境变量传入。
        extractResource("js/node-resolve-hook.cjs", runtimeDir.resolve("node-resolve-hook.cjs"));
        ProcessBuilder pb = new ProcessBuilder(nodeExecutable(), bridgeScript.toAbsolutePath().toString());
        if (requireCwd != null && !requireCwd.toString().isEmpty()) {
            pb.directory(requireCwd.toAbsolutePath().toFile());
        }
        pb.environment().put("DSH_CORDIS_SHIM", shim.toAbsolutePath().normalize().toString());
        if (moduleBases != null && !moduleBases.isEmpty()) {
            String joined = moduleBases.stream()
                    .filter(Objects::nonNull)
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .collect(Collectors.joining(File.pathSeparator));
            if (!joined.isEmpty()) {
                pb.environment().put("NODE_PATH", joined);
                pb.environment().put("DSH_MODULE_BASES", joined);
            }
        }
        // M7-5:额外环境变量(测试注入 DSH_HOME 等,验证 worker 侧 !!js 的 process.env 求值)
        if (extraEnv != null) {
            extraEnv.forEach(pb.environment()::put);
        }
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);   // worker stderr 透传给宿主(诊断可见)
        this.process = pb.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.readerThread = new Thread(this::readLoop, "dsh-node-worker-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        this.deathWatcher = new Thread(this::watchLoop, "dsh-node-worker-watcher");
        this.deathWatcher.setDaemon(true);
        this.deathWatcher.start();
    }

    private static String nodeExecutable() {
        String env = System.getenv("NODE");
        return env != null && !env.isBlank() ? env : "node";
    }

    /** 从 classpath 抽取一个资源到目标路径(Node 无法直接加载 classpath 资源);文件退出时清理。 */
    private static Path extractResource(String resource, Path target) throws IOException {
        try (InputStream is = NodeWorkerJsHost.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IllegalStateException("cannot find " + resource + " on classpath");
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        }
    }

    // ---- JsHost ----

    @Override public PluginModule eval(String script) {
        return nodePluginModule(request("eval", Map.of("script", script)));
    }

    /** 求值一个 dsh {@code !!js} 表达式(M7-6 disabled 通道):worker 侧 evalJsExpression 求值
     *  (scope = process + dshHomePath),结果返回原始值(boolean/number/string);undefined → null。 */
    @Override public Object evalJs(String expr) {
        Object v = fromJsonNode(request("evalJs", Map.of("expr", expr)));
        return v == UNDEFINED ? null : v;
    }

    @Override public PluginModule require(String specifier) {
        return nodePluginModule(request("require", Map.of("specifier", specifier)));
    }

    @Override public PluginModule loadModule(Path file) {
        return nodePluginModule(request("load", Map.of("file", file.toAbsolutePath().toString())));
    }

    private NodePluginModule nodePluginModule(JsonNode resp) {
        if (resp == null || !resp.isObject() || !resp.has("$kind")) {
            throw new NodeBridgeError("node worker did not return a module handle: " + resp);
        }
        String kind = resp.path("$kind").asText();
        long id = resp.path("id").asLong(-1);
        if (!("module".equals(kind) || "fn".equals(kind)) || id < 0) {
            throw new NodeBridgeError("node worker returned unsupported handle kind '" + kind + "'");
        }
        String name = resp.hasNonNull("name") ? resp.path("name").asText() : null;
        String[] inject = stringArray(resp.path("inject"));
        String[] provide = stringArray(resp.path("provide"));
        return new NodePluginModule(this, new NodeRef(id, kind), name, inject, provide);
    }

    private static String[] stringArray(JsonNode node) {
        if (node == null || !node.isArray()) return new String[0];
        String[] out = new String[node.size()];
        for (int i = 0; i < node.size(); i++) out[i] = node.get(i).asText();
        return out;
    }

    // ---- 供 NodePluginModule / NodeWorkerBridge 使用的桥 API(包私有) ----

    /** 让 worker 建一个 ctx shim,并把 ctx id 绑定到给定桥(apply 之前注册)。 */
    NodeRef createCtx(NodeWorkerBridge bridge) {
        JsonNode resp = request("createCtx", Map.of());
        if (resp == null || !resp.isObject() || !"ctx".equals(resp.path("$kind").asText())) {
            throw new NodeBridgeError("createCtx: worker did not return a ctx handle");
        }
        long id = resp.path("id").asLong(-1);
        NodeRef ctxRef = new NodeRef(id, "ctx");
        bridges.put(id, bridge);
        bridge.ctxId(id);
        return ctxRef;
    }

    /** 调插件 apply(module, ctx, config),返回反序列化结果(disposer 为 fn 句柄)。 */
    Object applyPlugin(NodeRef module, NodeRef ctx, Object config) {
        ObjectNode payload = mapper.createObjectNode();
        payload.set("module", toJsonNode(module));
        payload.set("ctx", toJsonNode(ctx));
        payload.set("config", toJsonNode(config));
        JsonNode resp = request("apply", payload);
        return fromJsonNode(resp);
    }

    /** 阻塞调用 JS 函数句柄。 */
    Object invokeFn(NodeRef fn, List<Object> args) {
        if (!"fn".equals(fn.kind())) throw new NodeBridgeError("not a function handle: " + fn);
        ObjectNode payload = mapper.createObjectNode();
        payload.set("handle", toJsonNode(fn));
        payload.set("args", toJsonArray(args));
        return fromJsonNode(request("invokeFn", payload));
    }

    /** 非阻塞调用 JS 函数句柄(reader 线程上避免死锁;结果丢弃)。 */
    void invokeFnAsync(NodeRef fn, List<Object> args) {
        if (!"fn".equals(fn.kind())) return;
        ObjectNode payload = mapper.createObjectNode();
        payload.set("handle", toJsonNode(fn));
        payload.set("args", toJsonArray(args));
        sendNoWait("invokeFn", payload);
    }

    /**
     * 在事件 dispatch 中调 JS 监听器:reader 线程上须 fire-and-forget(否则与 worker 嵌套
     * 等待死锁),其余线程阻塞等结果。
     */
    Object invokeListener(NodeRef fn, Object[] args) {
        List<Object> list = new ArrayList<>(args.length);
        for (Object a : args) list.add(a);
        if (Thread.currentThread() == readerThread) {
            invokeFnAsync(fn, list);
            return null;
        }
        return invokeFn(fn, list);
    }

    void releaseCtx(NodeRef ctx) {
        bridges.remove(ctx.id());
        sendNoWait("releaseCtx", jsonOf("ctx", ctx.id()));
    }

    /**
     * 释放一个远程 fn 句柄(worker 侧删除注册表条目)。fire-and-forget:发送即返回,
     * worker 按 id 回复 result 但 Java 侧无对应 future,忽略。句柄耗尽即回收,
     * 防止长生命周期宿主(跨多次插件加载/热重载)在 worker 侧 fnById 累积泄漏。
     */
    void releaseFn(NodeRef fn) {
        if (fn == null || !"fn".equals(fn.kind())) return;
        sendNoWait("release", jsonOf("handle", fn.id()));
    }

    /** 把一个 Java 对象注册为远程服务,返回 svc 句柄。 */
    NodeRef registerService(Object svc) {
        long id = serviceSeq.getAndIncrement();
        services.put(id, svc);
        return new NodeRef(id, "svc");
    }

    /** 反射调用远程 Java 服务(worker 经 invokeService 消息发起)。 */
    Object invokeService(long handle, String method, List<Object> args) {
        Object svc = services.get(handle);
        if (svc == null) throw new NodeBridgeError("unknown service handle " + handle);
        return ServiceInvoker.invoke(svc, method, args);
    }

    boolean isReaderThread(Thread t) { return t == readerThread; }

    // ---- 错误分类(resolver 兜底用)----

    /** 确定性限制:macrotask await / top-level await(同步宿主无法等待,重试无益)→ 明确失败上报。 */
    static boolean isAsyncUnsupported(Throwable t) {
        String m = t == null ? null : String.valueOf(t.getMessage());
        if (m == null) return false;
        String lower = m.toLowerCase(Locale.ROOT);
        return lower.contains("macrotask")
                || lower.contains("top-level await")
                || lower.contains("did not settle synchronously");
    }

    /** 瞬时 worker 故障(进程死 / 超时 / 管道关闭)——值得重建 worker 重试一次。 */
    static boolean isRetryable(NodeBridgeError e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("timed out")
                || m.contains("process exited")
                || m.contains("stdout closed")
                || m.contains("not alive");
    }

    /** 测试用:强制杀死进程(阻塞至终止),验证崩溃检测。 */
    void killForTest() {
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- NDJSON 收发 ----

    private void write(String line) {
        try {
            synchronized (stdin) {
                stdin.write(line);
                stdin.newLine();
                stdin.flush();
            }
        } catch (IOException e) {
            throw new NodeBridgeError("node worker stdin write failed (process alive=" + process.isAlive() + ")", e);
        }
    }

    /** 发送一个请求并阻塞等待响应(payload 为键值对)。 */
    private JsonNode request(String type, Map<String, Object> payload) {
        ObjectNode node = mapper.createObjectNode();
        if (payload != null) {
            payload.forEach((k, v) -> node.set(k, toJsonNode(v)));
        }
        return request(type, node);
    }

    /** 发送一个请求并阻塞等待响应(payload 为已序列化的 JSON 对象)。 */
    private JsonNode request(String type, ObjectNode payload) {
        ensureAlive();
        long id = seq.getAndIncrement();
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("id", id);
        if (payload != null) node.setAll(payload);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        write(node.toString());
        try {
            return future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new NodeBridgeError("node worker request '" + type + "' timed out after "
                    + REQUEST_TIMEOUT_MS + "ms (id " + id + ", process alive=" + process.isAlive() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeBridgeError("interrupted waiting for node worker '" + type + "'", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof NodeBridgeError nbe) throw nbe;
            throw new NodeBridgeError("node worker request '" + type + "' failed", c);
        } finally {
            pending.remove(id);
        }
    }

    /** 发送一条请求但不登记 future(响应到来时按 unknown id 忽略)。 */
    private void sendNoWait(String type, ObjectNode payload) {
        if (closed || !process.isAlive()) return;
        long id = seq.getAndIncrement();
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("id", id);
        node.setAll(payload);
        try {
            write(node.toString());
        } catch (NodeBridgeError e) {
            // 进程已死:忽略 fire-and-forget
        }
    }

    private void ensureAlive() {
        if (closed || !process.isAlive()) {
            throw new NodeBridgeError("node worker process is not alive (closed=" + closed + ")");
        }
    }

    // ---- reader 线程 ----

    private void readLoop() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE_LENGTH) continue; // hostile: 超大行丢弃
                handleLine(line);
            }
        } catch (IOException e) {
            // 进程退出/管道关闭:走 deathWatcher 统一收尾
        } finally {
            if (!closed) {
                failAllPending("node worker stdout closed");
            }
        }
    }

    private void handleLine(String line) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (Exception e) {
            return; // hostile: 畸形 JSON 忽略
        }
        if (msg == null || !msg.isObject()) return;
        String type = msg.path("type").asText("");
        switch (type) {
            case "result" -> {
                long id = msg.path("id").asLong(-1);
                CompletableFuture<JsonNode> f = pending.remove(id);
                if (f == null) return; // hostile: unknown/duplicate correlation id
                f.complete(msg.has("value") ? msg.get("value") : NullNode.instance);
            }
            case "error" -> {
                long id = msg.hasNonNull("id") ? msg.path("id").asLong(-1) : -1;
                CompletableFuture<JsonNode> f = id >= 0 ? pending.remove(id) : null;
                String message = msg.path("message").asText("node worker error");
                if (f != null) f.completeExceptionally(new NodeBridgeError(message));
                else if (id < 0) failAllPending(message); // worker 启动级错误
            }
            case "ctxCall", "invokeService" -> respondToWorker(msg);
            default -> {
                // hostile: 未知 type 忽略
            }
        }
    }

    private void respondToWorker(JsonNode msg) {
        long id = msg.path("id").asLong(-1);
        try {
            JsonNode result;
            if ("ctxCall".equals(msg.path("type").asText())) {
                long ctxId = msg.path("ctx").asLong(-1);
                NodeWorkerBridge bridge = bridges.get(ctxId);
                if (bridge == null) throw new NodeBridgeError("unknown ctx handle " + ctxId);
                result = bridge.handleCtxCall(msg);
            } else {
                long handle = msg.path("handle").asLong(-1);
                String method = msg.path("method").asText("");
                result = toJsonNode(invokeService(handle, method, toJavaArgs(msg.path("args"))));
            }
            ObjectNode resp = mapper.createObjectNode();
            resp.put("type", "ctxResult");
            resp.put("id", id);
            resp.set("result", result == null ? NullNode.instance : result);
            write(resp.toString());
        } catch (Throwable t) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("type", "ctxResult");
            resp.put("id", id);
            resp.put("error", String.valueOf(t.getMessage()));
            write(resp.toString());
        }
    }

    private void failAllPending(String reason) {
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.completeExceptionally(new NodeBridgeError(reason));
        }
        pending.clear();
    }

    // ---- death watcher ----

    private void watchLoop() {
        try {
            int code = process.waitFor();
            if (!closed) {
                closed = true;
                failAllPending("node worker process exited unexpectedly (code " + code + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 序列化 ----

    JsonNode toJsonNode(Object value) {
        if (value == null) return NullNode.instance;
        if (value == UNDEFINED) {
            ObjectNode n = mapper.createObjectNode();
            n.put("$kind", "undefined");
            return n;
        }
        if (value instanceof JsonNode j) return j;
        if (value instanceof NodeRef ref) {
            ObjectNode n = mapper.createObjectNode();
            n.put("$kind", ref.kind());
            n.put("id", ref.id());
            return n;
        }
        if (value instanceof String s) return mapper.getNodeFactory().textNode(s);
        if (value instanceof Boolean b) return mapper.getNodeFactory().booleanNode(b);
        if (value instanceof Integer i) return mapper.getNodeFactory().numberNode(i);
        if (value instanceof Long l) return mapper.getNodeFactory().numberNode(l);
        if (value instanceof Double d) return mapper.getNodeFactory().numberNode(d);
        if (value instanceof Float f) return mapper.getNodeFactory().numberNode(f);
        if (value instanceof Short s) return mapper.getNodeFactory().numberNode(s);
        if (value instanceof Byte by) return mapper.getNodeFactory().numberNode(by);
        if (value instanceof BigInteger bi) return mapper.getNodeFactory().numberNode(bi);
        if (value instanceof Map<?, ?> map) {
            ObjectNode n = mapper.createObjectNode();
            for (Map.Entry<?, ?> e : map.entrySet()) n.set(String.valueOf(e.getKey()), toJsonNode(e.getValue()));
            return n;
        }
        if (value instanceof Iterable<?> it) return toJsonArray(it);
        if (value instanceof Object[] arr) return toJsonArray(List.of(arr));
        // 其他 Java 对象 → 远程服务句柄(worker 侧经 Proxy 反射调用)
        return toJsonNode(registerService(value));
    }

    ArrayNode toJsonArray(Object values) {
        ArrayNode arr = mapper.createArrayNode();
        if (values instanceof Iterable<?> it) {
            for (Object v : it) arr.add(toJsonNode(v));
        } else if (values instanceof Object[] a) {
            for (Object v : a) arr.add(toJsonNode(v));
        }
        return arr;
    }

    private ObjectNode jsonOf(String key, long value) {
        ObjectNode n = mapper.createObjectNode();
        n.put(key, value);
        return n;
    }

    Object fromJsonNode(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isObject() && v.hasNonNull("$kind")) {
            String kind = v.path("$kind").asText();
            long id = v.path("id").asLong(-1);
            switch (kind) {
                case "fn", "module", "ctx", "svc" -> { return new NodeRef(id, kind); }
                case "undefined" -> { return UNDEFINED; }
                case "bigint" -> { return new BigInteger(v.path("value").asText()); }
                default -> { return fromPlain(v); }
            }
        }
        return fromPlain(v);
    }

    private Object fromPlain(JsonNode v) {
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isFloatingPointNumber()) return v.asDouble();
        if (v.isArray()) {
            List<Object> out = new ArrayList<>(v.size());
            for (JsonNode e : v) out.add(fromJsonNode(e));
            return out;
        }
        if (v.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            var it = v.fields();
            while (it.hasNext()) {
                var e = it.next();
                out.put(e.getKey(), fromJsonNode(e.getValue()));
            }
            return out;
        }
        return null;
    }

    private List<Object> toJavaArgs(JsonNode args) {
        List<Object> out = new ArrayList<>();
        if (args != null && args.isArray()) {
            for (JsonNode a : args) out.add(fromJsonNode(a));
        }
        return out;
    }

    // ---- ServiceInvoker(仿 ServiceProxy 的反射调用,不依赖 GraalJS context)----

    private static final class ServiceInvoker {
        static Object invoke(Object svc, String method, List<Object> args) {
            if ("$call".equals(method)) {
                if (svc instanceof java.util.function.Function && !args.isEmpty()) {
                    return ((java.util.function.Function<Object, Object>) svc).apply(args.get(0));
                }
                if (svc instanceof java.util.function.Consumer<?> c && !args.isEmpty()) {
                    ((java.util.function.Consumer<Object>) c).accept(args.get(0));
                    return null;
                }
                if (svc instanceof Runnable r) {
                    r.run();
                    return null;
                }
                // fall through: 具名方法
            }
            for (Method m : svc.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                if (m.getName().equals(method)) {
                    try {
                        Class<?>[] pt = m.getParameterTypes();
                        Object[] ja = new Object[args.size()];
                        for (int i = 0; i < args.size(); i++) {
                            ja[i] = i < pt.length ? coerce(args.get(i), pt[i]) : args.get(i);
                        }
                        return m.invoke(svc, ja);
                    } catch (Exception e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        throw new NodeBridgeError("service invocation failed: " + svc.getClass().getSimpleName() + "." + method
                                + " → " + c, c);
                    }
                }
            }
            // 无匹配方法 → 回退读 public 实例字段(JS 属性访问语义)。AgentRegistry 的
            // internal/status 监听器读 fiber.state;Fiber.state 是 public volatile 字段,
            // 没有 state() 访问器 —— 字段回退避免该监听器在每次 fiber 状态变更时抛错。
            for (Field f : svc.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().equals(method)) {
                    try {
                        return f.get(svc);
                    } catch (IllegalAccessException e) {
                        throw new NodeBridgeError("service field read failed: " + svc.getClass().getSimpleName() + "." + method, e);
                    }
                }
            }
            throw new NodeBridgeError("no public method '" + method + "' on service " + svc.getClass().getSimpleName());
        }

        /** 反射参数收窄(与 ServiceProxy.coerce 同语义:JS number → int/long/double...)。 */
        private static Object coerce(Object v, Class<?> target) {
            if (v instanceof Number num) {
                if (target == int.class || target == Integer.class) return num.intValue();
                if (target == long.class || target == Long.class) return num.longValue();
                if (target == double.class || target == Double.class) return num.doubleValue();
                if (target == float.class || target == Float.class) return num.floatValue();
                if (target == short.class || target == Short.class) return num.shortValue();
                if (target == byte.class || target == Byte.class) return num.byteValue();
            }
            if (v instanceof Boolean b && (target == boolean.class || target == Boolean.class)) return b;
            if (v instanceof String s && (target == char.class || target == Character.class) && !s.isEmpty()) {
                return s.charAt(0);
            }
            return v;
        }
    }

    // ---- JsHost.close ----

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try {
            if (process.isAlive()) {
                try {
                    ObjectNode node = mapper.createObjectNode();
                    node.put("type", "close");
                    node.put("id", seq.getAndIncrement());
                    write(node.toString());
                } catch (NodeBridgeError ignored) { }
                // 关闭 worker stdin:worker 的 reader 线程阻塞在 fs.readSync 读 stdin,
                // 收到 EOF 才退出(Windows 上进程仍存活时 process.exit 会挂起)。关闭写端
                // 使 reader 得 EOF → worker 干净退出,close 不必等 destroyForcibly。
                closeWorkerStdin();
                // 给 worker 一个退出窗口,然后强制回收
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                else process.destroy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        services.clear();
        bridges.clear();
        failAllPending("node worker closed");
    }

    /** 关闭 worker 的 stdin(与 write 同锁,避免并发写途中关闭)。 */
    private void closeWorkerStdin() {
        synchronized (stdin) {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // worker 已死 / 已关闭:忽略
            }
        }
    }
}
