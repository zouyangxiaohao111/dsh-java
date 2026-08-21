package dev.dsh.cordis.js;

/**
 * 一个跨桥句柄(Java 侧对 worker 侧 JS 值的引用)。kind ∈ {fn, module, ctx} 指向
 * worker 侧注册表;kind ∈ {svc} 指向 Java 侧服务(经 invokeService 反射调用)。
 * 序列化为 {@code {"$kind": kind, "id": id}}。
 *
 * <p>{@code async} 仅对 fn 有意义(M11-7):标记该 fn 是否为 async 函数 —— async fn
 * 回调(如 agents.create 的 setup,内部 fs.stat/动态 import 依赖 macrotask)被跨 worker
 * 调用时必须异步(不阻塞调用方 worker 事件循环,防互等死锁);同步 fn(registerProvider
 * 的 create)保持同步。async 标记在 exportFn(全局句柄)与跨 worker 转发时保留,读方
 * worker 的 remote stub 据此分流。
 */
public record NodeRef(long id, String kind, boolean async) {
    public NodeRef(long id, String kind) {
        this(id, kind, false);
    }
}
