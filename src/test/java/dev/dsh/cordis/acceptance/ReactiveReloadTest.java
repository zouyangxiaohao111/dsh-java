package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import dev.dsh.cordis.util.Disposable;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ReactiveReloadTest {
    static final class ImplA { String tag() { return "A"; } }
    static final class ImplB { String tag() { return "B"; } }

    @Test
    void dependentsReloadWhenProviderSwaps() throws Exception {
        Context root = new Context();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger unloads = new AtomicInteger();

        // provider 必须是带独立 fiber 的插件:换实现 = dispose 旧 provider + 注册新 provider
        Plugin<?> providerA = PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("svc", new ImplA())).provide("svc");
        root.plugin(providerA, null);

        Fiber consumer = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            loads.incrementAndGet();
            // Java 移植:PluginApply 返回 Object(异步 apply 可返回 CompletableFuture);清理语义用
            // ctx.effect 表达(cordis _execute 会 collect 回调返回的 Disposable)。保留全部 reactive-reload 断言。
            ctx.effect(() -> Disposable.of(unloads::incrementAndGet), "unload-counter");
            return null;
        }).inject("svc"), null);
        consumer.await().join();
        assertThat(loads.get()).isEqualTo(1);

        // swap: dispose providerA(unprovide → store.remove → notify → consumer unload)
        root.registry.delete(providerA);
        assertThat(unloads.get()).isEqualTo(1);
        assertThat(consumer.state).isEqualTo(FiberState.PENDING);

        // re-register providerB
        Plugin<?> providerB = PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("svc", new ImplB())).provide("svc");
        root.plugin(providerB, null);
        consumer.await().join();

        assertThat(loads.get()).isEqualTo(2);   // consumer 用新实现重载
        assertThat(consumer.ctx.<ImplB>get("svc")).isInstanceOf(ImplB.class);
        root.fiber.dispose().join();
    }
}
