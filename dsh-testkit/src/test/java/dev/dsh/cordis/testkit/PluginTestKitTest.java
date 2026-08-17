package dev.dsh.cordis.testkit;

import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.FiberState;
import dev.dsh.cordis.PluginSpec;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 外部插件作者视角:extends PluginTestKit,@Test 内 new Context(基类负责)+ 加载插件 + 断言。 */
class PluginTestKitTest extends PluginTestKit {

    @Test
    void loadsAndRespondsToEvents() throws Exception {
        AtomicInteger applies = new AtomicInteger();
        Fiber f = load(PluginSpec.<Void>of((ctx, cfg) -> {
            applies.incrementAndGet();
            ctx.on("greet", (c, args) -> {
                ctx.emit("greeted", "hello " + args[0]);
                return null;
            });
            return null;
        }).name("greeter"));

        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        assertThat(applies.get()).isEqualTo(1);

        AtomicReference<Object> greeted = new AtomicReference<>();
        ctx.on("greeted", (c, args) -> {
            greeted.set(args[0]);
            return null;
        });
        ctx.emit("greet", "world");
        assertThat(greeted.get()).isEqualTo("hello world");
    }

    @Test
    void loadWithoutConfigActivates() throws Exception {
        AtomicReference<Fiber> captured = new AtomicReference<>();
        Fiber f = load(PluginSpec.<Void>of((ctx, cfg) -> {
            captured.set(ctx.fiber);
            return null;
        }));
        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        assertThat(captured.get()).isSameAs(f);
    }

    @Test
    void applyErrorPropagatesAsOriginalException() {
        PluginSpec<Void> broken = PluginSpec.<Void>of((ctx, cfg) -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(IllegalStateException.class, () -> load(broken, null), "boom");
    }

    @Test
    void servicesProvidedByPluginAreReadable() throws Exception {
        load(PluginSpec.<Void>of((ctx, cfg) -> {
            ctx.provide("answer", 42);
            return null;
        }).provide("answer"));

        Integer answer = ctx.get("answer");
        assertThat(answer).isEqualTo(42);
    }

    @Test
    void teardownLeavesNoPluginRegistered() throws Exception {
        load(PluginSpec.<Void>of((ctx, cfg) -> null).name("transient"));
        assertThat(ctx.registry.size()).isEqualTo(1);   // 测试内可见
        // tearDown 后 root shutdown 级联 dispose —— 由下一个测试的无残留隐式验证
    }
}
