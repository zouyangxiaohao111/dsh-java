package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;

/** Built-in logging service (logger.ts:194-270). Java-ization: callable → `get(name)`. */
public final class LoggerService extends Service {
    int sn = 0;
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
            int id = ++sn;
            this.exporters.put(id, exporter);
            return Disposable.of(() -> this.exporters.remove(id));
        }, "ctx.logger.exporter()");
    }

    /** Named logger for a subsystem (logger.ts:251-261, invoke body). */
    public Logger get(String name) {
        return new Logger(name, 1, this, null);
    }

    /** Logger derived from the calling fiber's name (default `ctx.logger()` behavior). */
    public Logger current() {
        return get(ctx.fiber.name());
    }

    void emit(Message message, int fallbackLevel) {
        for (Exporter exporter : exporters.values()) {
            exporter.export(message);
        }
    }
}
