package dev.dsh.cordis.testkit;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.FiberState;
import dev.dsh.cordis.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 插件作者测试基类(M6 §5 PluginTestKit)。外部插件工程通过
 * {@code testImplementation("dev.dsh:dsh-testkit")} 引入,写 {@code @Test} 本地跑插件:
 * 每个测试方法一个独立 root {@link Context},自动 new Context、加载插件并等待激活、
 * 测试结束逆序卸载 + 关根 —— 测试方法之间零残留。
 *
 * <p>用法:
 * <pre>{@code
 * class GreeterPluginTest extends PluginTestKit {
 *     @Test
 *     void greets() {
 *         Fiber f = load(PluginSpec.<Void>of((ctx, cfg) -> {
 *             ctx.on("greet", (c, args) -> ctx.emit("greeted", "hello " + args[0]));
 *             return null;
 *         }).name("greeter"));
 *         assertThat(f.state()).isEqualTo(FiberState.ACTIVE);
 *         ctx.emit("greet", "world");   // 断言 "greeted" 事件
 *     }
 * }
 * }</pre>
 *
 * <p>纯核心依赖:不引入 loader / js-host / GraalJS。插件 apply 内 new Context 同款用法
 * 即核心可测面({@link Context#plugin} = {@code Registry.plugin},与 loader 注册语义一致)。
 * 只 import 核心公共 API(dev.dsh.cordis.*)即可写测试 —— 见 M6 §4 "核心公共面干净"。
 */
public abstract class PluginTestKit {

    /** 当前测试的 root context(内置 services 已装好);setUp 前 / tearDown 后为 null。 */
    protected Context ctx;

    private final List<Fiber> loaded = new ArrayList<>();

    /** 每个测试前创建独立 root context。 */
    @BeforeEach
    protected void setUp() {
        ctx = new Context();
    }

    /** 逆序卸载本测试加载的插件 fiber,再关闭 root(未注册到 {@link #load} 的嵌套插件由
     *  root 的 effect 级联一并清理)。 */
    @AfterEach
    protected void tearDown() {
        if (ctx == null) return;
        for (int i = loaded.size() - 1; i >= 0; i--) {
            Fiber f = loaded.get(i);
            if (f.state != FiberState.DISPOSED) {
                f.dispose().join();
            }
        }
        loaded.clear();
        ctx.fiber.shutdown().join();
        ctx = null;
    }

    /** 注册插件并等待激活。apply 抛错 / 异步 apply 失败在此以原异常抛出(解包
     *  {@link CompletionException});依赖未满足时 fiber 保持 PENDING,不抛。 */
    protected <T> Fiber load(Plugin<T> plugin, T config) {
        ensureCtx();
        Fiber f = ctx.plugin(plugin, config);
        loaded.add(f);
        try {
            f.await().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw e;
        }
        return f;
    }

    /** 注册插件(无配置)并等待激活。 */
    protected <T> Fiber load(Plugin<T> plugin) {
        return load(plugin, null);
    }

    /** 断言 fiber 处于 {@link FiberState#ACTIVE},否则抛 {@link AssertionError}(附 apply 错误)。 */
    protected void assertActive(Fiber fiber) {
        if (fiber == null) {
            throw new AssertionError("expected an active fiber but got null");
        }
        if (fiber.state == FiberState.FAILED) {
            throw new AssertionError("plugin failed to activate: " + fiber.error(), fiber.error());
        }
        org.junit.jupiter.api.Assertions.assertEquals(FiberState.ACTIVE, fiber.state, "fiber state");
    }

    private void ensureCtx() {
        if (ctx == null) {
            throw new IllegalStateException(
                    "context is not initialized — call load() inside a @Test method");
        }
    }
}
