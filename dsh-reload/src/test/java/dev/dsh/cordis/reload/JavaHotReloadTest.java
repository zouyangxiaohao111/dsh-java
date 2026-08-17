package dev.dsh.cordis.reload;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class JavaHotReloadTest {
    @Test
    void reloadSwapsPluginImplementation() throws Exception {
        Context root = new Context();
        Path srcDir = Files.createTempDirectory("dsh-plugin");
        Path src = srcDir.resolve("GreeterPlugin.java");
        Files.writeString(src,
            "import dev.dsh.cordis.*;\n" +
            "public class GreeterPlugin implements Plugin<Void> {\n" +
            "  public Object apply(Context ctx, Void cfg) {\n" +
            "    ctx.provide(\"greeter\", new Greeter(\"v1\"));\n" +
            "    return null;\n" +
            "  }\n" +
            "  public static class Greeter { private final String v; public Greeter(String v) { this.v = v; } public String greet() { return v; } }\n" +
            "}\n");
        PluginReloader reloader = new PluginReloader(root, new UrlPluginClassLoaderFactory(),
            Files.createTempDirectory("dsh-reload-out"));
        Plugin<?> p1 = reloader.reload(src, null, null);
        assertThat(callGreet(root)).isEqualTo("v1");

        Files.writeString(src,
            "import dev.dsh.cordis.*;\n" +
            "public class GreeterPlugin implements Plugin<Void> {\n" +
            "  public Object apply(Context ctx, Void cfg) {\n" +
            "    ctx.provide(\"greeter\", new Greeter(\"v2\"));\n" +
            "    return null;\n" +
            "  }\n" +
            "  public static class Greeter { private final String v; public Greeter(String v) { this.v = v; } public String greet() { return v; } }\n" +
            "}\n");
        Plugin<?> p2 = reloader.reload(src, p1, null);
        assertThat(callGreet(root)).isEqualTo("v2");
        root.fiber.dispose().join();
    }

    private String callGreet(Context root) {
        Object svc = root.get("greeter");
        try { return (String) svc.getClass().getMethod("greet").invoke(svc); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
