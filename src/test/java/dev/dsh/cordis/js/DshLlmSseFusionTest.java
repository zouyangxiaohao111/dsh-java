package dev.dsh.cordis.js;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 NEEDS — real {@code ctx.llm.stream} from the REAL
 * {@code @deepseek-ai/dsh-llm} + REAL {@code @deepseek-ai/dsh-llm-deepseek}
 * with REAL fetch + SSE against a mock endpoint.
 *
 * <p>The NodeWorkerJsHost bridge is a synchronous host — a real {@code fetch}
 * (macrotask) can never settle inside it — so the real network runs in the
 * {@code llm-stream-runner} subprocess: a standalone Node process holding the
 * REAL LlmRuntime + REAL DeepSeekAdapter, doing REAL
 * {@code fetch(`${baseURL}/chat/completions`)} + REAL SSE parsing
 * (eventsource-parser) + REAL chunk translation. The {@code llm-driver}
 * worker plugin mounts the real llm machinery in the bridge (the real
 * LlmRuntime registered into the Java core, the real llm-deepseek apply /
 * resolveAdapterOptions / settings section / credential seam) and streams
 * through the runner.
 *
 * <p>Assertions:
 *   1. driver registration surface — providers == [deepseek-official],
 *      settings section registered, real resolveAdapterOptions baseURL,
 *      real credential resolution, real adapter listModels;
 *   2. real stream through the runner — Java triggers the driver's
 *      {@code streamReal} probe, the runner fetches the mock SSE endpoint
 *      with the resolved bearer key, chunks stream back, assembled text
 *      "Hello world" + usage + stop finish are asserted;
 *   3. independent Java→runner path — Java spawns the runner itself and
 *      drives {@code ctx.llm.stream} end to end.
 *
 * <p><b>前置</b>: node 可执行(测试用)。overlays 随测试提交。
 */
class DshLlmSseFusionTest {

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

    /** One in-memory mock SSE chat-completions server (Java HttpServer). */
    static final class MockSseServer implements AutoCloseable {
        private final HttpServer server;
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<String> requestBody = new AtomicReference<>();
        final AtomicReference<String> userAgent = new AtomicReference<>();

        MockSseServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v1/chat/completions", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    authorization.set(auth);
                    userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    requestBody.set(new String(body, StandardCharsets.UTF_8));

                    byte[] sse = sseBody();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, sse.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(sse);
                    }
                }
            });
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        /** Two text deltas + usage/stop terminal chunk + [DONE]. */
        static byte[] sseBody() {
            StringBuilder sb = new StringBuilder();
            sse(sb, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}");
            sse(sb, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}");
            sse(sb, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}");
            sse(sb, "[DONE]");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static void sse(StringBuilder sb, String data) {
            sb.append("data: ").append(data).append("\n\n");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * Driver mounts the REAL llm machinery in the worker; the real
     * fetch+SSE stream runs in the runner subprocess and chunks return to Java.
     */
    @Test
    void realDeepSeekAdapterStreamsMockSseChunksThroughRunner() throws Exception {
        try (MockSseServer mock = new MockSseServer()) {
            Context root = new Context();
            try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
                try {
                    root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("llm-driver/index.js"))),
                            Map.of(
                                    "baseURL", mock.baseUrl(),
                                    "apiKeyEnv", "DEEPSEEK_API_KEY",
                                    "apiKey", "sk-test-real"));

                    Map<String, Object> probe = map(root.get("llmProbe"));
                    assertThat(probe.get("serviceName")).isEqualTo("llm");
                    assertThat((List<String>) probe.get("providers")).containsExactly("deepseek-official");
                    assertThat(probe.get("settingsRegistered")).isEqualTo(true);

                    // Real resolveAdapterOptions surface: the resolved baseURL is
                    // the mock endpoint; connection facts are detached.
                    NodeRef resolve = (NodeRef) probe.get("resolveAdapterOptions");
                    Map<String, Object> resolved = map(host.invokeFn(resolve, List.of(Map.of("baseURL", mock.baseUrl()))));
                    assertThat(resolved.get("baseURL")).isEqualTo(mock.baseUrl());

                    // Real credential seam resolves the test key.
                    NodeRef creds = (NodeRef) probe.get("credentialsResolved");
                    Map<String, Object> credential = map(host.invokeFn(creds, List.of("DEEPSEEK_API_KEY")));
                    assertThat(credential.get("value")).isEqualTo("sk-test-real");
                    assertThat(credential.get("source")).isEqualTo("test-env");

                    // Real adapter discovery surface.
                    NodeRef listModels = (NodeRef) probe.get("listModels");
                    List<Map<String, Object>> models = list(host.invokeFn(listModels, List.of()));
                    assertThat(models).extracting(m -> m.get("id")).containsExactly("deepseek-v4-flash", "deepseek-v4-pro");

                    // The REAL network path: driver spawns the runner, runner does
                    // fetch+SSE against the mock, real chunks return to Java.
                    NodeRef streamReal = (NodeRef) probe.get("streamReal");
                    List<Map<String, Object>> chunks = list(host.invokeFn(streamReal, List.of(Map.of("prompt", "ping"))));

                    assertThat(chunks).hasSize(6);
                    Map<String, Object> first = chunks.get(0);
                    assertThat(first.get("type")).isEqualTo("block-start");
                    assertThat(((Number) first.get("index")).intValue()).isZero();
                    assertThat(first.get("blockType")).isEqualTo("text");

                    List<String> deltas = new ArrayList<>();
                    for (Map<String, Object> c : chunks) {
                        if ("text-delta".equals(c.get("type"))) deltas.add((String) c.get("text"));
                    }
                    assertThat(deltas).containsExactly("Hello", " world");

                    Map<String, Object> blockEnd = chunks.get(3);
                    assertThat(blockEnd.get("type")).isEqualTo("block-end");
                    Map<String, Object> block = map(blockEnd.get("block"));
                    assertThat(block.get("type")).isEqualTo("text");
                    assertThat(block.get("text")).isEqualTo("Hello world");

                    Map<String, Object> usage = chunks.get(4);
                    assertThat(usage.get("type")).isEqualTo("usage");
                    assertThat(((Number) map(usage.get("usage")).get("inputTokens")).intValue()).isEqualTo(10);
                    assertThat(((Number) map(usage.get("usage")).get("outputTokens")).intValue()).isEqualTo(2);

                    Map<String, Object> finish = chunks.get(5);
                    assertThat(finish.get("type")).isEqualTo("finish");
                    assertThat(map(finish.get("reason")).get("kind")).isEqualTo("stop");

                    // The mock actually received the REAL wire request: bearer key
                    // from the credentials seam, app attribution, real body.
                    assertThat(mock.authorization.get()).isEqualTo("Bearer sk-test-real");
                    assertThat(mock.userAgent.get()).startsWith("deepseek-harness/");
                    assertThat(mock.requestBody.get()).contains("\"model\":\"deepseek-v4-flash\"");
                    assertThat(mock.requestBody.get()).contains("\"stream\":true");
                } finally {
                    root.fiber.dispose().join();
                }
            }
        }
    }

    /**
     * Independent Java→runner path: Java spawns llm-stream-runner itself and
     * drives the REAL ctx.llm.stream end to end (init + stream), asserting the
     * real chunks stream back over stdout NDJSON.
     */
    @Test
    void javaDrivesRealCtxLlmStreamViaRunnerSubprocess() throws Exception {
        try (MockSseServer mock = new MockSseServer()) {
            String runnerFile = overlay("llm-stream-runner/index.js").toString();
            ProcessBuilder pb = new ProcessBuilder("node", runnerFile);
            pb.directory(overlay("").toFile());
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process proc = pb.start();

            String init = "{\"type\":\"init\",\"id\":1,\"config\":{\"baseURL\":\""
                    + mock.baseUrl() + "\",\"apiKeyEnv\":\"DEEPSEEK_API_KEY\",\"apiKey\":\"sk-test-real\"}}\n";
            String stream = "{\"type\":\"stream\",\"id\":2,\"options\":{\"prompt\":\"ping\"}}\n";
            String close = "{\"type\":\"close\",\"id\":3}\n";
            proc.getOutputStream().write((init + stream + close).getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().flush();
            proc.getOutputStream().close();

            List<Map<String, Object>> chunks = new ArrayList<>();
            boolean done = false;
            for (String line : new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                Map<String, Object> msg = map(json(line));
                if (((Number) msg.get("id")).intValue() != 2) continue;
                if ("chunk".equals(msg.get("type"))) chunks.add(map(msg.get("chunk")));
                else if ("done".equals(msg.get("type"))) done = true;
                else if ("error".equals(msg.get("type"))) throw new AssertionError("runner error: " + msg.get("message"));
            }
            assertThat(proc.waitFor()).isZero();
            assertThat(done).isTrue();
            assertThat(chunks).hasSize(6);

            StringBuilder text = new StringBuilder();
            for (Map<String, Object> c : chunks) {
                if ("text-delta".equals(c.get("type"))) text.append(c.get("text"));
            }
            assertThat(text).hasToString("Hello world");
            assertThat(map(chunks.get(5).get("reason")).get("kind")).isEqualTo("stop");
            assertThat(mock.authorization.get()).isEqualTo("Bearer sk-test-real");
        }
    }

    /** Jackson decode for the runner's NDJSON (values are plain). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String line) throws Exception {
        return MAPPER.readValue(line, Map.class);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
