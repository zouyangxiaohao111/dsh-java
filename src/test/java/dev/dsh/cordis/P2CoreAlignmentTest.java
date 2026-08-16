package dev.dsh.cordis;

import dev.dsh.cordis.annotation.Inject;
import dev.dsh.cordis.util.Disposable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** M4 P2 core alignment: @Inject annotation scanning + Context.get error +
 *  Fiber.getEffects + Registry iteration (registry.ts / reflect.ts / fiber.ts). */
class P2CoreAlignmentTest {

    @Inject({"a", "b"})
    static final class AnnotatedPlugin implements Plugin<Void> {
        @Override public Object apply(Context ctx, Void config) { return null; }
    }

    @Inject({"a"})
    static final class MergedPlugin implements Plugin<Void> {
        @Override public Object apply(Context ctx, Void config) { return null; }
        @Override public String[] inject() { return new String[]{"c"}; }
        @Override public Map<String, Object> injectConfig() { return Map.of("a", 1); }
    }

    // ---- task 1: @Inject class annotation (registry.ts:37-60) ----

    @Test
    void injectAnnotationScannedIntoDependencyMap() {
        Context root = new Context();
        Fiber f = root.plugin(new AnnotatedPlugin(), null);
        assertThat(f.inject).containsKeys("a", "b");
        root.fiber.dispose().join();
    }

    @Test
    void injectAnnotationMergesWithMethodOverrides() {
        Context root = new Context();
        Fiber f = root.plugin(new MergedPlugin(), null);
        // injectConfig() 的 a→1 覆盖注解的 a→null;inject() 增加 c
        assertThat(f.inject).containsEntry("a", 1).containsEntry("c", null);
        root.fiber.dispose().join();
    }

    // ---- task 2: Context.get missing service throws (reflect.ts:144) ----

    @Test
    void pluginGetMissingServiceThrows() {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> null), null);
        f.await().join();
        assertThatThrownBy(() -> f.ctx.get("missing"))
                .isInstanceOf(CordisError.class)
                .hasMessageContaining("cannot get property \"missing\" without inject");
        root.fiber.dispose().join();
    }

    @Test
    void pluginGetInactiveRequiredServiceThrows() {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> null).inject("svc"), null);
        assertThat(f.state).isEqualTo(FiberState.PENDING);   // svc 未提供
        assertThatThrownBy(() -> f.ctx.get("svc"))
                .isInstanceOf(CordisError.class)
                .hasMessageContaining("cannot get required service \"svc\" in inactive context");
        root.fiber.dispose().join();
    }

    @Test
    void rootGetMissingServiceReturnsNull() {
        Context root = new Context();
        assertThat((Object) root.get("missing")).isNull();
    }

    // ---- task 3: Fiber.getEffects (fiber.ts:568-572) ----

    @Test
    void getEffectsCollectsLabels() {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            ctx.effect(() -> Disposable.none(), "first");
            ctx.effect(() -> Disposable.none(), "second");
            return null;
        }), null);
        f.await().join();
        assertThat(f.getEffects()).extracting(Fiber.EffectMeta::label).contains("first", "second");
        root.fiber.dispose().join();
    }

    @Test
    void getEffectsNestsCollectedEffects() {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            ctx.effect(() -> {
                Disposable inner = ctx.effect(() -> Disposable.none(), "inner");
                return inner;
            }, "outer");
            return null;
        }), null);
        f.await().join();
        List<Fiber.EffectMeta> effects = f.getEffects();
        assertThat(effects).extracting(Fiber.EffectMeta::label).containsExactly("outer");
        assertThat(effects.get(0).children()).extracting(Fiber.EffectMeta::label).containsExactly("inner");
        root.fiber.dispose().join();
    }

    // ---- task 4: Registry keys/entries/forEach (registry.ts:270-291) ----

    @Test
    void registryIterationMethods() {
        Context root = new Context();
        PluginSpec<Void> p = PluginSpec.<Void>of((ctx, cfg) -> null).name("iter");
        root.plugin(p, null);

        assertThat(root.registry.keys()).containsExactly(p);
        assertThat(root.registry.values()).extracting(Plugin.Runtime::name).containsExactly("iter");
        assertThat(root.registry.entries()).hasSize(1);
        Map.Entry<Plugin<?>, Plugin.Runtime> e = root.registry.entries().iterator().next();
        assertThat(e.getKey()).isSameAs(p);
        assertThat(e.getValue()).isSameAs(root.registry.get(p));

        List<String> visited = new ArrayList<>();
        root.registry.forEach((runtime, plugin) -> visited.add(runtime.name() + ":" + (plugin == p)));
        assertThat(visited).containsExactly("iter:true");

        root.fiber.dispose().join();
    }
}
