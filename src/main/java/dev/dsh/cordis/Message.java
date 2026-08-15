package dev.dsh.cordis;

/** Structured log record (logger.ts:29-39). */
public record Message(long sn, long ts, String name, String type, int level, Object[] args) {}
