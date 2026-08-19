package dev.dsh.cordis;

import java.util.HashMap;
import java.util.Map;

/**
 * Built-in loader service (cordis's {@code @cordisjs/loader}): the bridge-visible
 * {@code ctx.loader} for JS plugins that inject or read it (e.g.
 * {@code cordis-plugin-hmr}). M7-8 B+C: Java core provides the minimal surface
 * the real consumers actually touch.
 *
 * <p>The Java core owns loading and hot-reload (dsh-loader / dsh-reload); this
 * service only exposes the <i>contract</i>. {@link #internal} is present so hmr's
 * constructor can bind it ({@code this.internal = ctx.loader.internal}) without
 * failing its {@code --expose-internals} guard; the deeper internals usage
 * (module-cache manipulation / specifier resolution) is owned by the Java core
 * and is deliberately not routed through this facade — callers get a clear error.
 */
public final class LoaderService extends Service {
    /** Node module-internals surface ({@code --expose-internals} equivalent). */
    public final LoaderInternal internal = new LoaderInternal();

    public LoaderService(Context ctx) {
        super(ctx, "loader");
    }

    /**
     * M8:loader settle signal — the surface real consumers read with
     * {@code ctx.get("loader")?.await()}(e.g. dsh-web-app's URL line waits for
     * the tree to settle before printing). The Java core drives loading
     * synchronously through the bridge, so there is nothing to await: return
     * {@link Context#NO_SERVICE}, which the JS bridges map to JS {@code undefined}
     * — the "no settle promise" branch consumers treat as already-settled.
     */
    public Object await() {
        return Context.NO_SERVICE;
    }

    private volatile java.util.List<Object> entries = java.util.List.of();

    /**
     * M8:loader entry list — the surface {@code typert-loader} / {@code dsh-client-modules}
     * read with {@code for (const entry of ctx.loader.entries()) entry.options.name}.
     * The Java loader (dsh-loader PluginLoaderService) owns the real entry list; the
     * application layer sets it after loading ({@link #setEntries}), so the client-modules
     * node half scans the composed entries for {@code dsh.client} packages → the browser
     * boot manifest. Each entry carries {@code {options:{name}}, fiber, disabled} (the
     * shape modules reads: {@code entry.options.name} / {@code entry.fiber !== undefined} /
     * {@code !entry.disabled}).
     */
    public java.util.List<Object> entries() {
        return entries;
    }

    /** M8:应用层在加载后注入 loader 条目(dsh-loader 的已加载句柄 → modules 扫描面)。 */
    public void setEntries(java.util.List<Object> entries) {
        this.entries = entries == null ? java.util.List.of() : java.util.List.copyOf(entries);
    }

    /** M8:loader store facade(目录) —— directory-picker-auto 读 {@code ctx.loader.store[id]}。
     *  空:Java 核心 owns 加载,worker 侧无可挂载条目。 */
    public final java.util.Map<String, Object> store = new java.util.concurrent.ConcurrentHashMap<>();

    /** M8:loader create(挂载条目)—— directory-picker-auto 用它挂 native/browse picker 后端。
     *  Java 核心 owns 加载,worker 侧动态挂载不支持 → 返回一个 no-op id(不真实挂载;picker
     *  的 unmount 经 {@link #store} 检查到 id 不在 → 跳过 remove)。诚实边界:原生目录选择器
     *  的宿主挂载 seam 未实现,apiproxy 的 directoryPicker 注入仍被服务存在满足。 */
    public String create(Map<?, ?> options) {
        return "m8-noop-" + (createSeq.getAndIncrement());
    }

    /** M8:loader remove(卸载条目)—— no-op(create 是 no-op,无可卸载)。 */
    public void remove(String id) {
        store.remove(id);
    }

    private static final java.util.concurrent.atomic.AtomicLong createSeq = new java.util.concurrent.atomic.AtomicLong(1);

    /** Node module-loader internals facade (the surface hmr reads). */
    public static final class LoaderInternal {
        /** v1/v2 specifier-resolution protocol (hmr {@code _resolve} switch). */
        public String version = "v1";
        /** Module cache facade keyed by URL. Empty: the Java core drives loading;
         *  worker-side module-cache manipulation is not wired through here. */
        public Map<String, Object> loadCache = new HashMap<>();

        /** Not supported by the Java loader (specifier resolution crosses into the
         *  worker's Node resolver, owned by the core). Callers get a clear error. */
        public Object resolve(String specifier, String parentURL, Object attributes) {
            throw new UnsupportedOperationException(
                    "loader.internal.resolve is not supported by the Java loader");
        }

        /** Not supported by the Java loader (see {@link #resolve}). */
        public Object resolveSync(String parentURL, Object options) {
            throw new UnsupportedOperationException(
                    "loader.internal.resolveSync is not supported by the Java loader");
        }
    }
}
