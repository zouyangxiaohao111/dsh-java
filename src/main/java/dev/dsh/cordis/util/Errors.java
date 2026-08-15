package dev.dsh.cordis.util;

/** Error composition helpers (simplified port of utils.ts composeError). */
public final class Errors {
    private Errors() {}

    /** Run `body`; if it throws, surface it as a RuntimeException. */
    public static <T> T compose(FallibleSupplier<T> body) {
        try {
            return body.get();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface FallibleSupplier<T> {
        T get() throws Exception;
    }
}
