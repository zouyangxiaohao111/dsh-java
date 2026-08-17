package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class JsCtxBridgeTest {
    @Test
    void jsPluginEmitsBackToJava() throws Exception {
        Context root = new Context();
        try (GraalJsHost host = new GraalJsHost()) {
            AtomicReference<String> got = new AtomicReference<>();
            root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

            org.graalvm.polyglot.Value fn = host.evalValue("(ctx) => { ctx.on('app/ready', (msg) => { ctx.emit('done', 'hi ' + msg); }); }");
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            fn.execute(bridge.ctxShim());

            root.emit("app/ready", "bob");
            assertThat(got.get()).isEqualTo("hi bob");
        }
        root.fiber.dispose().join();
    }
}
