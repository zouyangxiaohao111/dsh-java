package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.FiberState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-6 fiber isolation — cross-worker sibling visibility through the bridge:
 * two JS plugins in the same scope (each in its own Node worker, mirroring the
 * loader's one-worker-per-JS-entry shape), the provider provides a service into
 * the shared store, the consumer reads it via {@code ctx.get()} through the
 * bridge. Before the fix the consumer's fiber-chain walk could not reach the
 * sibling's store and the read threw "cannot get property svc without inject";
 * now the shared store keyed by the isolate label makes same-scope siblings
 * visible (the dsh-base llm/sessions/agent/tools sibling reads).
 */
class SiblingVisibilityTest {

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private static Path resource(String rel) {
        return Path.of("src/test/resources/sibling-visibility").resolve(rel).toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @Test
    void siblingServiceVisibleAcrossWorkersThroughBridge() throws Exception {
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost();
             NodeWorkerJsHost consumerHost = new NodeWorkerJsHost()) {
            // provider mounts first (row order); the consumer resolves it later.
            Fiber provider = root.plugin(new JsPluginAdapter(providerHost,
                    providerHost.loadModule(resource("provider/index.js"))), null);
            provider.await().join();
            assertThat(provider.state).isEqualTo(FiberState.ACTIVE);

            Fiber consumer = root.plugin(new JsPluginAdapter(consumerHost,
                    consumerHost.loadModule(resource("consumer/index.js"))), null);
            consumer.await().join();
            assertThat(consumer.state).isEqualTo(FiberState.ACTIVE);

            Map<String, Object> read = map(root.get("siblingRead"));
            assertThat(read.get("visible")).isEqualTo(true);
            assertThat(read.get("value")).isEqualTo("from-provider");
        } finally {
            root.fiber.dispose().join();
        }
    }
}
