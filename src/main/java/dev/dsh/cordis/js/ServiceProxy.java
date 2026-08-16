package dev.dsh.cordis.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** 跨语言服务桥:Java 服务 → JS 可调对象(JSON 参数/返回)。 */
public final class ServiceProxy {
    private final Context graalContext;

    public ServiceProxy(Context graalContext) { this.graalContext = graalContext; }

    /** 把 Java 服务对象暴露给 JS:公共方法映射为 JS 可调成员(参数/返回经 JSON 校验)。 */
    public Value expose(Object javaService) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (Method m : javaService.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            members.put(m.getName(), (org.graalvm.polyglot.proxy.ProxyExecutable) args -> {
                Class<?>[] paramTypes = m.getParameterTypes();
                Object[] javaArgs = new Object[args.length];
                for (int i = 0; i < args.length; i++) {
                    Object v = toJava(args[i]);
                    javaArgs[i] = i < paramTypes.length ? coerce(v, paramTypes[i]) : v;
                }
                Object result;
                try { result = m.invoke(javaService, javaArgs); }
                catch (Exception e) { throw new RuntimeException(e); }
                return toJs(result);
            });
        }
        return graalContext.asValue(ProxyObject.fromMap(members));
    }

    /** 把 JS 值换算为 Java 可反射调用参数(JSON 模型:number → Long/Double,string → String,
     *  boolean → Boolean,array → List)。对象型 JS 值原样透传为 Value。 */
    private Object toJava(Object v) {
        if (v instanceof Value val) {
            if (val.isString()) return val.asString();
            if (val.isBoolean()) return val.asBoolean();
            if (val.fitsInLong()) return val.asLong();
            if (val.fitsInDouble()) return val.asDouble();
            if (val.hasArrayElements()) {
                java.util.List<Object> l = new java.util.ArrayList<>();
                for (long i = 0; i < val.getArraySize(); i++) l.add(toJava(val.getArrayElement(i)));
                return l;
            }
            return val;
        }
        return v;
    }

    /** 反射调用参数须与形参类型赋值兼容:JS number 一律落为 Long/Double,直接传给
     *  int/float/... 形参会被反射拒绝(argument type mismatch)。这里按目标形参类型收窄。 */
    private static Object coerce(Object v, Class<?> target) {
        if (v instanceof Number num) {
            if (target == int.class || target == Integer.class) return num.intValue();
            if (target == long.class || target == Long.class) return num.longValue();
            if (target == double.class || target == Double.class) return num.doubleValue();
            if (target == float.class || target == Float.class) return num.floatValue();
            if (target == short.class || target == Short.class) return num.shortValue();
            if (target == byte.class || target == Byte.class) return num.byteValue();
        }
        if (v instanceof Boolean b && (target == boolean.class || target == Boolean.class)) return b;
        if (v instanceof String s && (target == char.class || target == Character.class) && !s.isEmpty()) {
            return s.charAt(0);
        }
        return v;
    }

    private Object toJs(Object v) {
        if (v == null) return null;
        if (v instanceof java.util.Collection<?> || v instanceof Map<?, ?>) return graalContext.asValue(v);
        return v;
    }
}
