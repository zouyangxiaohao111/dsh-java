package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import dev.dsh.cordis.util.Disposable;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DisposeOrderTest {
    @Test
    void disposersRunInReverseOrder() throws Exception {
        Context root = new Context();
        List<String> order = new ArrayList<>();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            ctx.effect(() -> Disposable.of(() -> order.add("a")), "a");
            ctx.effect(() -> Disposable.of(() -> order.add("b")), "b");
            ctx.effect(() -> Disposable.of(() -> order.add("c")), "c");
            return null;
        }), null);
        f.await().join();
        f.dispose().join();
        assertThat(order).containsExactly("c", "b", "a");
    }
}
