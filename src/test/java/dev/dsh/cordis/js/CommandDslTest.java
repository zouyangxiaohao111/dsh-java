package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 任务 5:command DSL 注册 + Java 模拟消息触发(design §4.2)。 */
class CommandDslTest {
    @Test
    void commandActionRunsAndReturnsReply() throws Exception {
        try (JsHost host = new GraalJsHost()) {
            JsCtxBridge bridge = new JsCtxBridge(host, null);   // 命令 DSL 测试不需完整 ctx
            Value fn = host.eval("(ctx) => { ctx.command('echo <message:text>').action(({session, options}, message) => 'echo: ' + message); }");
            fn.execute(bridge.ctxShim());
            Object reply = bridge.dispatchCommand("echo hello");
            assertThat(String.valueOf(reply)).isEqualTo("echo: hello");
        }
    }
}
