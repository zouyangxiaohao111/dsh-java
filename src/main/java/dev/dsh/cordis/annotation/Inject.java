package dev.dsh.cordis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-based service dependency declaration (registry.ts:37-60 {@code @Inject}
 * class decorator; design §3.3).
 *
 * <p>Annotate a class plugin to declare the services it requires; the plugin
 * registry normalizes these into its dependency map, merged with any
 * {@code inject()}/{@code injectConfig()} overrides on the plugin (own entries
 * win). The method-decorator form (deferring a method call until services are
 * available) is not ported — dependencies are declared at class scope.
 *
 * <p>Named {@code Inject} to mirror the TS decorator; it lives in the
 * {@code dev.dsh.cordis.annotation} package so it does not collide with the
 * {@code dev.dsh.cordis.Inject} declaration utility.
 *
 * <pre>{@code
 * import dev.dsh.cordis.annotation.Inject;
 *
 * @Inject({"counter"})
 * public class Greeter implements Plugin<Void> { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Inject {
    /** Required service name(s); {@code @Inject("a")} and {@code @Inject({"a", "b"})} both work. */
    String[] value();
}
