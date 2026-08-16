package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommandOptionTest {
    @Test
    void echoEscapeOptionTriggersEscape() throws Exception {
        try (JsHost host = new GraalJsHost()) {
            JsCtxBridge bridge = new JsCtxBridge(host, null);
            Value fn = host.eval(
                "(ctx) => { ctx.command('echo <message:text>').option('escape', '-e', { value: false })" +
                ".option('unescape', '-E', { value: false })" +
                ".action(({options, session}, message) => options.escape ? 'esc:' + message : 'echo:' + message); }");
            fn.execute(bridge.ctxShim());
            assertThat(String.valueOf(bridge.dispatchCommand("echo -e hello"))).isEqualTo("esc:hello");
            assertThat(String.valueOf(bridge.dispatchCommand("echo plain"))).isEqualTo("echo:plain");
        }
    }
}
