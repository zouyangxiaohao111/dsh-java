package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CordisErrorTest {
    @Test
    void inactiveEffectCodeAndDefaultMessage() {
        CordisError e = new CordisError(CordisError.Code.INACTIVE_EFFECT);
        assertThat(e.code).isEqualTo(CordisError.Code.INACTIVE_EFFECT);
        assertThat(e.getMessage()).isEqualTo("INACTIVE_EFFECT");
    }

    @Test
    void validationErrorFormatsIssues() {
        ValidationError e = new ValidationError(java.util.List.of(
                new ValidationError.Issue("must be a string", "name")));
        assertThat(e.getMessage()).contains("invalid config:")
                .contains("- must be a string").contains("(at name)");
    }
}
