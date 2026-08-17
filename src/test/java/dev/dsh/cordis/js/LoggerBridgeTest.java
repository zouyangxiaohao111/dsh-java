package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Exporter;
import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.Logger;
import dev.dsh.cordis.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 NEEDS #4 — {@code ctx.logger} 桥(worker → Java Logger 格式化层)。
 *
 * <p>The worker-side {@code ctx.logger(name).warn/info/error/debug} and the no-name
 * {@code ctx.logger.warn(...)} (both callable-and-methods per cordis) forward via
 * {@code ctxCall 'logger'} to {@link NodeWorkerBridge#handleCtxCall}, which routes
 * to Java {@code ctx.logger(name).<type>(...)}. The message enters the Java
 * {@code LoggerService} and flows through the P3-aligned {@link Logger#format}
 * layer (printf placeholders, per-line truncation, ANSI name color), so a Java
 * exporter observes the fully formatted output.
 *
 * <p>This test drives the real {@code @deepseek-ai/dsh-agent} AgentRegistry in the
 * worker: a throwing JS listener on {@code agent/disposed} makes the registry's
 * {@code emitDisposed()} call {@code this.ctx.logger.warn(...)} (the real agent
 * logger path) while the plugin fiber is disposed — captured by the Java exporter.
 *
 * <p><b>前置</b>: node 可执行(测试用)。{@code @deepseek-ai/*} overlays 随测试提交。
 */
class LoggerBridgeTest {

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private static Path overlay(String rel) {
        return Path.of("src/test/resources/agent-fusion").resolve(rel).toAbsolutePath();
    }

    private static String format(Exporter exporter, Message m) {
        return Logger.format(exporter, m);
    }

    /** Worker logger calls cross the bridge into the Java formatting layer; the real
     *  dsh AgentRegistry's ctx.logger.warn path is captured when its fiber is disposed. */
    @Test
    void workerLoggerCrossesBridgeIntoJavaFormattingLayer() throws Exception {
        Context root = new Context();
        List<Message> captured = new ArrayList<>();
        Exporter exporter = new Exporter() {
            @Override public void export(Message m) { captured.add(m); }
            // raise the default threshold so warn/debug (level 2/3) pass the
            // P3 level filter (default logger level is INFO=1, which would drop them).
            @Override public Map<String, Integer> levels() { return Map.of("default", 3); }
        };
        root.logger.exporter(exporter);
        // A second exporter with ANSI colors enabled proves the P3 color layer
        // (the %C placeholder) is reached from the worker across the bridge.
        List<String> coloredOut = new ArrayList<>();
        Exporter colored = new Exporter() {
            @Override public void export(Message m) { coloredOut.add(Logger.format(this, m)); }
            @Override public int colors() { return 2; }
            @Override public Map<String, Integer> levels() { return Map.of("default", 3); }
        };
        root.logger.exporter(colored);

        Fiber pluginFiber = null;
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            try {
                pluginFiber = root.plugin(new JsPluginAdapter(host, host.loadModule(overlay("logger-driver/index.js"))),
                        Map.of("agentId", "a1"));

                // Named-logger printf calls from the worker crossed the bridge and the
                // P3 formatting layer expanded the placeholders.
                List<Message> named = captured.stream().filter(m -> "worker-sub".equals(m.name())).toList();
                assertThat(named).isNotEmpty();
                assertThat(format(exporter, find(named, "warn"))).isEqualTo("warn app of 7");
                assertThat(format(exporter, find(named, "info"))).isEqualTo("info ok");
                assertThat(format(exporter, find(named, "error"))).isEqualTo("error bad");
                assertThat(format(exporter, find(named, "debug"))).isEqualTo("debug hidden");
                // Same %C message through an ANSI exporter → the P3 color layer is
                // reached from the worker (name-derived 256-color decoration).
                Message coloredMsg = named.stream()
                        .filter(m -> "info".equals(m.type()) && String.valueOf(m.args()[0]).contains("colored %C"))
                        .findFirst().orElseThrow();
                assertThat(format(exporter, coloredMsg)).isEqualTo("colored app");
                assertThat(coloredOut).anySatisfy(line -> {
                    assertThat(line).startsWith("colored [38;5;");
                    assertThat(line).endsWith("app[0m");
                });

                // No-name form → fiber-derived default logger name (cordis semantics:
                // ctx.logger.info(...) ≈ ctx.logger().info(...)).
                Message noName = captured.stream()
                        .filter(m -> "logger-driver".equals(m.name()) && "info".equals(m.type()))
                        .findFirst().orElseThrow();
                assertThat(format(exporter, noName)).isEqualTo("no-name x");

                // Agent registration itself must not warn (no throwing agent/created listener).
                assertThat(captured.stream()
                        .filter(m -> "warn".equals(m.type()) && String.valueOf(m.args()[0]).contains("agent/disposed"))
                        .toList()).isEmpty();

                // Disposing the plugin fiber runs the register disposer → detach →
                // emitDisposed → the throwing agent/disposed listener is caught and the
                // registry calls ctx.logger.warn across the bridge.
                pluginFiber.dispose().join();

                Message warn = captured.stream()
                        .filter(m -> "warn".equals(m.type()) && String.valueOf(m.args()[0]).contains("agent/disposed"))
                        .findFirst().orElseThrow();
                String text = format(exporter, warn);
                assertThat(text).contains("agent \"a1\"");
                assertThat(text).contains("agent/disposed listener threw");
                assertThat(text).contains("boom-from-listener");
            } finally {
                if (pluginFiber != null) pluginFiber.dispose().join();
                root.fiber.dispose().join();
            }
        }
    }

    private static Message find(List<Message> msgs, String type) {
        return msgs.stream().filter(m -> type.equals(m.type())).findFirst().orElseThrow();
    }
}
