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
    void missingTargetThrows() {
        HostSelector sel = new HostSelector();
        assertThatThrownBy(() -> sel.select(new Entry("p", null, null, null), tmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither source nor path");
    }
}
