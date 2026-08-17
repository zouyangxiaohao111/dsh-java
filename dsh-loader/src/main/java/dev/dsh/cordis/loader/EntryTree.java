package dev.dsh.cordis.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * cordis.yml 条目树(design §2 EntryTree):本地 {@code plugins[]} + {@code include} 子文件,
 * 先 include 后本地(同文件内条目按声明顺序),递归解析防循环。
 *
 * <p>展平语义:include 子树的条目先入,本文件条目后入;同名条目后者覆盖前者(替换/组合语义,
 * 本地可压过 include 的声明)。覆盖只发生在展平,不修改原始文件。
 */
public final class EntryTree {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path source;                // 本节点 yml 文件(绝对)
    private final List<Entry> plugins;        // 本文件声明的条目
    private final List<EntryTree> includes;   // include 子节点(声明顺序)

    private EntryTree(Path source, List<Entry> plugins, List<EntryTree> includes) {
        this.source = source;
        this.plugins = List.copyOf(plugins);
        this.includes = List.copyOf(includes);
    }

    /** 解析一个 cordis.yml(含 include 递归);include 循环抛 {@link IllegalStateException}。 */
    public static EntryTree parse(Path yml) throws IOException {
        return parse(yml, new LinkedHashSet<>());
    }

    private static EntryTree parse(Path yml, Set<Path> visiting) throws IOException {
        Path abs = yml.toAbsolutePath().normalize();
        if (!visiting.add(abs)) {
            throw new IllegalStateException("cordis.yml include cycle detected: " + abs);
        }
        try {
            JsonNode root = YAML.readTree(abs.toFile());
            Path base = abs.getParent();
            if (base == null) base = Path.of(".");

            List<Entry> plugins = new ArrayList<>();
            List<String> includes = new ArrayList<>();
            if (root != null && !root.isMissingNode() && !root.isNull()) {
                if (root.isArray()) {
                    // dsh 顶层数组写法(每项一条插件)
                    for (JsonNode n : root) if (n.isObject()) plugins.add(Entry.parse(n));
                } else {
                    JsonNode ps = root.path("plugins");
                    if (ps.isArray()) {
                        for (JsonNode n : ps) if (n.isObject()) plugins.add(Entry.parse(n));
                    }
                    JsonNode inc = root.path("include");
                    if (inc.isArray()) {
                        for (JsonNode n : inc) if (n.isTextual()) includes.add(n.asText());
                    }
                }
            }

            List<EntryTree> children = new ArrayList<>();
            for (String inc : includes) {
                Path incPath = base.resolve(inc).normalize();
                if (!Files.isRegularFile(incPath)) {
                    throw new IOException("include not found: " + inc + " (from " + abs + ")");
                }
                children.add(parse(incPath, visiting));
            }
            return new EntryTree(abs, plugins, children);
        } finally {
            visiting.remove(abs);
        }
    }

    /** 展平为插件条目序列:include 子树先(声明顺序),本文件条目后;同名后者覆盖前者。 */
    public List<Entry> flatten() {
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (EntryTree inc : includes) {
            for (Entry e : inc.flatten()) byName.put(e.name(), e);
        }
        for (Entry e : plugins) byName.put(e.name(), e);
        return new ArrayList<>(byName.values());
    }

    /** 本树涉及的全部 yml 文件(本文件 + 所有 include,含嵌套)——热更新监听面。 */
    public List<Path> sources() {
        List<Path> out = new ArrayList<>();
        out.add(source);
        for (EntryTree inc : includes) out.addAll(inc.sources());
        return out;
    }
}
