package dev.dsh.cordis.util;

import java.util.concurrent.CompletableFuture;

/** A cleanup callback. May be async; disposal is awaited. */
@FunctionalInterface
public interface Disposable {
    CompletableFuture<Void> dispose();

    static Disposable of(Runnable run) {
        return () -> {
            try { run.run(); return CompletableFuture.completedFuture(null); }
            catch (Throwable t) { return CompletableFuture.failedFuture(t); }
        };
    }

    static Disposable none() {
        return () -> CompletableFuture.completedFuture(null);
    }
}
