package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class StateMachineTest {
    @Test
    void failedPluginOnApplyException() {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> { throw new RuntimeException("boom"); }), null);
        Throwable t = catchThrowable(() -> f.await().join());
        assertThat(t).isNotNull();
        assertThat(f.state).isEqualTo(FiberState.FAILED);
        // FAILED 后 _error 未清,await() 保持 completed exceptionally(fiber.ts:704-710)
        assertThat(f.await()).isCompletedExceptionally();
    }

    @Test
    void failedPluginRecoversOnRestart() throws Exception {
        Context root = new Context();
        AtomicInteger attempts = new AtomicInteger();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            if (attempts.incrementAndGet() == 1) throw new RuntimeException("boom");
        }), null);
        Throwable t = catchThrowable(() -> f.await().join());
        assertThat(f.state).isEqualTo(FiberState.FAILED);
        assertThat(t).isNotNull();

        f.restart().join();   // 重试 → 第二次成功
        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        assertThat(attempts.get()).isEqualTo(2);
        f.dispose().join();
        assertThat(f.state).isEqualTo(FiberState.DISPOSED);
    }

    @Test
    void activeThenDisposed() throws Exception {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {}), null);
        f.await().join();
        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        f.dispose().join();
        assertThat(f.state).isEqualTo(FiberState.DISPOSED);
    }
}
