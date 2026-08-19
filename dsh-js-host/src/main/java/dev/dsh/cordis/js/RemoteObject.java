package dev.dsh.cordis.js;

import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M7-7:Java 侧对 JS 侧 live 对象(带原型方法的类实例等,对应 worker {@code $kind:'obj'} 标记)
 * 的跨桥句柄。
 *
 * <p>方法调用经 {@code invokeObj} RPC 到 worker 侧执行并返回结果;结果里的嵌套 live 对象 /
 * iterable 由 worker 侧 {@code serializeValue(liveHandles)} 递归句柄化 —— Java 再得一个
 * 新的 {@link RemoteObject}/{@link JsIterable},逐层按需 RPC,不强转纯数据。
 *
 * <p>句柄在 {@link #release()} 或 GC({@link java.lang.ref.Cleaner},宿主注册)后释放。
 * 提供 {@link #call(String, Object...)} 通用调用,与 {@link #as(Class)} 类型化动态代理
 * (方法名 → RPC,便于 Java 侧按接口直调)。
 *
 * <p>M7-8:实现 {@link Map} 门面 —— 经 provide 通道注册的 live 服务在 Java 侧仍可像 M7-7 的
 * 服务值(Map)那样按成员名读:方法成员 → fn 句柄({@link NodeRef}),getter/数据字段 →
 * {@code invokeGet} 读值(触发 worker 侧 getter)。{@code map(root.get("service"))} 的既有
 * 消费方式(方法经 fn 句柄 invokeFn 调、字段直读)保持可用,同时方法/getter 已可跨 worker
 * 路由到属主 worker 执行。只读门面:变更类方法抛 {@link UnsupportedOperationException}。
 */
public final class RemoteObject extends AbstractMap<String, Object> {
    private final NodeWorkerJsHost host;
    private final long handle;
    private volatile boolean released;

    RemoteObject(NodeWorkerJsHost host, long handle) {
        this.host = host;
        this.handle = handle;
    }

    /** 底层 worker 句柄 id(测试 / 显式释放用)。 */
    public long handle() { return handle; }

    /** 属主宿主(跨 worker 导出 / 路由诊断用)。 */
    NodeWorkerJsHost host() { return host; }

    /** 调用 JS 对象的一个方法(无参方法用空 varargs)。 */
    public Object call(String method, Object... args) {
        if (released) throw new NodeBridgeError("remote object handle " + handle + " already released");
        return host.invokeObj(handle, method, args == null ? List.of() : Arrays.asList(args));
    }

    /**
     * 读取 JS 对象的一个属性(M7-7 发射器形状补齐):JS 属性访问本身会触发 getter,故
     * getter 派生值(config / sandboxMode)、数据字段(id)与子发射器成员(session.events,
     * live 子对象)都能经此读回。结果递归句柄化:live 子对象 / iterable → 新
     * {@link RemoteObject}/{@link JsIterable}(可继续订阅/遍历),函数 → fn 句柄。
     */
    public Object get(String property) {
        if (released) throw new NodeBridgeError("remote object handle " + handle + " already released");
        Object v = host.invokeGet(handle, property);
        return v == NodeWorkerJsHost.UNDEFINED ? null : v;   // Map 门面把 undefined 归一为 null
    }

    /**
     * 把该远程对象投射为一个 Java 接口的动态代理:接口方法调用按方法名转发到 worker
     * (参数 / 返回值走桥序列化)。仅供调用方把已知形状的远程对象当本地接口用。
     */
    @SuppressWarnings("unchecked")
    public <T> T as(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, m, margs) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        return switch (m.getName()) {
                            case "toString" -> "RemoteObject(" + handle + ")";
                            case "hashCode" -> Long.hashCode(handle);
                            case "equals" -> proxy == margs[0];
                            default -> throw new UnsupportedOperationException(m.getName());
                        };
                    }
                    return call(m.getName(), margs == null ? new Object[0] : margs);
                });
    }

    /** 显式释放 worker 侧句柄。幂等。 */
    public void release() {
        if (released) return;
        released = true;
        host.releaseObj(handle);
    }

    // ---- Map 门面(M7-8:服务值在 Java 侧仍可按成员名读)----

    /** 成员名 → fn 句柄 / getter 读值 / 数据字段。{@code null} key → null(与标准 Map 一致)。 */
    @Override public Object get(Object key) {
        return key instanceof String s ? get(s) : null;
    }

    @Override public boolean containsKey(Object key) {
        return key instanceof String s && get(s) != null;
    }

    /** 一次性拉取成员名集合(经 invokeMembers RPC)。失败 → 空集(不抛)。 */
    @Override public Set<String> keySet() {
        Object members = host.invokeMembersForFacade(handle);
        Set<String> out = new LinkedHashSet<>();
        if (members instanceof List<?> list) {
            for (Object it : list) {
                if (it instanceof Map<?, ?> m && m.get("name") instanceof String s) out.add(s);
            }
        }
        return out;
    }

    /** 一次性拉取成员名→值(每成员一次 invokeGet;仅诊断/断言用,方法成员为 fn 句柄)。 */
    @Override public Set<Entry<String, Object>> entrySet() {
        Set<Entry<String, Object>> out = new LinkedHashSet<>();
        for (String k : keySet()) out.add(new SimpleImmutableEntry<>(k, get(k)));
        return out;
    }

    @Override public boolean isEmpty() { return keySet().isEmpty(); }

    @Override public int size() { return keySet().size(); }

    @Override public Collection<Object> values() {
        List<Object> out = new ArrayList<>();
        for (String k : keySet()) out.add(get(k));
        return out;
    }

    @Override public String toString() {
        return "RemoteObject(" + handle + ")";
    }

    @Override public int hashCode() { return Long.hashCode(handle); }

    @Override public boolean equals(Object o) {
        return o == this || (o instanceof RemoteObject r && r.handle == handle && r.host == host);
    }

    @Override public Object put(String key, Object value) {
        throw new UnsupportedOperationException("remote service " + handle + " is read-only (M7-8 Map facade)");
    }

    @Override public void clear() { throw new UnsupportedOperationException("remote service " + handle + " is read-only (M7-8 Map facade)"); }

    @Override public Object remove(Object key) { throw new UnsupportedOperationException("remote service " + handle + " is read-only (M7-8 Map facade)"); }
}
