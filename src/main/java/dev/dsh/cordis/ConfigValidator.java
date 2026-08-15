package dev.dsh.cordis;

import com.fasterxml.jackson.databind.JsonNode;

/** Validates/normalizes plugin config before a fiber activates.
 *  Equivalent to `Plugin.Config['~standard'].validate` in cordis.
 *  Throws {@link ValidationError} when the config fails validation. */
@FunctionalInterface
public interface ConfigValidator<T> {
    T validate(JsonNode node);
}
