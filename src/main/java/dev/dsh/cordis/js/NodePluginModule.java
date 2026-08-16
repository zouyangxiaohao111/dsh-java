package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.util.Disposable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Node worker 版 {@link PluginModule}:包装远程句柄({@link NodeRef},kind ∈
 * {module, fn})。apply 经 RPC 在 worker 进程内执行,ctx 桥操作(emit/on/get/...)经
 * {@link NodeWorkerBridge} 往返 Java 核心。
 */
public final class NodePluginModule implements PluginModule {
    private final NodeWorkerJsHost host;
    private final NodeRef ref;
    private final String name;
    private final String[] inject;
    private final String[] provide;

    NodePluginModule(NodeWorkerJsHost host, NodeRef ref, String name, String[] inject, String[] provide) {
        this.host = host;
        this.ref = ref;
        this.name = name;
        this.inject = inject;
        this.provide = provide;
    }

    /** The remote handle backing this module. */
    public NodeRef ref() { return ref; }

    @Override public String name() { return name; }
    @Override public String[] inject() { return inject; }
    @Override public String[] provide() { return provide; }

    @Override
    public Object apply(Context ctx, Object config) {
        NodeWorkerBridge bridge = new NodeWorkerBridge(host, ctx);
        // worker 侧建 ctx shim(桥 id 与 ctx 绑定),再发 apply
        NodeRef ctxRef = host.createCtx(bridge);
        Object result = host.applyPlugin(ref, ctxRef, config);

        // 先注册 ctx 释放,再注册 disposer:effect 逆序执行 ⇒ disposer 先跑,ctx 后释放
        // (disposer 里可能还会调 ctx.emit,若 ctx 已释放会报 "unknown ctx handle")。
        NodeRef released = ctxRef;
        ctx.effect(() -> (Disposable) () -> {
            host.releaseCtx(released);
            return CompletableFuture.completedFuture(null);
        }, "node-js-ctx-release");

        // JS 插件返回 disposer 函数 → 注册为 fiber effect(卸载时经 RPC 调用)
        if (result instanceof NodeRef r && "fn".equals(r.kind())) {
            NodeRef disposer = r;
            ctx.effect(() -> (Disposable) () -> {
                host.invokeFn(disposer, List.of());
                return CompletableFuture.completedFuture(null);
            }, "node-js-plugin-disposer");
        }

        // disposer 已作为 effect 处理,不向上返回函数句柄
        return result instanceof NodeRef r && "fn".equals(r.kind()) ? null : result;
    }
}
