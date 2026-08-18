package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-6 fiber isolation: same-scope sibling plugins must see each other's services
 * (mirror the dsh loader's service-availability activation, where the dsh-base tree
 * rows are flat siblings — llm / sessions / agent / tools / ... all share the root
 * scope). Each plugin runs in its own child fiber, so the fiber-chain walk never
 * reaches a sibling's store; cordis resolves {@code ctx.get(name)} through the shared
 * store keyed by the isolate label, which is the fallback this milestone adds.
 */
class FiberIsolationTest {

    @Test
    void siblingProvidedServiceVisibleViaGet() {
        Context root = new Context();
        Fiber provider = root.plugin(
                PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("svc", "from-A")).provide("svc"), null);
        provider.await().join();
        assertThat(provider.state).isEqualTo(FiberState.ACTIVE);

        // sibling fiber reads the service both during its apply and afterwards —
        // before the fix the fiber-chain walk could not reach the sibling's store
        // and threw "cannot get property \"svc\" without inject".
        AtomicReference<Object> readDuringApply = new AtomicReference<>();
        Fiber consumer = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            readDuringApply.set(ctx.get("svc"));
            return null;
        }), null);
        consumer.await().join();
        assertThat(consumer.state).isEqualTo(FiberState.ACTIVE);
        assertThat(readDuringApply.get()).isEqualTo("from-A");
        assertThat(consumer.ctx.<String>get("svc")).isEqualTo("from-A");
        root.fiber.dispose().join();
    }

    @Test
    void siblingProvidedServiceVisibleViaGetService() {
        Context root = new Context();
        Fiber provider = root.plugin(
                PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("svc", "from-A")).provide("svc"), null);
        provider.await().join();

        // the JS bridges route every ctx read through the non-throwing getService path
        AtomicReference<Object> readDuringApply = new AtomicReference<>();
        Fiber consumer = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            readDuringApply.set(ctx.getService("svc"));
            return null;
        }), null);
        consumer.await().join();
        assertThat(consumer.state).isEqualTo(FiberState.ACTIVE);
        assertThat(readDuringApply.get()).isEqualTo("from-A");
        assertThat(consumer.ctx.<String>getService("svc")).isEqualTo("from-A");
        root.fiber.dispose().join();
    }

    @Test
    void missingServiceGetServiceReturnsNoService() {
        // mirrors dsh-launch-environment: launchEnvironmentOf(ctx) =
        // ctx.get('launchEnvironment') ?? fallback — a launcher-owned slot the harness
        // never provides, so getService must read NO_SERVICE (not throw "without inject").
        Context root = new Context();
        AtomicReference<Object> readDuringApply = new AtomicReference<>();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            readDuringApply.set(ctx.getService("launchEnvironment"));
            return null;
        }), null);
        f.await().join();
        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        assertThat(readDuringApply.get()).isSameAs(Context.NO_SERVICE);
        assertThat((Object) f.ctx.getService("launchEnvironment")).isSameAs(Context.NO_SERVICE);
        root.fiber.dispose().join();
    }

    @Test
    void injectedSiblingServiceActivatesAfterProvider() {
        // plan-mode / agent-loop pattern: a consumer injected on a service provided by a
        // LATER sibling row stays PENDING until the provider is active, then activates and
        // resolves the service (service-availability-driven activation, not row order).
        Context root = new Context();
        AtomicReference<Object> readAfterActivation = new AtomicReference<>();
        Fiber consumer = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            readAfterActivation.set(ctx.get("systemPrompt"));
            return null;
        }).inject("systemPrompt"), null);
        assertThat(consumer.state).isEqualTo(FiberState.PENDING);

        // provider mounts later (system-prompt is a later dsh-base row) → notify wakes consumer
        Fiber provider = root.plugin(
                PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("systemPrompt", "the-prompt")).provide("systemPrompt"), null);
        provider.await().join();
        consumer.await().join();

        assertThat(consumer.state).isEqualTo(FiberState.ACTIVE);
        assertThat(readAfterActivation.get()).isEqualTo("the-prompt");
        root.fiber.dispose().join();
    }

    @Test
    void isolatedScopeStillIndependent() {
        // regression: isolate() still splits the shared store slot — cordis ctx.get
        // resolves through the caller's own isolate label, so an isolated context reads
        // null while root keeps the parent value (the shared-store fallback must respect
        // the label boundary and must not leak the root implementation into the scope).
        Context root = new Context();
        root.provide("svc", "parent");
        Context isolated = root.isolate("svc");

        assertThat((Object) isolated.getService("svc")).isSameAs(Context.NO_SERVICE);
        assertThat(root.<String>getService("svc")).isEqualTo("parent");
        root.fiber.dispose().join();
    }
}
