package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import dev.dsh.cordis.util.Disposable;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** P1 核心对齐:M4 审计发现的核心缺口 —— internal/config/update waterfall 钩子、
 *  internal/* 事件回填、Reflect.mixin 转发(对照 fiber.ts / events.ts / reflect.ts)。 */
public class InternalHooksTest {

    public static class Counter {
        public int count = 0;
        public int next() { return ++count; }
        public int current() { return count; }
    }

    /** 接口 default 方法转发用(P3:mixin 兜底扫接口)。 */
    public interface Greeter {
        default String greet() { return "hello from " + tag(); }
        String tag();
    }

    public static final class GreeterService implements Greeter {
        public String tag() { return "svc"; }
    }

    /** internal/config waterfall 在 schema 校验前改写原始配置(fiber.ts:641-644)。 */
    @Test
    void internalConfigWaterfallTransformsRawConfig() throws Exception {
        Context root = new Context();
        root.on("internal/config", (ctx, args) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) args[0];
            @SuppressWarnings("unchecked")
            Supplier<Object> next = (Supplier<Object>) args[1];
            cfg.put("hooked", true);
            return next.get();
        });

        AtomicReference<Object> seen = new AtomicReference<>();
        Fiber f = root.plugin(PluginSpec.<Map<String, Object>>of((ctx, cfg) -> { seen.set(cfg); return null; }),
                new LinkedHashMap<>(Map.of("a", 1)));
        f.await().join();
        @SuppressWarnings("unchecked")
        Map<String, Object> applied = (Map<String, Object>) seen.get();
        assertThat(applied.get("a")).isEqualTo(1);
        assertThat(applied.get("hooked")).isEqualTo(true);
        f.dispose().join();
    }

    /** internal/update 监听器不调 next 即 veto,restart 不执行(fiber.ts:736-753)。 */
    @Test
    void internalUpdateHookCanVetoRestart() throws Exception {
        Context root = new Context();
        AtomicInteger applies = new AtomicInteger();
        AtomicInteger hookCalls = new AtomicInteger();
        Fiber f = root.plugin(PluginSpec.<Object>of((ctx, cfg) -> {
            ctx.on("internal/update", (c, args) -> {
                hookCalls.incrementAndGet();
                return "vetoed";   // 不调 next → 否决本次 update
            });
            applies.incrementAndGet();
            return null;
        }), null);
        f.await().join();
        assertThat(applies.get()).isEqualTo(1);

        // fiber.ts:753 契约:update 返回 waterfall 结果——veto 值(非 future)原样透出
        Object veto = f.update(new LinkedHashMap<>(Map.of("k", 1)), false);
        assertThat(veto).isEqualTo("vetoed");   // 未包空 CF,否决值可见
        assertThat(hookCalls.get()).isEqualTo(1);
        assertThat(applies.get()).isEqualTo(1);   // 未重启

        f.dispose().join();
    }

    /** internal/update 监听器调 next 则继续;可改写配置后再重启。 */
    @Test
    void internalUpdateHookPassesThroughAndRestarts() throws Exception {
        Context root = new Context();
        AtomicInteger applies = new AtomicInteger();
        AtomicReference<Object> seen = new AtomicReference<>();
        Fiber f = root.plugin(PluginSpec.<Object>of((ctx, cfg) -> {
            ctx.on("internal/update", (c, args) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cfgMap = (Map<String, Object>) args[0];
                cfgMap.put("marker", "m1");
                @SuppressWarnings("unchecked")
                Supplier<Object> next = (Supplier<Object>) args[args.length - 1];
                return next.get();
            });
            seen.set(cfg);
            applies.incrementAndGet();
            return null;
        }), null);
        f.await().join();
        assertThat(applies.get()).isEqualTo(1);

        ((CompletableFuture<?>) f.update(new LinkedHashMap<>(Map.of("k", 1)), false)).join();
        assertThat(applies.get()).isEqualTo(2);   // 已重启
        @SuppressWarnings("unchecked")
        Map<String, Object> applied = (Map<String, Object>) seen.get();
        assertThat(applied.get("marker")).isEqualTo("m1");   // 钩子在重启前改写配置

        f.dispose().join();
    }

    /** 全局 internal/update 监听器(注册在 hooks map)在 orchestrator 的 fiber 链之后继续。 */
    @Test
    void globalInternalUpdateHookRunsAfterOrchestrator() throws Exception {
        Context root = new Context();
        List<String> order = new ArrayList<>();
        root.on("internal/update", (ctx, args) -> {
            order.add("global-before");
            @SuppressWarnings("unchecked")
            Supplier<Object> next = (Supplier<Object>) args[args.length - 1];
            Object r = next.get();
            order.add("global-after");
            return r;
        }, new Events.EventOptions().global(true));

        AtomicInteger applies = new AtomicInteger();
        Fiber f = root.plugin(PluginSpec.<Object>of((ctx, cfg) -> {
            ctx.on("internal/update", (c, args) -> {
                order.add("fiber-hook");
                @SuppressWarnings("unchecked")
                Supplier<Object> next = (Supplier<Object>) args[args.length - 1];
                return next.get();
            });
            applies.incrementAndGet();
            return null;
        }), null);
        f.await().join();
        order.clear();

        ((CompletableFuture<?>) f.update(new LinkedHashMap<>(Map.of("k", 1)), false)).join();
        assertThat(order).containsExactly("fiber-hook", "global-before", "global-after");
        assertThat(applies.get()).isEqualTo(2);
        f.dispose().join();
    }

    /** 插件 fiber 创建即发布 internal/plugin(fiber.ts:299-303)。 */
    @Test
    void internalPluginEventFiresOnFiberCreation() throws Exception {
        Context root = new Context();
        AtomicReference<Fiber> published = new AtomicReference<>();
        root.on("internal/plugin", (ctx, args) -> { published.set((Fiber) args[0]); return null; });

        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> null), null);
        f.await().join();
        assertThat(published.get()).isSameAs(f);
        f.dispose().join();
    }

    /** 状态变更即 emit internal/status(fiber.ts:587-595)。 */
    @Test
    void internalStatusEmittedOnStateTransitions() throws Exception {
        Context root = new Context();
        List<Fiber> seenFibers = new ArrayList<>();
        List<FiberState> oldStates = new ArrayList<>();
        root.on("internal/status", (ctx, args) -> {
            seenFibers.add((Fiber) args[0]);
            oldStates.add((FiberState) args[1]);
            return null;
        });

        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> null), null);
        f.await().join();
        assertThat(seenFibers).contains(f);
        // internal/status 携带旧状态;同步插件的过渡序列是确定的
        assertThat(oldStates).containsExactly(FiberState.PENDING, FiberState.LOADING);

        f.dispose().join();
        assertThat(oldStates).containsExactly(FiberState.PENDING, FiberState.LOADING, FiberState.ACTIVE, FiberState.PENDING);
        assertThat(f.state).isEqualTo(FiberState.DISPOSED);
    }

    /** 服务绑定发布 internal/service(reflect.ts:330-334)。 */
    @Test
    void internalServiceEventFiresOnProvide() throws Exception {
        Context root = new Context();
        AtomicReference<Context> seenCtx = new AtomicReference<>();
        AtomicReference<String> seenName = new AtomicReference<>();
        AtomicReference<Object> seenValue = new AtomicReference<>();
        root.on("internal/service", (ctx, args) -> {
            seenCtx.set(ctx);
            seenName.set((String) args[0]);
            seenValue.set(args[1]);
            return null;
        });

        String svc = "hello";
        root.provide("svc", svc);
        assertThat(seenName.get()).isEqualTo("svc");
        assertThat(seenValue.get()).isEqualTo(svc);
        assertThat(seenCtx.get()).isNotNull();   // filter-scoped child context
    }

    /** 公开事件派发前发布 internal/dispatch(events.ts:169)。 */
    @Test
    void internalDispatchReportedForPublicEvents() throws Exception {
        Context root = new Context();
        List<String> dispatched = new ArrayList<>();
        root.on("internal/dispatch", (ctx, args) -> {
            String mode = (String) args[0];
            String name = (String) args[1];
            Object[] listenerArgs = (Object[]) args[2];
            Object thisArg = args[3];
            dispatched.add(mode + ":" + name + ":" + listenerArgs.length + ":" + (thisArg != null));
            return null;
        });

        root.emit("public-event", "x");
        assertThat(dispatched).containsExactly("emit:public-event:1:false");

        // internal 事件本身不触发 internal/dispatch(避免自循环)
        root.emit("internal/anything", "y");
        assertThat(dispatched).containsExactly("emit:public-event:1:false");
    }

    /** mixin 转发 + bind 语义(reflect.ts:364-390)。 */
    @Test
    void mixinForwardsMembersAndBinds() throws Throwable {
        Context root = new Context();
        Counter counter = new Counter();
        root.provide("counter", counter);

        Disposable mixin = root.mixin("counter", List.of("count", "next", "current"));

        // 字段转发读取
        assertThat(root.<Integer>get("count")).isEqualTo(0);
        // 方法成员绑定到服务实例:调用即操作 service,不脱绑
        Object next = root.get("next");
        assertThat(next).isInstanceOf(MethodHandle.class);
        ((MethodHandle) next).invokeWithArguments();
        assertThat(counter.count).isEqualTo(1);
        // set 经 accessor 写回服务
        root.set("count", 42);
        assertThat(counter.count).isEqualTo(42);

        // 重命名 mixin:source-key → ctx-key
        Disposable renamed = root.mixin("counter", Map.of("count", "total"));
        assertThat(root.<Integer>get("total")).isEqualTo(42);

        mixin.dispose();
        renamed.dispose();
        assertThat((Object) root.get("count")).isNull();   // accessor 已移除
        assertThat((Object) root.get("total")).isNull();
    }

    /** mixin 转发接口方法(含 default 方法):findPublicMethod 兜底扫接口,绑定到服务实例。 */
    @Test
    void mixinForwardsInterfaceDefaultMethods() throws Throwable {
        Context root = new Context();
        GreeterService svc = new GreeterService();
        root.provide("greeter", svc);

        Disposable mixin = root.mixin("greeter", List.of("greet", "tag"));

        Object greet = root.get("greet");
        assertThat(greet).isInstanceOf(MethodHandle.class);
        assertThat(((MethodHandle) greet).invokeWithArguments()).isEqualTo("hello from svc");
        Object tag = root.get("tag");
        assertThat(((MethodHandle) tag).invokeWithArguments()).isEqualTo("svc");

        mixin.dispose();
        assertThat((Object) root.get("greet")).isNull();
    }
}
