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
 * M5 NEEDS #2 — the REAL {@code @deepseek-ai/dsh-tools} in the worker.
 *
 * <p>The {@code agent-loop-driver} now mounts the real {@code ToolRuntime}
 * (registry + {@code TOOL_RUNTIME_SCHEDULER} + pre/guard/around/post/result
 * execution pipeline) worker-locally, provides {@code ctx.tools} into the Java
 * core through the bridge, and registers a real {@code echo} tool via
 * {@code ctx.tools.register()}. This test asserts the REAL execution surface:
 *
 * <ol>
 *   <li>{@code ctx.tools} from the Java core IS the real ToolRuntime (its
 *       {@code schemas()} returns the registered {@code echo} definition);</li>
 *   <li>driving {@code ctx.tools.execute()} directly runs the real pipeline —
 *       args snapshot+frozen, pre-execute gate, body dispatch, output-schema
 *       validation, content render, post-execute, materialized result;</li>
 *   <li>an unknown tool routes through the real registry to
 *       {@code UNKNOWN_TOOL} (ToolNotFoundError), not a stub answer.</li>
 * </ol>
 *
 * <p><b>Honest boundaries (NEEDS)</b>: the {@code llm} seam streams canned
 * chunks from Java; a {@code ctx.codeRuntime} for Code Mode is not mounted
 * (mode stays {@code native}); the hybrid loopCtx keeps the real services
 * worker-local.
 *
 * <p><b>前置</b>: node 可执行(测试用)。{@code @deepseek-ai/*} overlays 随测试提交。
 */
class DshToolsFusionTest {

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

    /** ctx.llm seam (the driver's only Java inject): canned chunks, records calls. */
    public static final class LlmStub {
        public int streamCalls = 0;

        public Object stream(Map<String, Object> request) {
            streamCalls++;
            return chunks(textChunk("fallback text"), finishChunk());
        }

        public Object resolveModelInfo(Map<String, Object> options) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("provider", options.get("provider"));
            info.put("model", options.get("model"));
            return info;
        }
    }

    private static Map<String, Object> textChunk(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
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

    /** The driver mounts the real ToolRuntime; ctx.tools is the real registry. */
    @Test
    void realToolRegistryIsProvidedIntoJavaCore() throws Exception {
        Context root = new Context();
        root.provide("llm", new LlmStub());

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-loop-driver/index.js"))),
                        Map.of("agentId", "agent-a"));

                Map<String, Object> probe = map(root.get("agentLoopProbe"));
                assertThat(probe.get("toolsProvided")).isEqualTo(true);
                @SuppressWarnings("unchecked")
                List<String> toolNames = (List<String>) probe.get("toolNames");
                assertThat(toolNames).containsExactly("echo");

                // ctx.tools from the JAVA core is the real ToolRuntime (provided
                // by the worker's dsh-tools Service through the bridge): its
                // schemas() method is callable and returns the real echo schema.
                Map<String, Object> tools = map(root.get("tools"));
                assertThat(tools).isNotNull();
                NodeRef schemas = (NodeRef) tools.get("schemas");
                List<Map<String, Object>> schemasList = list(host.invokeFn(schemas, List.of()));
                assertThat(schemasList).extracting(s -> s.get("name")).containsExactly("echo");
                Map<String, Object> echo = schemasList.get(0);
                assertThat(String.valueOf(echo.get("description"))).contains("Echo the given message back");
            } finally {
                root.fiber.dispose().join();
            }
        }
    }

    /** Direct ctx.tools.execute(): the real pipeline produces the real executed result. */
    @Test
    void directToolExecutionRunsRealPipelineAndReturnsResult() throws Exception {
        Context root = new Context();
        root.provide("llm", new LlmStub());

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-loop-driver/index.js"))),
                        Map.of("agentId", "agent-a"));

                Map<String, Object> probe = map(root.get("agentLoopProbe"));
                NodeRef executeTool = (NodeRef) probe.get("executeTool");

                // Real echo execution: body ran, value validated against the
                // declared output schema, definition-owned render projected content.
                Map<String, Object> ok = map(host.invokeFn(executeTool,
                        List.of("echo", Map.of("msg", "direct hello"))));
                assertThat(ok.get("isError")).isEqualTo(false);
                assertThat(map(ok.get("value"))).isEqualTo(Map.of("text", "echo: direct hello"));
                List<Map<String, Object>> content = list(ok.get("content"));
                assertThat(content).extracting(c -> c.get("type")).containsExactly("text");
                assertThat(String.valueOf(content.get(0).get("text"))).isEqualTo("echo: direct hello");

                // Unknown tool routes through the real registry to UNKNOWN_TOOL,
                // not a canned stub answer.
                Map<String, Object> missing = map(host.invokeFn(executeTool,
                        List.of("does-not-exist", Map.of())));
                assertThat(missing.get("isError")).isEqualTo(true);
                assertThat(String.valueOf(missing.get("error"))).contains("unknown tool \"does-not-exist\"");
            } finally {
                root.fiber.dispose().join();
            }
        }
    }
}
