package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Fiber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 agent-fusion — the real {@code @deepseek-ai/dsh-agent} package
 * (core/agent: the {@code AgentRegistry} Service) loaded into the
 * NodeWorkerJsHost worker, its ctx bridged back to the JAVA cordis core.
 *
 * <p>The worker loads the {@code agent-driver} plugin (ESM, bare specifiers):
 * it imports {@code @deepseek-ai/dsh-agent} (overlay of the real package,
 * type-stripped), whose value deps are the {@code @deepseek-ai/cordis} shim
 * (now exposing {@code FiberState}/{@code getTraceable}/{@code symbols.original}
 * for the registry), the dsh-scope overlay ({@code scopeTarget}) and the real
 * {@code node:async_hooks} {@code AsyncLocalStorage} (initiator semantics stay
 * native inside the worker). Instantiating {@code AgentRegistry} runs the
 * constructor's seams through the worker ctx shim bridge:
 *
 * <ul>
 *   <li>{@code ctx.inject(['typert'], ...)} — inert, typert never resolves;</li>
 *   <li>{@code ctx.accessor('agent', {get})} — the new accessor seam;</li>
 *   <li>{@code ctx.on('internal/status', ...)} — fire-and-forget, contained;</li>
 *   <li>{@code ctx.effect(generator)} — the enhanced generator-effect seam that
 *       runs generator bodies to completion (cordis semantics);</li>
 *   <li>{@code this.ctx.events.dispatch('emit', args)} (via announce /
 *       emitDisposed) — the new events.dispatch seam into the Java core;</li>
 *   <li>{@code this.ctx.fiber} — a minimal terminating fiber seam;</li>
 *   <li>{@code ctx.serial} — the ordered JS-listener fold seam.</li>
 * </ul>
 *
 * <p>Java registers listeners on {@code agent/created}/{@code agent/status}/
 * {@code agent/disposed}; the worker's {@code register()} announces
 * {@code agent/created} into the Java core, and disposing the plugin fiber runs
 * the register effect disposer, emitting {@code agent/disposed} back.
 *
 * <p><b>前置</b>: node 可执行(测试用)。{@code @deepseek-ai/*} overlays 随测试提交。
 */
class AgentFusionTest {

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

    /** Java registers listeners → worker registers an agent → events fire into Java. */
    @Test
    void agentRegistryRegistersAgentAndEmitsEventsThroughNodeWorker() throws Exception {
        Context root = new Context();
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
                root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-driver/index.js"))),
                        Map.of("agentId", "a1"));

                // 'agent/created' fired into the Java core at registration time
                // (the worker's generator effect ran announce()).
                assertThat(created).containsExactly("a1");
                // 'agent/status' fired through the fused agentEvents dispatcher.
                assertThat(status).containsExactly("running");

                // The registry service ('ctx.agents') crossed the bridge: methods are fn handles.
                Map<String, Object> agents = map(root.get("agents"));
                assertThat(agents.get("name")).isEqualTo("agents");
                assertThat(agents.get("get")).isInstanceOf(NodeRef.class);
                assertThat(agents.get("list")).isInstanceOf(NodeRef.class);
                assertThat(agents.get("roots")).isInstanceOf(NodeRef.class);

                // Java drives the registry over the bridge: read the live entry.
                Map<String, Object> got = map(host.invokeFn((NodeRef) agents.get("get"), List.of("a1")));
                assertThat(got.get("id")).isEqualTo("a1");
                List<Map<String, Object>> all = list(host.invokeFn((NodeRef) agents.get("list"), List.of()));
                assertThat(all).extracting(a -> a.get("id")).containsExactly("a1");
                // A missing id reads back as JS undefined (bridge sentinel) — never a map.
                Object missing = host.invokeFn((NodeRef) agents.get("get"), List.of("missing"));
                assertThat(missing).isNotInstanceOf(Map.class);

                // The initiator (AsyncLocalStorage) + serial probe snapshot.
                Map<String, Object> probe = map(root.get("agentDriverProbe"));
                assertThat(probe.get("serviceName")).isEqualTo("agents");
                assertThat(probe.get("agentId")).isEqualTo("a1");
                assertThat(((Number) probe.get("listLength")).intValue()).isEqualTo(1);
                assertThat(((Number) probe.get("rootCount")).intValue()).isEqualTo(1);
                // Real ALS inside the worker: boundary carries the agent; cleared outside.
                assertThat(probe.get("initiatorInside")).isEqualTo("a1");
                assertThat(probe.get("initiatorOutside")).isNull();
                assertThat(probe.get("requireThrows")).isEqualTo("no initiating agent is active");
                // events.dispatch (emit) + serial seams folded JS listeners. serial returns
                // the JS undefined (bridge sentinel) when no listener bails — cordis semantics.
                assertThat(probe.get("statusHeard")).asList().containsExactly("running");
                Object serial = probe.get("serialResult");
                assertThat(serial).satisfiesAnyOf(
                        r -> assertThat(r).isNull(),
                        r -> assertThat(r).isEqualTo(NodeWorkerJsHost.UNDEFINED));
                // ALS survives an async (microtask) hop inside the boundary — Java invokes
                // the probe fn through the bridge (top-level pump on the worker side).
                Object asyncProbe = root.get("probeAsyncInitiator");
                assertThat(asyncProbe).isInstanceOf(NodeRef.class);
                assertThat(host.invokeFn((NodeRef) asyncProbe, List.of())).isEqualTo("a1");
            } finally {
                root.fiber.dispose().join();
            }
        }
    }

    /** Disposing the owning fiber runs the register effect disposer → 'agent/disposed'. */
    @Test
    void agentDisposedFiresWhenOwnerFiberDisposesThroughNodeWorker() throws Exception {
        Context root = new Context();
        List<String> created = new ArrayList<>();
        List<String> disposed = new ArrayList<>();
        root.on("agent/created", (ctx, args) -> {
            Map<String, Object> payload = map(args[0]);
            Map<String, Object> agent = map(payload.get("agent"));
            created.add(String.valueOf(agent.get("id")));
            return null;
        });
        root.on("agent/disposed", (ctx, args) -> {
            Map<String, Object> payload = map(args[0]);
            Map<String, Object> agent = map(payload.get("agent"));
            disposed.add(String.valueOf(agent.get("id")));
            return null;
        });

        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            Fiber pluginFiber = null;
            try {
                pluginFiber = root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("agent-driver/index.js"))),
                        Map.of("agentId", "a1"));
                assertThat(created).containsExactly("a1");
                assertThat(disposed).isEmpty();

                // Unload the plugin fiber: the agents.register() effect disposer
                // (detach) emits 'agent/disposed' into the Java core synchronously.
                pluginFiber.dispose().join();

                assertThat(disposed).containsExactly("a1");
                // The registry is unprovided once its owning fiber is gone.
                assertThat((Object) root.get("agents")).isNull();
            } finally {
                if (pluginFiber != null) pluginFiber.dispose().join();
                root.fiber.dispose().join();
            }
        }
    }
}
