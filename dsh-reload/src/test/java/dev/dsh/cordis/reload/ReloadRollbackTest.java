package dev.dsh.cordis.reload;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReloadRollbackTest {
    @Test
    void failedReloadKeepsOldImplementation() throws Exception {
        Context root = new Context();
        Path src = Files.createTempDirectory("dsh-rb").resolve("P.java");
        Files.writeString(src, "import dev.dsh.cordis.*; public class P implements Plugin<Void> { " +
            "public Object apply(Context ctx, Void cfg) { ctx.provide(\"svc\", \"v1\"); return null; } }");
        PluginReloader reloader = new PluginReloader(root, new UrlPluginClassLoaderFactory(), Files.createTempDirectory("dsh-rb-out"));
        Plugin<?> p1 = reloader.reload(src, null, null);
        assertThat((String) root.get("svc")).isEqualTo("v1");

        Files.writeString(src, "import dev.dsh.cordis.*; public class P implements Plugin<Void> { this is broken ");
        assertThatThrownBy(() -> reloader.reload(src, p1, null)).isInstanceOf(Exception.class);
        assertThat((String) root.get("svc")).isEqualTo("v1");
        root.fiber.dispose().join();
    }
}
