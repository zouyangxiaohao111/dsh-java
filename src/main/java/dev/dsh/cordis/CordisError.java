package dev.dsh.cordis;

/** Framework error with a stable machine-readable code. */
public class CordisError extends RuntimeException {
    public enum Code {
        INACTIVE_EFFECT("cannot create effect on inactive context");
        public final String message;
        Code(String message) { this.message = message; }
    }

    public final Code code;

    public CordisError(Code code) {
        this(code, null);
    }

    public CordisError(Code code, String message) {
        super(message != null ? message : code.message);
        this.code = code;
    }
}
