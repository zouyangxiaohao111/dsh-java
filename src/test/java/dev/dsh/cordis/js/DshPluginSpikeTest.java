package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike:能否把 deepseek-harness 的真实 dsh 插件(零移植)经 GraalJS 桥直接加载运行。
 *
 * <p>候选 1:{@code @deepseek-ai/dsh-session-stats}(packages/session/session-stats)。
 * 插件源码只做了两件事——(a) type-strip(Node 的 stripTypeScriptTypes,erasable-only),(b) 把
 * strip 后的 ESM 机械转 CJS(import/export → require/module.exports,相对路径 .ts→.js)。
 * value imports:zod(真实 npm 包)、@deepseek-ai/dsh-llm/message(CJS shim,实现 isTokenDelta)、
 * ./projection.js(本地)。@deepseek-ai/cordis 与 @deepseek-ai/dsh-session-projection 均为
 * type-only,strip 后消失。
 *
 * <p>Java 侧提供插件 inject 的 {@code sessionProjections} 服务桩(register 捕获投影定义),经
 * ServiceProxy 暴露给 JS;ctx shim 的 Proxy 回退把 {@code ctx.sessionProjections} 路由到
 * {@code bridge.get} → Java 服务。触发断言:插件 apply 后注册了一个 key='sessionStats' 的投影,
 * 且该投影的 fold 逻辑(step/start→chunk→message→step/end)在真实 JS 中跑出预期计数。
 *
 * <p><b>前置</b>:{@code src/test/resources/dsh-plugin} 下需已装真实 zod(源码里的 value import,
 * 经 GraalJS commonjs-require 解析):{@code cd src/test/resources/dsh-plugin && npm install zod@^4.4.3}
 * (zod 目录已 gitignore)。手写的 {@code @deepseek-ai/*} CJS shim(dsh-llm / schemastery)是
 * GraalJS 独立版无法 require dsh 包 ESM 输出的临时替身,随测试提交。
 */
class DshPluginSpikeTest {

    /** sessionProjections 最小桩:捕获 register 进来的投影定义(JS Value)。 */
    public static final class SessionProjectionsStub {
        public final List<Value> definitions = new ArrayList<>();
        public Value register(Value definition) {
            definitions.add(definition);
            return null;
        }
    }

    private static Path resource(String rel) {
        return Path.of("src/test/resources/dsh-plugin").resolve(rel).toAbsolutePath();
    }

    @Test
    void sessionStatsPluginLoadsAndFoldsThroughBridge() throws Exception {
        Context root = new Context();
        // 先 provide 插件 inject 的服务,使 fiber 依赖解析成功、apply 得以执行(与 cordis 一致)
        SessionProjectionsStub projections = new SessionProjectionsStub();
        root.provide("sessionProjections", projections);

        try (GraalJsHost host = new GraalJsHost(resource("node_modules"))) {
            // 加载 strip+转 CJS 后的真实插件源码(相对 node_modules 目录下的本地模块与依赖)
            Value plugin = host.loadModuleValue(resource("session-stats/index.js"));
            root.plugin(new JsPluginAdapter(host, host.module(plugin)), null);

            // apply 已跑:插件把 sessionStats 投影单元注册进 sessionProjections
            assertThat(projections.definitions).hasSize(1);
            Value def = projections.definitions.get(0);
            assertThat(def.getMember("key").asString()).isEqualTo("sessionStats");

            // 触发投影 fold:一个 step,带一个 text-delta chunk 与一次 assistant/message
            Value state = def.getMember("init").execute();
            state = def.getMember("apply").execute(state, event(host, "step/start", "{ turn: 1, step: 1 }", 0));
            state = def.getMember("apply").execute(state, event(host, "assistant/chunk",
                    "{ turn: 1, step: 1, chunk: { type: 'text-delta', text: 'hi' } }", 100));
            state = def.getMember("apply").execute(state, event(host, "assistant/message",
                    "{ turn: 1, step: 1, usage: { outputTokens: 5 } }", 200));
            state = def.getMember("apply").execute(state, event(host, "step/end", "{ turn: 1 }", 200));

            Value view = def.getMember("view").execute(state);
            assertThat(view.getMember("turns").asInt()).isEqualTo(1);
            assertThat(view.getMember("steps").asInt()).isEqualTo(1);
            assertThat(view.getMember("llmMs").asLong()).isEqualTo(200);    // 0 → 200
            assertThat(view.getMember("ttftSteps").asInt()).isEqualTo(1);
            assertThat(view.getMember("ttftMs").asLong()).isEqualTo(100);   // 0 → 100
            assertThat(view.getMember("decodeMs").asLong()).isEqualTo(100); // 100 → 200
            assertThat(view.getMember("decodeTokens").asInt()).isEqualTo(5);
        }
        root.fiber.dispose().join();
    }

    @Test
    void sessionStatsEmptyDeltaDoesNotCountAsFirstToken() throws Exception {
        Context root = new Context();
        SessionProjectionsStub projections = new SessionProjectionsStub();
        root.provide("sessionProjections", projections);

        try (GraalJsHost host = new GraalJsHost(resource("node_modules"))) {
            Value plugin = host.loadModuleValue(resource("session-stats/index.js"));
            root.plugin(new JsPluginAdapter(host, host.module(plugin)), null);
            Value def = projections.definitions.get(0);

            // 空 delta 心跳(heartbeat)不算首 token:isTokenDelta 返回 false
            Value state = def.getMember("init").execute();
            state = def.getMember("apply").execute(state, event(host, "step/start", "{ turn: 1, step: 1 }", 0));
            state = def.getMember("apply").execute(state, event(host, "assistant/chunk",
                    "{ turn: 1, step: 1, chunk: { type: 'text-delta', text: '' } }", 100));
            state = def.getMember("apply").execute(state, event(host, "assistant/message",
                    "{ turn: 1, step: 1, usage: {} }", 200));
            state = def.getMember("apply").execute(state, event(host, "step/end", "{ turn: 1 }", 200));

            Value view = def.getMember("view").execute(state);
            assertThat(view.getMember("ttftSteps").asInt()).isEqualTo(0);   // 空 delta 未计数
            assertThat(view.getMember("ttftMs").asLong()).isEqualTo(0);
            assertThat(view.getMember("decodeMs").asLong()).isEqualTo(0);   // 无 outputTokens,不计 decode
            assertThat(view.getMember("steps").asInt()).isEqualTo(1);
        }
        root.fiber.dispose().join();
    }

    /** 在 GraalJS 里构造一个 dsh 会话事件: { type, data, time }。 */
    private static Value event(GraalJsHost host, String type, String dataObjLiteral, long time) {
        return host.evalValue("({ type: '" + type + "', data: " + dataObjLiteral + ", time: " + time + " })");
    }

    // ---- 候选 2:@deepseek-ai/dsh-repeat-tool-reminder ----

    /**
     * 插件 apply 注册两个监听器(tools/post-execute 与 agent/pre-step),经 ctx.on 走桥的
     * next 链。测试在插件之后注册 Java "最终决策" 监听器,使 next() 有落点;用同一个
     * exec 对象(同一 agent 引用)连发相同 tool call,断言阈值命中时插件经 dsh-llm 的
     * createUserMessage 注入提醒(记录在 globalThis.__dshCreatedUserMessages 观测钩子)。
     */
    @Test
    void repeatToolReminderPluginInjectsRemindersThroughBridge() throws Exception {
        Context root = new Context();
        try (GraalJsHost host = new GraalJsHost(resource("node_modules"))) {
            host.evalValue("globalThis.__dshCreatedUserMessages = []");

            Value plugin = host.loadModuleValue(resource("repeat-tool-reminder/index.js"));
            // schemastery 校验被桥跳过(不填默认),config 需显式给全
            Value config = host.evalValue("({ thresholds: [3, 5, 8], include: [], exclude: [], argumentsPreviewChars: 500 })");
            root.plugin(new JsPluginAdapter(host, host.module(plugin)), config);

            // 插件监听器之后注册的 Java "最终决策" 落点:next() 委派到它
            root.on("tools/post-execute", (c, args) -> java.util.Map.of("kind", "pass"));
            root.on("agent/pre-step", (c, args) -> null);

            // 同一个 exec(同一 agent 对象引用)连发 → WeakMap 链累积
            Value exec = host.evalValue("({ agent: { id: 'a1' }, name: 'write_file', arguments: { path: '/tmp/a', content: 'x' } })");
            root.emit("tools/post-execute", exec, null);
            root.emit("tools/post-execute", exec, null);
            root.emit("tools/post-execute", exec, null);   // 第 3 次 → gentle 提醒

            assertThat(host.evalValue("globalThis.__dshCreatedUserMessages.length").asLong()).isEqualTo(1);
            Value msg = host.evalValue("globalThis.__dshCreatedUserMessages[0]");
            assertThat(msg.getMember("role").asString()).isEqualTo("user");
            Value source = msg.getMember("source");
            assertThat(source.getMember("kind").asString()).isEqualTo("plugin");
            assertThat(source.getMember("plugin").asString()).isEqualTo("repeat-tool-reminder");
            assertThat(source.getMember("form").asString()).isEqualTo("notice");
            assertThat(source.getMember("summary").asString()).isEqualTo("write_file × 3");
            String text = msg.getMember("content").getArrayElement(0).getMember("text").asString();
            assertThat(text).contains("You are repeating the exact same tool call");

            // 第 4 次不在阈值 → 不新增;第 5 次命中 → detailed 提醒(点名 tool/count/参数)
            root.emit("tools/post-execute", exec, null);
            assertThat(host.evalValue("globalThis.__dshCreatedUserMessages.length").asLong()).isEqualTo(1);
            root.emit("tools/post-execute", exec, null);
            assertThat(host.evalValue("globalThis.__dshCreatedUserMessages.length").asLong()).isEqualTo(2);
            String text2 = host.evalValue("globalThis.__dshCreatedUserMessages[1].content[0].text").asString();
            assertThat(text2).contains("Repeated tool call detected");
            assertThat(text2).contains("tool: write_file");
            assertThat(text2).contains("consecutive_calls: 5");

            // 用户插话(agent/pre-step 带 user 消息)→ 链重置;再 3 次才提醒
            Value pre = host.evalValue("({ agent: { id: 'a1' }, messages: [{ source: { kind: 'user' } }] })");
            root.emit("agent/pre-step", pre);
            root.emit("tools/post-execute", exec, null);
            root.emit("tools/post-execute", exec, null);
            root.emit("tools/post-execute", exec, null);
            assertThat(host.evalValue("globalThis.__dshCreatedUserMessages.length").asLong()).isEqualTo(3);
        }
        root.fiber.dispose().join();
    }
}
