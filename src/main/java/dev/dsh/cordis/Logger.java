package dev.dsh.cordis;

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
