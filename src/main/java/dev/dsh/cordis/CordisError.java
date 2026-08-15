package dev.dsh.cordis;

/** Framework error with a stable machine-readable code. */
public class CordisError extends RuntimeException {
    public enum Code { INACTIVE_EFFECT }

    public final Code code;

    public CordisError(Code code) {
        this(code, null);
    }

    public CordisError(Code code, String message) {
        super(message != null ? message : code.name());
        this.code = code;
    }
}
