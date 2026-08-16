package dev.dsh.cordis;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Named logger facade (logger.ts:74-162) + printf formatting layer (logger.ts:49-131). */
public final class Logger {
    public final String name;
    public final int level;
    private final LoggerService service;

    // WeakRef fiber 元数据 M1 从简,故无 meta 字段(对应 logger.ts 构造中的 meta: { fiber })
    Logger(String name, int level, LoggerService service) {
        this.name = name; this.level = level; this.service = service;
    }

    public void error(Object format, Object... args) { emit("error", 0, format, args); }
    public void info(Object format, Object... args) { emit("info", 1, format, args); }
    public void warn(Object format, Object... args) { emit("warn", 2, format, args); }
    public void debug(Object format, Object... args) { emit("debug", 3, format, args); }

    private void emit(String type, int level, Object format, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = format;
        System.arraycopy(args, 0, all, 1, args.length);
        service.emit(new Message(service.nextMessageSn(), System.currentTimeMillis(), name, type, level, all), this.level);
    }

    // ---- formatting layer (logger.ts:49-131) ----

    /** Formatter used to resolve a printf-style placeholder (logger.ts:19). */
    @FunctionalInterface
    public interface Formatter {
        Object format(Object value, Exporter exporter, Message message);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** ANSI 16-color palette indexes used for logger name coloring (logger.ts:165). */
    public static final int[] c16 = {6, 2, 3, 4, 5, 1};

    /** ANSI 256-color palette indexes used for logger name coloring (logger.ts:167-173). */
    public static final int[] c256 = {
        20, 21, 26, 27, 32, 33, 38, 39, 40, 41, 42, 43, 44, 45, 56, 57, 62,
        63, 68, 69, 74, 75, 76, 77, 78, 79, 80, 81, 92, 93, 98, 99, 112, 113,
        129, 134, 135, 148, 149, 160, 161, 162, 163, 164, 165, 166, 167, 168,
        169, 170, 171, 172, 173, 178, 179, 184, 185, 196, 197, 198, 199, 200,
        201, 202, 203, 204, 205, 206, 207, 208, 209, 214, 215, 220, 221,
    };

    /** Built-in placeholder formatters used by {@link #format} (logger.ts:50-61). */
    public static final Map<Character, Formatter> defaultFormatters = Map.ofEntries(
        Map.entry('s', (v, e, m) -> String.valueOf(v)),
        Map.entry('d', (v, e, m) -> (long) toDouble(v)),   // (long) truncates toward zero = JS Math.trunc
        Map.entry('i', (v, e, m) -> (long) toDouble(v)),
        Map.entry('f', (v, e, m) -> toDouble(v)),
        Map.entry('o', (v, e, m) -> json(v)),
        Map.entry('O', (v, e, m) -> json(v)),
        Map.entry('c', (v, e, m) -> ""),
        Map.entry('C', (v, e, m) -> color(e, code(m.name(), e.colors()), v, ""))
    );

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    private static String json(Object v) {
        if (v instanceof Throwable t) return stackOf(t);
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String stackOf(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n\tat ").append(e);
        }
        return sb.toString();
    }

    /** ANSI-wrap one value with the color code derived from its logger name (logger.ts:84-87). */
    public static String color(Exporter exporter, int code, Object value, String decoration) {
        if (exporter.colors() == 0) return String.valueOf(value);
        String prefix = code < 8 ? "3" + code : "38;5;" + code;
        String suffix = exporter.colors() >= 2 ? decoration : "";
        return "\u001b[" + prefix + suffix + "m" + value + "\u001b[0m";
    }

    /** Deterministic color index for a logger name; -1 when colors are disabled (logger.ts:89-97). */
    public static int code(String name, int level) {
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            hash = (hash << 3) - hash + name.charAt(i) + 13;
        }
        if (level <= 0) return -1;
        int[] colors = level >= 2 ? c256 : c16;
        return colors[(int) (Math.abs((long) hash) % colors.length)];
    }

    /** Format a structured message into a printable string (logger.ts:99-131). */
    public static String format(Exporter exporter, Message message) {
        Object[] args = message.args().clone();
        if (args.length > 0 && args[0] instanceof Throwable err) {
            // Error first arg → stack string with a %s placeholder (logger.ts:101-103)
            Object[] prep = new Object[args.length + 1];
            prep[0] = "%s";
            prep[1] = stackOf(err);
            System.arraycopy(args, 1, prep, 2, args.length - 1);
            args = prep;
        } else if (args.length == 0 || !(args[0] instanceof String)) {
            // non-string first arg → %o fallback (logger.ts:104-106)
            Object[] prep = new Object[args.length + 1];
            prep[0] = "%o";
            System.arraycopy(args, 0, prep, 1, args.length);
            args = prep;
        }

        String format = (String) args[0];
        StringBuilder out = new StringBuilder();
        int argIndex = 1;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (i + 1 >= format.length()) {
                out.append('%');
                break;
            }
            char next = format.charAt(i + 1);
            if (next == '%') {
                out.append('%');
                i++;
                continue;
            }
            if (Character.isLetter(next)) {
                Formatter formatter = formatterOf(exporter, next);
                if (formatter != null) {
                    Object value = argIndex < args.length ? args[argIndex] : null;
                    out.append(formatter.format(value, exporter, message));
                    argIndex++;
                } else {
                    out.append('%').append(next);
                }
                i++;
            } else {
                out.append('%');
            }
        }

        // trailing args are space-joined; non-primitives pass through the `o` formatter
        Formatter oFormatter = exporter.formatters().getOrDefault('o', defaultFormatters.get('o'));
        for (; argIndex < args.length; argIndex++) {
            Object arg = args[argIndex];
            if (arg != null && !(arg instanceof String) && !(arg instanceof Number)
                    && !(arg instanceof Boolean) && !(arg instanceof Character)) {
                out.append(' ').append(oFormatter.format(arg, exporter, message));
            } else {
                out.append(' ').append(arg);
            }
        }

        // per-line truncation (logger.ts:127-130)
        int maxLength = exporter.maxLength();
        String[] lines = out.toString().split("\r?\n", -1);
        StringBuilder result = new StringBuilder();
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.length() > maxLength) result.append(line, 0, maxLength).append("...");
            else result.append(line);
            if (li < lines.length - 1) result.append('\n');
        }
        return result.toString();
    }

    private static Formatter formatterOf(Exporter exporter, char c) {
        Formatter f = exporter.formatters().get(c);
        return f != null ? f : defaultFormatters.get(c);
    }
}
