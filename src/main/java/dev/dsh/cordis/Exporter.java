package dev.dsh.cordis;

import java.util.Map;

/** Sink receiving structured log messages (logger.ts:41-47), plus optional
 *  formatting fields: {@code colors}, {@code maxLength}, {@code levels},
 *  {@code formatters}. */
@FunctionalInterface
public interface Exporter {
    void export(Message message);

    /** ANSI color depth: 0 = disabled, 1 = 16-color, 2+ = 256-color (logger.ts:42). */
    default int colors() { return 0; }

    /** Max characters per line before truncation (logger.ts:43; JS default 10240). */
    default int maxLength() { return 10240; }

    /** Per-name severity threshold; the {@code "default"} key applies to names not
     *  listed. A message is skipped when this threshold is below its level (logger.ts:44). */
    default Map<String, Integer> levels() { return Map.of(); }

    /** Custom placeholder formatters overriding the built-in {@code defaultFormatters} (logger.ts:45). */
    default Map<Character, Logger.Formatter> formatters() { return Map.of(); }
}
