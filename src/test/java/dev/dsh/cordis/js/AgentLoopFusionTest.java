package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 agent-loop fusion — the real {@code @deepseek-ai/dsh-agent-loop} package
 * (core/agent-loop: the {@code AgentLoop} factory service + the
 * {@code ReactLoopAgent} turn/step driver) loaded into the NodeWorkerJsHost
 * worker, its ctx bridged back to the JAVA cordis core.
 *
 * <p>The worker loads the {@code agent-loop-driver} plugin (ESM, bare
 * specifiers): it constructs the real {@code AgentLoop} against a HYBRID
 * loopCtx — the machine-critical seams (AgentRegistry / SessionStore /
 * SystemPrompt / the symbol-keyed tools scheduler / the agentLoop service)
 * stay worker-local REAL instances so the live Agent/Session never lose their
 * prototype methods, while the generic cordis surface delegates to the bridge
 * ctx. Event payloads that carry the live Agent/Session are SANITIZED at the
 * bridge boundary (a lightweight {id,status,sessionId} envelope), because the
 * bridge's serializer treats the Agent/Session object graph (shared
 * references) as cycles.
 *
 * <p>The {@code ctx.llm} seam is JAVA-provided: {@code llm.stream(request)}
 * returns a materialized chunk array (the machine's {@code for await}
 * iterates it), {@code llm.prepareCall} returns a PreparedLlmCall whose
 * {@code stream} serves Java's chunks, {@code llm.resolveModelInfo}
 * delegates. {@code ctx.tools} is the REAL {@code @deepseek-ai/dsh-tools}
 * {@code ToolRuntime} the driver mounts worker-locally (M5 NEEDS #2): it
 * provides {@code tools} into the Java core, registers a real {@code echo}
 * tool, and the agent-loop's {@code tool-calls} machine drives the real
 * pre/guard/dispatch/post/result pipeline. The driver publishes a live
 * {@code ReactLoopAgent} (session/agent entered + announced into Java), and
 * Java triggers one agent turn via the {@code runTurn} probe (followup +
 * whenIdle, settled through the sync host's microtask pump), then reads the
 * real session log back and asserts the turn/step machine ran (llm.stream +
 * real tool execution, assistant message, multi-step tool turn).
 *
 * <p><b>Honest boundaries (NEEDS)</b>: the {@code llm} seam streams canned
 * chunks from Java (no real adapter / streaming transport);
 * {@code settings} resolves through an inert {@code ctx.inject}; the hybrid
 * loopCtx keeps the real services worker-local (the bridge cannot carry live
 * prototype-bearing objects).
 *
 * <p><b>前置</b>: node 可执行(测试用)。{@code @deepseek-ai/*} overlays 随测试提交。
 */
class AgentLoopFusionTest {

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private static Path overlay(String rel) {
        return Path.of("src/test/resources/agent-fusion").resolve(rel).toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object v) {
        return (List<Map<String, Object>>) v;
    }

    // ---- Java-provided ctx.llm seam ----

    /** ctx.llm: stream serves a queue of chunk sequences; prepareCall returns a config; resolveModelInfo delegates. */
    public static final class LlmStub {
        public final List<Map<String, Object>> requests = new ArrayList<>();
        private final List<List<Map<String, Object>>> responses = new LinkedList<>();
        public int streamCalls = 0;
        public int prepareCalls = 0;
        public int resolveCalls = 0;

        public LlmStub response(List<Map<String, Object>> chunks) {
            responses.add(chunks);
            return this;
        }

        public Object stream(Map<String, Object> request) {
            streamCalls++;
            requests.add(request);
            if (!responses.isEmpty()) return responses.remove(0);
            return chunks(textChunk("fallback text"), finishChunk());
        }

        public Object prepareCall(Map<String, Object> config, Object signal) {
            prepareCalls++;
            // A PreparedLlmCall without a stream: the loop falls back to ctx.llm.stream.
            Map<String, Object> prepared = new LinkedHashMap<>();
            prepared.put("config", config);
            return prepared;
        }

        public Object resolveModelInfo(Map<String, Object> options) {
            resolveCalls++;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("provider", options.get("provider"));
            info.put("model", options.get("model"));
            return info;
        }
    }

    // ---- chunk / block builders (real StreamChunk JSON shape) ----

    private static Map<String, Object> textChunk(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return blockEnd(block);
    }

    private static Map<String, Object> toolCallChunk(String id, String name, String arguments) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool-call");
        block.put("id", id);
        block.put("name", name);
        block.put("arguments", arguments);
        return blockEnd(block);
    }

    private static Map<String, Object> blockEnd(Map<String, Object> block) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("type", "block-end");
        chunk.put("index", 0);
        chunk.put("block", block);
        return chunk;
    }

    private static Map<String, Object> finishChunk() {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("type", "finish");
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("kind", "stop");
        chunk.put("reason", reason);
        return chunk;
    }

    private static List<Map<String, Object>> chunks(Map<String, Object>... cs) {
        return List.of(cs);
    }

    /** Java triggers one agent turn → the real turn/step machine runs (llm.stream) → result crosses back. */
    @Test
    void agentLoopTextTurnRunsStepMachineAndReturnsResultToJava() throws Exception {
        Context root = new Context();
        LlmStub llm = new LlmStub().response(chunks(textChunk("Hello from agent-loop"), finishChunk()));
        root.provide("llm", llm);
        List<String> created = new ArrayList<>();
        List<String> status = new ArrayList<>();
        root.on("agent/created", (ctx, args) -> {
            Map<String, Object> payload = map(args[0]);
            Map<String, Object> agent = map(payload.get("agent"));
            created.add(String.valueOf(agent.get("id")));
            return null;
        });
        root.on("agent/status", (ctx, args) -> {
            Map<String, Object> payload = map(args[0]);
            status.add(String.valueOf(payload.get("status")));
            return null;
        });

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-loop-driver/index.js"))),
                        Map.of("agentId", "agent-a"));

                // AgentLoop.create published a live agent into the Java core.
                assertThat(created).containsExactly("agent-a");

                Map<String, Object> probe = map(root.get("agentLoopProbe"));
                assertThat(probe.get("serviceName")).isEqualTo("agentLoop");
                assertThat(probe.get("llmProvided")).isEqualTo(true);
                NodeRef runTurn = (NodeRef) probe.get("runTurn");

                // Java triggers a turn; the machine runs to completion (sync-host pump).
                Map<String, Object> snap = map(host.invokeFn(runTurn, List.of("hello agent loop")));
                assertThat(snap.get("status")).isEqualTo("idle");
                assertThat(((Number) snap.get("lastTurn")).intValue()).isEqualTo(1);

                // llm.stream served the step; the Java seam saw the frozen loop request.
                assertThat(llm.streamCalls).isEqualTo(1);
                Map<String, Object> req = llm.requests.get(0);
                assertThat(req.get("provider")).isEqualTo("test");
                assertThat(req.get("model")).isEqualTo("test-model");
                assertThat(req.get("sessionId")).isEqualTo("agent-a");

                // The turn/step machine's durable log, read back into Java.
                NodeRef getLog = (NodeRef) probe.get("getSessionLog");
                List<Map<String, Object>> log = list(host.invokeFn(getLog, List.of()));
                List<String> types = log.stream().map(e -> String.valueOf(e.get("type"))).toList();
                assertThat(types).contains("turn/start", "step/start", "user/message",
                        "assistant/chunk", "assistant/message", "step/end", "turn/end");
                Map<String, Object> assistant = log.stream()
                        .filter(e -> "assistant/message".equals(e.get("type"))).findFirst().orElseThrow();
                assertThat(String.valueOf(assistant.get("text"))).isEqualTo("Hello from agent-loop");
                Map<String, Object> turnEnd = log.stream()
                        .filter(e -> "turn/end".equals(e.get("type"))).findFirst().orElseThrow();
                assertThat(map(turnEnd.get("reason"))).isEqualTo(Map.of("kind", "completed"));

                // The resolveModelInfo seam is reachable over the bridge.
                NodeRef resolve = (NodeRef) probe.get("callResolveModelInfo");
                Map<String, Object> info = map(host.invokeFn(resolve, List.of()));
                assertThat(info.get("model")).isEqualTo("test-model");
                assertThat(llm.resolveCalls).isEqualTo(1);
            } finally {
                root.fiber.dispose().join();
            }
        }
    }

    /** A tool-call turn: the REAL @deepseek-ai/dsh-tools registry executes the echo tool and the machine continues to a second step. */
    @Test
    void agentLoopToolTurnRunsRealToolAndSecondStep() throws Exception {
        Context root = new Context();
        LlmStub llm = new LlmStub()
                .response(chunks(toolCallChunk("call-1", "echo", "{\"msg\":\"hi\"}"), finishChunk()))
                .response(chunks(textChunk("tool result acknowledged"), finishChunk()));
        root.provide("llm", llm);

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-loop-driver/index.js"))),
                        Map.of("agentId", "agent-a"));

                Map<String, Object> probe = map(root.get("agentLoopProbe"));
                // ctx.tools IS the real ToolRuntime mounted by the driver.
                assertThat(probe.get("toolsProvided")).isEqualTo(true);
                @SuppressWarnings("unchecked")
                List<String> toolNames = (List<String>) probe.get("toolNames");
                assertThat(toolNames).containsExactly("echo");

                NodeRef runTurn = (NodeRef) probe.get("runTurn");
                Map<String, Object> snap = map(host.invokeFn(runTurn, List.of("run the tool")));
                assertThat(snap.get("status")).isEqualTo("idle");

                // Two llm.stream calls (step 1 tool-call, step 2 text).
                assertThat(llm.streamCalls).isEqualTo(2);

                NodeRef getLog = (NodeRef) probe.get("getSessionLog");
                List<Map<String, Object>> log = list(host.invokeFn(getLog, List.of()));
                List<Map<String, Object>> stepStarts = log.stream()
                        .filter(e -> "step/start".equals(e.get("type"))).toList();
                assertThat(stepStarts).hasSize(2);
                List<Map<String, Object>> toolCalls = log.stream()
                        .filter(e -> "tool/call".equals(e.get("type"))).toList();
                assertThat(toolCalls).hasSize(1);
                assertThat(toolCalls.get(0).get("toolName")).isEqualTo("echo");
                List<Map<String, Object>> toolResults = log.stream()
                        .filter(e -> "tool/result".equals(e.get("type"))).toList();
                assertThat(toolResults).hasSize(1);
                assertThat(toolResults.get(0).get("callId")).isEqualTo("call-1");
                // REAL execution (not a canned stub result): the echo tool's body ran,
                // its value validated against the declared output schema, and the
                // definition-owned render projected the model content.
                assertThat(String.valueOf(toolResults.get(0).get("text"))).isEqualTo("echo: hi");
                assertThat(toolResults.get(0).get("isError")).isEqualTo(false);
                Map<String, Object> assistant2 = log.stream()
                        .filter(e -> "assistant/message".equals(e.get("type")) && "2".equals(String.valueOf(e.get("step"))))
                        .findFirst().orElseThrow();
                assertThat(String.valueOf(assistant2.get("text"))).isEqualTo("tool result acknowledged");
                Map<String, Object> turnEnd = log.stream()
                        .filter(e -> "turn/end".equals(e.get("type"))).findFirst().orElseThrow();
                assertThat(map(turnEnd.get("reason"))).isEqualTo(Map.of("kind", "completed"));
            } finally {
                root.fiber.dispose().join();
            }
        }
    }
}
