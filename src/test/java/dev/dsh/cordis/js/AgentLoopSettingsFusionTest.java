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
 * M5 NEEDS #3 — real {@code ctx.settings} from the REAL
 * {@code @deepseek-ai/dsh-settings} inside the NodeWorkerJsHost worker.
 *
 * <p>The overlay now carries a faithful JS port of the real package: the
 * {@link dev.dsh.cordis.js.NodeWorkerBridge} load path hands the worker a
 * concrete {@code SettingsProvider} subclass that READS the overlay's test
 * configuration document ({@code settings-test.json}). The real
 * {@code AgentLoop} constructor's {@code installSettingsSection} then registers
 * its own {@code agent-loop} namespace against the live provider, so the
 * machine's {@code config.maxParallelToolCalls} reflects the config value (4)
 * — not the constructor entry (2) or the schema default (10).
 *
 * <p>The test asserts, through probes crossing back to Java:
 *   1. the REAL agent-loop wiring read the config (resolved
 *      {@code maxParallelToolCalls} == 4);
 *   2. direct {@code ctx.settings.register}/{@code scope.get()} reads of a
 *      second namespace returned the config's real values
 *      ({@code temperature 0.7}, {@code apiKey "sk-test-real"});
 *   3. {@code describe({redactSecrets:true})} stripped the {@code role('secret')}
 *      field at the wire boundary (apiKey absent) while listing it in
 *      {@code secrets};
 *   4. an in-process {@code update} merges into the resolved value and the
 *      machine's config getter follows.
 *
 * <p><b>前置</b>: node 可执行(测试用)。{@code @deepseek-ai/*} overlays 随测试提交。
 */
class AgentLoopSettingsFusionTest {

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

    /** Minimal Java ctx.llm seam: the agent is created but no turn is run, so no stream is served. */
    public static final class LlmStub {
        public Object stream(Map<String, Object> request) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("type", "finish");
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("kind", "stop");
            chunk.put("reason", reason);
            return List.of(chunk);
        }

        public Object prepareCall(Map<String, Object> config, Object signal) {
            Map<String, Object> prepared = new LinkedHashMap<>();
            prepared.put("config", config);
            return prepared;
        }

        public Object resolveModelInfo(Map<String, Object> options) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("provider", options.get("provider"));
            info.put("model", options.get("model"));
            return info;
        }
    }

    /** Minimal Java ctx.tools seam: the exclusive-mode scheduler stub (no tool turn in this test). */
    public static final class ToolsStub {
        public Object executionMode(Map<String, Object> exec) {
            Map<String, Object> mode = new LinkedHashMap<>();
            mode.put("kind", "exclusive");
            return mode;
        }

        public Object schedulerPrepare(Map<String, Object> exec) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "final-result");
            out.put("exec", exec);
            return out;
        }

        public Object schedulerDispatch(Map<String, Object> exec) {
            return new LinkedHashMap<>();
        }

        public Object schedulerFinish(Map<String, Object> exec, Map<String, Object> result) {
            return result;
        }

        public Object schedulerFinalize(Map<String, Object> exec, Map<String, Object> result) {
            return result;
        }
    }

    /** The REAL agent-loop wiring + direct ctx.settings reads resolve the test-config document. */
    @Test
    void realSettingsBackendFeedsAgentLoopAndDirectReads() throws Exception {
        Context root = new Context();
        root.provide("llm", new LlmStub());
        root.provide("tools", new ToolsStub());

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-loop-driver/index.js"))),
                        Map.of("agentId", "agent-a", "settingsPath", overlay("settings-test.json").toString()));

                Map<String, Object> probe = map(root.get("agentLoopProbe"));
                assertThat(probe.get("serviceName")).isEqualTo("agentLoop");
                assertThat(probe.get("settingsEnabled")).isEqualTo(true);

                // 1. Real agent-loop wiring: installSettingsSection registered the
                //    'agent-loop' namespace against the real provider, so the machine's
                //    maxParallelToolCalls getter reads THROUGH the settings scope.
                NodeRef snapshot = (NodeRef) probe.get("getSettingsSnapshot");
                Map<String, Object> snap = map(host.invokeFn(snapshot, List.of()));
                assertThat(snap.get("enabled")).isEqualTo(true);
                assertThat(snap.get("documentPath")).isEqualTo(overlay("settings-test.json").toString());
                assertThat(((Number) snap.get("resolvedMaxParallelToolCalls")).intValue()).isEqualTo(4);

                // Both namespaces are live on the provider: the real AgentLoop's own
                // 'agent-loop' and the driver's direct 'probe-settings'.
                @SuppressWarnings("unchecked")
                List<String> namespaces = (List<String>) snap.get("registeredNamespaces");
                assertThat(namespaces).containsExactlyInAnyOrder("agent-loop", "probe-settings");

                // describe() — the wire surface: value is the resolved config; the
                // role('secret') apiKey is stripped and enumerated in secrets.
                List<Map<String, Object>> describe = list(snap.get("describe"));
                Map<String, Object> agentLoopDesc = describe.stream()
                        .filter(d -> "agent-loop".equals(d.get("ns"))).findFirst().orElseThrow();
                assertThat(((Number) map(agentLoopDesc.get("value")).get("maxParallelToolCalls")).intValue()).isEqualTo(4);
                Map<String, Object> probeDesc = describe.stream()
                        .filter(d -> "probe-settings".equals(d.get("ns"))).findFirst().orElseThrow();
                Map<String, Object> probeValue = map(probeDesc.get("value"));
                assertThat(probeValue.get("temperature")).isEqualTo(0.7);
                assertThat(probeValue).doesNotContainKey("apiKey");
                Map<String, Object> secret = list(probeDesc.get("secrets")).get(0);
                assertThat(((List<String>) secret.get("path"))).containsExactly("apiKey");
                assertThat(secret.get("set")).isEqualTo(true);
                // schema.toJSON() crossed the bridge (the agent-loop object schema).
                assertThat(map(agentLoopDesc.get("schema")).get("type")).isEqualTo("object");

                // The raw document read is observable too.
                Map<String, Object> document = map(snap.get("document"));
                assertThat(((Number) map(document.get("agent-loop")).get("maxParallelToolCalls")).intValue()).isEqualTo(4);
                assertThat(map(document.get("probe-settings"))).containsEntry("apiKey", "sk-test-real");

                // 2. Direct ctx.settings read: the driver registered 'probe-settings'
                //    straight against the provider's register/scope.get and the raw
                //    resolved value (incl. the secret) is observable in-process.
                NodeRef direct = (NodeRef) probe.get("probeSettingsRead");
                Map<String, Object> resolved = map(host.invokeFn(direct, List.of()));
                assertThat(resolved).isEqualTo(Map.of("temperature", 0.7, "apiKey", "sk-test-real"));

                // 3. In-process update merges + commits; the machine's config follows.
                NodeRef update = (NodeRef) probe.get("updateSettings");
                Map<String, Object> updated = map(host.invokeFn(update, List.of("agent-loop", Map.of("maxParallelToolCalls", 7))));
                assertThat(((Number) updated.get("maxParallelToolCalls")).intValue()).isEqualTo(7);
                Map<String, Object> snapAfter = map(host.invokeFn(snapshot, List.of()));
                assertThat(((Number) snapAfter.get("resolvedMaxParallelToolCalls")).intValue()).isEqualTo(7);
            } finally {
                root.fiber.dispose().join();
            }
        }
    }

    /** Without a settingsPath, the seam stays inert (no provider, no namespaces). */
    @Test
    void settingsSeamStaysInertWithoutConfigPath() throws Exception {
        Context root = new Context();
        root.provide("llm", new LlmStub());
        root.provide("tools", new ToolsStub());

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-loop-driver/index.js"))),
                        Map.of("agentId", "agent-a"));

                Map<String, Object> probe = map(root.get("agentLoopProbe"));
                assertThat(probe.get("settingsEnabled")).isEqualTo(false);
                NodeRef snapshot = (NodeRef) probe.get("getSettingsSnapshot");
                Map<String, Object> snap = map(host.invokeFn(snapshot, List.of()));
                assertThat(snap.get("enabled")).isEqualTo(false);
                // No provider mounted → the machine keeps its constructor entry (2).
                assertThat(snap).doesNotContainKey("resolvedMaxParallelToolCalls");
            } finally {
                root.fiber.dispose().join();
            }
        }
    }
}
