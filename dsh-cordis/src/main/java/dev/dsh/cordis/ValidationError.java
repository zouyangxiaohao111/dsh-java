package dev.dsh.cordis;

/** Error raised when plugin configuration fails validation. */
public class ValidationError extends RuntimeException {
    public record Issue(String message, String path) {}

    private final java.util.List<Issue> issues;

    public ValidationError(java.util.List<Issue> issues) {
        super(buildMessage(issues));
        this.issues = java.util.List.copyOf(issues);
    }

    private static String buildMessage(java.util.List<Issue> issues) {
        StringBuilder sb = new StringBuilder("invalid config:");
        for (Issue i : issues) {
            sb.append("\n  - ").append(i.message());
            if (i.path() != null) sb.append(" (at ").append(i.path()).append(')');
        }
        return sb.toString();
    }

    public java.util.List<Issue> issues() { return issues; }
}
