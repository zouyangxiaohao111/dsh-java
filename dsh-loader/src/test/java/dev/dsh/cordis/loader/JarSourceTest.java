package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.HostKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6 §5.2 jar: 源类型 —— cordis.yml 加载外部 jar 插件。
 *
 * <p>覆盖:显式 {@code mainClass} / 扫描 {@code implements Plugin} / ServiceLoader
 * ({@code META-INF/services/dev.dsh.cordis.Plugin}) 三种类发现;jar 文件变更热重载
 * (updateIfChanged → 新 CL 重新加载);无插件类 / jar 缺失报错;与 JS 插件混排。
 *
 * <p>jar 在测试内动态编译并打包(依赖 dsh-cordis 经测试运行时 classpath,见 {@link #compileJarPlugin})。
 */
class JarSourceTest {

    @TempDir
    Path tmp;

    // ---- ① 类发现:显式 mainClass / 扫描 / ServiceLoader ----

    @Test
    void loadJarWithExplicitMainClass() throws Exception {
        Path jar = compileJarPlugin("plugins/exp.jar", "v1");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n"
                + "  - name: jg\n"
                + "    source: jar:./plugins/exp.jar\n"
                + "    mainClass: com.example.jar.JarGreeter\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.JAVA);
            assertThat(loaded.get(0).source()).isEqualTo(jar);
            assertThat(loaded.get(0).plugin().name()).isEqualTo("jar-greeter");
            assertThat(jarGreet(root)).isEqualTo("v1");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void loadJarDiscoversByScan() throws Exception {
        // 无 mainClass → 扫描 jar 条目 implements Plugin
        compileJarPlugin("plugins/scan.jar", "scan-v1");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: jg\n    source: jar:./plugins/scan.jar\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(jarGreet(root)).isEqualTo("scan-v1");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void loadJarViaServiceLoader() throws Exception {
        // META-INF/services/dev.dsh.cordis.Plugin 声明入口 → ServiceLoader 发现
        compileJarPlugin("plugins/svc.jar", "svc-v1",
                "com.example.jar.JarGreeter\n");   // service 描述文件内容
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: jg\n    source: jar:./plugins/svc.jar\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(jarGreet(root)).isEqualTo("svc-v1");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void loadJarMixedWithJsPlugin() throws Exception {
        // jar 插件与 JS 插件混排:同一 cordis.yml 双宿主共存
        compileJarPlugin("plugins/mix.jar", "mix-v1");
        Files.writeString(tmp.resolve("greeter.js"), """
                module.exports = { name: 'js-greet', provide: ['js-greet'], apply(ctx) { ctx.provide('js-greet', 'js-v1'); } }
                """);
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: jg
                    source: jar:./plugins/mix.jar
                    mainClass: com.example.jar.JarGreeter
                  - name: js
                    path: ./greeter.js
                """);

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(2);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.JAVA);
            assertThat(loaded.get(1).kind()).isEqualTo(HostKind.GRAAL);
            assertThat(jarGreet(root)).isEqualTo("mix-v1");
            assertThat(svc(root, "js-greet")).isEqualTo("js-v1");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- ② 热重载:jar 文件变更 → 新 CL 重新加载 ----

    @Test
    void hotReloadReloadsJarOnFileChange() throws Exception {
        Path jar = compileJarPlugin("plugins/hot.jar", "v1");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n"
                + "  - name: jg\n"
                + "    source: jar:./plugins/hot.jar\n"
                + "    mainClass: com.example.jar.JarGreeter\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(jarGreet(root)).isEqualTo("v1");
            LoadedPlugin first = loader.loaded().get(0);
            ClassLoader firstCl = first.classLoader();
            sleepMtime();

            // 覆盖 jar 文件(v1 → v2)→ updateIfChanged 检测到 → 新 CL 重新加载
            rebuildJar(jar, "v2");
            sleepMtime();
            assertThat(loader.updateIfChanged()).isTrue();
            assertThat(jarGreet(root)).isEqualTo("v2");
            assertThat(loader.loaded()).hasSize(1);
            assertThat(loader.loaded().get(0)).isNotSameAs(first);
            assertThat(loader.loaded().get(0).classLoader()).isNotSameAs(firstCl);   // 新隔离 CL
            assertThat(loader.loaded().get(0).classLoader()).isInstanceOf(java.net.URLClassLoader.class);
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- ③ 失败 ----

    @Test
    void loadJarWithoutPluginClassFails() throws Exception {
        // jar 里只有普通类(非 Plugin)→ 扫描不到 → 报错
        Path srcDir = tmp.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("NotPlugin.java"), """
                package com.example.jar;
                public class NotPlugin {
                    public static String x() { return "x"; }
                }
                """);
        Path classes = tmp.resolve("classes");
        compileDir(srcDir, classes);
        Path jar = tmp.resolve("plugins/plain.jar");
        Files.createDirectories(jar.getParent());
        jarIt(classes, jar);

        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: jg\n    source: jar:./plugins/plain.jar\n");
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
    void loadMissingJarFails() throws Exception {
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: jg\n    source: jar:./nope.jar\n");
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

    // ---- 小工具:动态编译 + 打包 jar ----

    /** 写插件源码 → 编译 → 打包为 jar(带 mainClass);返回 jar 路径。 */
    private Path compileJarPlugin(String jarRel, String value) throws IOException {
        return compileJarPlugin(jarRel, value, null);
    }

    /** 写插件源码 → 编译 → 打包为 jar(可选 META-INF/services 描述);返回 jar 路径。 */
    private Path compileJarPlugin(String jarRel, String value, String serviceEntry) throws IOException {
        Path srcDir = tmp.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("JarGreeter.java"), jarGreeterSource(value));
        Path classes = tmp.resolve("classes");
        compileDir(srcDir, classes);
        if (serviceEntry != null) {
            Path svc = classes.resolve("META-INF/services/dev.dsh.cordis.Plugin");
            Files.createDirectories(svc.getParent());
            Files.writeString(svc, serviceEntry);
        }
        Path jar = tmp.resolve(jarRel);
        Files.createDirectories(jar.getParent());
        jarIt(classes, jar);
        return jar;
    }

    /** 覆盖重建 jar:内容改为新 value(热重载用)。 */
    private void rebuildJar(Path jar, String value) throws IOException {
        Path classes = tmp.resolve("classes");
        // 重编译到新 classes 目录,再覆盖写 jar
        Path srcDir = tmp.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("JarGreeter.java"), jarGreeterSource(value));
        Path fresh = tmp.resolve("classes-fresh");
        Files.createDirectories(fresh);
        compileDir(srcDir, fresh);
        jarIt(fresh, jar);
    }

    /** 编译 srcDir 下全部 .java → outDir(以测试运行时 classpath 为 -cp,dsh-cordis 可见)。 */
    private static void compileDir(Path srcDir, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        String classpath = System.getProperty("java.class.path");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        try (Stream<Path> files = Files.walk(srcDir)) {
            List<String> srcs = files.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
            List<String> args = new java.util.ArrayList<>();
            args.add("-d"); args.add(outDir.toString());
            args.add("-cp"); args.add(classpath);
            args.addAll(srcs);
            int rc = javac.run(null, null, null, args.toArray(String[]::new));
            if (rc != 0) throw new IllegalStateException("jar plugin compilation failed");
        }
    }

    /** 把 classes 目录下全部文件打包进 jar。 */
    private static void jarIt(Path classes, Path jar) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String rel = classes.relativize(p).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(rel));
                    jos.write(Files.readAllBytes(p));
                    jos.closeEntry();
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** 插件源码:provide 'jar-greet' 一个带 greet() 成员的对象。 */
    private static String jarGreeterSource(String value) {
        return "package com.example.jar;\n"
                + "import dev.dsh.cordis.*;\n"
                + "public class JarGreeter implements Plugin<Void> {\n"
                + "  public String name() { return \"jar-greeter\"; }\n"
                + "  public String[] provide() { return new String[]{\"jar-greet\"}; }\n"
                + "  public Object apply(Context ctx, Void cfg) {\n"
                + "    ctx.provide(\"jar-greet\", new Greeter(\"" + value + "\"));\n"
                + "    return null;\n"
                + "  }\n"
                + "  public static class Greeter {\n"
                + "    public final String value;\n"
                + "    public Greeter(String value) { this.value = value; }\n"
                + "    public String greet() { return value; }\n"
                + "  }\n"
                + "}\n";
    }

    /** 读 'jar-greet' 服务(隔离 ClassLoader 实例,反射调 greet())。 */
    private static String jarGreet(Context root) {
        Object svc = root.get("jar-greet");
        if (svc == null) return null;
        try {
            Method m = svc.getClass().getMethod("greet");
            return (String) m.invoke(svc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 读任一服务,经 toString 取文本;未提供时返回 null。 */
    private static String svc(Context root, String name) {
        Object v = root.get(name);
        return v == null ? null : String.valueOf(v);
    }

    private static void sleepMtime() throws InterruptedException {
        Thread.sleep(30);   // 保证 mtime 变化被 FileWatcher 捕获(Windows 粒度)
    }
}
