package dev.dsh.cordis.reload;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PluginCompilerTest {
    @Test
    void compilesSourceToClass() throws Exception {
        Path src = Files.createTempDirectory("dsh-src").resolve("t/P.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package t; import dev.dsh.cordis.*; " +
            "public class P implements Plugin<Void> { public Object apply(Context ctx, Void cfg) { return null; } }");
        Path out = Files.createTempDirectory("dsh-out");
        new PluginCompiler().compile(src, out);
        assertThat(out.resolve("t/P.class")).exists();
    }
}
