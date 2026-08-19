package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.CordisError;
import dev.dsh.cordis.Context;
import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.FiberState;
import dev.dsh.cordis.LoaderService;
import dev.dsh.cordis.Plugin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-8 B+C 核心面:Java 核心提供 {@code loader} 服务(ctx.loader),{@code Fiber.assertActive()}
 * 在 fiber dispose 后抛 INACTIVE_EFFECT(worker 侧 ctx.fiber.assertActive() 的桥对面)。
 */
class LoaderAndFiberAssertTest {

    /** 核心始终提供 loader 服务(根上下文可直接读,与 logger/registry 同级)。 */
    @Test
    void coreProvidesLoaderService() {
        Context root = new Context();
        try {
            assertThat(root.loader).isInstanceOf(LoaderService.class);
            assertThat((Object) root.get("loader")).isSameAs(root.loader);
            assertThat((Object) root.getService("loader")).isSameAs(root.loader);
            // hmr 构造器读的面:internal 真值 + version
            assertThat(root.loader.internal).isNotNull();
            assertThat(root.loader.internal.version).isEqualTo("v1");
        } finally {
            root.fiber.dispose().join();
        }
    }

    /** 插件 fiber 的 ctx.get('loader') 也能解析到核心提供的 loader(跨隔离域可见)。 */
    @Test
    void pluginFiberReadsCoreLoader() {
        Context root = new Context();
        Fiber[] captured = new Fiber[1];
        try {
            Plugin<Object> p = new Plugin<>() {
                @Override public String name() { return "loader-reader"; }
                @Override public String[] inject() { return new String[]{"loader"}; }
                @Override public String[] provide() { return new String[0]; }
                @Override public Object apply(Context ctx, Object config) {
                    captured[0] = ctx.fiber;
                    assertThat((Object) ctx.get("loader")).isInstanceOf(LoaderService.class);
                    return null;
                }
            };
            root.plugin(p, null);
            assertThat(captured[0].state).isEqualTo(FiberState.ACTIVE);
        } finally {
            root.fiber.dispose().join();
        }
    }

    /** Fiber.assertActive():存活 fiber 不抛;dispose 后抛 INACTIVE_EFFECT。 */
    @Test
    void disposedFiberAssertActiveThrows() {
        Context root = new Context();
        Fiber[] fiber = new Fiber[1];
        try {
            root.plugin(new Plugin<>() {
                @Override public String name() { return "probe"; }
                @Override public String[] inject() { return new String[0]; }
                @Override public String[] provide() { return new String[0]; }
                @Override public Object apply(Context ctx, Object config) {
                    fiber[0] = ctx.fiber;
                    ctx.fiber.assertActive();   // 存活:不抛
                    return null;
                }
            }, null);
            assertThat(fiber[0].state).isNotEqualTo(FiberState.DISPOSED);
        } finally {
            root.fiber.dispose().join();
        }
        assertThat(fiber[0].state).isEqualTo(FiberState.DISPOSED);
        assertThatThrownBy(() -> fiber[0].assertActive())
                .isInstanceOfSatisfying(CordisError.class, e ->
                        assertThat(e.code).isEqualTo(CordisError.Code.INACTIVE_EFFECT));
    }
}
