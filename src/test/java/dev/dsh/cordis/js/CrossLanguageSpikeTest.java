package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 设计 §4.1:Java provide counter → JS 插件 inject 并调用 → JS emit 回 Java。 */
class CrossLanguageSpikeTest {
    public static final class Counter {
        int value = 0;
        public int next() { return ++value; }
    }

    @Test
    void javaProvidesJsCallsAndEmitsBack() throws Exception {
        Context root = new Context();
        try (GraalJsHost host = new GraalJsHost()) {
            root.provide("counter", new Counter());

            AtomicReference<String> done = new AtomicReference<>();
            root.on("done", (c, args) -> { done.set(args[0] + "#" + args[1]); return null; });

            root.plugin(new JsPluginAdapter(host, host.eval(
                "(ctx) => { ctx.on('app/ready', () => { " +
                "  const c = ctx.get('counter'); ctx.emit('done', 'n', c.next()); " +
                "}); }")), null);

            root.emit("app/ready", "started");
            assertThat(done.get()).isEqualTo("n#1");
        }
        root.fiber.dispose().join();
    }
}
