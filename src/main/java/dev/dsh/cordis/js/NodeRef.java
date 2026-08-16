package dev.dsh.cordis.js;

/**
 * 一个跨桥句柄(Java 侧对 worker 侧 JS 值的引用)。kind ∈ {fn, module, ctx} 指向
 * worker 侧注册表;kind ∈ {svc} 指向 Java 侧服务(经 invokeService 反射调用)。
 * 序列化为 {@code {"$kind": kind, "id": id}}。
 */
public record NodeRef(long id, String kind) {
}
