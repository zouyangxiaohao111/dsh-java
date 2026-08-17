package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncApplyTest {
    @Test
    void asyncApplyAwaitedBeforeActive() throws Exception {
        Context root = new Context();
        AtomicBoolean applied = new AtomicBoolean(false);
        PluginSpec<Void> p = PluginSpec.<Void>of((ctx, cfg) -> {
            return CompletableFuture.runAsync(() -> applied.set(true));
        });
        Fiber f = root.plugin(p, null);
        f.await().join();
        assertThat(applied.get()).isTrue();
        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        root.fiber.dispose().join();
    }
}
