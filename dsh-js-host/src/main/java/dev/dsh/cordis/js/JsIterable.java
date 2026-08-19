package dev.dsh.cordis.js;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * M7-7:Java 侧对 JS 侧 iterable/iterator 的跨桥句柄(对应 worker {@code $kind:'iter'} 标记)。
 *
 * <p>实现 {@link Iterable} + {@link Iterator},使 Java 的 {@code for-each} 能直接遍历
 * JS 提供 / 方法返回的 iterable(agent-loop 遍历 {@code ctx.agents}、goal-round-driver 的
 * {@code ctx.agents.list()} 同形)。每次 {@link #hasNext()}/{@link #next()} 经
 * {@code invokeObj} RPC 到 worker:worker 侧的 {@code makeIterableView} 统一暴露 {@code next()}
 * (JS 迭代器协议 {@code {done, value}}),Java 侧 pre-fetch 一次缓存以支持 {@code hasNext()}。
 *
 * <p>遍历终结({@code done} = true)自动 {@link #release()} 释放 worker 侧句柄(注册表删除),
 * 防止长生命周期宿主在 objById 累积泄漏;调用方也可显式 {@link #release()}。句柄同时注册
 * {@link java.lang.ref.Cleaner},代理 GC 后兜底释放。
 */
public final class JsIterable implements Iterable<Object>, Iterator<Object> {
    private final NodeWorkerJsHost host;
    private final long handle;
    private boolean done;
    private boolean hasCached;
    private Object cachedValue;
    private boolean released;

    JsIterable(NodeWorkerJsHost host, long handle) {
        this.host = host;
        this.handle = handle;
    }

    /** 底层 worker 句柄 id(测试 / 显式释放用)。 */
    public long handle() { return handle; }

    @Override public Iterator<Object> iterator() { return this; }

    @Override public boolean hasNext() {
        // M8 low ④:release 后守卫 —— 句柄已释放(显式 release / 遍历终结),不再跨桥调用
        // worker(已删除的 objById 条目会回 "unknown handle")。按遍历终结语义返回 false。
        if (released || done) return false;
        if (!hasCached) {
            Object step = host.invokeObj(handle, "next", List.of());
            if (step instanceof Map<?, ?> m) {
                done = Boolean.TRUE.equals(m.get("done"));
                cachedValue = m.get("value");
            } else {
                // 异常形状:worker 应返回 {done, value};按 done 终止
                done = true;
            }
            hasCached = true;
            if (done) release();
        }
        return !done;
    }

    @Override public Object next() {
        if (released || done) throw new NoSuchElementException();
        if (!hasCached) hasNext();
        if (released || done) throw new NoSuchElementException();
        hasCached = false;
        return cachedValue;
    }

    /** 显式释放 worker 侧句柄(遍历终结 / 调用方主动放弃)。幂等。 */
    public void release() {
        if (released) return;
        released = true;
        host.releaseObj(handle);
    }
}
