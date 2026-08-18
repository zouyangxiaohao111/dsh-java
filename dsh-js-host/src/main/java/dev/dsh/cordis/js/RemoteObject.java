package dev.dsh.cordis.js;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

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
 */
public final class RemoteObject {
    private final NodeWorkerJsHost host;
    private final long handle;
    private volatile boolean released;

    RemoteObject(NodeWorkerJsHost host, long handle) {
        this.host = host;
        this.handle = handle;
    }

    /** 底层 worker 句柄 id(测试 / 显式释放用)。 */
    public long handle() { return handle; }

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
        return host.invokeGet(handle, property);
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
}
