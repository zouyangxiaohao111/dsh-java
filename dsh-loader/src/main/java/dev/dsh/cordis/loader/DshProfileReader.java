package dev.dsh.cordis.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * dsh profile 读取与 patch 组合(M6-6,镜像 {@code @deepseek-ai/dsh-app-boot/profile.ts})。
 *
 * <p>消费 "dsh plugin add" 装好的产物,不复刻 dsh plugin add 本身:<b>dsh profile</b> =
 * {@code $DSH_HOME/profiles/<name>}(默认 {@code ~/.dsh/profiles/<name>}),目录里:
 * <ul>
 *   <li>{@code package.json} — 声明 {@code dsh.profile.bundles} = 有序 bundle 包名列表;</li>
 *   <li>{@code cordis.patch.yml} — 用户自己的 patch 层(在全部 bundle 层之后应用)。</li>
 * </ul>
 *
 * <p>每个 <b>bundle</b> 是一个 npm 包,其 {@code package.json} 声明 {@code dsh.bundle.patch}
 * 指向该包根下的 {@code cordis.patch.yml}。包目录按两 anchor 解析(镜像 dsh
 * {@code resolveBundleDir}):先 {@code installAnchor}(dsh 安装,即 vendor/dsh 的
 * package.json)再 profile 目录;每个 anchor 用 {@code createRequire.resolve.paths}
 * 等价的 node_modules 上行查找定位包目录。
 *
 * <p><b>patch 组合</b>(镜像 {@code applyEntryPatches}):把各 bundle 层 + 用户层的 patch
 * 展平成一个列表,依序应用 —— {@code insert}(无 id 追加到根;带 id 且目标为 group 时插入
 * group 的 config,我们的 loader 不建模 group → 记日志跳过)、{@code id} 定向的
 * {@code config}/{@code disabled} 覆盖(整 config 替换,last-write-wins;{@code name}
 * 不匹配记日志跳过)。插入的行被索引,同层后续 patch 可继续定向它们。
 *
 * <p><b>产出</b>:组合后的行为我们的 {@link Entry} 列表(供 {@link PluginLoaderService}
 * {@code loadEntries} 消费)。未启用行(disabled 为字面量真 / 非空字符串 / 无法求值的对象,
 * 保守跳过)与 group 容器行不产出;{@code config} 透传到 {@link Entry#config()}。
 *
 * <p><b>JS 表达式({@code !!js})</b>:patch 解析用 SnakeYAML 自定义构造器,把 {@code !!js}
 * 标量(如 {@code dshHomePath('sessions')})解析成显式标记对象
 * {@code {"$dshJs": "<expr>"}}(不裸传字符串,防 config 通道把它当普通文本)。config 里的
 * 标记原样透传到 worker,由 worker 侧在 apply 前求值(M7-5,node-bridge.js 的
 * {@code evalJsMarkers};scope = process + dshHomePath)。
 *
 * <p><b>disabled 通道(M7-6)</b>:{@code disabled: {$dshJs: expr}} 标记不再在 Java 侧保守跳过
 * —— 行保留并透传给 loader,由 loader 在加载前让宿主求值表达式(scope 同 config);
 * 求值为真 → 插件不加载,为假 → 正常加载;求值失败 → 保守按禁用处理(宁可少载,不误启
 * 本应关闭的插件)。标记对象经 {@link Entry#disabled()} 携带到 {@link PluginLoaderService}。
 */
public final class DshProfileReader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 显式标记对象里 !!js 表达式的键(不裸传字符串,防 config 通道把它当普通文本)。 */
    public static final String JS_EXPR_KEY = "$dshJs";

    /** SnakeYAML 的 {@code !!js} 显式 tag(!! 前缀展开为全局 tag)。 */
    private static final Tag JS_TAG = new Tag("tag:yaml.org,2002:js");

    /** !!js 表达式标记:Java 侧无法求值,标记后原样透传,worker 侧求值。 */
    private record JsExpression(String expr) {
    }

    /** SnakeYAML 构造器:!!js 标量 → JsExpression 标记;其余按安全默认解析。 */
    private static final class JsTagConstructor extends SafeConstructor {
        JsTagConstructor(LoaderOptions opts) {
            super(opts);
            this.yamlConstructors.put(JS_TAG, new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    return new JsExpression(((ScalarNode) node).getValue());
                }
            });
        }
    }

    /** dsh home 下的 profile 目录名。 */
    public static final String PROFILES_DIR = "profiles";

    /** profile 目录里的用户 patch 层文件名。 */
    public static final String PROFILE_PATCH_FILENAME = "cordis.patch.yml";

    /** 一个已解析的 bundle 层。 */
    public record Layer(String packageName, Path packageDir, Path patchPath, List<JsonNode> patches) {
    }

    /** 一个已加载的 profile:有序 bundle 层 + 用户自己的 patch 层。 */
    public record Profile(String name, Path dir, List<Layer> layers, Path patchPath, List<JsonNode> patches) {
    }

    /** 解析默认 dsh home:{@code $DSH_HOME}(非空)否则 {@code ~/.dsh}(镜像 dsh resolveDshHome)。 */
    public static Path defaultDshHome() {
        String home = System.getenv("DSH_HOME");
        if (home != null && !home.isBlank()) return Path.of(home);
        return Path.of(System.getProperty("user.home"), ".dsh");
    }

    /**
     * 解析一个 profile 目录:{@code <home>/profiles/<name>}(镜像 dsh resolveProfileDir)。
     * {@code node_modules}(dsh 保留的扁平回退目录)与路径穿越名视为非法。
     */
    public static Path resolveProfileDir(Path home, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..") || name.equals("node_modules")) {
            throw new IllegalArgumentException("dsh: invalid profile name '" + name + "'");
        }
        return home.toAbsolutePath().normalize().resolve(PROFILES_DIR).resolve(name);
    }

    /** profile 目录是否为 dsh profile:存在 package.json 且声明 {@code dsh.profile.bundles} 数组。 */
    public static boolean isDshProfile(Path profileDir) {
        Path manifest = profileDir.resolve("package.json");
        if (!Files.isRegularFile(manifest)) return false;
        try {
            JsonNode root = readManifest(manifest);
            return root.path("dsh").path("profile").path("bundles").isArray();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读一个 dsh profile:解析 manifest → 按 {@code dsh.profile.bundles} 顺序解析每个
     * bundle 的 patch 层 → 加用户自己的 {@code cordis.patch.yml} 层。
     *
     * @param profileDir     profile 目录(绝对)
     * @param installAnchor  dsh 安装的 package.json 路径(两 anchor 解析的第一个;文件不存在时跳过)
     * @return 加载的 profile
     * @throws IOException profile 缺失 / manifest 无效 / bundle 无法解析或未声明 dsh.bundle
     */
    public Profile read(Path profileDir, Path installAnchor) throws IOException {
        Path dir = profileDir.toAbsolutePath().normalize();
        Path manifestPath = dir.resolve("package.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("dsh profile manifest not found: " + manifestPath);
        }
        JsonNode manifest = readManifest(manifestPath);
        JsonNode bundles = manifest.path("dsh").path("profile").path("bundles");
        List<Layer> layers = new ArrayList<>();
        if (bundles.isArray()) {
            for (JsonNode b : bundles) {
                if (!b.isTextual() || b.asText().isBlank()) continue;
                String packageName = b.asText().trim();
                Path packageDir = resolveBundleDir(installAnchor, dir, packageName);
                JsonNode bundleManifest = readManifest(packageDir.resolve("package.json"));
                JsonNode declared = bundleManifest.path("dsh").path("bundle").path("patch");
                if (!declared.isTextual() || declared.asText().isBlank()) {
                    throw new IOException("profile bundle '" + packageName + "' declares no dsh.bundle.patch in "
                            + packageDir.resolve("package.json"));
                }
                Path patchPath = packageDir.resolve(declared.asText()).normalize();
                layers.add(new Layer(packageName, packageDir, patchPath, readPatchList(patchPath)));
            }
        }
        Path userPatch = dir.resolve(PROFILE_PATCH_FILENAME);
        List<JsonNode> userPatches = Files.isRegularFile(userPatch) ? readPatchList(userPatch) : List.of();
        return new Profile(dir.getFileName().toString(), dir, List.copyOf(layers), userPatch, userPatches);
    }

    /**
     * 解析一个 bundle 包目录:先 install anchor 后 profile 目录(node_modules 上行查找)。
     *
     * @param installAnchor dsh 安装的 package.json(文件不存在时跳过该 anchor)
     * @param profileDir    profile 目录(第二 anchor,其 package.json 必须存在)
     * @param packageName   bundle 包名(可含 scope,如 {@code @deepseek-ai/dsh-base})
     * @return 包目录
     * @throws IOException 两 anchor 都解析不到
     */
    public static Path resolveBundleDir(Path installAnchor, Path profileDir, String packageName) throws IOException {
        if (installAnchor != null && Files.isRegularFile(installAnchor)) {
            Path dir = packageDirFromAnchor(installAnchor, packageName);
            if (dir != null) return dir;
        }
        Path profileManifest = profileDir.toAbsolutePath().normalize().resolve("package.json");
        if (Files.isRegularFile(profileManifest)) {
            Path dir = packageDirFromAnchor(profileManifest, packageName);
            if (dir != null) return dir;
        }
        throw new IOException("cannot resolve profile bundle '" + packageName + "' from the dsh installation ("
                + (installAnchor != null ? installAnchor : "<none>") + ") or profile " + profileDir
                + "; run the dsh plugin installer if its dependency is not installed");
    }

    /** {@code createRequire(anchor).resolve.paths(packageName)} 等价:从 anchor 所在目录上行
     *  检查 {@code node_modules/<packageName>} 是否含 package.json;无则 null。 */
    static Path packageDirFromAnchor(Path anchorFile, String packageName) {
        Path dir = anchorFile.toAbsolutePath().getParent();
        while (dir != null) {
            Path candidate = dir.resolve("node_modules").resolve(packageName).normalize();
            if (Files.isRegularFile(candidate.resolve("package.json"))) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    /** 组合 profile 的全部 patch 层(镜像 {@code applyEntryPatches} 的展平单次调用)。 */
    public List<Entry> composeEntries(Profile profile) {
        List<JsonNode> patches = new ArrayList<>();
        for (Layer l : profile.layers()) patches.addAll(l.patches());
        patches.addAll(profile.patches());
        return compose(patches);
    }

    /** 一次读 + 组合:返回可被 {@link PluginLoaderService#loadEntries} 消费的 Entry 列表。 */
    public List<Entry> load(Path profileDir, Path installAnchor) throws IOException {
        return composeEntries(read(profileDir, installAnchor));
    }

    /**
     * 应用 patch 列表到空 entry 列表(applyEntryPatches 语义,含"插入即索引、后续 patch
     * 可定向刚插入的行")。产出经 disabled/group/name 过滤后的 {@link Entry} 列表。
     */
    List<Entry> compose(List<JsonNode> patches) {
        List<JsonNode> data = new ArrayList<>();
        Map<String, JsonNode> byId = new HashMap<>();
        // 镜像 applyEntryPatches 的 structuredClone(layers.flat()):compose 输入永不改动,
        // 同一 Profile 重复 compose 结果一致(patch 定向的行只属于本次组合)。
        List<JsonNode> work = new ArrayList<>(patches.size());
        for (JsonNode p : patches) work.add(p.deepCopy());

        for (JsonNode patch : work) {
            JsonNode insert = patch.get("insert");
            if (insert != null && insert.isArray()) {
                if (patch.hasNonNull("id")) {
                    String id = patch.path("id").asText();
                    JsonNode target = byId.get(id);
                    if (target == null) {
                        warn("patch insert: entry '" + id + "' not found");
                        continue;
                    }
                    if (!target.path("group").asBoolean(false)) {
                        warn("patch insert: entry '" + id + "' is not a group (groups not modeled by the loader)");
                        continue;
                    }
                    // group 的 config 是子行数组;就地追加 insert(target 与 data/byId 同引用)。
                    // 我们的 loader 不建模 group 容器行,此处仅保持 patch 语义完整。
                    ObjectNode targetObj = (ObjectNode) target;
                    JsonNode children = target.get("config");
                    ArrayNode arr = (children != null && children.isArray())
                            ? (ArrayNode) children
                            : targetObj.putArray("config");
                    for (JsonNode n : insert) arr.add(n);
                    index(byId, insert);
                } else {
                    for (JsonNode n : insert) data.add(n);
                    index(byId, insert);
                }
                continue;
            }
            String id = patch.path("id").asText(null);
            if (id == null || id.isBlank()) {
                warn("patch: id is required for non-insert patches");
                continue;
            }
            JsonNode target = byId.get(id);
            if (target == null) {
                warn("patch: entry '" + id + "' not found");
                continue;
            }
            if (patch.hasNonNull("name")) {
                String want = patch.path("name").asText();
                String have = target.path("name").asText(null);
                if (!want.equals(have)) {
                    warn("patch: name mismatch for '" + id + "' (expected '" + want + "', got '" + have + "'), skipping");
                    continue;
                }
            }
            ObjectNode targetObj = (ObjectNode) target;
            for (Iterator<Map.Entry<String, JsonNode>> it = patch.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                if (key.equals("id") || key.equals("insert")) continue;
                targetObj.set(key, e.getValue());
            }
        }

        List<Entry> out = new ArrayList<>();
        for (JsonNode row : data) {
            String id = row.path("id").asText(null);
            if (id == null || id.isBlank()) {
                warn("composed entry without id skipped");
                continue;
            }
            JsonNode disabled = row.get("disabled");
            if (isLiteralDisabled(disabled)) continue;         // 字面量禁用(布尔真/非空串/无法求值的对象)
            if (row.path("group").asBoolean(false)) continue;  // group 容器行无模块可加载
            String module = row.path("name").asText(null);
            if (module == null || module.isBlank()) continue;  // 无模块说明符
            JsonNode config = row.get("config");
            // disabled 为 {$dshJs: expr} 标记对象 → 不再保守跳过,经 Entry.disabled 透传给
            // loader,由 loader 让宿主求值(M7-6;scope = process + dshHomePath,同 config 通道)。
            JsonNode disabledMarker = isJsMarker(disabled) ? disabled : null;
            out.add(new Entry(id.trim(), module.trim(), null, null, null, config, disabledMarker));
        }
        return out;
    }

    /**
     * 字面量 disabled 判定:布尔真、非空字符串、或无法求值的对象(→ 禁用)。
     * {@code {$dshJs: expr}} 标记对象除外 —— 不在这里判定,交给 loader 在宿主求值(M7-6)。
     */
    private static boolean isLiteralDisabled(JsonNode d) {
        if (d == null || d.isNull() || d.isMissingNode()) return false;
        if (d.isBoolean()) return d.asBoolean();
        if (d.isTextual()) return !d.asText().trim().isEmpty();
        if (d.isObject()) return !isJsMarker(d);   // 标记对象交给 worker;其余对象无法求值 → 保守禁用
        return d.asBoolean(false);
    }

    /** 是否为 {@code {$dshJs: expr}} 标记对象(与 config 通道同形状,worker 侧 evalJsMarkers 同识别)。 */
    static boolean isJsMarker(JsonNode d) {
        return d != null && d.isObject() && d.size() == 1
                && d.hasNonNull(JS_EXPR_KEY) && d.path(JS_EXPR_KEY).isTextual();
    }

    /** 把 patch 列表解析为 JsonNode 数组;非顶层数组/非法行 → fail loud(镜像 dsh)。
     *  {@code !!js} 标量经 {@link JsTagConstructor} 标记为 {@code {"$dshJs": expr}}。 */
    private static List<JsonNode> readPatchList(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("dsh overlay not found: " + file);
        }
        JsonNode root = readPatchYaml(file);
        if (root == null || !root.isArray()) {
            throw new IOException("dsh overlay " + file + " must be a top-level YAML array of loader patch entries");
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : root) {
            if (!n.isObject()) {
                throw new IOException("dsh overlay " + file + " holds a non-mapping patch entry: " + n);
            }
            out.add(n);
        }
        return out;
    }

    /**
     * 经 SnakeYAML 读 patch YAML,转 Jackson JsonNode 树。
     * TagInspector 只放行 {@code !!js}(tag:yaml.org,2002:js)进自定义构造器;标准 tag
     * (str/int/bool/null/float/seq/map/…)由 SafeConstructor 原生解析,不受 TagInspector 约束;
     * 其余未知全局 tag 被 SnakeYAML 拒绝(fail loud,防任意 tag 注入构造器)。
     */
    private static JsonNode readPatchYaml(Path file) throws IOException {
        LoaderOptions opts = new LoaderOptions();
        opts.setTagInspector(tag -> tag.equals(JS_TAG));
        Yaml yaml = new Yaml(new JsTagConstructor(opts));
        Object root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = yaml.load(r);
        } catch (IOException e) {
            throw new IOException("failed to parse dsh overlay " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("failed to parse dsh overlay " + file + ": " + e.getMessage(), e);
        }
        return toJsonNode(root);
    }

    /** SnakeYAML 对象树 → Jackson JsonNode;{@link JsExpression} 标记 → {@code {$dshJs: expr}} 对象节点。 */
    private static JsonNode toJsonNode(Object value) {
        if (value == null) return NullNode.getInstance();
        if (value instanceof JsExpression je) {
            ObjectNode marker = YAML.createObjectNode();
            marker.put(JS_EXPR_KEY, je.expr());
            return marker;
        }
        if (value instanceof String s) return TextNode.valueOf(s);
        if (value instanceof Boolean b) return BooleanNode.valueOf(b);
        if (value instanceof Integer i) return IntNode.valueOf(i);
        if (value instanceof Long l) return LongNode.valueOf(l);
        if (value instanceof Short sh) return ShortNode.valueOf(sh.shortValue());
        if (value instanceof Byte by) return ShortNode.valueOf(by.shortValue());
        if (value instanceof BigInteger bi) return YAML.getNodeFactory().numberNode(bi);
        if (value instanceof BigDecimal bd) return YAML.getNodeFactory().numberNode(bd);
        if (value instanceof Double d) return DoubleNode.valueOf(d);
        if (value instanceof Float f) return DoubleNode.valueOf(f.doubleValue());
        if (value instanceof Map<?, ?> m) {
            ObjectNode o = YAML.createObjectNode();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                o.set(String.valueOf(e.getKey()), toJsonNode(e.getValue()));
            }
            return o;
        }
        if (value instanceof List<?> l) {
            ArrayNode a = YAML.createArrayNode();
            for (Object item : l) a.add(toJsonNode(item));
            return a;
        }
        // 其余(日期等)→ 字符串原样(与 Jackson YAML 的字符串化行为对齐)
        return TextNode.valueOf(String.valueOf(value));
    }

    private static JsonNode readManifest(Path file) throws IOException {
        try {
            return YAML.readTree(file.toFile());
        } catch (IOException e) {
            throw new IOException("failed to read " + file + ": " + e.getMessage(), e);
        }
    }

    /** 索引一组行(id → 行)。 */
    private static void index(Map<String, JsonNode> byId, Iterable<JsonNode> rows) {
        for (JsonNode row : rows) {
            String id = row.path("id").asText(null);
            if (id != null) byId.put(id, row);
        }
    }

    private static void warn(String message) {
        System.err.println("dshj: [DshProfileReader] " + message);
    }
}
