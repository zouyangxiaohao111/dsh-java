package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class InjectDeferredTest {
    @Test
    void dependentWaitsForProviderThenActivates() throws Exception {
        Context root = new Context();
        AtomicBoolean activated = new AtomicBoolean(false);

        Fiber greeter = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> { activated.set(true); return null; }).inject("svc"), null);
        assertThat(greeter.state).isEqualTo(FiberState.PENDING);

        root.provide("svc", new Object());
        greeter.await().join();

        assertThat(activated.get()).isTrue();
        assertThat(greeter.state).isEqualTo(FiberState.ACTIVE);
        assertThat(greeter.inertia).isNull();   // 回归:reload 成功后 inertia 应为 null(锁 bug-1)
        root.fiber.dispose().join();
    }
}
