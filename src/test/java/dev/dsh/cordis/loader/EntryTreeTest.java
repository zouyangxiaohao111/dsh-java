package dev.dsh.cordis.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M5 §2 EntryTree:plugins[] / include 子集 / 防循环 / 展平覆盖语义。 */
class EntryTreeTest {

    @TempDir
    Path tmp;

    @Test
    void parsesPluginsWithSourceAndPath() throws Exception {
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: echo
                    path: node_modules/@koishijs/plugin-echo
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                  - name: greeter
                    source: graaljs:./plugins/greeter
                  - name: session
                    source: node:./plugins/session
                """);
        List<Entry> entries = EntryTree.parse(yml).flatten();
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).name()).isEqualTo("echo");
        assertThat(entries.get(0).path()).isEqualTo("node_modules/@koishijs/plugin-echo");
        assertThat(entries.get(1).source()).isEqualTo("java:dev.dsh.demo.CounterPlugin");
        assertThat(entries.get(2).source()).isEqualTo("graaljs:./plugins/greeter");
        assertThat(entries.get(3).source()).isEqualTo("node:./plugins/session");
    }

    @Test
    void includeFlattenedAndLocalOverridesInclude() throws Exception {
        Path base = tmp.resolve("base.yml");
        Files.writeString(base, """
                plugins:
                  - name: from-base
                    source: java:a.Base
                  - name: shared
                    source: java:old.Shared
                """);
        Path main = tmp.resolve("cordis.yml");
        Files.writeString(main, """
                plugins:
                  - name: local
                    source: java:c.Local
                  - name: shared
                    source: java:new.Shared
                include:
                  - ./base.yml
                """);
        List<Entry> entries = EntryTree.parse(main).flatten();
        // include 先入(from-base, shared),本地后入(local);同名 shared 本地覆盖、占 include 槽位
        assertThat(entries).extracting(Entry::name).containsExactly("from-base", "shared", "local");
        assertThat(entries.get(1).source()).isEqualTo("java:new.Shared");
    }

    @Test
    void includeCycleThrows() throws Exception {
        Path a = tmp.resolve("a.yml");
        Path b = tmp.resolve("b.yml");
        Files.writeString(a, "include:\n  - ./b.yml\n");
        Files.writeString(b, "include:\n  - ./a.yml\n");
        assertThatThrownBy(() -> EntryTree.parse(a)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void selfIncludeThrows() throws Exception {
        Path a = tmp.resolve("self.yml");
        Files.writeString(a, "include:\n  - ./self.yml\n");
        assertThatThrownBy(() -> EntryTree.parse(a)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void missingIncludeThrows() throws Exception {
        Path main = tmp.resolve("cordis.yml");
        Files.writeString(main, "include:\n  - ./nope.yml\n");
        assertThatThrownBy(() -> EntryTree.parse(main)).isInstanceOf(IOException.class)
                .hasMessageContaining("nope.yml");
    }

    @Test
    void dshTopLevelArraySupported() throws Exception {
        Path yml = tmp.resolve("agent.cordis.yml");
        Files.writeString(yml, """
                - id: skill-filesystem
                  disabled: false
                - id: tool-fs
                """);
        List<Entry> entries = EntryTree.parse(yml).flatten();
        assertThat(entries).extracting(Entry::name).containsExactly("skill-filesystem", "tool-fs");
    }

    @Test
    void sourcesIncludesNestedFiles() throws Exception {
        Path inner = tmp.resolve("inner.yml");
        Files.writeString(inner, "plugins:\n  - name: x\n    source: java:a.X\n");
        Path outer = tmp.resolve("outer.yml");
        Files.writeString(outer, "include:\n  - ./inner.yml\nplugins:\n  - name: y\n    source: java:a.Y\n");
        List<Path> sources = EntryTree.parse(outer).sources();
        assertThat(sources).containsExactlyInAnyOrder(
                inner.toAbsolutePath().normalize(), outer.toAbsolutePath().normalize());
    }
}
