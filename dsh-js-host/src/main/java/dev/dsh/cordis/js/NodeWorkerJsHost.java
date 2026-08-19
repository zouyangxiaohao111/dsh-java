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
import java.lang.ref.Cleaner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
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
    static final long REQUEST_TIMEOUT_MS = 120_000;
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
    /** M7-7:JS 侧 live 对象 / iterable 句柄 → Java 侧包装(RemoteObject / JsIterable)。
     *  worker 经 invokeService 调用这些句柄时,Java 转发 invokeObj 回属主 worker。 */
    private final Map<Long, Object> remoteObjects = new ConcurrentHashMap<>();
    /** M7-7:remote 句柄 GC 后兜底释放(代理不可达时 worker 侧注册表不泄漏)。 */
    private final Cleaner cleaner = Cleaner.create();

    // ---- M7-8:跨 worker 服务路由(provide 通道服务句柄化)----
    // 提供方 worker 把服务对象注册为 objById,Java 分配全局句柄 id(≥ GLOBAL_OBJ_ID_BASE,
    // 远大于 worker 本地 nextHandle 计数 → 永不与任一 worker 的本地 obj/fn 句柄冲突)并
    // rehandle(worker 侧 objById 条目迁到全局 id)。全局 id → 属主宿主 的映射 JVM 级共享,
    // 使读方 worker 的宿主能把 invokeService(invokeObj/invokeGet/invokeMembers)转发回属主
    // worker。宿主关闭 / 句柄释放时清理条目。
    private static final long GLOBAL_OBJ_ID_BASE = 100_000_000L;
    private static final Map<Long, NodeWorkerJsHost> REMOTE_OBJ_OWNERS = new ConcurrentHashMap<>();
    private static final AtomicLong GLOBAL_OBJ_ID = new AtomicLong(GLOBAL_OBJ_ID_BASE);
    // M7-8:跨 worker 回调 —— 服务方法参数里的 fn 句柄导出为全局 id(≥ GLOBAL_FN_ID_BASE,
    // 与 obj 空间错开),全局 fn id → 属主宿主 映射,使属主 worker 之外的 worker 能经 Java
    // 路由回来执行回调(如 skill 服务的 registerProvider(cb)、systemPrompt.tools(cb))。
    private static final long GLOBAL_FN_ID_BASE = 200_000_000L;
    private static final Map<Long, NodeWorkerJsHost> FUNCTION_OWNERS = new ConcurrentHashMap<>();
    private static final AtomicLong GLOBAL_FN_ID = new AtomicLong(GLOBAL_FN_ID_BASE);

    private final AtomicLong seq = new AtomicLong(1);
    private final AtomicLong serviceSeq = new AtomicLong(1_000_000);
    /** M8 low ②:跨 worker 句柄转发专用固定大小 daemon 线程池 —— 复用线程、限制并发(原虚拟
     *  线程每任务一线程,并发无界)。reader 线程只入队不阻塞;池线程阻塞等属主 worker 回复
     *  (reader 线程继续泵消息,防互等死锁)。宿主 close() 时 shutdownNow 回收。 */
    static final int REMOTE_FORWARD_POOL_SIZE = 4;
    private final java.util.concurrent.ExecutorService remoteForwardExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(REMOTE_FORWARD_POOL_SIZE, r -> {
                Thread t = new Thread(r, "dsh-remote-forward");
                t.setDaemon(true);
                return t;
            });

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
        // M8:`node:process` 内置模块在桥侧活跃读 stdin 时 import 会死锁(Windows,Node 24)——
        // resolve 钩子把它拦到进程 shim(惰性 re-export globalThis.process),路径经环境变量传入。
        Path processShim = extractResource("js/node-process-shim.mjs", runtimeDir.resolve("node-process-shim.mjs"));
        ProcessBuilder pb = new ProcessBuilder(nodeExecutable(), bridgeScript.toAbsolutePath().toString());
        if (requireCwd != null && !requireCwd.toString().isEmpty()) {
            pb.directory(requireCwd.toAbsolutePath().toFile());
        }
        pb.environment().put("DSH_CORDIS_SHIM", shim.toAbsolutePath().normalize().toString());
        pb.environment().put("DSH_PROCESS_SHIM", processShim.toAbsolutePath().normalize().toString());
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

    /** 阻塞调用 JS 函数句柄(全局 fn 句柄跨 worker 路由到属主宿主执行)。 */
    Object invokeFn(NodeRef fn, List<Object> args) {
        if (!"fn".equals(fn.kind())) throw new NodeBridgeError("not a function handle: " + fn);
        NodeWorkerJsHost owner = FUNCTION_OWNERS.get(fn.id());
        if (owner != null && owner != this) return owner.invokeFnRpc(fn, args);
        return invokeFnRpc(fn, args);
    }

    /** 阻塞调用本 worker 的 JS 函数句柄(invokeFn RPC)。 */
    private Object invokeFnRpc(NodeRef fn, List<Object> args) {
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
        FUNCTION_OWNERS.remove(fn.id());   // M7-8:全局 fn 句柄路由清理(本地 id 为 no-op)
        sendNoWait("release", jsonOf("handle", fn.id()));
    }

    /** 把一个 Java 对象注册为远程服务,返回 svc 句柄。 */
    NodeRef registerService(Object svc) {
        long id = serviceSeq.getAndIncrement();
        services.put(id, svc);
        return new NodeRef(id, "svc");
    }

    /**
     * 反射调用远程服务(worker 经 invokeService 消息发起)。三类路由:
     *   - Java 服务(services 注册)→ {@link ServiceInvoker}(含 M7-8 $members/$get 元操作);
     *   - JS 侧句柄(RemoteObject / JsIterable,remoteObjects 或 REMOTE_OBJ_OWNERS)→ 转发到
     *     属主 worker 执行(invokeObj / invokeGet / invokeMembers)。
     * 注意:reader 线程上不得阻塞转发**本 worker 自己的句柄**(会死锁)——调用方
     * (respondToWorker)已在转发前把自持句柄分流到 helper 线程;跨 worker 转发阻塞在
     * 属主宿主的 reader 线程上,可安全内联。
     */
    Object invokeService(long handle, String method, List<Object> args) {
        // M7-8:全局 fn 句柄(跨 worker 回调参数)→ 路由到属主 worker 执行(invokeFn)。
        NodeWorkerJsHost fnOwner = FUNCTION_OWNERS.get(handle);
        if (fnOwner != null) {
            return fnOwner.invokeFnRpc(new NodeRef(handle, "fn"), args);
        }
        Object svc = services.get(handle);
        if (svc != null) {
            if ("$members".equals(method)) return ServiceInvoker.members(svc);
            if ("$get".equals(method)) {
                String name = args.isEmpty() ? "" : String.valueOf(args.get(0));
                return ServiceInvoker.field(svc, name);
            }
            return ServiceInvoker.invoke(svc, method, args);
        }
        NodeWorkerJsHost owner = ownerOf(handle);
        if (owner == null) throw new NodeBridgeError("unknown service handle " + handle);
        if ("$members".equals(method)) return owner.invokeMembersRpc(handle);
        if ("$get".equals(method)) {
            String name = args.isEmpty() ? "" : String.valueOf(args.get(0));
            return owner.invokeGetRpc(handle, name);
        }
        return owner.invokeObjRpc(handle, method, args);
    }

    /** M7-8:把句柄解析到属主宿主 —— 本 worker(remoteObjects 注册)或跨 worker 全局句柄。 */
    private NodeWorkerJsHost ownerOf(long handle) {
        if (remoteObjects.containsKey(handle)) return this;
        return REMOTE_OBJ_OWNERS.get(handle);
    }

    /** 阻塞调用本 worker 的 JS 侧 live 对象方法(invokeObj RPC)。 */
    private Object invokeObjRpc(long handle, String method, List<Object> args) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("handle", handle);
        payload.put("method", method);
        payload.set("args", toJsonArray(args));
        return fromJsonNode(request("invokeObj", payload));
    }

    /** 阻塞读取本 worker 的 JS 侧 live 对象属性(invokeGet RPC,触发 getter)。 */
    private Object invokeGetRpc(long handle, String prop) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("handle", handle);
        payload.put("prop", prop);
        return fromJsonNode(request("invokeGet", payload));
    }

    /** 阻塞读取本 worker 的 JS 侧 live 对象成员描述符(invokeMembers RPC)。 */
    private Object invokeMembersRpc(long handle) {
        return fromJsonNode(request("invokeMembers", jsonOf("handle", handle)));
    }

    /** 阻塞调用 JS 侧 live 对象方法(RemoteObject / JsIterable 的 RPC 底层;同宿主直发)。 */
    Object invokeObj(long handle, String method, List<Object> args) {
        return invokeObjRpc(handle, method, args);
    }

    /** 读取 JS 侧 live 对象的一个属性(M7-7 发射器形状补齐:含 getter、子发射器成员)。 */
    Object invokeGet(long handle, String prop) {
        return invokeGetRpc(handle, prop);
    }

    /** M7-8:RemoteObject Map 门面的成员描述符读取(路由到属主 worker)。 */
    Object invokeMembersForFacade(long handle) {
        NodeWorkerJsHost owner = ownerOf(handle);
        if (owner == null) throw new NodeBridgeError("unknown service handle " + handle);
        return owner.invokeMembersRpc(handle);
    }

    // ---- M7-8:跨 worker 服务句柄导出(provide 值与跨 worker 方法返回里的 live 对象)----

    /**
     * 把本 worker 的本地 obj 句柄导出为全局句柄:分配全局 id → 通知 worker 把 objById 条目
     * 迁到全局 id(rehandleObj,fire-and-forget;wire 顺序保证先于 provide/方法返回的回复)→
     * 本机 remoteObjects re-key → 注册跨 worker 路由。已是全局的句柄(透传)原样返回。
     */
    RemoteObject exportRemote(RemoteObject ro) {
        long local = ro.handle();
        if (REMOTE_OBJ_OWNERS.containsKey(local)) return ro;   // 已全局(跨 worker 透传)
        long g = GLOBAL_OBJ_ID.getAndIncrement();
        sendNoWait("rehandleObj", jsonOf("from", local, "to", g));
        remoteObjects.remove(local);
        RemoteObject exported = new RemoteObject(this, g);
        remoteObjects.put(g, exported);
        cleaner.register(exported, () -> releaseObj(g));
        REMOTE_OBJ_OWNERS.put(g, this);
        return exported;
    }

    /**
     * 递归导出值里的 live 对象句柄(跨 worker 方法返回 / 提供值里的嵌套 RemoteObject)。
     * 使读方 worker 拿到的 {@code {$kind:'obj'}} id 是全局 id → 后续方法/getter 调用能路由回
     * 属主 worker。导出始终在属主宿主上执行(ro.host() —— 结果可能来自其它宿主的 fromJsonNode,
     * 若在本宿主 rehandle 会发给错误的 worker)。纯数据 / Java 服务 / JsIterable 原样返回
     * (不强转)。
     */
    Object exportRemoteDeep(Object v) {
        if (v instanceof RemoteObject ro) return ro.host().exportRemote(ro);
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : new ArrayList<>(m.entrySet())) {
                Object nv = exportRemoteDeep(e.getValue());
                if (nv != e.getValue()) ((Map<Object, Object>) m).put(e.getKey(), nv);
            }
            return m;
        }
        if (v instanceof List<?> list) {
            List<Object> mutable = (List<Object>) list;   // fromJsonNode 产物恒为可变 ArrayList
            for (int i = 0; i < mutable.size(); i++) {
                Object nv = exportRemoteDeep(mutable.get(i));
                if (nv != mutable.get(i)) mutable.set(i, nv);
            }
            return v;
        }
        return v;
    }

    /** 释放一个 JS 侧 live 对象 / iterable 句柄(worker 删除 objById 条目;跨 worker 路由同步清理)。
     *  fire-and-forget。 */
    void releaseObj(long handle) {
        remoteObjects.remove(handle);
        REMOTE_OBJ_OWNERS.remove(handle);
        sendNoWait("releaseObj", jsonOf("handle", handle));
    }

    boolean isReaderThread(Thread t) { return t == readerThread; }

    /** 测试钩子:M7-8 跨 worker 路由表是否仍含某全局句柄(泄漏检查用)。 */
    static boolean routeRegistered(long handle) { return REMOTE_OBJ_OWNERS.containsKey(handle); }

    /**
     * 把本 worker 的本地 fn 句柄导出为全局句柄(跨 worker 服务方法参数里的回调):分配全局
     * fn id → 通知属主 worker 把 fnById 条目迁到全局 id(rehandleFn,fire-and-forget;泵内
     * 同步处理)→ 注册"全局 fn id → 属主宿主"路由。已是全局的句柄(透传)原样返回。
     */
    private NodeRef exportFn(NodeRef fn) {
        long local = fn.id();
        if (FUNCTION_OWNERS.containsKey(local)) return fn;   // 已全局(透传)
        long g = GLOBAL_FN_ID.getAndIncrement();
        sendNoWait("rehandleFn", jsonOf("from", local, "to", g));
        FUNCTION_OWNERS.put(g, this);
        return new NodeRef(g, "fn");
    }

    /** 递归导出跨 worker 服务参数里的 fn 句柄(仅对指向本 worker 的本地句柄导出;全局句柄 /
     *  Java 服务句柄 / RemoteObject / 纯数据原样保留)。 */
    private List<Object> exportFnDeep(List<Object> args) {
        for (int i = 0; i < args.size(); i++) {
            args.set(i, exportFnDeepValue(args.get(i)));
        }
        return args;
    }

    private Object exportFnDeepValue(Object v) {
        if (v instanceof NodeRef ref && "fn".equals(ref.kind())) return exportFn(ref);
        // RemoteObject/JsIterable 是句柄(RemoteObject 因 Map 门面也是 Map,必须先于 Map 分支
        // 排除,否则会被当可变 Map 遍历/put → "read-only")。句柄原样保留,由 exportRemoteDeep
        // 统一导出。
        if (v instanceof RemoteObject || v instanceof JsIterable) return v;
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : new ArrayList<>(m.entrySet())) {
                Object nv = exportFnDeepValue(e.getValue());
                if (nv != e.getValue()) ((Map<Object, Object>) m).put(e.getKey(), nv);
            }
            return m;
        }
        if (v instanceof List<?> list) {
            List<Object> mutable = (List<Object>) list;
            for (int i = 0; i < mutable.size(); i++) {
                Object nv = exportFnDeepValue(mutable.get(i));
                if (nv != mutable.get(i)) mutable.set(i, nv);
            }
            return v;
        }
        return v;
    }

    // ---- 错误分类(resolver 兜底用)----

    /**
     * 确定性限制(剩余):M5 深化后 worker 已是 async 事件循环 —— apply/invokeFn 里的 macrotask
     * 可 await,ESM top-level await 经动态 import() 消化。仅剩"同步 serial/waterfall 折叠里的
     * macrotask await"无法同步 settle(同步折叠固有限制)→ 明确失败上报,重试无益。
     */
    static boolean isAsyncUnsupported(Throwable t) {
        String m = t == null ? null : String.valueOf(t.getMessage());
        if (m == null) return false;
        return m.toLowerCase(Locale.ROOT).contains("did not settle synchronously");
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
                // M7-8:reader 线程上对"非 Java 服务"句柄一律分到 helper 线程执行 —— 不能阻塞
                // reader 线程:跨 worker 调用的嵌套反向调用(worker B → hostB → hostA → worker A
                // → 回调回 worker B)会让两个 reader 线程互等死锁。Java 服务(services 注册)是纯
                // 同步调用、无 worker 往返,reader 线程内联安全。
                if (Thread.currentThread() == readerThread && !services.containsKey(handle)) {
                    forwardRemoteAsync(msg, id);
                    return;
                }
                result = toJsonNode(handleServiceInvocation(handle, msg.path("method").asText(""), msg.path("args")));
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

    /**
     * 处理一次 worker 发起的 invokeService:M7-8 跨 worker 语义 —— 参数里的 fn 回调导出为全局
     * 句柄(属主 worker 之外能路由回来执行),路由到属主 worker 执行,返回里的 live 对象导出为
     * 全局句柄(读方拿到可路由的 {$kind:'obj'} id)。Java 服务/自持句柄(本 worker)不导出。
     */
    private Object handleServiceInvocation(long handle, String method, JsonNode argsNode) {
        NodeWorkerJsHost target = FUNCTION_OWNERS.get(handle);
        if (target == null && !services.containsKey(handle)) target = ownerOf(handle);
        List<Object> javaArgs = toJavaArgs(argsNode);
        if (target != null && target != this) {
            javaArgs = exportFnDeep(javaArgs);
            exportRemoteDeep(javaArgs);   // 参数里的 live 对象(如回调的 AbortSignal)也导出,读方才可路由
        }
        return exportRemoteDeep(invokeService(handle, method, javaArgs));
    }

    /** reader 线程上的句柄转发:helper 池线程阻塞等属主 worker 回复,reader 线程继续读
     *  (防互等死锁)。M8 low ②:走 {@link #remoteForwardExecutor}(固定大小 daemon 线程池,
     *  每宿主一个执行器),不再每次 new Thread,限制并发。 */
    private void forwardRemoteAsync(JsonNode msg, long id) {
        remoteForwardExecutor.execute(() -> {
            try {
                long handle = msg.path("handle").asLong(-1);
                Object result = handleServiceInvocation(handle, msg.path("method").asText(""), msg.path("args"));
                ObjectNode resp = mapper.createObjectNode();
                resp.put("type", "ctxResult");
                resp.put("id", id);
                resp.set("result", result == null ? NullNode.instance : toJsonNode(result));
                write(resp.toString());
            } catch (Throwable t2) {
                ObjectNode resp = mapper.createObjectNode();
                resp.put("type", "ctxResult");
                resp.put("id", id);
                resp.put("error", String.valueOf(t2.getMessage()));
                try { write(resp.toString()); } catch (RuntimeException ignored) {
                    // worker 已关闭:放弃
                }
            }
        });
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
        // M8:Context.NO_SERVICE(JS ctx.get 语义的"服务不可用"哨兵)统一映射 JS undefined。
        // LoaderService.await() 返回它 → worker 侧 web-runtime 的 `ctx.get("loader")?.await()`
        // 得到 undefined → "no settle promise" 分支(已 settle)直接 printUrl。
        if (value == dev.dsh.cordis.Context.NO_SERVICE) {
            return toJsonNode(UNDEFINED);
        }
        if (value instanceof JsonNode j) return j;
        if (value instanceof NodeRef ref) {
            ObjectNode n = mapper.createObjectNode();
            n.put("$kind", ref.kind());
            n.put("id", ref.id());
            return n;
        }
        // M7-7:JS 侧句柄原样回传(JsIterable 也是 Iterable,必须先于 Iterable 物化判定)。
        if (value instanceof RemoteObject ro) {
            ObjectNode n = mapper.createObjectNode();
            n.put("$kind", "obj");
            n.put("id", ro.handle());
            return n;
        }
        if (value instanceof JsIterable ji) {
            ObjectNode n = mapper.createObjectNode();
            n.put("$kind", "iter");
            n.put("id", ji.handle());
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
        // 仅纯数据集合(Collection)物化;live iterable 服务(如 AgentRegistry,非 Collection)
        // 走 svc 句柄 —— JS 侧经服务代理 Symbol.iterator RPC hasNext/next 遍历(目标行同形:
        // ctx.agents 既有 .list() 方法又可被 for...of 遍历)。数组同样物化。
        if (value instanceof Collection<?> c) return toJsonArray(c);
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

    private ObjectNode jsonOf(String k1, long v1, String k2, long v2) {
        ObjectNode n = mapper.createObjectNode();
        n.put(k1, v1);
        n.put(k2, v2);
        return n;
    }

    Object fromJsonNode(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isObject() && v.hasNonNull("$kind")) {
            String kind = v.path("$kind").asText();
            long id = v.path("id").asLong(-1);
            switch (kind) {
                case "fn", "module", "ctx", "svc" -> { return new NodeRef(id, kind); }
                case "obj" -> {
                    // JS 侧 live 对象 → RemoteObject(方法调用 RPC 回 worker);注册 remote 句柄
                    // 供 worker 经 invokeService 调用时转发,并挂 Cleaner 兜底 GC 释放。
                    RemoteObject ro = new RemoteObject(this, id);
                    remoteObjects.put(id, ro);
                    cleaner.register(ro, () -> releaseObj(id));
                    return ro;
                }
                case "iter" -> {
                    JsIterable ji = new JsIterable(this, id);
                    remoteObjects.put(id, ji);
                    cleaner.register(ji, () -> releaseObj(id));
                    return ji;
                }
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
        /** M7-8:Java 服务成员描述符(供 JS 侧服务代理分辨方法/getter)。public 方法全标
         *  'function'(可调用);public 实例字段标 'value' —— proxy 的 get trap 对 value 成员发
         *  {@code $get} 直接读值。M7-8 B+C:这使 {@code ctx.loader.internal.version} 读链
         *  (LoaderInternal 的 version 字段)跨桥读到真值,而不是被误当方法。方法名优先:
         *  同名 public 字段不覆盖方法(JS 侧对该名仍得可调用),避免字段遮蔽方法调用。 */
        static Object members(Object svc) {
            List<Map<String, Object>> out = new ArrayList<>();
            java.util.Set<String> methods = new java.util.LinkedHashSet<>();
            for (Method m : svc.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                methods.add(m.getName());
                out.add(Map.of("name", m.getName(), "type", "function"));
            }
            for (Field f : svc.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (methods.contains(f.getName())) continue;   // 方法优先,不遮蔽
                out.add(Map.of("name", f.getName(), "type", "value"));
            }
            return out;
        }

        /** M7-8:Java 服务成员值读(proxy 的 $get):public 实例字段读。方法名落到这里 → 无字段
         *  返回 null(仅防御;proxy 只在成员类型为 'value' 时发 $get,Java 服务成员全为方法)。 */
        static Object field(Object svc, String name) {
            for (Field f : svc.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().equals(name)) {
                    try {
                        return f.get(svc);
                    } catch (IllegalAccessException e) {
                        throw new NodeBridgeError("service field read failed: " + svc.getClass().getSimpleName() + "." + name, e);
                    }
                }
            }
            return null;
        }

        static Object invoke(Object svc, String method, List<Object> args) {
            // M7-7:java.util.Iterator 的 JS 迭代器协议适配 —— JS for...of 调 next() 期望
            // {done, value}(Java Iterator.next() 直接返回元素,协议不匹配)。hasNext/next
            // 特判成 {done, value};iterator() 返回自身(不是 Iterable 的对象也能被 for...of)。
            if (svc instanceof java.util.Iterator<?> it) {
                if ("iterator".equals(method)) return it;
                if ("hasNext".equals(method)) return it.hasNext();
                if ("next".equals(method)) {
                    if (!it.hasNext()) return Map.of("done", true);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("done", false);
                    m.put("value", it.next());
                    return m;
                }
                if ("remove".equals(method)) { it.remove(); return null; }
            }
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
        // M7-8:清理本宿主在跨 worker 路由表里的全局句柄条目(worker 进程已终止,句柄随之失效)
        for (Long h : remoteObjects.keySet()) REMOTE_OBJ_OWNERS.remove(h);
        FUNCTION_OWNERS.values().removeIf(owner -> owner == this);
        services.clear();
        bridges.clear();
        remoteObjects.clear();
        failAllPending("node worker closed");
        // M8 low ②:回收 reader 句柄转发的固定大小线程池(close 后不再有入站消息需要转发)
        remoteForwardExecutor.shutdownNow();
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
