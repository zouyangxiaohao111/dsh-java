package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceRpcTest {
    public static class Greeter {
        public String greet(String name) { return "hi " + name; }
        public int add(int a, int b) { return a + b; }
    }

    @Test
    void jsCallsJavaServiceViaProxy() throws Exception {
        Context root = new Context();
        try (GraalJsHost host = new GraalJsHost()) {
            root.provide("greeter", new Greeter());
            AtomicInteger got = new AtomicInteger();
            root.on("result", (c, args) -> { got.set(Integer.parseInt(String.valueOf(args[0]))); return null; });

            root.plugin(new JsPluginAdapter(host, host.eval(
                "(ctx) => { ctx.on('go', () => { const g = ctx.get('greeter'); ctx.emit('result', g.add(2, 3)); }); }")), null);
            root.emit("go");
            assertThat(got.get()).isEqualTo(5);
        }
        root.fiber.dispose().join();
    }
}
