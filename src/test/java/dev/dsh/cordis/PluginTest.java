package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PluginTest {
    @Test
    void specCarriesMetadata() {
        PluginSpec<Void> p = PluginSpec.of((ctx, cfg) -> {})
                .name("greeter").inject("counter").provide("out");
        assertThat(p.name()).isEqualTo("greeter");
        assertThat(p.inject()).containsExactly("counter");
        assertThat(p.provide()).containsExactly("out");
    }
}
