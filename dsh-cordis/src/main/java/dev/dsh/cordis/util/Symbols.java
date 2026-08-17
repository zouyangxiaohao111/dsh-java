package dev.dsh.cordis.util;

/** Named keys standing in for JS unique symbols (utils.ts:50-73).
 *  The string values mirror the JS {@code Symbol.for('cordis.X')} descriptors so a
 *  future JS-bridge can map between the two representations 1:1. */
public final class Symbols {
    private Symbols() {}

    // internal symbols
    /** Marks a traced shadow object (utils.ts:52). */
    public static final String SHADOW = "cordis.shadow";
    /** Receiver override on a traced proxy (utils.ts:53). */
    public static final String RECEIVER = "cordis.receiver";
    /** Underlying target of a traced proxy (utils.ts:54). */
    public static final String ORIGINAL = "cordis.original";
    /** Service-invocation metadata key (utils.ts:55). */
    public static final String METADATA = "cordis.metadata";
    /** Constructor hooks invoked after a class-style plugin is `new`-ed (utils.ts:56). */
    public static final String INIT_HOOKS = "cordis.initHooks";
    /** Marks an inject map as inherited from a superclass (utils.ts:57). */
    public static final String CHECK_PROTO = "cordis.checkProto";

    // context symbols
    /** Marker key under which a disposer exposes its EffectMeta tree (utils.ts:60). */
    public static final String EFFECT = "cordis.effect";
    /** Listener filter consulted on event dispatch (utils.ts:61). */
    public static final String FILTER = "cordis.filter";
    /** Isolation scope map key (utils.ts:62). */
    public static final String ISOLATE = "cordis.isolate";
    /** Intercept config map key (utils.ts:63). */
    public static final String INTERCEPT = "cordis.intercept";

    // service symbols
    /** Class-style plugin init method (utils.ts:66). */
    public static final String INIT = "cordis.init";
    /** Service availability predicate (utils.ts:67). */
    public static final String CHECK = "cordis.check";
    /** Config validator key (utils.ts:68). */
    public static final String CONFIG = "cordis.config";
    /** Callable-service invocation dispatch key (utils.ts:69). */
    public static final String INVOKE = "cordis.invoke";
    /** Service extend/child hook (utils.ts:70). */
    public static final String EXTEND = "cordis.extend";
    /** Traceable proxy tracker key (utils.ts:71). */
    public static final String TRACKER = "cordis.tracker";
    /** Ancestor intercept config resolution key (utils.ts:72). */
    public static final String RESOLVE_CONFIG = "cordis.resolveConfig";
}
