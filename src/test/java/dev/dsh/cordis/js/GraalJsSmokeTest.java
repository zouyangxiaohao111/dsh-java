package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GraalJsSmokeTest {
    @Test
    void evalAndCallJsFunction() {
        try (Context ctx = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .build()) {
            Value fn = ctx.eval("js", "(a, b) => a + b");
            assertThat(fn.canExecute()).isTrue();
            assertThat(fn.execute(2, 3).asInt()).isEqualTo(5);
        }
    }
}
