package dev.dsh.cordis;

import java.util.*;

/** Base class for services exposing a named API on ctx (service.ts:11-115).
 *
 *  <p>traceable / withProps / createCallable(utils.ts:117-233)——简化移植与偏差:
 *  JS 用 Proxy 在服务方法调用时把 {@code this.ctx} 重绑定到「调用方」context,并把
 *  ctx 属性/服务字段合成到 receiver(createShadow / withProps)。Java 服务是<b>无状态
 *  对象</b>,不持有可变的 ctx 绑定:需要调用方上下文的操作通过显式参数传递
 *  (如 {@code Events.on(caller, ...)} / {@code Reflect.provide(caller, ...)}),
 *  ctx 门面方法(如 {@link Context#get})直接以调用者 {@code this} 为 ctx。因此无需
 *  Proxy 追踪;{@code LoggerService.current(Context)} 把调用方 fiber 名透传给 logger,
 *  是 traceable 的等价简化。偏差:无 createCallable(JS 可调用服务对象),Java 用
 *  Service 子类 + ctx 门面方法。 */
public abstract class Service {
    protected final Context ctx;
    public final String name;

    protected Service(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
        ctx.provide(name, this, ignored -> check());
    }

    /** Availability predicate consulted before dependents may load. */
    protected boolean check() { return true; }

    /** Merge intercept config from ancestors with optional base and head (service.ts:86-102). */
    @SuppressWarnings("unchecked")
    protected <T> T resolveConfig(T base, T head) {
        List<Object> configs = new ArrayList<>();
        Context c = this.ctx;
        while (c != null) {
            if (c.intercept.containsKey(this.name)) configs.add(0, c.intercept.get(this.name));
            c = c.parent;
        }
        if (base != null) configs.add(0, base);
        if (head != null) configs.add(head);
        Object merged = null;
        for (Object cfg : configs) {
            if (cfg instanceof Map<?, ?> m) {
                Map<Object, Object> acc = merged instanceof Map<?, ?> am
                        ? new LinkedHashMap<>(am) : new LinkedHashMap<>();
                acc.putAll(m);
                merged = acc;
            } else {
                merged = cfg;
            }
        }
        return (T) merged;
    }
}
