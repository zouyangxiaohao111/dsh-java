package dev.dsh.cordis.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.PluginRuntimeResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 宿主选择(design §2.2 HostSelector):解析 {@code source} 的 {@code java:}/{@code node:}/
 * {@code graaljs:} 前缀,或无前缀 / {@code path} 时交给 {@link PluginRuntimeResolver} 自动检测。
 *
 * <pre>{@code
 *   source: java:dev.dsh.demo.CounterPlugin  → JAVA,类名
 *   source: java:./P.java                     → JAVA,源码文件
 *   source: jar:./plugins/x.jar               → JAVA,jar 插件(mainClass 可选)
 *   source: graaljs:./plugins/greeter         → GRAAL,路径
 *   source: node:./plugins/foo                → NODE,路径
 *   path: node_modules/@koishijs/plugin-echo  → 无前缀 → resolver.detect
 * }</pre>
 *
 * <p>选宿主优先级:source 前缀 &gt; yml 显式 {@code host:} 字段 &gt; {@link PluginRuntimeResolver} 自动检测。
 */
public final class HostSelector {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginRuntimeResolver resolver;

    public HostSelector() {
        this(new PluginRuntimeResolver());
    }

    public HostSelector(PluginRuntimeResolver resolver) {
        this.resolver = resolver;
    }

    /** 供加载方复用同一 resolver(缓存/失效一致)。 */
    public PluginRuntimeResolver resolver() {
        return resolver;
    }

    /**
     * 解析一条声明。
     *
     * @param baseDir yml 所在目录(相对引用的解析基准)
     */
    public ResolvedEntry select(Entry entry, Path baseDir) {
        if (!entry.hasTarget()) {
            throw new IllegalArgumentException("plugin entry '" + entry.name() + "' has neither source nor path");
        }
        String src = entry.source();
        HostKind kind = null;
        boolean explicit = false;
        String ref = null;

        // ① source 前缀解析
        if (src != null && !src.isBlank()) {
            String trimmed = src.trim();
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                HostKind prefixed = prefixKind(trimmed.substring(0, colon));
                if (prefixed != null) {
                    kind = prefixed;
                    explicit = true;
                    ref = trimmed.substring(colon + 1).trim();
                }
            }
            if (ref == null) ref = trimmed;
        }

        // ② yml 显式 host 字段(无前缀时生效)
        if (kind == null && entry.host() != null) {
            kind = entry.host();
            explicit = true;
        }

        // ③ path 兜底为引用
        if (ref == null && entry.path() != null && !entry.path().isBlank()) {
            ref = entry.path().trim();
        }

        // ④ 无前缀 → PluginRuntimeResolver 自动检测(缓存 + 运行时兜底由加载方保留)
        if (kind == null) {
            Path candidate = baseDir.resolve(ref).normalize();
            // M6-6 dsh profile 组合行的模块说明符(如 @deepseek-ai/dsh-llm)经 node_modules
            // 上行查找解析(createRequire.resolve.paths 等价):字面量不存在时从 baseDir 上行
            // 检查 node_modules/<ref>。找不到则保持原路径(加载期报清晰错误)。
            if (!Files.exists(candidate)) {
                Path viaNodeModules = resolveFromNodeModules(baseDir, ref);
                if (viaNodeModules != null) candidate = viaNodeModules;
            }
            kind = resolver.detect(candidate);
            return new ResolvedEntry(entry, kind, false, ref, candidate);
        }

        // 显式宿主:java: 类名不落盘;.java/.class/.jar 源落盘;js 路径落盘
        if (kind == HostKind.JAVA) {
            String lower = ref.toLowerCase(Locale.ROOT);
            Path abs = (lower.endsWith(".java") || lower.endsWith(".class") || lower.endsWith(".jar"))
                    ? baseDir.resolve(ref).normalize()
                    : null;
            return new ResolvedEntry(entry, kind, true, ref, abs);
        }
        Path abs = baseDir.resolve(ref).normalize();
        return new ResolvedEntry(entry, kind, explicit, ref, abs);
    }

    /**
     * 从 {@code baseDir} 上行查找 {@code node_modules/<ref>}(Node createRequire.resolve.paths
     * 等价);找到返回其绝对路径,找不到返回 null。用于 dsh profile 组合行的 npm 模块说明符
     * (如 {@code @deepseek-ai/dsh-llm})。与 {@link DshProfileReader#packageDirFromAnchor}
     * 同构,这里是模块根查找而非 bundle 解析。
     */
    static Path resolveFromNodeModules(Path baseDir, String ref) {
        Path dir = baseDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve("node_modules").resolve(ref).normalize();
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    /** 解析 JS 插件路径为入口文件:目录 → package.json {@code main}(回退 index.js/cjs/mjs)。 */
    static Path resolveJsEntry(Path p) {
        if (Files.isRegularFile(p)) return p;
        if (!Files.isDirectory(p)) return p;
        Path pkg = p.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            try {
                JsonNode main = MAPPER.readTree(pkg.toFile()).path("main");
                if (main.isTextual() && !main.asText().isBlank()) {
                    Path m = p.resolve(main.asText());
                    if (Files.isRegularFile(m)) return m;
                }
            } catch (IOException ignored) {
                // 落到 index.* 兜底
            }
        }
        for (String candidate : new String[]{"index.js", "index.cjs", "index.mjs"}) {
            Path m = p.resolve(candidate);
            if (Files.isRegularFile(m)) return m;
        }
        throw new IllegalStateException("no JS entry file found under plugin dir: " + p);
    }

    private static HostKind prefixKind(String prefix) {
        return switch (prefix.toLowerCase(Locale.ROOT)) {
            case "java" -> HostKind.JAVA;
            case "jar" -> HostKind.JAVA;   // jar 插件也是 Java 宿主(mainClass/扫描发现)
            case "node" -> HostKind.NODE;
            case "graaljs", "graal" -> HostKind.GRAAL;
            default -> null;
        };
    }
}
