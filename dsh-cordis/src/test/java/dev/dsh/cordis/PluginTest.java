package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PluginTest {
    @Test
    void specCarriesMetadata() {
        PluginSpec<Void> p = PluginSpec.<Void>of((ctx, cfg) -> { return null; })
                .name("greeter").inject("counter").provide("out");
        assertThat(p.name()).isEqualTo("greeter");
        assertThat(p.inject()).containsExactly("counter");
        assertThat(p.provide()).containsExactly("out");
    }

    @Test
    void injectResolveMergesOwnOverInherited() {
        java.util.Map<String, Object> inherited = new java.util.LinkedHashMap<>();
        inherited.put("a", "old");
        java.util.Map<String, Object> merged = Inject.resolve(Inject.of("a", "b"), inherited);
        assertThat(merged).containsEntry("a", null); // own(null) 覆盖 inherited
        assertThat(merged).containsEntry("b", null);
    }

    @Test
    void injectConfigMapForm() {
        java.util.Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("a", 1);
        java.util.Map<String, Object> merged = Inject.resolve(Inject.config(cfg), java.util.Map.of());
        assertThat(merged).containsEntry("a", 1);
    }

    @Test
    void specAccumulatesInjectConfig() {
        PluginSpec<Void> p = PluginSpec.<Void>of((ctx, cfg) -> { return null; }).injectConfig("a", 1).injectConfig("b", 2);
        assertThat(p.injectConfig()).containsEntry("a", 1).containsEntry("b", 2);
    }
}
