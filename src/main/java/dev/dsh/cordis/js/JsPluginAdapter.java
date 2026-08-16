package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;
import dev.dsh.cordis.util.Disposable;
import org.graalvm.polyglot.Value;

import java.util.concurrent.CompletableFuture;

/** Wraps a JS plugin (function or { apply } object) as a Java Plugin (design §3.4). */
public final class JsPluginAdapter implements Plugin<Object> {
    private final JsHost host;
    private final Value pluginValue;   // JS default export: function OR { apply, inject, provide, name }
    private final String name;
    private final String[] inject;
    private final String[] provide;

    public JsPluginAdapter(JsHost host, Value pluginValue) {
        this.host = host;
        this.pluginValue = pluginValue;
        this.name = memberString("name");
        this.inject = memberStringArray("inject");
        this.provide = memberStringArray("provide");
    }

    @Override public String name() { return name; }
    @Override public String[] inject() { return inject; }
    @Override public String[] provide() { return provide; }

    /** The underlying JsHost backing this adapter (hot-reload closes it on swap). */
    public JsHost host() { return host; }

    @Override
    public Object apply(Context ctx, Object config) {
        JsCtxBridge bridge = new JsCtxBridge(host, ctx);
        Value jsCtx = bridge.ctxShim();
        Value applyFn = resolveApply();
        Object result = applyFn.execute(jsCtx, config);
        // 若 JS 插件返回 disposer 函数,注册为 fiber effect
        if (result instanceof Value v && v.canExecute()) {
            Value disposer = v;
            ctx.effect(() -> (Disposable) () -> {
                disposer.execute();
                return CompletableFuture.completedFuture(null);
            }, "js-plugin-disposer");
        }
        // JS 插件返回 Promise(GraalJS Value)时非可执行函数 → 返回它;Fiber.reload 只 await 真
        // CompletableFuture,GraalJS Value 不匹配 → JS async apply 暂不 await(TODO: JS Promise 完整桥接)。
        return result instanceof Value v && v.canExecute() ? null : result;
    }

    private Value resolveApply() {
        if (pluginValue.canExecute()) return pluginValue;
        Value apply = pluginValue.getMember("apply");
        if (apply != null && apply.canExecute()) return apply;
        throw new IllegalArgumentException("JS plugin is neither a function nor { apply }");
    }

    private String memberString(String key) {
        Value v = pluginValue.getMember(key);
        return v != null && v.isString() ? v.asString() : null;
    }

    private String[] memberStringArray(String key) {
        Value v = pluginValue.getMember(key);
        if (v == null || !v.hasArrayElements()) return new String[0];
        long len = v.getArraySize();
        String[] out = new String[(int) len];
        for (int i = 0; i < len; i++) out[i] = v.getArrayElement(i).asString();
        return out;
    }
}
