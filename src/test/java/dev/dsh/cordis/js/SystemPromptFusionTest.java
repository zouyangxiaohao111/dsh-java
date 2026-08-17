package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 agent-fusion — the real @deepseek-ai/dsh-system-prompt package loaded into
 * the NodeWorkerJsHost worker, its ctx bridged back to the JAVA cordis core.
 *
 * <p>The worker loads the {@code system-prompt-driver} plugin (ESM, bare
 * specifiers): it imports {@code @deepseek-ai/dsh-system-prompt} (overlay of
 * the real package), whose value deps are the {@code @deepseek-ai/cordis} shim
 * (Service bridges registration into the Java core), the dsh-scope overlay
 * ({@code ScopedLayers}/{@code NamedEntries}/{@code scopeTarget}) and the
 * schemastery overlay. Instantiating {@code SystemPrompt} runs the cordis-shim
 * {@code Service} base (registers {@code ctx.systemPrompt} into the Java core)
 * and the constructor's own sections through the worker's ctx.effect seam,
 * which steps generator effect bodies synchronously so the ScopedLayers tables
 * are populated.
 *
 * <p>Java triggers {@code assemble} by invoking the registered service's
 * {@code assemble} fn handle; the merged result crosses back into Java for
 * assertion. The second test proves the ctx.waterfall seam folds a JS listener
 * chain synchronously (Java hands the worker the JS listener handles; the
 * worker calls them locally, so no cross-bridge round-trip deadlocks).
 *
 * <p><b>前置</b>: node 可执行(测试用)。{@code @deepseek-ai/*} overlays 随测试提交。
 */
class SystemPromptFusionTest {

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

    /** Register a prompt section → trigger assemble from Java → assert the merged result in Java. */
    @Test
    void systemPromptAssemblesSectionsThroughNodeWorker() throws Exception {
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("system-prompt-driver/index.js"))), null);

                // The real SystemPrompt extended the cordis-shim Service, so
                // super(ctx, 'systemPrompt') registered the value into the core.
                Object svc = root.get("systemPrompt");
                assertThat(svc).isInstanceOf(Map.class);
                Map<String, Object> systemPrompt = map(svc);
                assertThat(systemPrompt.get("name")).isEqualTo("systemPrompt");
                // public methods were bound to own props → crossed the bridge as fn handles.
                assertThat(systemPrompt.get("assemble")).isInstanceOf(NodeRef.class);
                assertThat(((NodeRef) systemPrompt.get("assemble")).kind()).isEqualTo("fn");
                assertThat(systemPrompt.get("section")).isInstanceOf(NodeRef.class);

                // Java triggers the real assemble() (async, resolves via microtasks).
                Object result = host.invokeFn((NodeRef) systemPrompt.get("assemble"), List.of());
                Map<String, Object> assembly = map(result);

                // Merged sections, ordered: harness identity (-100) → persona (0) → tools guidance (100).
                List<Map<String, Object>> sections = list(assembly.get("sections"));
                assertThat(sections).extracting(s -> s.get("name"))
                        .containsExactly("harness:identity", "deployment:persona", "tools:guidance");
                assertThat(sections).anySatisfy(s -> {
                    if ("tools:guidance".equals(s.get("name"))) {
                        assertThat(s.get("text")).isEqualTo("Prefer deterministic tools over free-form reasoning.");
                    }
                });
                assertThat(sections).anySatisfy(s -> {
                    if ("harness:identity".equals(s.get("name"))) {
                        assertThat(s.get("text")).isEqualTo("You are an AI agent powered by DeepSeek Harness.");
                    }
                });
                assertThat(sections).anySatisfy(s -> {
                    if ("deployment:persona".equals(s.get("name"))) {
                        assertThat(s.get("text")).isEqualTo("You are the deployment persona.");
                    }
                });

                // Variables, contexts, tools merged.
                Map<String, Object> variables = map(assembly.get("variables"));
                assertThat(variables.get("deployment_name")).isEqualTo("Acme");
                assertThat(assembly.get("contexts")).asList().isEmpty();
                assertThat(assembly.get("tools")).asList().isEmpty();
            } finally {
                root.fiber.dispose().join();
            }
        }
    }

    /** The ctx.waterfall seam folds a JS listener on 'system-prompt/assemble' synchronously. */
    @Test
    void systemPromptWaterfallFoldsJsListenerThroughNodeWorker() throws Exception {
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("system-prompt-driver/index.js"))),
                        Map.of("waterfallListener", true));

                Map<String, Object> systemPrompt = map(root.get("systemPrompt"));
                Object result = host.invokeFn((NodeRef) systemPrompt.get("assemble"), List.of());
                Map<String, Object> assembly = map(result);

                List<Map<String, Object>> sections = list(assembly.get("sections"));
                assertThat(sections).extracting(s -> s.get("name"))
                        .containsExactly("harness:identity", "deployment:persona", "tools:guidance", "waterfall:post");
                assertThat(sections.get(3).get("text")).isEqualTo("appended by waterfall listener");
            } finally {
                root.fiber.dispose().join();
            }
        }
    }
}
