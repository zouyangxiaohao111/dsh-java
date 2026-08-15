package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IsolateTest {
    @Test
    void isolatedScopesDoNotSeeEachOther() throws Exception {
        Context root = new Context();
        root.provide("svc", "parent");

        Fiber fa = root.isolate("svc").plugin(
                PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("svc", "A")).provide("svc"), null);
        fa.await().join();
        assertThat(fa.ctx.<String>get("svc")).isEqualTo("A");

        Fiber fb = root.isolate("svc").plugin(
                PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("svc", "B")).provide("svc"), null);
        fb.await().join();
        assertThat(fb.ctx.<String>get("svc")).isEqualTo("B");

        assertThat(root.<String>get("svc")).isEqualTo("parent");
        root.fiber.dispose().join();
    }

    @Test
    void bareIsolatedChildDoesNotSeeParentService() {
        Context root = new Context();
        root.provide("svc", "parent");
        Context child = root.isolate("svc");
        assertThat(child.<String>get("svc")).isNull();
        assertThat(root.<String>get("svc")).isEqualTo("parent");
    }
}
