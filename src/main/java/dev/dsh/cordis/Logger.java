package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;

/** Structured log record (logger.ts:29-39). */
public record Message(long sn, long ts, String name, String type, int level, Object[] args) {}

/** Sink receiving structured log messages (logger.ts:41-47). */
@FunctionalInterface
public interface Exporter {
    void export(Message message);
}

/** Named logger facade (logger.ts:74-162). */
public final class Logger {
    public final String name;
    public final int level;
    private final LoggerService service;
    private final Message meta;

    Logger(String name, int level, LoggerService service, Message meta) {
        this.name = name; this.level = level; this.service = service; this.meta = meta;
    }

    public void error(Object format, Object... args) { emit("error", 0, format, args); }
    public void info(Object format, Object... args) { emit("info", 1, format, args); }
    public void warn(Object format, Object... args) { emit("warn", 2, format, args); }
    public void debug(Object format, Object... args) { emit("debug", 3, format, args); }

    private void emit(String type, int level, Object format, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = format;
        System.arraycopy(args, 0, all, 1, args.length);
        service.emit(new Message(0, System.currentTimeMillis(), name, type, level, all), this.level);
    }
}

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
