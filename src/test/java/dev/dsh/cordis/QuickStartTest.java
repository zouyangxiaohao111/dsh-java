package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 复刻 cordis README Quick Start: counter 服务 + greeter 插件 + app/ready 事件。 */
class QuickStartTest {
    static final class Counter {
        int value = 0;
        int next() { return ++value; }
    }

    @Test
    void quickStartScenario() throws Exception {
        Context root = new Context();
        root.provide("counter", new Counter());

        AtomicReference<String> seen = new AtomicReference<>();
        PluginSpec<Void> greeter = PluginSpec.<Void>of((ctx, cfg) ->
                ctx.on("app/ready", (c, args) -> {
                    Counter counterSvc = ctx.get("counter");   // 捕获插件 ctx(监听器 ctx 参数对 plain emit 为 null)
                    seen.set(args[0] + " #" + counterSvc.next());
                    return null;
                }))
                .inject("counter");
        root.plugin(greeter, null).await().join();

        root.emit("app/ready", "started");
        assertThat(seen.get()).isEqualTo("started #1");

        root.fiber.dispose().join();
    }
}
