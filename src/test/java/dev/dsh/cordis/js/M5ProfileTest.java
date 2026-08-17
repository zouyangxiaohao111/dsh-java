package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;
import dev.dsh.cordis.loader.Entry;
import dev.dsh.cordis.loader.LoadedPlugin;
import dev.dsh.cordis.loader.PluginLoaderService;
import dev.dsh.demo.SeamPlugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 验收 §4:一份 cordis.yml 混排 Java + dsh JS 插件,全部加载进 registry。
 *
 * <p>profile = {@code src/test/resources/m5/cordis.yml}:
 * <ul>
 *   <li>Java 插件(2):{@code java:dev.dsh.demo.CounterPlugin}、{@code java:dev.dsh.demo.SeamPlugin}
 *       (提供 {@code llm} 服务 seam);</li>
 *   <li>dsh JS 插件(3,node: 宿主):require-probe / agent-loop / fusion
 *       (复用 agent-fusion overlay 的 node_modules);fusion 内部同 worker mount 真实
 *       session-stats({@code mountSessionStats},见 fusion-plugin);agent-loop 内部再注册
 *       systemPrompt / agents / sessions / tools 服务(后者为真实 dsh-tools
 *       ToolRuntime,由 worker 经桥 provide 进 Java 核心,M5-NEEDS-tools)。注:
 *       session-stats 不单列为 node: 条目 —— 它 inject fusion 的
 *       {@code sessionProjections},而每个 node: 插件跑独立 worker,fusion 提供的值携带
 *       worker 内 fn 句柄跨 worker 无法反序列化(基线静默吞掉该 apply 失败);跨 worker
 *       JS→JS 组合不在 M5 范围。</li>
 * </ul>
 *
 * <p>覆盖(M5 验收 1/3):
 * <ol>
 *   <li>混排全部注册进 registry:断言 {@code registry.size}、宿主选择(Java→JAVA、dsh→NODE);</li>
 *   <li>组合:Java 插件 provide {@code llm} + worker 内真实 dsh-tools provide
 *       {@code tools} → 真实 dsh agent-loop(node:) 消费(经 ctx,跨桥 RPC)——
 *       跑一轮文本 + 一轮工具,断言真实 echo 工具执行结果;</li>
 *   <li>Java → JS:真实 dsh system-prompt 的 {@code assemble()} 从 Java 触发并合并回 Java。</li>
 * </ol>
 *
 * <p>驱动 JS fn 句柄需 {@code NodeWorkerJsHost.invokeFn}(js 包私有),故与 M4 融合测试同包。
 * node 可执行缺失时整个测试 skip(与 NodeEnv.assumeNode 同语义)。
 */
class M5ProfileTest {

    @BeforeEach
    void assumeNode() {
        Assumptions.assumeTrue(nodeAvailable(), "skipped: no node executable on PATH (set NODE to override)");
    }

    private static Path profile() {
        return Path.of("src/test/resources/m5/cordis.yml").toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object v) {
        return (List<Map<String, Object>>) v;
    }

    @Test
    void mixedProfileRegistersAllPluginsAndComposesAcrossHosts() throws Exception {
        Path yml = profile();
        assertThat(Files.isRegularFile(yml)).as("m5 profile exists").isTrue();

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            // ---- ① 混排加载:registry 全量 + 宿主选择 ----
            List<LoadedPlugin> loaded = loader.load(yml);
            // loader 恰好管理配置声明的 5 个插件;registry 还含 dsh 包经 ctx.inject
            // 注册的匿名插件(如 AgentRegistry 的 typert lookup),故 size >= 5 且逐个在册。
            assertThat(root.registry.size()).isGreaterThanOrEqualTo(5);
            assertThat(loaded).hasSize(5);
            assertThat(loader.entries()).extracting(Entry::name)
                    .containsExactly("counter", "seams", "require-probe",
                            "agent-loop", "fusion");
            assertThat(root.registry.keys()).extracting(Plugin::name)
                    .contains("counter", "seams", "require-probe", "agent-loop-driver",
                            "dsh-fusion-spike");

            // Java 插件 → JAVA 宿主;dsh JS 插件 → NODE 宿主(NodeWorkerJsHost)
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.JAVA);
            assertThat(loaded.get(1).kind()).isEqualTo(HostKind.JAVA);
            java.util.List<JsHost> nodeHosts = new java.util.ArrayList<>();
            for (int i = 2; i < loaded.size(); i++) {
                assertThat(loaded.get(i).kind()).isEqualTo(HostKind.NODE);
                assertThat(loaded.get(i).host()).isInstanceOf(NodeWorkerJsHost.class);
                nodeHosts.add(loaded.get(i).host());
            }
            // M5 深化 ①:每 node: 插件独立 worker —— 三个 dsh 插件持有互不相同的
            // NodeWorkerJsHost(不同 Node 进程),跨插件 ctx 调用并行互不阻塞。
            assertThat(nodeHosts).doesNotHaveDuplicates();

            // Java 插件提供的服务在 registry
            assertThat((Object) root.get("counter")).isNotNull();
            SeamPlugin.Llm llm = (SeamPlugin.Llm) root.get("llm");
            assertThat(llm).isNotNull();
            // tools 由 worker 内真实 @deepseek-ai/dsh-tools ToolRuntime 经桥 provide
            // 进 Java 核心(M5-NEEDS-tools,worker→Java 组合方向),非 Java seam。
            assertThat((Object) root.get("tools")).isNotNull();

            // dsh JS 插件注册的服务可见(经桥);systemPrompt 由 agent-loop 内部 SystemPrompt 提供
            assertThat((Object) root.get("systemPrompt")).isNotNull();
            assertThat((Object) root.get("agents")).isNotNull();          // agent-loop 内部 AgentRegistry
            assertThat((Object) root.get("agentLoop")).isNotNull();
            assertThat((Object) root.get("agentLoopProbe")).isNotNull();
            assertThat((Object) root.get("requireProbe")).isNotNull();
            assertThat((Object) root.get("sessionProjections")).isNotNull(); // fusion
            assertThat((Object) root.get("fusionProbe")).isNotNull();

            // ---- ② 组合:Java llm/tools seam → 真实 dsh agent-loop 消费 ----
            NodeWorkerJsHost loopHost = (NodeWorkerJsHost) loaded.get(3).host();
            Map<String, Object> probe = map(root.get("agentLoopProbe"));
            NodeRef runTurn = (NodeRef) probe.get("runTurn");
            NodeRef getSessionLog = (NodeRef) probe.get("getSessionLog");

            // 预置 llm.stream 响应:turn1 文本、turn2 工具调用 + 收尾文本
            llm.response(SeamPlugin.chunks(
                    SeamPlugin.textChunk("Hello from M5 profile"), SeamPlugin.finishChunk()));
            llm.response(SeamPlugin.chunks(
                    SeamPlugin.toolCallChunk("call-1", "echo", "{\"msg\":\"hi\"}"), SeamPlugin.finishChunk()));
            llm.response(SeamPlugin.chunks(
                    SeamPlugin.textChunk("tool result acknowledged"), SeamPlugin.finishChunk()));

            // turn 1(文本):Java llm.stream 被真实 dsh 机器消费
            Map<String, Object> snap = map(loopHost.invokeFn(runTurn, List.of("hello m5")));
            assertThat(snap.get("status")).isEqualTo("idle");
            assertThat(((Number) snap.get("lastTurn")).intValue()).isEqualTo(1);
            assertThat(llm.streamCalls).isEqualTo(1);
            Map<String, Object> req = llm.requests.get(0);
            assertThat(req.get("provider")).isEqualTo("test");
            assertThat(req.get("model")).isEqualTo("test-model");
            assertThat(req.get("sessionId")).isEqualTo("agent-a");

            // 回合日志回 Java:turn/step 机器真跑(design 组合证据)
            List<Map<String, Object>> log = list(loopHost.invokeFn(getSessionLog, List.of()));
            List<String> types = log.stream().map(e -> String.valueOf(e.get("type"))).toList();
            assertThat(types).contains("turn/start", "step/start", "assistant/message", "step/end", "turn/end");
            Map<String, Object> assistant = log.stream()
                    .filter(e -> "assistant/message".equals(e.get("type"))).findFirst().orElseThrow();
            assertThat(String.valueOf(assistant.get("text"))).isEqualTo("Hello from M5 profile");

            // resolveModelInfo seam 经桥可达
            NodeRef resolve = (NodeRef) probe.get("callResolveModelInfo");
            Map<String, Object> info = map(loopHost.invokeFn(resolve, List.of()));
            assertThat(info.get("model")).isEqualTo("test-model");
            assertThat(llm.resolveCalls).isEqualTo(1);

            // turn 2(工具):真实机器调 worker 内真实 ToolRuntime,echo 工具真实执行
            Map<String, Object> snap2 = map(loopHost.invokeFn(runTurn, List.of("run the tool")));
            assertThat(snap2.get("status")).isEqualTo("idle");
            assertThat(llm.streamCalls).isEqualTo(3);   // 1(文本)+ 2(工具调用的两步)
            List<Map<String, Object>> log2 = list(loopHost.invokeFn(getSessionLog, List.of()));
            List<Map<String, Object>> toolResults = log2.stream()
                    .filter(e -> "tool/result".equals(e.get("type"))).toList();
            assertThat(toolResults).hasSize(1);
            assertThat(toolResults.get(0).get("callId")).isEqualTo("call-1");
            // 真实执行结果(非罐装桩):echo 工具 body 跑过、值过 output schema 校验、
            // definition render 投影出模型内容。
            assertThat(String.valueOf(toolResults.get(0).get("text"))).isEqualTo("echo: hi");
            assertThat(toolResults.get(0).get("isError")).isEqualTo(false);

            // ---- ③ Java → JS:agent-loop 内部 dsh system-prompt 真实 assemble() 从 Java 触发 ----
            Map<String, Object> systemPrompt = map(root.get("systemPrompt"));
            NodeRef assemble = (NodeRef) systemPrompt.get("assemble");
            Map<String, Object> assembly = map(loopHost.invokeFn(assemble, List.of()));
            List<Map<String, Object>> sections = list(assembly.get("sections"));
            assertThat(sections).extracting(s -> s.get("name"))
                    .contains("harness:identity", "deployment:persona", "tools:guidance");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    /** 与 NodeWorkerJsHost.nodeExecutable 同探测逻辑(P3 可移植性)。 */
    private static boolean nodeAvailable() {
        String cmd = System.getenv("NODE");
        if (cmd == null || cmd.isBlank()) cmd = "node";
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
