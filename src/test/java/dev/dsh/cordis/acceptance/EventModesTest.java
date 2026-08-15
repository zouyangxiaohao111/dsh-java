package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

class EventModesTest {
    @Test
    void bailStopsOnFirstBailValue() {
        Context root = new Context();
        AtomicInteger calls = new AtomicInteger();
        root.on("e", (ctx, args) -> { calls.incrementAndGet(); return "stop"; });
        root.on("e", (ctx, args) -> { calls.incrementAndGet(); return null; });
        Object result = root.bail("e");
        assertThat(result).isEqualTo("stop");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void serialStopsOnBail() {
        Context root = new Context();
        AtomicInteger calls = new AtomicInteger();
        root.on("s", (ctx, args) -> { calls.incrementAndGet(); return "stop"; });
        root.on("s", (ctx, args) -> { calls.incrementAndGet(); return null; });
        Object result = root.serial("s").join();
        assertThat(result).isEqualTo("stop");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void parallelRunsAllAndAggregatesErrors() {
        Context root = new Context();
        AtomicInteger ran = new AtomicInteger();
        root.on("p", (ctx, args) -> { ran.incrementAndGet(); throw new IllegalStateException("boom1"); });
        root.on("p", (ctx, args) -> { ran.incrementAndGet(); return null; });
        Throwable t = catchThrowable(() -> root.parallel("p").join());
        assertThat(ran.get()).isEqualTo(2);   // 全部执行,不 fail-fast
        assertThat(t).hasRootCauseInstanceOf(Events.AggregateError.class);
    }

    @Test
    void waterfallComposesAroundNext() {
        Context root = new Context();
        StringBuilder trace = new StringBuilder();
        root.on("wf", (ctx, args) -> {
            trace.append("m1-before;");
            ((Supplier<Object>) args[args.length - 1]).get();
            trace.append("m1-after;");
            return null;
        });
        root.on("wf", (ctx, args) -> {
            trace.append("m2-before;");
            ((Supplier<Object>) args[args.length - 1]).get();
            trace.append("m2-after;");
            return null;
        });
        Events.Listener inner = (ctx, args) -> { trace.append("inner;"); return null; };
        root.waterfall("wf", inner);
        assertThat(trace.toString()).isEqualTo("m1-before;m2-before;inner;m2-after;m1-after;");
    }
}
