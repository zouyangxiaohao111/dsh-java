package dev.dsh.host.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.dsh.cordis.loader.DshProfileReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * profile 组合配置写回(M6-7):{@code dshj plugin add} 把一条 Java 插件条目写进 profile,
 * 位置与读取方一致 —— 有 {@code cordis.yml} 时写 {@code plugins[]}(Java harness 树,
 * 经 {@code PluginLoaderService.load} 读);是 dsh profile({@code package.json} 声明
 * {@code dsh.profile.bundles})且无 {@code cordis.yml} 时写 {@code cordis.patch.yml}
 * (用户 patch 层,经 {@link DshProfileReader} 组合读);两者皆无 → 新建 {@code cordis.yml}
 * (Java 侧安装的家)。
 *
 * <p>写 cordis.yml 条目:
 * <pre>{@code
 * plugins:
 *   - name: <name>
 *     source: jar:./plugins/jars/x.jar     # 或 java:./plugins/src/...
 *     mainClass: <可选>
 * }</pre>
 * 写 cordis.patch.yml 补丁(镜像 dsh patch 的 {@code insert} 语义,entry 的 {@code name}
 * 字段承载 source 前缀,使组合后的 Entry.source 带 {@code jar:}/{@code java:}):
 * <pre>{@code
 * - insert:
 *     - id: <name>
 *       name: jar:./plugins/jars/x.jar
 * }</pre>
 *
 * <p>两者都追加(保留既有条目/补丁与其它顶层键);Jackson 重写会丢弃注释,语义不变。
 */
public final class ProfileConfigWriter {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 一条要写入的 Java 插件声明。 */
    public record Entry(String name, String source, String mainClass) {
        public Entry(String name, String source) {
            this(name, source, null);
        }
    }

    /** 选择写目标:cordis.yml(cordis.yml 存在 / 非 dsh profile)否则 cordis.patch.yml。 */
    public static Path targetFile(Path profileDir) {
        Path yml = profileDir.resolve("cordis.yml");
        if (Files.isRegularFile(yml)) return yml;
        if (DshProfileReader.isDshProfile(profileDir)) {
            return profileDir.resolve(DshProfileReader.PROFILE_PATCH_FILENAME);
        }
        return yml;
    }

    /**
     * 追加一条 Java 插件条目到 profile(按 {@link #targetFile} 选目标文件,不存在则创建,
     * 目录不存在则一并创建)。返回实际写到的文件。
     */
    public Path append(Path profileDir, Entry entry) throws IOException {
        Path target = targetFile(profileDir);
        Files.createDirectories(profileDir);
        boolean patch = target.getFileName().toString().equals(DshProfileReader.PROFILE_PATCH_FILENAME);
        JsonNode existing = Files.isRegularFile(target) ? YAML.readTree(target.toFile()) : null;
        if (patch) {
            writePatch(target, existing, entry);
        } else {
            writeCordisYml(target, existing, entry);
        }
        return target;
    }

    /** cordis.yml:根对象(或新建)的 {@code plugins[]} 追加一条。 */
    private static void writeCordisYml(Path target, JsonNode root, Entry entry) throws IOException {
        ObjectNode doc;
        if (root != null && root.isObject()) {
            doc = (ObjectNode) root;
        } else {
            doc = YAML.createObjectNode();
        }
        ArrayNode plugins = doc.hasNonNull("plugins") && doc.get("plugins").isArray()
                ? (ArrayNode) doc.get("plugins")
                : doc.putArray("plugins");
        ObjectNode node = plugins.addObject();
        node.put("name", entry.name());
        node.put("source", entry.source());
        if (entry.mainClass() != null && !entry.mainClass().isBlank()) {
            node.put("mainClass", entry.mainClass());
        }
        YAML.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), doc);
    }

    /** cordis.patch.yml:顶层数组追加一个 {@code insert} 补丁。 */
    private static void writePatch(Path target, JsonNode root, Entry entry) throws IOException {
        ArrayNode doc;
        if (root != null && root.isArray()) {
            doc = (ArrayNode) root;
        } else {
            doc = YAML.createArrayNode();
        }
        ObjectNode patch = doc.addObject();
        ArrayNode insert = patch.putArray("insert");
        ObjectNode row = insert.addObject();
        row.put("id", entry.name());
        row.put("name", entry.source());
        YAML.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), doc);
    }
}
