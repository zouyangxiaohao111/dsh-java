package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;
import dev.dsh.cordis.util.Symbols;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** M4 P3 core alignment: logger formatting layer (logger.ts:49-131) + Context
 *  is/extend(meta)/isolate(label) (context.ts:61-68,99-145) + root fiber dispose
 *  = restart (fiber.ts:331) + traceable simplification (utils.ts:117-233) + symbols. */
class P3CoreAlignmentTest {

    private static Exporter plainExporter() {
        return m -> { };
    }

    // ---- task 1: logger formatting layer (logger.ts:49-131) ----

    @Test
    void formatSubstitutesPlaceholders() {
        Message msg = new Message(1, 0, "test", "info", 1,
                new Object[]{"%s = %d, %f", "x", 42, 2.5});
        assertThat(Logger.format(plainExporter(), msg)).isEqualTo("x = 42, 2.5");
    }

    @Test
    void formatEscapesPercent() {
        Message msg = new Message(1, 0, "test", "info", 1, new Object[]{"100%% done"});
        assertThat(Logger.format(plainExporter(), msg)).isEqualTo("100% done");
    }

    @Test
    void formatNonStringFirstArgUsesO() {
        Message msg = new Message(1, 0, "test", "info", 1, new Object[]{42});
        assertThat(Logger.format(plainExporter(), msg)).isEqualTo("42");
    }

    @Test
    void formatTrailingObjectsPassThroughO() {
        Message msg = new Message(1, 0, "test", "info", 1, new Object[]{"value", Map.of("a", 1)});
        assertThat(Logger.format(plainExporter(), msg)).isEqualTo("value {\"a\":1}");
    }

    @Test
    void formatErrorFirstArgUsesStack() {
        RuntimeException ex = new RuntimeException("boom");
        Message msg = new Message(1, 0, "test", "error", 0, new Object[]{ex});
        String out = Logger.format(plainExporter(), msg);
        assertThat(out).startsWith("java.lang.RuntimeException: boom");
        assertThat(out).contains("at ");
    }

    @Test
    void formatMaxLengthTruncatesPerLine() {
        Exporter exporter = new Exporter() {
            @Override public void export(Message m) { }
            @Override public int maxLength() { return 10; }
        };
        Message msg = new Message(1, 0, "test", "info", 1, new Object[]{"%s", "abcdefghijklmnop"});
        assertThat(Logger.format(exporter, msg)).isEqualTo("abcdefghij...");
        Message multi = new Message(2, 0, "test", "info", 1, new Object[]{"%s", "aaaa\nbbbbbbbbbbb"});
        assertThat(Logger.format(exporter, multi)).isEqualTo("aaaa\nbbbbbbbbbb...");
    }

    @Test
    void formatCustomFormatterOverridesDefault() {
        Exporter exporter = new Exporter() {
            @Override public void export(Message m) { }
            @Override public Map<Character, Logger.Formatter> formatters() {
                return Map.of('x', (v, e, m) -> "<" + v + ">");
            }
        };
        Message msg = new Message(1, 0, "test", "info", 1, new Object[]{"%x", 7});
        assertThat(Logger.format(exporter, msg)).isEqualTo("<7>");
    }

    @Test
    void colorAndCodePalette() {
        // colors disabled → plain value
        assertThat(Logger.color(plainExporter(), 6, "v", "")).isEqualTo("v");
        // colors enabled → ANSI wrap; level 1 uses c16 (code < 8)
        Exporter colored = new Exporter() {
            @Override public void export(Message m) { }
            @Override public int colors() { return 2; }
        };
        int code = Logger.code("test", 1);
        assertThat(code).isGreaterThanOrEqualTo(0);
        assertThat(Logger.color(colored, code, "v", "")).startsWith("\u001b[3");
        assertThat(Logger.color(colored, code, "v", "")).endsWith("m");
        // level >= 2 uses c256 (codes >= 20)
        assertThat(Logger.code("test", 3)).isGreaterThanOrEqualTo(20);
        // level <= 0 → disabled sentinel
        assertThat(Logger.code("test", 0)).isEqualTo(-1);
        // deterministic per name
        assertThat(Logger.code("foo", 1)).isEqualTo(Logger.code("foo", 1));
        // palettes match the TS source
        assertThat(Logger.c16).containsExactly(6, 2, 3, 4, 5, 1);
        assertThat(Logger.c256).hasSize(75);
    }

    @Test
    void exporterLevelsThresholdFiltersMessages() {
        Context root = new Context();
        List<String> seen = new ArrayList<>();
        Disposable d = root.logger.exporter(new Exporter() {
            @Override public void export(Message m) { seen.add(m.type()); }
            @Override public Map<String, Integer> levels() { return Map.of("default", 2); }
        });
        root.logger().info("i");   // 1 < 2 → emit
        root.logger().warn("w");   // 2 < 2 → emit
        root.logger().debug("d");  // 2 < 3 → skip
        assertThat(seen).containsExactly("info", "warn");
        d.dispose().join();
        root.fiber.dispose().join();
    }

    @Test
    void loggerNameColorPlaceholder() {
        // %C renders the name-colored value when colors are enabled
        Exporter colored = new Exporter() {
            @Override public void export(Message m) { }
            @Override public int colors() { return 2; }
        };
        Message msg = new Message(1, 0, "test", "info", 1, new Object[]{"%C", "v"});
        assertThat(Logger.format(colored, msg)).startsWith("\u001b[");
        // %c consumes the arg without emitting it
        Message style = new Message(2, 0, "test", "info", 1, new Object[]{"%c rest", "ignored"});
        assertThat(Logger.format(plainExporter(), style)).isEqualTo(" rest");
    }

    // ---- task 2: Context is/extend(meta)/isolate(label) (context.ts:61-68, 99-145) ----

    @Test
    void contextIsBrandDetection() {
        Context root = new Context();
        assertThat(Context.is(root)).isTrue();
        assertThat(Context.is(root.extend())).isTrue();
        assertThat(Context.is("x")).isFalse();
        assertThat(Context.is(null)).isFalse();
        root.fiber.dispose().join();
    }

    @Test
    void extendMetaShadowsFiber() {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> null), null);
        Context child = root.extend(Map.of("fiber", f));
        assertThat(child.fiber).isSameAs(f);
        assertThat(child.parent).isSameAs(root);
        assertThat(child.root).isSameAs(root);
        root.fiber.dispose().join();
    }

    @Test
    void extendMetaShadowsIsolateAndIntercept() {
        Context root = new Context();
        root.provide("svc", "parent");
        Map<String, String> iso = new HashMap<>();
        iso.put("svc", "scoped");
        Context child = root.extend(Map.of(Symbols.ISOLATE, iso));
        // svc resolves in the new scope, not the parent value
        assertThat((Object) child.get("svc")).isNull();
        Context withIntercept = root.extend(Map.of(Symbols.INTERCEPT, Map.of("logger", 5)));
        assertThat(withIntercept.intercept.get("logger")).isEqualTo(5);
        root.fiber.dispose().join();
    }

    @Test
    void isolateSameLabelJoinsScopes() {
        Context root = new Context();
        root.provide("svc", "parent");
        Context a = root.isolate("svc", "shared");
        Context b = root.isolate("svc", "shared");
        a.provide("svc", "A");
        assertThat(a.<String>get("svc")).isEqualTo("A");
        assertThat(b.<String>get("svc")).isEqualTo("A");   // same label → joined scope
        assertThat(root.<String>get("svc")).isEqualTo("parent");
        root.fiber.dispose().join();
    }

    @Test
    void distinctLabelsRemainIsolated() {
        Context root = new Context();
        Context a = root.isolate("svc");
        Context b = root.isolate("svc");
        a.provide("svc", "A");
        assertThat(a.<String>get("svc")).isEqualTo("A");
        assertThat((Object) b.get("svc")).isNull();   // different auto labels
        root.fiber.dispose().join();
    }

    // ---- task 3: root fiber dispose = restart (fiber.ts:331) ----

    @Test
    void rootFiberDisposeRestartsNotTearsDown() {
        Context root = new Context();
        Fiber plugin = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> ctx.provide("tmp", "x")), null);
        plugin.await().join();
        assertThat(plugin.state).isEqualTo(FiberState.ACTIVE);

        root.fiber.dispose().join();
        // root stays ACTIVE (JS root dispose = restart), never DISPOSED
        assertThat(root.fiber.state).isEqualTo(FiberState.ACTIVE);
        // plugin fiber was torn down via the cascade disposer
        assertThat(plugin.state).isEqualTo(FiberState.DISPOSED);
        // built-in services survive the root dispose
        assertThat((Object) root.get("logger")).isNotNull();
        assertThat(root.events).isNotNull();
        assertThat(root.reflect).isNotNull();
        assertThat(root.registry).isNotNull();

        // explicit shutdown entry performs the full teardown
        root.fiber.shutdown().join();
        assertThat(root.fiber.state).isEqualTo(FiberState.DISPOSED);
    }

    @Test
    void rootRestartUnloadsPluginsAndKeepsContextAlive() {
        Context root = new Context();
        Fiber plugin = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> null), null);
        plugin.await().join();
        root.fiber.restart().join();
        assertThat(root.fiber.state).isEqualTo(FiberState.ACTIVE);
        assertThat(plugin.state).isEqualTo(FiberState.DISPOSED);
        // still usable
        assertThat(root.registry).isNotNull();
        root.fiber.shutdown().join();
    }

    // ---- task 4: traceable simplification — caller ctx reaches the logger ----

    @Test
    void loggerDerivesNameFromCallerFiber() {
        Context root = new Context();
        List<String> names = new ArrayList<>();
        root.logger.exporter(new Exporter() {
            @Override public void export(Message m) { names.add(m.name()); }
        });
        Fiber plugin = root.plugin(PluginSpec.<Void>of((ctx, cfg) -> {
            ctx.logger().info("hi");   // caller ctx → plugin fiber name
            return null;
        }).name("named-plugin"), null);
        plugin.await().join();
        assertThat(names).contains("named-plugin");
        root.fiber.dispose().join();
    }

    // ---- task 5: symbols completion (utils.ts:50-73) ----

    @Test
    void symbolsKeysMatchTsDescriptors() {
        assertThat(Symbols.SHADOW).isEqualTo("cordis.shadow");
        assertThat(Symbols.RECEIVER).isEqualTo("cordis.receiver");
        assertThat(Symbols.ORIGINAL).isEqualTo("cordis.original");
        assertThat(Symbols.METADATA).isEqualTo("cordis.metadata");
        assertThat(Symbols.INIT_HOOKS).isEqualTo("cordis.initHooks");
        assertThat(Symbols.CHECK_PROTO).isEqualTo("cordis.checkProto");
        assertThat(Symbols.EFFECT).isEqualTo("cordis.effect");
        assertThat(Symbols.FILTER).isEqualTo("cordis.filter");
        assertThat(Symbols.ISOLATE).isEqualTo("cordis.isolate");
        assertThat(Symbols.INTERCEPT).isEqualTo("cordis.intercept");
        assertThat(Symbols.INIT).isEqualTo("cordis.init");
        assertThat(Symbols.CHECK).isEqualTo("cordis.check");
        assertThat(Symbols.CONFIG).isEqualTo("cordis.config");
        assertThat(Symbols.INVOKE).isEqualTo("cordis.invoke");
        assertThat(Symbols.EXTEND).isEqualTo("cordis.extend");
        assertThat(Symbols.TRACKER).isEqualTo("cordis.tracker");
        assertThat(Symbols.RESOLVE_CONFIG).isEqualTo("cordis.resolveConfig");
    }
}
