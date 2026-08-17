package dev.dsh.cordis.loader;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dsh.cordis.js.HostKind;

import java.util.Locale;

/**
 * 一条插件声明(cordis.yml {@code plugins[]} 的一项,design §2.1;也是 dsh profile
 * bundle patch 组合后的行 —— M6-6 {@link DshProfileReader})。
 *
 * <p>定位字段:
 * <ul>
 *   <li>{@code source} — 扩展写法,可带 {@code java:}/{@code node:}/{@code graaljs:}/{@code jar:} 前缀
 *       (如 {@code java:dev.dsh.demo.CounterPlugin}、{@code graaljs:./plugins/greeter}、
 *       {@code jar:./plugins/x.jar});dsh 组合行里是模块说明符(如 {@code @deepseek-ai/dsh-llm});</li>
 *   <li>{@code path} — dsh 兼容写法(JS 插件路径,如 {@code node_modules/@koishijs/plugin-echo});</li>
 *   <li>{@code host} — 可选的显式宿主覆盖(yml 直接写 {@code host:} 时),null = 未显式指定,
 *       交由 {@link HostSelector} 前缀 / {@link PluginRuntimeResolver} 自动检测;</li>
 *   <li>{@code mainClass} — 可选,jar 插件的显式入口类名(缺省扫描 {@code implements Plugin} /
 *       ServiceLoader 发现);</li>
 *   <li>{@code config} — 可选插件配置(JSON/YAML 节点,dsh 的 {@code config} 透传);null = 无配置。</li>
 * </ul>
 */
public record Entry(String name, String source, String path, HostKind host, String mainClass, JsonNode config) {

    /** 4 参便捷构造(无 {@code mainClass}/{@code config})——保持旧调用方兼容。 */
    public Entry(String name, String source, String path, HostKind host) {
        this(name, source, path, host, null, null);
    }

    /** 5 参便捷构造(无 {@code config})——保持旧调用方兼容。 */
    public Entry(String name, String source, String path, HostKind host, String mainClass) {
        this(name, source, path, host, mainClass, null);
    }

    /** 从 yml 节点解析;{@code name} 缺省回退到 dsh 写法的 {@code id}。 */
    public static Entry parse(JsonNode node) {
        String name = node.path("name").isTextual() ? node.path("name").asText() : node.path("id").asText(null);
        String source = node.path("source").isTextual() ? node.path("source").asText() : null;
        String path = node.path("path").isTextual() ? node.path("path").asText() : null;
        String mainClass = node.path("mainClass").isTextual() ? node.path("mainClass").asText().trim() : null;
        JsonNode config = node.get("config");
        HostKind host = null;
        JsonNode h = node.path("host");
        if (h.isTextual() && !h.asText().isBlank()) {
            try {
                host = HostKind.valueOf(h.asText().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("bad host '" + h.asText() + "' in plugin entry '" + name + "'", e);
            }
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("plugin entry is missing a name (expecting name/id)");
        }
        if (mainClass != null && mainClass.isBlank()) mainClass = null;
        return new Entry(name.trim(), source, path, host, mainClass, config);
    }

    /** 原始引用(source / path 之一;前缀剥离属 HostSelector 的职责)。 */
    public String ref() {
        if (source != null && !source.isBlank()) return source.trim();
        return path;
    }

    /** 是否声明了任何可解析目标(source 或 path)。 */
    public boolean hasTarget() {
        return (source != null && !source.isBlank()) || (path != null && !path.isBlank());
    }
}
