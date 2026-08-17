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
            kind = resolver.detect(candidate);
            return new ResolvedEntry(entry, kind, false, ref, candidate);
        }

        // 显式宿主:java: 类名不落盘;.java/.class 源落盘;js 路径落盘
        if (kind == HostKind.JAVA) {
            String lower = ref.toLowerCase(Locale.ROOT);
            Path abs = (lower.endsWith(".java") || lower.endsWith(".class"))
                    ? baseDir.resolve(ref).normalize()
                    : null;
            return new ResolvedEntry(entry, kind, true, ref, abs);
        }
        Path abs = baseDir.resolve(ref).normalize();
        return new ResolvedEntry(entry, kind, explicit, ref, abs);
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
            case "node" -> HostKind.NODE;
            case "graaljs", "graal" -> HostKind.GRAAL;
            default -> null;
        };
    }
}
