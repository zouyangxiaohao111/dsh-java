package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CordisErrorTest {
    @Test
    void inactiveEffectCodeAndDefaultMessage() {
        CordisError e = new CordisError(CordisError.Code.INACTIVE_EFFECT);
        assertThat(e.code).isEqualTo(CordisError.Code.INACTIVE_EFFECT);
        assertThat(e.getMessage()).isEqualTo("cannot create effect on inactive context");
    }

    @Test
    void customMessageOverridesDefault() {
        CordisError e = new CordisError(CordisError.Code.INACTIVE_EFFECT, "boom");
        assertThat(e.getMessage()).isEqualTo("boom");
    }

    @Test
    void validationErrorFormatsIssues() {
        ValidationError e = new ValidationError(java.util.List.of(
                new ValidationError.Issue("must be a string", "name")));
        assertThat(e.getMessage()).contains("invalid config:")
                .contains("- must be a string").contains("(at name)");
    }

    @Test
    void validationErrorWithoutPath() {
        ValidationError e = new ValidationError(java.util.List.of(
                new ValidationError.Issue("must be present", null)));
        assertThat(e.getMessage()).contains("- must be present").doesNotContain("(at");
    }
}
