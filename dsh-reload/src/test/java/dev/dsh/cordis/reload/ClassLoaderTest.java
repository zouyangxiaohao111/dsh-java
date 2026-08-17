package dev.dsh.cordis.reload;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ClassLoaderTest {
    @Test
    void newLoaderLoadsClassAndParentResolvesCore() throws Exception {
        Path dir = Files.createTempDirectory("dsh-cl");
        String src = "package t; import dev.dsh.cordis.*; public class P implements Plugin<Void> { " +
                "public Object apply(Context ctx, Void cfg) { return null; } }";
        Path file = dir.resolve("t/P.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, src);
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", dir.toString(),
                "-cp", System.getProperty("java.class.path"), file.toString());
        assertThat(rc).isZero();

        PluginClassLoaderFactory factory = new UrlPluginClassLoaderFactory();
        URLClassLoader cl = factory.create(java.util.List.of(dir), getClass().getClassLoader());
        Class<?> pClass = cl.loadClass("t.P");
        assertThat(dev.dsh.cordis.Plugin.class.isAssignableFrom(pClass)).isTrue();
        assertThat(pClass.getClassLoader()).isNotEqualTo(getClass().getClassLoader());
    }
}
