package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;

/** Built-in logging service (logger.ts:194-270). Java-ization: callable → `get(name)`. */
public final class LoggerService extends Service {
    int snExporter = 0;
    int snMessage = 0;
    final Map<Integer, Exporter> exporters = new LinkedHashMap<>();
    final List<Message> buffer = new ArrayList<>();
    int bufferSize = 1000;

    public LoggerService(Context ctx) {
        super(ctx, "logger");
        exporter(m -> { buffer.add(m); while (buffer.size() > bufferSize) buffer.remove(0); });
    }

    /** Register an exporter disposed with the current fiber (logger.ts:232-237). */
    public Disposable exporter(Exporter exporter) {
        return ctx.fiber.effect(() -> {
            int id = ++snExporter;
            this.exporters.put(id, exporter);
            return Disposable.of(() -> this.exporters.remove(id));
        }, "ctx.logger.exporter()");
    }

    /** Monotonic per-service message sequence number (logger.ts:152). */
    int nextMessageSn() { return ++snMessage; }

    /** Named logger for a subsystem (logger.ts:251-261, invoke body).
     *  注意:M1 未消费 `ctx.intercept('logger', ...)` 的 name/level 配置(对应 logger.ts invoke 体的 _resolveConfig),如需请后续补。 */
    public Logger get(String name) {
        return new Logger(name, 1, this);
    }

    /** Logger derived from the calling fiber's name (default `ctx.logger()` behavior).
     *  注意:M1 未消费 `ctx.intercept('logger', ...)` 的 name/level 配置(对应 logger.ts invoke 体的 _resolveConfig),如需请后续补。 */
    public Logger current() {
        return get(ctx.fiber.name());
    }

    void emit(Message message, int fallbackLevel) {
        for (Exporter exporter : exporters.values()) {
            if (fallbackLevel < message.level()) continue;
            exporter.export(message);
        }
    }
}
