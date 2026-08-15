package dev.dsh.cordis;

import com.fasterxml.jackson.databind.JsonNode;

/** Validates/normalizes plugin config before a fiber activates.
 *  Equivalent to `Plugin.Config['~standard'].validate` in cordis. */
@FunctionalInterface
public interface ConfigValidator<T> {
    T validate(JsonNode node);

    default ConfigValidator<T> andThen(ConfigValidator<T> other) {
        return node -> other.validate((JsonNode) this.validate(node));
    }
}
