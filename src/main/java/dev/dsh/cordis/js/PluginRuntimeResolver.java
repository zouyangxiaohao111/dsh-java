package dev.dsh.cordis.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.PolyglotException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 三宿主 PluginRuntimeResolver(design §2.3):给一个插件决定宿主
 * ① Java(ClassLoader)/ ② GraalJsHost / ③ NodeWorkerJsHost。
 *
 * <p>两层决策:
 * <ol>
 *   <li><b>静态检测</b>(加载前,每插件路径缓存):Java class? native({@code .node}/binding.gyp)?
 *       ESM({@code type:module} 且无 CJS / {@code .mjs} / 源码 ESM 语法)? 重 Node(源码扫
 *       Node 内置与 {@code process})?</li>
 *   <li><b>运行时兜底</b>:默认 GraalJS 试跑;加载抛 {@link PolyglotException} 且为缺模块
 *       (cannot load/find module、unsupported .node)→ dispose GraalJS → Node worker 重试。
 *       兜底是正确性根基,静态检测只是优化。</li>
 * </ol>
 *
 * <p>每插件独立选 + 结果缓存({@link ConcurrentHashMap});热重载后调用
 * {@link #invalidate(Path)} 使缓存失效(文件语义可能已变)。
 */
public final class PluginRuntimeResolver {

    /** package.json 解析用(只读,线程安全)。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 决策缓存:插件路径(绝对归一化)→ 宿主判定。 */
    private final Map<Path, HostKind> cache = new ConcurrentHashMap<>();
    private final JsHostFactory hostFactory;

    public PluginRuntimeResolver() {
        this(new JsHostFactory());
    }

    public PluginRuntimeResolver(JsHostFactory hostFactory) {
        this.hostFactory = hostFactory;
    }

    // ---- 对外 API ----

    /** 静态检测插件应走哪个宿主(加载前,可缓存)。 */
    public HostKind detect(Path pluginPath) {
        Path abs = pluginPath.toAbsolutePath().normalize();
        return cache.computeIfAbsent(abs, this::detectUncached);
    }

    /** 使某插件路径的静态判定缓存失效(热重载后调用)。 */
    public void invalidate(Path pluginPath) {
        cache.remove(pluginPath.toAbsolutePath().normalize());
    }

    /** 清空全部决策缓存。 */
    public void clear() {
        cache.clear();
    }

    /**
     * 按决策创建宿主并加载插件模块(require 基准 = 插件文件所在目录)。GraalJS 试跑失败
     * (缺模块)自动 dispose 并 Node worker 重试,并把缓存提升为 {@link HostKind#NODE}。
     */
    public ResolvedJsPlugin loadJs(Path pluginFile) throws IOException {
        return loadJs(pluginFile, requireCwd(pluginFile));
    }

    /**
     * 按决策创建宿主并加载插件模块;{@code requireCwd} 显式指定 require 基准目录
     * (如插件依赖经某 node_modules 解析时)。
     */
    public ResolvedJsPlugin loadJs(Path pluginFile, Path requireCwd) throws IOException {
        Path abs = pluginFile.toAbsolutePath();
        HostKind kind = detect(abs);
        if (kind == HostKind.JAVA) {
            throw new IllegalArgumentException(
                    "detect() selected JAVA for " + abs + "; Java plugins load via ClassLoader (PluginReloader), not JsHost");
        }
        if (kind == HostKind.NODE) {
            return loadNode(abs, requireCwd);
        }
        // ② 默认 GraalJS 试跑(静态检测为优化;正确性靠兜底)
        JsHost graal = hostFactory.create(HostKind.GRAAL, requireCwd);
        try {
            return new ResolvedJsPlugin(HostKind.GRAAL, graal, new JsPluginAdapter(graal, graal.loadModule(abs)));
        } catch (PolyglotException e) {
            if (!isMissingModule(e)) throw e;
            graal.close();
            ResolvedJsPlugin resolved;
            try {
                resolved = loadNode(abs, requireCwd);
            } catch (NodeBridgeError nbe) {
                throw reportNodeFailure(abs, nbe);
            }
            cache.put(abs, HostKind.NODE);   // 提升缓存:后续直接走 Node,不再试 Graal
            return resolved;
        }
    }

    /**
     * Node 路径:创建 worker 加载模块;瞬时 worker 故障(进程死 / 请求超时 / 管道关闭)重建
     * worker 重试一次(类 code-runtime 的失败回收),再失败转 {@link #reportNodeFailure} 明确上报
     * (macrotask / top-level await 限制给可执行建议,其余原样透出)。
     */
    private ResolvedJsPlugin loadNode(Path abs, Path requireCwd) throws IOException {
        JsHost node = hostFactory.create(HostKind.NODE, requireCwd);
        try {
            return new ResolvedJsPlugin(HostKind.NODE, node, new JsPluginAdapter(node, node.loadModule(abs)));
        } catch (NodeBridgeError first) {
            node.close();   // 无论是否重试,先回收故障 worker(避免 Windows 下其 cwd=插件目录锁泄漏)
            if (!NodeWorkerJsHost.isRetryable(first)) throw reportNodeFailure(abs, first);
            JsHost fresh = hostFactory.create(HostKind.NODE, requireCwd);
            try {
                return new ResolvedJsPlugin(HostKind.NODE, fresh, new JsPluginAdapter(fresh, fresh.loadModule(abs)));
            } catch (NodeBridgeError second) {
                fresh.close();
                throw reportNodeFailure(abs, second);
            }
        }
    }

    /** 把 Node 宿主失败转成明确失败上报:macrotask / TLA 限制给可执行建议,其余原样透出。 */
    private static NodeBridgeError reportNodeFailure(Path abs, NodeBridgeError e) {
        if (NodeWorkerJsHost.isAsyncUnsupported(e)) {
            return new NodeBridgeError("plugin '" + abs + "' cannot run on NodeWorkerJsHost: the module uses top-level "
                    + "await or awaits a macrotask (timer / I/O) in apply, which the synchronous Node worker host cannot "
                    + "await (see design doc 'NodeWorkerJsHost 同步宿主限制'). Restructure to avoid top-level await / "
                    + "macrotask awaits, or use the GraalJS host for such plugins. Root cause: " + e.getMessage(), e);
        }
        return e;
    }

    // ---- 静态检测 ----

    private HostKind detectUncached(Path abs) {
        String fileName = abs.getFileName().toString().toLowerCase(Locale.ROOT);
        // ① Java class / 源码 → ClassLoader 宿主
        if (fileName.endsWith(".class") || fileName.endsWith(".java")) return HostKind.JAVA;

        Path root = Files.isDirectory(abs) ? abs : abs.getParent();
        if (root == null || !Files.isDirectory(root)) return HostKind.GRAAL;

        // ② native:目录内(含 node_modules)有 .node / binding.gyp → 只能真 Node
        if (hasNative(root)) return HostKind.NODE;

        // ③ ESM → 真 Node
        if (isEsm(abs)) return HostKind.NODE;

        // ④ 重 Node:插件自身源码扫 Node 内置 / process → 真 Node
        if (isHeavyNode(root)) return HostKind.NODE;

        return HostKind.GRAAL;
    }

    // ---- native 检测 ----

    /** 目录(递归,含 node_modules)内是否存在 native 模块:.node 文件或 binding.gyp。 */
    private static boolean hasNative(Path root) {
        try (Stream<Path> s = Files.walk(root, 8)) {
            return s.filter(Files::isRegularFile).anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".node") || n.equals("binding.gyp");
            });
        } catch (IOException e) {
            return false;
        }
    }

    // ---- ESM 检测 ----

    /** 插件是否为 ESM(type:module 且无 CJS / .mjs / 源码 ESM 语法)→ 需真 Node。 */
    private static boolean isEsm(Path abs) {
        String name = abs.getFileName().toString();
        if (name.endsWith(".mjs")) return true;
        if (Files.isDirectory(abs)) {
            Path entry = entryOf(abs);
            if (entry == null) return false;
            return entryIsEsm(entry);
        }
        return entryIsEsm(abs);
    }

    /** 目录插件的入口文件(package.json main,否则 index.js/cjs/mjs)。 */
    private static Path entryOf(Path dir) {
        Path pkg = dir.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            try {
                JsonNode main = MAPPER.readTree(pkg.toFile()).path("main");
                if (main.isTextual() && !main.asText().isBlank()) {
                    Path m = dir.resolve(main.asText());
                    if (Files.isRegularFile(m)) return m;
                }
            } catch (IOException ignored) {
                // 落到 index.* 兜底
            }
        }
        for (String candidate : new String[]{"index.js", "index.cjs", "index.mjs"}) {
            Path p = dir.resolve(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static boolean entryIsEsm(Path entry) {
        String name = entry.getFileName().toString();
        if (name.endsWith(".mjs")) return true;
        if (name.endsWith(".cjs")) return false;          // 显式 CJS:GraalJS 可跑
        if (hasTypeModuleScope(entry)) {
            // type:module 作用域内:有 CJS 替身(dual package)或源码是 CJS(require/module.exports)
            // → GraalJS 可跑 CJS 侧;真正 ESM 源码(import/export)→ 需真 Node
            if (hasCjsSibling(entry)) return false;
            String src = readSource(entry);
            if (src != null && CJS_MARKER.matcher(src).find()) return false;
            return true;
        }
        return hasEsmSyntax(entry);                        // 无 type 字段 → 源码 ESM 语法兜底
    }

    /** 从插件文件目录向上找最近的 package.json 是否声明 type:module。 */
    private static boolean hasTypeModuleScope(Path file) {
        Path dir = file.getParent();
        while (dir != null) {
            Path pkg = dir.resolve("package.json");
            if (Files.isRegularFile(pkg)) {
                try {
                    JsonNode type = MAPPER.readTree(pkg.toFile()).path("type");
                    return type.isTextual() && "module".equals(type.asText());
                } catch (IOException ignored) {
                    return false;
                }
            }
            dir = dir.getParent();
        }
        return false;
    }

    /** 同目录是否存在同名 .cjs 替身(dual package)。 */
    private static boolean hasCjsSibling(Path entry) {
        Path dir = entry.getParent();
        if (dir == null) return false;
        String base = entry.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return Files.isRegularFile(dir.resolve(base + ".cjs"));
    }

    private static final Pattern ESM_STATEMENT = Pattern.compile("(?m)^\\s*(?:import\\s|export\\s)");
    private static final Pattern CJS_MARKER = Pattern.compile("(?:module\\.exports|exports\\.|require\\s*\\()");

    /** 源码无 CJS 标记但含顶层 import/export 语句 → ESM。 */
    private static boolean hasEsmSyntax(Path file) {
        String src = readSource(file);
        if (src == null) return false;
        return ESM_STATEMENT.matcher(src).find() && !CJS_MARKER.matcher(src).find();
    }

    // ---- 重 Node 检测 ----

    /** Node 内置模块(裸名或 node: 前缀),GraalJS 均无法加载(实测统一 Cannot load module)。 */
    private static final Pattern HEAVY_NODE = Pattern.compile(
            "(?i)(?:require\\s*\\(\\s*['\"]"
            + "(?:node:)?(?:fs|path|os|stream|crypto|child_process|worker_threads|util|events|http|https|net|tls|zlib|url|buffer|vm|assert|dns|string_decoder|readline|querystring|timers|cluster|dgram|module|process)"
            + "['\"]\\s*\\)"
            + "|\\b(?:from|import)\\s+['\"]node:(?:fs|path|os|stream|crypto|child_process|worker_threads|util|events|http|https|net|tls|zlib|url|buffer|vm|assert|dns|string_decoder|readline|querystring|timers|cluster|dgram|module|process)['\"]"
            + "|\\bprocess\\.)");

    /** 插件自身源码(排除 node_modules)是否引用 Node 内置 / process。 */
    private static boolean isHeavyNode(Path root) {
        try (Stream<Path> s = Files.walk(root, 6)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> isJsSource(p))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .anyMatch(p -> {
                        String src = readSource(p);
                        return src != null && HEAVY_NODE.matcher(src).find();
                    });
        } catch (IOException e) {
            return false;
        }
    }

    // ---- 运行时兜底 ----

    /** GraalJS 加载失败是否因"缺模块"(Node 内置 / .mjs / native / 缺失依赖)。 */
    private static boolean isMissingModule(PolyglotException e) {
        String message = e.getMessage();
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("cannot load module")
                || m.contains("cannot find module")
                || m.contains("unsupported .node file");
    }

    // ---- 小工具 ----

    private static Path requireCwd(Path pluginFile) {
        Path parent = pluginFile.toAbsolutePath().getParent();
        return parent != null ? parent : Path.of("");
    }

    private static boolean isJsSource(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".js") || n.endsWith(".cjs") || n.endsWith(".mjs");
    }

    private static String readSource(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
