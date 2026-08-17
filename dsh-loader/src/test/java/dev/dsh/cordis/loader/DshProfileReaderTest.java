package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.HostKind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-6 DshProfileReader:消费 dsh profile({@code $DSH_HOME/profiles/<name>})的
 * {@code dsh.profile.bundles} + 各 bundle 的 {@code dsh.bundle.patch} → 组合 patch 层
 * (insert/id/config/disabled 语义)→ 我们的 {@link Entry} 列表。
 *
 * <p>镜像 dsh {@code @deepseek-ai/dsh-app-boot/profile.ts}:两 anchor bundle 解析
 * (install 优先、profile 其次,node_modules 上行查找)、patch 组合 last-write-wins、
 * 插入即索引、disabled 未启用、config 透传。
 */
class DshProfileReaderTest {

    @TempDir
    Path tmp;

    private Path home() {
        return tmp.resolve("home");
    }

    private Path installAnchor() {
        return home().resolve("vendor/dsh/package.json");
    }

    /** 写一个 bundle 包(node_modules 布局),返回包目录。 */
    private Path writeBundle(Path nodeModules, String name, String patch) throws Exception {
        Path pkg = nodeModules.resolve(name);
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("package.json"),
                "{\"name\":\"" + name + "\",\"version\":\"0.0.1\","
                        + "\"dsh\":{\"bundle\":{\"patch\":\"./cordis.patch.yml\"}}}");
        Files.writeString(pkg.resolve("cordis.patch.yml"), patch);
        return pkg;
    }

    /** 建 dsh 安装锚点(vendor/dsh/package.json),使 install 成为有效第一 anchor。 */
    private void writeInstall() throws Exception {
        Files.createDirectories(installAnchor().getParent());
        Files.writeString(installAnchor(), "{\"name\":\"dsh\",\"version\":\"0.0.0\"}");
    }

    private void writeProfile(Path profileDir, String bundlesJson, String userPatch) throws Exception {
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("package.json"),
                "{\"name\":\"dsh-profile-test\",\"private\":true,\"dsh\":{\"profile\":{\"bundles\":"
                        + bundlesJson + "}}}");
        if (userPatch != null) {
            Files.writeString(profileDir.resolve("cordis.patch.yml"), userPatch);
        }
    }

    // ---- profile 目录解析 ----

    @Test
    void resolveProfileDirUnderHome() {
        assertThat(DshProfileReader.resolveProfileDir(home(), "web"))
                .isEqualTo(home().resolve("profiles").resolve("web").toAbsolutePath().normalize());
    }

    @Test
    void resolveProfileDirRejectsPathTraversal() {
        assertThatThrownBy(() -> DshProfileReader.resolveProfileDir(home(), "../x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DshProfileReader.resolveProfileDir(home(), "a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DshProfileReader.resolveProfileDir(home(), "node_modules"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isDshProfileOnlyWhenBundlesDeclared() throws Exception {
        Path dir = home().resolve("profiles").resolve("x");
        writeProfile(dir, "[]", null);
        assertThat(DshProfileReader.isDshProfile(dir)).isTrue();
        // 无 package.json → 不是 dsh profile(落到 cordis.yml 路径)
        Files.delete(dir.resolve("package.json"));
        assertThat(DshProfileReader.isDshProfile(dir)).isFalse();
    }

    // ---- bundle 解析(两 anchor)----

    @Test
    void bundleResolvesFromInstallAnchorFirst() throws Exception {
        writeInstall();
        writeBundle(home().resolve("vendor/dsh/node_modules"), "@test/bundle-a", "- insert: []\n");
        // 同名单包同时存在于 profile node_modules → install 优先
        writeBundle(home().resolve("profiles").resolve("web/node_modules"), "@test/bundle-a", "- insert: []\n");

        Path dir = DshProfileReader.resolveBundleDir(installAnchor(), home().resolve("profiles/web"), "@test/bundle-a");
        assertThat(dir).isEqualTo(home().resolve("vendor/dsh/node_modules/@test/bundle-a").toAbsolutePath().normalize());
    }

    @Test
    void bundleResolvesFromProfileAnchorWhenInstallMissing() throws Exception {
        // install anchor 文件不存在(vendor/dsh 未建)→ 落到 profile node_modules(第二 anchor)
        writeProfile(home().resolve("profiles/web"), "[]", null);
        writeBundle(home().resolve("profiles/web/node_modules"), "@test/bundle-a", "- insert: []\n");
        Path dir = DshProfileReader.resolveBundleDir(installAnchor(), home().resolve("profiles/web"), "@test/bundle-a");
        assertThat(dir).isEqualTo(home().resolve("profiles/web/node_modules/@test/bundle-a").toAbsolutePath().normalize());
    }

    @Test
    void bundleResolveFailsWhenUnresolvable() throws Exception {
        writeBundle(home().resolve("profiles/web/node_modules"), "@test/bundle-a", "- insert: []\n");
        // install anchor 缺失 + profile 无该包 → 报错
        assertThatThrownBy(() -> DshProfileReader.resolveBundleDir(installAnchor(),
                home().resolve("profiles/web"), "@test/absent"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("cannot resolve profile bundle")
                .hasMessageContaining("@test/absent");
    }

    // ---- 完整读取 + 组合(核心)----

    @Test
    void composeOrderedBundlesAndUserLayer() throws Exception {
        // bundle-a 从 install anchor 解析(第一 anchor)
        writeInstall();
        writeBundle(home().resolve("vendor/dsh/node_modules"), "@test/bundle-a", """
                - insert:
                    - id: plugin-a
                      name: '@test/plugin-a'
                      config:
                        alpha: 1
                    - id: plugin-b
                      name: '@test/plugin-b'
                      config:
                        beta: 2
                """);
        // bundle-b 从 profile node_modules 解析(第二 anchor)
        writeBundle(home().resolve("profiles/test/node_modules"), "@test/bundle-b", """
                # 定向覆盖现有行(id 定向,整 config 替换,last-write-wins)
                - id: plugin-b
                  config:
                    beta: 20
                    extra: true
                # 插入新行;同层后续 patch 可定向刚插入的行
                - insert:
                    - id: plugin-c
                      name: '@test/plugin-c'
                      config:
                        gamma: 3
                - id: plugin-c
                  disabled: true
                """);
        // 用户层(bundle 之后应用):禁用 bundle 的行 + 加自己的行
        writeProfile(home().resolve("profiles/test"), "[\"@test/bundle-a\",\"@test/bundle-b\"]", """
                - id: plugin-a
                  disabled: true
                - insert:
                    - id: plugin-d
                      name: '@test/plugin-d'
                      config:
                        delta: 4
                """);

        List<Entry> entries = new DshProfileReader().load(home().resolve("profiles/test"), installAnchor());

        // plugin-a 被用户禁用 → 未启用;plugin-c 在 bundle 内被禁用 → 未启用。
        assertThat(entries).extracting(Entry::name).containsExactly("plugin-b", "plugin-d");

        Entry b = entries.get(0);
        assertThat(b.source()).isEqualTo("@test/plugin-b");
        assertThat(b.config().path("beta").asInt()).isEqualTo(20);
        assertThat(b.config().path("extra").asBoolean()).isTrue();

        Entry d = entries.get(1);
        assertThat(d.source()).isEqualTo("@test/plugin-d");
        assertThat(d.config().path("delta").asInt()).isEqualTo(4);

        // compose 是纯的(镜像 structuredClone):重复 load 结果一致,不因前次组合被污染
        List<Entry> again = new DshProfileReader().load(home().resolve("profiles/test"), installAnchor());
        assertThat(again).containsExactlyElementsOf(entries);
    }

    @Test
    void configJsExpressionPassedThroughAsString() throws Exception {
        writeInstall();
        writeBundle(home().resolve("vendor/dsh/node_modules"), "@test/bundle-js", """
                - insert:
                    - id: storage-json
                      name: '@test/storage-json'
                      config:
                        root: !!js dshHomePath('storages')
                """);
        writeProfile(home().resolve("profiles/test"), "[\"@test/bundle-js\"]", null);

        List<Entry> entries = new DshProfileReader().load(home().resolve("profiles/test"), installAnchor());
        assertThat(entries).hasSize(1);
        // !!js 标量被解析为普通字符串原样透传(求值属运行时职责)
        assertThat(entries.get(0).config().path("root").asText()).isEqualTo("dshHomePath('storages')");
    }

    @Test
    void jsDisabledExpressionRowsSkippedConservatively() throws Exception {
        writeInstall();
        writeBundle(home().resolve("vendor/dsh/node_modules"), "@test/bundle-dis", """
                - insert:
                    - id: tool-bash
                      name: '@test/tool-bash'
                      disabled: !!js process.platform === 'win32'
                    - id: enabled-row
                      name: '@test/enabled-row'
                """);
        writeProfile(home().resolve("profiles/test"), "[\"@test/bundle-dis\"]", null);

        List<Entry> entries = new DshProfileReader().load(home().resolve("profiles/test"), installAnchor());
        // disabled 表达式无法在 Java 侧求值 → 保守跳过(避免误启本应关闭的插件)
        assertThat(entries).extracting(Entry::name).containsExactly("enabled-row");
    }

    @Test
    void literalDisabledRowInInsertSkipped() throws Exception {
        writeInstall();
        writeBundle(home().resolve("vendor/dsh/node_modules"), "@test/bundle-lit", """
                - insert:
                    - id: disabled-row
                      name: '@test/disabled-row'
                      disabled: true
                    - id: enabled-row
                      name: '@test/enabled-row'
                """);
        writeProfile(home().resolve("profiles/test"), "[\"@test/bundle-lit\"]", null);
        assertThat(new DshProfileReader().load(home().resolve("profiles/test"), installAnchor()))
                .extracting(Entry::name).containsExactly("enabled-row");
    }

    @Test
    void profileWithoutBundlesComposesUserLayerOnly() throws Exception {
        writeProfile(home().resolve("profiles/test"), "[]", """
                - insert:
                    - id: solo
                      name: './greeter.js'
                """);
        List<Entry> entries = new DshProfileReader().load(home().resolve("profiles/test"), installAnchor());
        assertThat(entries).extracting(Entry::name).containsExactly("solo");
        assertThat(entries.get(0).source()).isEqualTo("./greeter.js");
    }

    @Test
    void bundleDeclaresNoPatchFailsLoud() throws Exception {
        writeInstall();
        Path pkg = home().resolve("vendor/dsh/node_modules/@test/nopatch");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("package.json"),
                "{\"name\":\"@test/nopatch\",\"version\":\"0.0.1\",\"dsh\":{\"profile\":{\"bundles\":[]}}}");
        writeProfile(home().resolve("profiles/test"), "[\"@test/nopatch\"]", null);

        assertThatThrownBy(() -> new DshProfileReader().load(home().resolve("profiles/test"), installAnchor()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("declares no dsh.bundle");
    }

    @Test
    void missingProfileManifestFailsLoud() throws Exception {
        Path dir = home().resolve("profiles/test");
        Files.createDirectories(dir);
        assertThatThrownBy(() -> new DshProfileReader().load(dir, installAnchor()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("manifest not found");
    }

    @Test
    void nonArrayPatchFileFailsLoud() throws Exception {
        writeInstall();
        writeBundle(home().resolve("vendor/dsh/node_modules"), "@test/badpatch", "id: not-a-list\n");
        writeProfile(home().resolve("profiles/test"), "[\"@test/badpatch\"]", null);
        assertThatThrownBy(() -> new DshProfileReader().load(home().resolve("profiles/test"), installAnchor()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("top-level YAML array");
    }

    // ---- 组合 + loader 消费(loadEntries 端到端)----

    @Test
    void loadEntriesConsumesComposedProfile() throws Exception {
        // 真实可解析的 bundle:行指向 profile 目录内的本地 JS 文件
        writeBundle(home().resolve("profiles/test/node_modules"), "@test/greeter-bundle", """
                - insert:
                    - id: greeter
                      name: './greeter.js'
                      config:
                        greeting: hello
                """);
        writeProfile(home().resolve("profiles/test"), "[\"@test/greeter-bundle\"]", null);
        Files.writeString(home().resolve("profiles/test/greeter.js"), """
                module.exports = { name: 'greeter', apply(ctx, config) { ctx.emit('greet-cfg', config) } }
                """);

        List<Entry> entries = new DshProfileReader().load(home().resolve("profiles/test"), installAnchor());
        assertThat(entries).hasSize(1);

        Context root = new Context();
        java.util.concurrent.atomic.AtomicReference<Object> got = new java.util.concurrent.atomic.AtomicReference<>();
        root.on("greet-cfg", (c, args) -> { got.set(args[0]); return null; });
        try (PluginLoaderService loader = new PluginLoaderService(root)) {
            List<LoadedPlugin> loaded = loader.loadEntries(entries, home().resolve("profiles/test"));
            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.GRAAL);
            // config 透传到插件 apply(Graal 桥把它转成 JS 对象;此处断言字段到达)
            assertThat(got.get()).isNotNull();
            assertThat(String.valueOf(got.get())).contains("hello");
        } finally {
            root.fiber.dispose().join();
        }
    }

    // ---- 真实 dsh bundle(集成,assumption 守卫)----

    @Test
    void composesRealDshBaseBundle() throws Exception {
        // Gradle test 的 user.dir = 模块目录(dsh-loader);仓库根的 vendor 在其上级。
        Path realPatch = Path.of(System.getProperty("user.dir"), "..", "vendor", "dsh",
                "packages", "bundle", "base", "cordis.patch.yml").normalize();
        if (!Files.isRegularFile(realPatch)) {
            realPatch = Path.of("vendor/dsh/packages/bundle/base/cordis.patch.yml");
        }
        Assumptions.assumeTrue(Files.isRegularFile(realPatch), "vendor/dsh bundle source not present (setup not run)");

        writeInstall();
        // 把真实 base bundle 的 patch 装进一个可解析的 bundle 包(install anchor)
        Path pkg = home().resolve("vendor/dsh/node_modules/@deepseek-ai/dsh-base");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("package.json"),
                "{\"name\":\"@deepseek-ai/dsh-base\",\"version\":\"0.0.0\","
                        + "\"dsh\":{\"bundle\":{\"patch\":\"./cordis.patch.yml\"}}}");
        Files.copy(realPatch, pkg.resolve("cordis.patch.yml"));
        writeProfile(home().resolve("profiles/test"), "[\"@deepseek-ai/dsh-base\"]", null);

        List<Entry> entries = new DshProfileReader().load(home().resolve("profiles/test"), installAnchor());
        assertThat(entries).isNotEmpty();

        Map<String, Entry> byId = entries.stream().collect(Collectors.toMap(Entry::name, e -> e));
        // 核心行都在
        assertThat(byId).containsKeys("timer", "hmr", "llm", "session", "agent", "web", "settings", "tools");
        // 配置透传为字符串(!!js 表达式原样)
        assertThat(byId.get("session-persistence-jsonl").config().path("root").asText())
                .isEqualTo("dshHomePath('sessions')");
        // 带 disabled 表达式(!!js)的行被保守跳过
        assertThat(byId).doesNotContainKey("tool-bash");
        assertThat(byId).doesNotContainKey("tool-pwsh");
        // config 结构透传(base 行 hmr 带 config.root = ['.'])
        assertThat(byId.get("hmr").config().path("root").get(0).asText()).isEqualTo(".");
    }
}
