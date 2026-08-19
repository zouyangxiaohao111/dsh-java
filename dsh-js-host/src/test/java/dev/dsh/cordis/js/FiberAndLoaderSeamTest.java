package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-8 B+C:ctx.fiber seam 跨桥(agent-loop 同形)+ ctx.loader 服务(hmr 同形)。
 *
 * <p><b>B(ctx.fiber)</b>:worker 侧 {@code ctx.fiber} 现在是<b>可调 live 代理</b>(node-bridge
 * {@code makeFiberProxy}),{@code assertActive()} 与 {@code state} 经 ctxCall 桥到 Java 核心的
 * fiber 状态 —— agent-loop {@code prepare} 的 {@code ownerCtx.fiber.assertActive()} 不再崩
 * "assertActive is not a function"。{@code parent} 保持自终止链(fiber.parent.fiber === fiber),
 * hasLifecycleAncestor 类身份比较链首次迭代即返回 false。
 *
 * <p><b>C(ctx.loader)</b>:Java 核心(dsh-cordis {@code LoaderService})提供 {@code loader} 服务,
 * 暴露 hmr 构造器真正用到的面:{@code ctx.loader.internal} 真值(hmr 的
 * {@code --expose-internals} 守卫)+ {@code ctx.baseUrl}(hmr 的
 * {@code new URL(config.base || '.', ctx.baseUrl)})。同形 Service 子类 apply 不再缺 ctx.loader。
 *
 * <p>前置:系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class FiberAndLoaderSeamTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private Path writePlugin(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    // ---- B:ctx.fiber live 代理(agent-loop 同形)----

    /**
     * apply 里 {@code ctx.fiber.assertActive()} 不崩(fiber 存活),{@code ctx.fiber.state}
     * 读到 Java 核心的真实数值状态(apply 期间 = LOADING),{@code parent} 终止链成立,
     * 代理可调。目标行同形:AgentLoop.prepare 的 ownerCtx.fiber.assertActive() 与
     * FactoryOwnership.isActive() 的 this.fiber.state。
     */
    @Test
    void ctxFiberAssertActiveAndStateCrossBridge() throws Exception {
        Path plugin = writePlugin("fiber-consumer.cjs", """
                const { FiberState } = require('@deepseek-ai/cordis')
                module.exports = {
                  name: 'fiber-consumer',
                  apply(ctx) {
                    let thrown = null
                    let returned = 'none'
                    try { returned = String(ctx.fiber.assertActive()) } catch (e) { thrown = String(e) }
                    const state = ctx.fiber.state
                    const stateIsLoading = state === FiberState.LOADING
                    const stateIsNumber = typeof state === 'number'
                    const parentIsSelf = ctx.fiber.parent.fiber === ctx.fiber
                    const callable = typeof ctx.fiber === 'function'
                    ctx.provide('fiberProbe', {
                      thrown, returned, state,
                      stateIsLoading, stateIsNumber, parentIsSelf, callable,
                    })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);

            Map<String, Object> probe = map(root.get("fiberProbe"));
            assertThat(probe.get("thrown")).isNull();                 // assertActive 不抛(null 表示无异常)
            assertThat(probe.get("returned")).isEqualTo("undefined"); // 返回正确(undefined)
            assertThat(probe.get("stateIsNumber")).isEqualTo(true);   // state 是数值
            assertThat(probe.get("stateIsLoading")).isEqualTo(true);  // apply 期间 LOADING(1)
            assertThat(probe.get("parentIsSelf")).isEqualTo(true);    // 终止链仍成立
            assertThat(probe.get("callable")).isEqualTo(true);        // 可调 fiber 代理
        } finally {
            root.fiber.dispose().join();
        }
    }

    // ---- C:ctx.loader 服务(hmr 同形)----

    /**
     * hmr 同形:Service 子类插件 inject {@code ['loader']},构造器读 {@code ctx.loader.internal}
     * (hmr 的 --expose-internals 守卫)+ {@code new URL(config.base || '.', ctx.baseUrl)}。
     * Java 核心提供 loader 后 apply 不再缺 ctx.loader,服务注册成功、probe 可读。
     */
    @Test
    void hmrLoaderSurfaceIsomorphicApply() throws Exception {
        Path plugin = writePlugin("hmr-like.cjs", """
                const { Service } = require('@deepseek-ai/cordis')
                module.exports = class HmrLike extends Service {
                  static inject = ['loader']
                  constructor(ctx, config) {
                    super(ctx, 'hmrLike')
                    if (!ctx.loader.internal) throw new Error('--expose-internals is required for HMR service')
                    this.internal = ctx.loader.internal
                    this.baseDir = new URL(config.base || '.', ctx.baseUrl).href
                    ctx.provide('hmrProbe', {
                      hasInternal: !!this.internal,
                      version: this.internal.version,
                      baseUrl: ctx.baseUrl,
                      baseDir: this.baseDir,
                    })
                  }
                }
                """);
        Context root = new Context();
        root.baseUrl = tmp.toUri().toString();                        // hmr 的 base 解析基准
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("base", ".");
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), config);

            Map<String, Object> probe = map(root.get("hmrProbe"));
            assertThat(probe.get("hasInternal")).isEqualTo(true);                 // ctx.loader.internal 真值
            assertThat(probe.get("version")).isEqualTo("v1");                     // internal.version 可读
            assertThat(probe.get("baseUrl")).isEqualTo(root.baseUrl);             // ctx.baseUrl 跨桥
            assertThat((String) probe.get("baseDir")).startsWith("file://");       // new URL('.', baseUrl) 可解析
        } finally {
            root.fiber.dispose().join();
        }
    }

    /**
     * loader 服务对跨 worker 读方可见:一个 worker 提供 loader 依赖方形状(读 ctx.loader),
     * Java 核心提供的 LoaderService 经 svc 句柄跨桥,成员读(version)可路由回 Java。
     */
    @Test
    void loaderServiceReadableFromWorkerThroughBridge() throws Exception {
        Path plugin = writePlugin("loader-reader.cjs", """
                module.exports = {
                  name: 'loader-reader',
                  inject: ['loader'],
                  apply(ctx) {
                    const has = ctx.loader !== undefined
                    const internal = ctx.loader.internal
                    ctx.provide('loaderProbe', { has, hasInternal: !!internal })
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);

            Map<String, Object> probe = map(root.get("loaderProbe"));
            assertThat(probe.get("has")).isEqualTo(true);             // ctx.loader 存在(不再缺)
            assertThat(probe.get("hasInternal")).isEqualTo(true);     // internal 面真值
        } finally {
            root.fiber.dispose().join();
        }
    }
}
