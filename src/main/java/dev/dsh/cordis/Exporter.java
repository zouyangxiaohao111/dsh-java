package dev.dsh.cordis;

/** Sink receiving structured log messages (logger.ts:41-47). */
@FunctionalInterface
public interface Exporter {
    void export(Message message);
}
