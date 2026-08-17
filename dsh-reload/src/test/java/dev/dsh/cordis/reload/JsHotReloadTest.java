package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.GraalJsHost;
import dev.dsh.cordis.js.JsPluginAdapter;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class JsHotReloadTest {
    @Test
    void jsPluginReloadOnFileChange() throws Exception {
        Context root = new Context();
        Path dir = Files.createTempDirectory("dsh-js");
        Path file = dir.resolve("plugin.js");
        Files.writeString(file, "module.exports = (ctx) => { ctx.on('go', () => ctx.emit('done', 'v1')); }");

        JsHost host1 = new GraalJsHost(dir);
        JsPluginAdapter p1 = new JsPluginAdapter(host1, host1.loadModule(file.toAbsolutePath()));
        root.plugin(p1, null);
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
        root.emit("go");
        assertThat(got.get()).isEqualTo("v1");

        Files.writeString(file, "module.exports = (ctx) => { ctx.on('go', () => ctx.emit('done', 'v2')); }");
        JsPluginReloader reloader = new JsPluginReloader(root);
        JsPluginAdapter p2 = reloader.reload(file, p1, null);

        root.emit("go");
        assertThat(got.get()).isEqualTo("v2");
        root.fiber.dispose().join();
    }
}
