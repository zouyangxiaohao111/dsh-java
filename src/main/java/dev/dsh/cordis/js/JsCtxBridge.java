package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Events;
import dev.dsh.cordis.util.Disposable;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bridges a JS ctx shim to the Java cordis Context (design §3.3). */
public final class JsCtxBridge {
    private final JsHost host;
    private final Context ctx;
    private Value shim;

    public JsCtxBridge(JsHost host, Context ctx) {
        this.host = host;
        this.ctx = ctx;
    }

    /** Create (once) and return the JS ctx shim bound to this bridge. */
    public Value ctxShim() {
        if (shim == null) {
            Value createCtx = host.eval(loadCtxJs());
            shim = createCtx.execute(host.graalContext().asValue(this));
        }
        return shim;
    }

    // ---- methods callable from JS ----

    @HostAccess.Export
    public Object on(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        Events.Listener l = (c, args) -> listener.execute(args);
        return ctx.on(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    @HostAccess.Export
    public Object once(String name, Value listener, Map<String, Object> opts) {
        boolean prepend = Boolean.TRUE.equals(opts.get("prepend"));
        boolean global = Boolean.TRUE.equals(opts.get("global"));
        Events.Listener l = (c, args) -> listener.execute(args);
        return ctx.once(name, l, new Events.EventOptions().prepend(prepend).global(global));
    }

    @HostAccess.Export
    public void emit(String name, Value argsArray) {
        Object[] args = toArgs(argsArray);
        ctx.emit(name, args);
    }

    @HostAccess.Export
    public Object get(String name) {
        return ctx.get(name);
    }

    @HostAccess.Export
    public Object provide(String name, Value value) {
        return ctx.provide(name, value);
    }

    @HostAccess.Export
    public Object effect(Value disposer) {
        Disposable d = () -> {
            disposer.execute();
            return CompletableFuture.completedFuture(null);
        };
        return ctx.effect(() -> d, "js-effect");
    }

    @HostAccess.Export
    public Object inject(Object deps, Value callback) {
        List<?> list = deps instanceof List<?> l ? l : List.of();
        String[] names = list.stream().map(String::valueOf).toArray(String[]::new);
        return ctx.inject(dev.dsh.cordis.Inject.of(names), (c, cfg) -> callback.execute());
    }

    private Object[] toArgs(Value argsArray) {
        if (!argsArray.hasArrayElements()) return new Object[0];
        long len = argsArray.getArraySize();
        Object[] out = new Object[(int) len];
        for (int i = 0; i < len; i++) out[i] = unwrap(argsArray.getArrayElement(i));
        return out;
    }

    /** Convert a guest value to a host Object for Java listeners (primitives become
     *  their Java boxed types; non-primitives stay as polyglot Values). */
    private static Object unwrap(Value v) {
        if (v.isString()) return v.asString();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.fitsInLong() ? v.asLong() : v.asDouble();
        return v;
    }

    private String loadCtxJs() {
        try (var is = getClass().getClassLoader().getResourceAsStream("js/ctx.js")) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load ctx.js", e);
        }
    }
}
