package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 agent-fusion — node_modules overlay + cordis shim.
 *
 * <p>Loads {@code src/test/resources/agent-fusion/} into the NodeWorkerJsHost
 * worker. The worker loads ESM via Node's require(esm), so "requireable" is
 * exercised for real: a probe plugin imports every overlay package by bare
 * specifier and reports a snapshot of its loaded surface. The four packages:
 * {@code @deepseek-ai/cordis} (shim), {@code @deepseek-ai/dsh-llm},
 * {@code @deepseek-ai/dsh-session-projection}, {@code @deepseek-ai/dsh-session-stats}
 * (plus real zod + dsh-llm/message value deps).
 *
 * <p>Beyond "requires": the fusion driver plugin
 * ({@code fusion-plugin/index.js}) instantiates the real dsh
 * {@code SessionProjectionRegistry} — whose {@code Service} base (the cordis
 * shim) registers ctx/name/check into the JAVA core over the node-bridge RPC —
 * then mounts the real session-stats plugin against the Java-held registry.
 * Java-side assertions read the registered service back through
 * {@link Context#get}: its name, its bound-method handles, and its check
 * predicate all crossed the bridge. (This host's effect model defers JS effect
 * bodies — e.g. the registry's {@code register} generator — to fiber unload,
 * a documented bridge simplification shared with the GraalJS host; the unit's
 * fold behavior itself is covered by {@link NodeWorkerFusionSpikeTest}.)
 *
 * <p><b>前置</b>: node 可执行(测试用),且 {@code node_modules/zod} 已装
 * ({@code cd src/test/resources/agent-fusion && npm install --no-save zod@^4.4.3};
 * zod 目录已 gitignore,{@code @deepseek-ai/*} overlays 随测试提交)。
 */
class AgentFusionOverlayTest {

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

    /** The four overlay packages require in the worker (real graph: zod + dsh-llm/message + cordis shim). */
    @Test
    void fourPackagesRequireInNodeWorker() throws Exception {
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("require-probe/index.js"))), null);

                Object probeObj = root.get("requireProbe");
                assertThat(probeObj).isInstanceOf(Map.class);
                Map<String, Object> probe = map(probeObj);

                // 1. @deepseek-ai/cordis — the shim (Service / Context, plus the surface the
                //    @deepseek-ai/dsh-agent overlay needs: FiberState / getTraceable / symbols).
                assertThat(probe.get("cordis")).asList()
                        .containsExactlyInAnyOrder("Context", "Service", "FiberState", "getTraceable", "symbols");
                // 2. @deepseek-ai/dsh-llm — root + /message (real isTokenDelta runs).
                assertThat(((Number) probe.get("llmExports")).longValue()).isGreaterThan(5);
                assertThat(probe.get("llmMessageHasTokenDelta")).isEqualTo(true);
                assertThat(probe.get("llmMessageProbe")).isEqualTo(true);
                // 3. @deepseek-ai/dsh-session-projection — the real Service class.
                assertThat(probe.get("sessionProjectionIsClass")).isEqualTo(true);
                // 4. @deepseek-ai/dsh-session-stats — root + /projection (real zod schema).
                assertThat(probe.get("sessionStatsName")).isEqualTo("session-stats");
                assertThat(probe.get("sessionStatsProjectionKey")).isEqualTo("sessionStats");
            } finally {
                root.fiber.dispose().join();
            }
        }
    }

    /**
     * Fusion driver: the cordis shim's Service registers ctx/name/check into
     * the Java core (the registered value crosses the bridge carrying its
     * bound-method and check handles), and the real session-stats plugin apply
     * runs against the Java-held registry without error.
     */
    @Test
    void fusionDriverBridgesServiceIntoJavaCore() throws Exception {
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(overlay("fusion-plugin/index.js")));
                root.plugin(adapter, null);

                // The real SessionProjectionRegistry extended the cordis-shim Service,
                // so super(ctx, 'sessionProjections') registered the value into the core.
                Object svc = root.get("sessionProjections");
                assertThat(svc).isInstanceOf(Map.class);
                Map<String, Object> registry = map(svc);
                assertThat(registry.get("name")).isEqualTo("sessionProjections");
                // public methods were bound to own props → crossed the bridge as fn handles.
                assertThat(registry.get("register")).isInstanceOf(NodeRef.class);
                assertThat(((NodeRef) registry.get("register")).kind()).isEqualTo("fn");
                // the check predicate crossed as a fn handle on the registered value.
                assertThat(registry.get("$check")).isInstanceOf(NodeRef.class);
                assertThat(((NodeRef) registry.get("$check")).kind()).isEqualTo("fn");

                // The fusion probe: the real session-stats plugin apply ran against
                // the Java-held registry without error, and the cordis Context
                // static surface is present in the worker.
                Object probeObj = root.get("fusionProbe");
                assertThat(probeObj).isInstanceOf(Map.class);
                Map<String, Object> probe = map(probeObj);
                assertThat(probe.get("registryName")).isEqualTo("sessionProjections");
                assertThat(probe.get("contextIsFn")).isEqualTo(true);
                assertThat(probe.get("statsMounted")).isEqualTo(true);
            } finally {
                root.fiber.dispose().join();
            }
        }
    }
}
