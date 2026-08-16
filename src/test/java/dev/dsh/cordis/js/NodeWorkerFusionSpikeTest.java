package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 §2.4 融合证明 spike:一个 GraalJS 跑不了的 dsh 插件(依赖 ESM 的
 * {@code @deepseek-ai/dsh-llm/message} 与 zod)走 ③ NodeWorkerJsHost 在真 Node 里跑。
 *
 * <p>与 {@link DshPluginSpikeTest} 的 GraalJS 路径对照:那边插件源码必须先做
 * type-strip + ESM→CJS 机械转换,{@code @deepseek-ai/*} 依赖还得手写 CJS shim
 * (GraalJS 独立版 CommonJS require 无法加载 dsh 包的 ESM 输出)。本测试加载
 * {@code src/test/resources/dsh-plugin/esm/} 下的<b>原形 ESM</b>插件(import/export,
 * {@code "type":"module"}),真 Node 直接吃,不需要 CJS shim、不需要 ESM→CJS 转换;
 * zod 是真 npm 包,{@code @deepseek-ai/dsh-llm/message} 是 ESM 模块,均被 Node 原生解析。
 *
 * <p>ctx 经 RPC 往返 Java 核心:插件 apply 里 {@code ctx.sessionProjections.register(def)}
 * → ctx shim 的 Proxy 回退 {@code ctx.get} → RPC → Java 的 {@code sessionProjections}
 * 服务桩(经 {@link NodeWorkerJsHost#invokeService} 反射接收),定义中的 fold 函数
 * (init/apply/view)以 fn 句柄捕获。Java 触发:经 {@code invokeFn} 逐事件驱动 fold,
 * 断言 view 汇总 —— 与 GraalJS spike 相同的触发/断言面,换到真 Node 执行。
 *
 * <p><b>前置</b>:{@code src/test/resources/dsh-plugin} 下需已装真实 zod(value import),
 * 与 DshPluginSpikeTest 相同:{@code cd src/test/resources/dsh-plugin && npm install zod@^4.4.3}
 * (zod 目录已 gitignore)。系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class NodeWorkerFusionSpikeTest {

    /**
     * sessionProjections 最小桩:捕获 register 进来的投影定义(经 RPC 反序列化为 Map,
     * 其中 init/apply/view 是跨桥的 fn 句柄 {@link NodeRef})。Java 用这些句柄驱动 fold。
     */
    public static final class SessionProjectionsStub {
        public final List<Map<String, Object>> definitions = new ArrayList<>();
        public Object register(Map<String, Object> definition) {
            definitions.add(definition);
            return null;
        }
    }

    /** 无 node 可执行时整个测试类 skip(P3 可移植性)。 */
    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private static Path resource(String rel) {
        return Path.of("src/test/resources/dsh-plugin/esm").resolve(rel).toAbsolutePath();
    }

    @Test
    void esmSessionStatsPluginLoadsAndFoldsThroughNodeWorker() throws Exception {
        Context root = new Context();
        SessionProjectionsStub projections = new SessionProjectionsStub();
        root.provide("sessionProjections", projections);

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            // 真 Node 直接吃 ESM:插件是 import/export 原形(无 CJS transform、无 CJS shim)
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(resource("session-stats/index.js")));
            assertThat(adapter.name()).isEqualTo("session-stats");
            assertThat(adapter.inject()).containsExactly("sessionProjections");
            root.plugin(adapter, null);

            // apply 已在 Node worker 内跑:ctx.sessionProjections → RPC → Java 桩捕获投影定义
            assertThat(projections.definitions).hasSize(1);
            Map<String, Object> def = projections.definitions.get(0);
            assertThat(def.get("key")).isEqualTo("sessionStats");
            NodeRef init = (NodeRef) def.get("init");
            NodeRef apply = (NodeRef) def.get("apply");
            NodeRef view = (NodeRef) def.get("view");
            assertThat(init.kind()).isEqualTo("fn");
            assertThat(apply.kind()).isEqualTo("fn");
            assertThat(view.kind()).isEqualTo("fn");

            // Java 触发 fold:一个 step,带一个 text-delta chunk 与一次 assistant/message
            Map<String, Object> state = cast(host.invokeFn(init, List.of()));
            state = cast(host.invokeFn(apply, List.of(state, event("step/start", obj("turn", 1, "step", 1), 0))));
            state = cast(host.invokeFn(apply, List.of(state, event("assistant/chunk",
                    obj("turn", 1, "step", 1, "chunk", obj("type", "text-delta", "text", "hi")), 100))));
            state = cast(host.invokeFn(apply, List.of(state, event("assistant/message",
                    obj("turn", 1, "step", 1, "usage", obj("outputTokens", 5)), 200))));
            state = cast(host.invokeFn(apply, List.of(state, event("step/end", obj("turn", 1), 200))));

            Map<String, Object> out = cast(host.invokeFn(view, List.of(state)));
            assertThat(out.get("turns")).isEqualTo(1L);
            assertThat(out.get("steps")).isEqualTo(1L);
            assertThat(out.get("llmMs")).isEqualTo(200L);    // 0 → 200
            assertThat(out.get("ttftSteps")).isEqualTo(1L);
            assertThat(out.get("ttftMs")).isEqualTo(100L);   // 0 → 100
            assertThat(out.get("decodeMs")).isEqualTo(100L); // 100 → 200
            assertThat(out.get("decodeTokens")).isEqualTo(5L);
        }
        root.fiber.dispose().join();
    }

    @Test
    void esmSessionStatsEmptyDeltaDoesNotCountAsFirstToken() throws Exception {
        Context root = new Context();
        SessionProjectionsStub projections = new SessionProjectionsStub();
        root.provide("sessionProjections", projections);

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(resource("session-stats/index.js")));
            root.plugin(adapter, null);
            Map<String, Object> def = projections.definitions.get(0);
            NodeRef init = (NodeRef) def.get("init");
            NodeRef apply = (NodeRef) def.get("apply");
            NodeRef view = (NodeRef) def.get("view");

            // 空 delta 心跳(heartbeat)不算首 token:ESM 的 isTokenDelta 返回 false
            Map<String, Object> state = cast(host.invokeFn(init, List.of()));
            state = cast(host.invokeFn(apply, List.of(state, event("step/start", obj("turn", 1, "step", 1), 0))));
            state = cast(host.invokeFn(apply, List.of(state, event("assistant/chunk",
                    obj("turn", 1, "step", 1, "chunk", obj("type", "text-delta", "text", "")), 100))));
            state = cast(host.invokeFn(apply, List.of(state, event("assistant/message",
                    obj("turn", 1, "step", 1, "usage", obj()), 200))));
            state = cast(host.invokeFn(apply, List.of(state, event("step/end", obj("turn", 1), 200))));

            Map<String, Object> out = cast(host.invokeFn(view, List.of(state)));
            assertThat(out.get("ttftSteps")).isEqualTo(0L);  // 空 delta 未计数
            assertThat(out.get("ttftMs")).isEqualTo(0L);
            assertThat(out.get("decodeMs")).isEqualTo(0L);   // 无 outputTokens,不计 decode
            assertThat(out.get("steps")).isEqualTo(1L);
        }
        root.fiber.dispose().join();
    }

    /** 跨桥回来的 fold 中间/结果值都是宿主 Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object v) {
        return (Map<String, Object>) v;
    }

    /** 构造一个 dsh 会话事件:{ type, data, time }(纯 JSON,经 RPC 发给 Node worker)。 */
    private static Map<String, Object> event(String type, Map<String, Object> data, long time) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        ev.put("data", data);
        ev.put("time", time);
        return ev;
    }

    /** 键值对 → Map(事件 data / 嵌套对象)。 */
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
