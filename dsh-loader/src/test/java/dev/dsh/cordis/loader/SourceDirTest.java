package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.HostKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-7 的 {@code java:} <b>目录源</b>:cordis.yml 指向一个源码目录 → loader 整目录编译
 * ({@code PluginCompiler.compileTree})→ 从编译产物扫描唯一 {@code implements Plugin} 类
 * → 实例化注册。与单文件 / 类名源并列,是 dshj plugin add 装源码目录的加载侧契约。
 */
class SourceDirTest {

    @TempDir
    Path tmp;

    @Test
    void loadSourceDirectoryCompilesAndDiscovers() throws Exception {
        // 源码目录(含 src/ 子目录,多文件结构)
        Path srcRoot = tmp.resolve("plugins/src/greet");
        Files.createDirectories(srcRoot.resolve("src/com/acme/src"));
        Files.writeString(srcRoot.resolve("src/com/acme/src/SrcGreeter.java"),
                "package com.acme.src;\n"
                        + "import dev.dsh.cordis.*;\n"
                        + "public class SrcGreeter implements Plugin<Void> {\n"
                        + "  public String name() { return \"src-greet\"; }\n"
                        + "  public String[] provide() { return new String[]{\"src-greet\"}; }\n"
                        + "  public Object apply(Context ctx, Void cfg) {\n"
                        + "    ctx.provide(\"src-greet\", new Greeter(\"hi\"));\n"
                        + "    return null;\n"
                        + "  }\n"
                        + "  public static class Greeter {\n"
                        + "    public final String value;\n"
                        + "    public Greeter(String value) { this.value = value; }\n"
                        + "    public String greet() { return value; }\n"
                        + "  }\n"
                        + "}\n");

        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n"
                + "  - name: greet\n"
                + "    source: java:./plugins/src/greet\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.JAVA);
            assertThat(loaded.get(0).plugin().name()).isEqualTo("src-greet");
            assertThat(greet(root)).isEqualTo("hi");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void sourceDirectoryWithoutPluginClassFails() throws Exception {
        Path srcRoot = tmp.resolve("plugins/src/plain");
        Files.createDirectories(srcRoot);
        Files.writeString(srcRoot.resolve("NotPlugin.java"),
                "package com.acme.plain;\n"
                        + "public class NotPlugin {}\n");

        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: p\n    source: java:./plugins/src/plain\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            assertThatThrownBy(() -> loader.load(yml))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("no Cordis plugin found");
            assertThat(loader.loaded()).isEmpty();
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void missingSourceDirectoryFails() throws Exception {
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: p\n    source: java:./nope/src\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            assertThatThrownBy(() -> loader.load(yml)).isInstanceOf(Exception.class);
            assertThat(loader.loaded()).isEmpty();
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    private static String greet(Context root) {
        Object svc = root.get("src-greet");
        if (svc == null) return null;
        try {
            Method m = svc.getClass().getMethod("greet");
            return (String) m.invoke(svc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
