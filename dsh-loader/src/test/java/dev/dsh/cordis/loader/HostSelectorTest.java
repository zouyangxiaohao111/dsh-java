package dev.dsh.cordis.loader;

import dev.dsh.cordis.js.HostKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M5 §2 HostSelector:java:/node:/graaljs: 前缀,或无前缀走 PluginRuntimeResolver。 */
class HostSelectorTest {

    @TempDir
    Path tmp;

    @Test
    void javaPrefixYieldsClassTarget() {
        HostSelector sel = new HostSelector();
        Entry e = new Entry("counter", "java:dev.dsh.demo.CounterPlugin", null, null);
        ResolvedEntry re = sel.select(e, tmp);
        assertThat(re.kind()).isEqualTo(HostKind.JAVA);
        assertThat(re.explicit()).isTrue();
        assertThat(re.ref()).isEqualTo("dev.dsh.demo.CounterPlugin");
        assertThat(re.abs()).isNull();          // 类名不落盘
    }

    @Test
    void javaPrefixWithSourceFileYieldsAbsPath() throws Exception {
        Path java = tmp.resolve("P.java");
        Files.writeString(java, "class P {}");
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("p", "java:./P.java", null, null), tmp);
        assertThat(re.kind()).isEqualTo(HostKind.JAVA);
        assertThat(re.ref()).isEqualTo("./P.java");
        assertThat(re.abs()).isEqualTo(java.toAbsolutePath().normalize());
    }

    @Test
    void graaljsPrefixYieldsGraalPath() {
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("greeter", "graaljs:./plugins/greeter", null, null), tmp);
        assertThat(re.kind()).isEqualTo(HostKind.GRAAL);
        assertThat(re.explicit()).isTrue();
        assertThat(re.ref()).isEqualTo("./plugins/greeter");
        assertThat(re.abs()).isEqualTo(tmp.resolve("./plugins/greeter").normalize());
    }

    @Test
    void nodePrefixYieldsNodePath() {
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("session", "node:./plugins/session", null, null), tmp);
        assertThat(re.kind()).isEqualTo(HostKind.NODE);
        assertThat(re.explicit()).isTrue();
        assertThat(re.abs()).isEqualTo(tmp.resolve("./plugins/session").normalize());
    }

    @Test
    void pathWithoutPrefixAutoDetectsGraalForCjs() throws Exception {
        Path js = tmp.resolve("p.cjs");
        Files.writeString(js, "module.exports = { apply(ctx) {} }");
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("p", null, "./p.cjs", null), tmp);
        assertThat(re.explicit()).isFalse();
        assertThat(re.kind()).isEqualTo(HostKind.GRAAL);
        assertThat(re.abs()).isEqualTo(js.toAbsolutePath().normalize());
    }

    @Test
    void npmSpecifierResolvesThroughNodeModulesWalk() throws Exception {
        // M6-6 dsh profile 组合行的 npm 模块说明符:baseDir 字面量不存在 → node_modules 上行查找
        Path pkg = tmp.resolve("app/node_modules/@deepseek-ai/dsh-llm");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("package.json"), "{\"name\":\"@deepseek-ai/dsh-llm\",\"main\":\"index.js\"}");
        Files.writeString(pkg.resolve("index.js"), "module.exports = { name: 'llm', apply(ctx) {} }");
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("llm", "@deepseek-ai/dsh-llm", null, null),
                tmp.resolve("app"));
        assertThat(re.explicit()).isFalse();
        assertThat(re.kind()).isEqualTo(HostKind.GRAAL);   // 纯 JS 包 → Graal
        assertThat(re.abs()).isEqualTo(pkg.toAbsolutePath().normalize());
    }

    @Test
    void npmSpecifierWithExportMapSubpathResolvesTargetFile() throws Exception {
        // M7-6:exports 子路径说明符(pkg/subpath)→ 经包 package.json exports map 解析到实际文件
        // (dsh profile 组合行的 @deepseek-ai/dsh-tool-subagent-control/list-agents 即此形状)
        Path pkg = tmp.resolve("app/node_modules/@deepseek-ai/dsh-tool-subagent-control");
        Files.createDirectories(pkg.resolve("lib/types"));
        Files.writeString(pkg.resolve("package.json"),
                "{\"name\":\"@deepseek-ai/dsh-tool-subagent-control\",\"type\":\"module\",\"main\":\"lib/index.js\","
                + "\"exports\":{\".\":{\"types\":\"./lib/types/index.d.ts\",\"default\":\"./lib/index.js\"},"
                + "\"./list-agents\":{\"types\":\"./lib/types/list-agents.d.ts\",\"default\":\"./lib/types/list-agents.js\"}}}");
        Files.writeString(pkg.resolve("lib/types/list-agents.js"), "export const name = 'list-agents';\n");
        Path base = tmp.resolve("app");
        Path resolved = HostSelector.resolveFromNodeModules(base,
                "@deepseek-ai/dsh-tool-subagent-control/list-agents");
        assertThat(resolved).isEqualTo(pkg.resolve("lib/types/list-agents.js").toAbsolutePath().normalize());
    }

    @Test
    void npmSpecifierWithExportMapSubpathSelectsResolvedFile() throws Exception {
        // 端到端 select:子路径说明符 → abs 落到 exports 解析出的文件(Node ESM 包)
        Path pkg = tmp.resolve("app/node_modules/@deepseek-ai/dsh-tool-subagent-control");
        Files.createDirectories(pkg.resolve("lib/types"));
        Files.writeString(pkg.resolve("package.json"),
                "{\"name\":\"@deepseek-ai/dsh-tool-subagent-control\",\"type\":\"module\",\"main\":\"lib/index.js\","
                + "\"exports\":{\".\":{\"types\":\"./lib/types/index.d.ts\",\"default\":\"./lib/index.js\"},"
                + "\"./list-agents\":{\"types\":\"./lib/types/list-agents.d.ts\",\"default\":\"./lib/types/list-agents.js\"}}}");
        Files.writeString(pkg.resolve("lib/types/list-agents.js"), "export const name = 'list-agents';\n");
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("list-agents",
                "@deepseek-ai/dsh-tool-subagent-control/list-agents", null, null), tmp.resolve("app"));
        assertThat(re.explicit()).isFalse();
        assertThat(re.kind()).isEqualTo(HostKind.NODE);   // type:module ESM → 真 Node
        assertThat(re.abs()).isEqualTo(pkg.resolve("lib/types/list-agents.js").toAbsolutePath().normalize());
    }

    @Test
    void barePackageRootStillResolvesWithoutSubpath() throws Exception {
        // 回归:无子路径的包根说明符行为不变
        Path pkg = tmp.resolve("app/node_modules/@deepseek-ai/dsh-llm");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("package.json"), "{\"name\":\"@deepseek-ai/dsh-llm\",\"main\":\"index.js\"}");
        Files.writeString(pkg.resolve("index.js"), "module.exports = { name: 'llm', apply(ctx) {} }");
        Path base = tmp.resolve("app");
        assertThat(HostSelector.resolveFromNodeModules(base, "@deepseek-ai/dsh-llm"))
                .isEqualTo(pkg.toAbsolutePath().normalize());
    }

    @Test
    void npmSpecifierWithoutInstallKeepsLiteralPath() {
        // 找不到时保持字面路径(加载期报清晰错误),不静默改判
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("llm", "@deepseek-ai/absent", null, null), tmp);
        assertThat(re.abs()).isEqualTo(tmp.resolve("@deepseek-ai/absent").toAbsolutePath().normalize());
    }

    @Test
    void sourceWithoutPrefixAutoDetectsNodeForEsm() throws Exception {
        Path js = tmp.resolve("esm.mjs");
        Files.writeString(js, "export function apply(ctx) {}");
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("p", "./esm.mjs", null, null), tmp);
        assertThat(re.explicit()).isFalse();
        assertThat(re.kind()).isEqualTo(HostKind.NODE);   // ESM → 真 Node
    }

    @Test
    void pathWithoutPrefixAutoDetectsJavaForClassFile() throws Exception {
        Path cls = tmp.resolve("P.class");
        Files.write(cls, new byte[0]);
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("p", null, "./P.class", null), tmp);
        assertThat(re.explicit()).isFalse();
        assertThat(re.kind()).isEqualTo(HostKind.JAVA);
    }

    @Test
    void explicitHostFieldUsedWhenNoPrefix() throws Exception {
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("p", null, "./p.cjs", HostKind.NODE), tmp);
        assertThat(re.kind()).isEqualTo(HostKind.NODE);
        assertThat(re.explicit()).isTrue();
    }

    @Test
    void sourcePrefixBeatsExplicitHostField() {
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("p", "graaljs:./g", null, HostKind.NODE), tmp);
        assertThat(re.kind()).isEqualTo(HostKind.GRAAL);   // 前缀优先
    }

    @Test
    void resolveJsEntryPicksPackageMain() throws Exception {
        Path dir = tmp.resolve("pkg");
        Files.createDirectories(dir.resolve("lib"));
        Files.writeString(dir.resolve("package.json"), "{\"main\":\"lib/entry.js\"}");
        Files.writeString(dir.resolve("lib/entry.js"), "module.exports = {}");
        assertThat(HostSelector.resolveJsEntry(dir)).isEqualTo(dir.resolve("lib/entry.js"));
    }

    @Test
    void resolveJsEntryFallsBackToIndex() throws Exception {
        Path dir = tmp.resolve("plain");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.js"), "module.exports = {}");
        assertThat(HostSelector.resolveJsEntry(dir)).isEqualTo(dir.resolve("index.js"));
    }

    @Test
    void jarPrefixYieldsJavaJarTarget() throws Exception {
        Path jar = tmp.resolve("plugins").resolve("x.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[0]);
        HostSelector sel = new HostSelector();
        ResolvedEntry re = sel.select(new Entry("p", "jar:./plugins/x.jar", null, null), tmp);
        assertThat(re.kind()).isEqualTo(HostKind.JAVA);
        assertThat(re.explicit()).isTrue();
        assertThat(re.ref()).isEqualTo("./plugins/x.jar");
        assertThat(re.abs()).isEqualTo(jar.toAbsolutePath().normalize());
    }

    @Test
    void mainClassParsedFromYmlNode() throws Exception {
        // Entry.parse 透传 mainClass(m6-design §5.2 jar 插件显式入口)
        var yaml = new com.fasterxml.jackson.dataformat.yaml.YAMLFactory();
        var node = new com.fasterxml.jackson.databind.ObjectMapper(yaml)
                .readTree("name: p\nsource: jar:./plugins/x.jar\nmainClass: com.example.ExternalPlugin");
        Entry e = Entry.parse(node);
        assertThat(e.mainClass()).isEqualTo("com.example.ExternalPlugin");
        assertThat(e.ref()).isEqualTo("jar:./plugins/x.jar");
    }

    @Test
    void missingTargetThrows() {
        HostSelector sel = new HostSelector();
        assertThatThrownBy(() -> sel.select(new Entry("p", null, null, null), tmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither source nor path");
    }
}
