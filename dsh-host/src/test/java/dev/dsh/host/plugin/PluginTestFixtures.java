package dev.dsh.host.plugin;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * M6-7 测试夹具:在测试内动态编译插件源码并打包为 jar(依赖 dsh-cordis 经测试运行时
 * classpath),或写出插件源码目录,供 {@code dshj plugin add} 的 jar:/java: 安装测试使用。
 */
public final class PluginTestFixtures {

    private PluginTestFixtures() {
    }

    /** 编译并打包一个提供 'jar-greet' 服务的插件 jar,返回 jar 路径。 */
    public static Path compilePluginJar(Path outJar) throws IOException {
        Path srcDir = Files.createTempDirectory("dshj-fix-src");
        Path srcFile = srcDir.resolve("JarPlugin.java");
        Files.writeString(srcFile, jarPluginSource());
        Path classes = Files.createTempDirectory("dshj-fix-classes");
        compile(srcDir, classes);
        Files.createDirectories(outJar.getParent());
        jarIt(classes, outJar);
        return outJar;
    }

    /** 把 jar 布到 MavenLocal 布局:&lt;mavenLocal&gt;/&lt;g path&gt;/a/v/a-v.jar。 */
    public static Path seedMavenLocal(Path mavenLocal, String group, String artifact, String version, Path jar)
            throws IOException {
        Path target = mavenLocal.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + ".jar");
        Files.createDirectories(target.getParent());
        Files.copy(jar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** 写一个提供 'src-greet' 服务的插件源码目录(根 + src/ 子目录),返回目录路径。 */
    public static Path writeSourceDir(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/com/acme/src"));
        Files.writeString(root.resolve("src/com/acme/src/SrcPlugin.java"), srcPluginSource());
        return root;
    }

    /** 写一个单文件插件源码,返回 .java 文件路径。 */
    public static Path writeSingleSourceFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, srcPluginSource());
        return file;
    }

    /**
     * 写一个<b>自包含 Java 插件 git 仓库</b>(M7-2 验证克隆源):标准
     * {@code src/main/java/} 布局 + README + build.gradle 外壳,插件实现
     * {@code dev.dsh.cordis.Plugin}(核心 provided,经测试运行 classpath),提供
     * {@code git-greet} 服务。返回仓库根(调用方 git init + commit 后经
     * {@code java:git+<url>} 走真实 clone 路径安装)。
     */
    public static Path writeGitPluginRepo(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/dev/acme/greeter"));
        Files.writeString(root.resolve("src/main/java/dev/acme/greeter/GreetPlugin.java"), gitPluginSource());
        Files.writeString(root.resolve("README.md"), "# greet-plugin\nM7-2 self-contained plugin repo\n");
        Files.writeString(root.resolve("build.gradle"),
                "dependencies {\n  implementation files('<dsh-cordis provided by harness>')\n}\n");
        return root;
    }

    static String gitPluginSource() {
        return "package dev.acme.greeter;\n"
                + "import dev.dsh.cordis.*;\n"
                + "public class GreetPlugin implements Plugin<Void> {\n"
                + "  public String name() { return \"greet-plugin\"; }\n"
                + "  public String[] provide() { return new String[]{\"git-greet\"}; }\n"
                + "  public Object apply(Context ctx, Void cfg) {\n"
                + "    ctx.provide(\"git-greet\", new Greeter(\"hi from git\"));\n"
                + "    return null;\n"
                + "  }\n"
                + "  public static class Greeter {\n"
                + "    public final String value;\n"
                + "    public Greeter(String value) { this.value = value; }\n"
                + "    public String greet() { return value; }\n"
                + "  }\n"
                + "}\n";
    }

    static String jarPluginSource() {
        return "package com.acme.jar;\n"
                + "import dev.dsh.cordis.*;\n"
                + "public class JarPlugin implements Plugin<Void> {\n"
                + "  public String name() { return \"jar-plugin\"; }\n"
                + "  public String[] provide() { return new String[]{\"jar-greet\"}; }\n"
                + "  public Object apply(Context ctx, Void cfg) {\n"
                + "    ctx.provide(\"jar-greet\", new Greeter(\"hello\"));\n"
                + "    return null;\n"
                + "  }\n"
                + "  public static class Greeter {\n"
                + "    public final String value;\n"
                + "    public Greeter(String value) { this.value = value; }\n"
                + "    public String greet() { return value; }\n"
                + "  }\n"
                + "}\n";
    }

    static String srcPluginSource() {
        return "package com.acme.src;\n"
                + "import dev.dsh.cordis.*;\n"
                + "public class SrcPlugin implements Plugin<Void> {\n"
                + "  public String name() { return \"src-plugin\"; }\n"
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
                + "}\n";
    }

    /** 编译 srcDir 下全部 .java → outDir(以测试运行时 classpath 为 -cp,dsh-cordis 可见)。 */
    static void compile(Path srcDir, Path outDir) throws IOException {
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
            if (rc != 0) throw new IllegalStateException("fixture plugin compilation failed");
        }
    }

    /** 把 classes 目录下全部文件打包进 jar。 */
    static void jarIt(Path classes, Path jar) throws IOException {
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
}
