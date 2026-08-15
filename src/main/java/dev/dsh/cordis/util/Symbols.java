package dev.dsh.cordis.util;

/** Named keys standing in for JS unique symbols (utils.ts:50-73). */
public final class Symbols {
    private Symbols() {}
    /** Marks an inject map as inherited from a superclass (checkProto). */
    public static final String CHECK_PROTO = "cordis.checkProto";
    /** Marker key under which a disposer exposes its EffectMeta tree. */
    public static final String EFFECT = "cordis.effect";
    /** Isolation scope map key. */
    public static final String ISOLATE = "cordis.isolate";
    /** Intercept config map key. */
    public static final String INTERCEPT = "cordis.intercept";
}
