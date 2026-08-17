package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.FiberState;
import dev.dsh.cordis.Plugin;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.JsHostFactory;
import dev.dsh.cordis.js.JsPluginAdapter;
import dev.dsh.cordis.js.PluginRuntimeResolver;
import dev.dsh.cordis.js.ResolvedJsPlugin;
import dev.dsh.cordis.reload.FileWatcher;
import dev.dsh.cordis.reload.PluginClassLoaderFactory;
import dev.dsh.cordis.reload.PluginCompiler;
import dev.dsh.cordis.reload.UrlPluginClassLoaderFactory;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 配置驱动加载(design §2 PluginLoaderService):读 cordis.yml → 条目树 → 逐条注册进 registry。
 *
 * <p>宿主路由经 {@link HostSelector}:{@code java:} 类名/源码走 ClassLoader(复用 M3
 * {@link PluginCompiler} + {@link PluginClassLoaderFactory}),{@code node:}/{@code graaljs:}
 * 显式宿主与无前缀自动检测走 {@link PluginRuntimeResolver}(保留运行时兜底)。
 *
 * <p>替换/组合(§2.2):{@link #reload(Path)} 重新求值,同名条目按 source/path 是否变更决定
 * 复用或替换;移除条目 dispose;失败按 M3 语义回滚(新实现加载失败 → 旧实现保留)。
 *
 * <p>自动热更新(§2.3):{@link #updateIfChanged()} 轮询配置 yml + 各插件源文件,变更即重载;
 * {@link #startWatch(long)} 起守护线程周期轮询。配置变更 → 全量 diff 重载;插件源变更 →
 * 仅重载该插件。监听面 = 配置树 yml(含 include)+ 各条目源文件。
 */
public final class PluginLoaderService implements AutoCloseable {
    private final Context ctx;
    private final HostSelector selector;
    private final PluginRuntimeResolver resolver;
    private final PluginCompiler compiler;
    private final PluginClassLoaderFactory clFactory;
    private final Path outputDir;
    private final JsHostFactory hostFactory;

    private Path configFile;                     // 当前激活配置(绝对)
    private Path baseDir;                        // 配置所在目录(相对引用基准)
    private final List<LoadedPlugin> loaded = new ArrayList<>();
    private final Map<Path, FileWatcher> configWatchers = new HashMap<>();
    private Thread watcherThread;
    private volatile Throwable lastError;         // 最近一次后台热更新失败(无失败为 null)
    private volatile Consumer<Throwable> onReloadError;

    public PluginLoaderService(Context ctx) {
        this(ctx, new PluginRuntimeResolver(), new UrlPluginClassLoaderFactory(), defaultOutputDir(), new JsHostFactory());
    }

    public PluginLoaderService(Context ctx, PluginRuntimeResolver resolver, PluginClassLoaderFactory clFactory, Path outputDir) {
        this(ctx, resolver, clFactory, outputDir, new JsHostFactory());
    }

    /**
     * 宿主工厂 seam:注入自定义 {@link JsHostFactory}(M6-4 应用层用它携带裸模块解析基址
     * vendor/dsh/node_modules + profile/node_modules;测试也用 close-throw 等宿主行为注入)。
     * 显式 {@code node:}/{@code graaljs:} 条目与 {@code hostFactory} 一致;resolver 内部的
     * 自动检测路径用的是它自己的 hostFactory(调用方注入时两者都应带同一批基址)。
     */
    public PluginLoaderService(Context ctx, PluginRuntimeResolver resolver, PluginClassLoaderFactory clFactory,
                               Path outputDir, JsHostFactory hostFactory) {
        this.ctx = ctx;
        this.resolver = resolver;
        this.selector = new HostSelector(resolver);
        this.compiler = new PluginCompiler();
        this.clFactory = clFactory;
        this.outputDir = outputDir;
        this.hostFactory = hostFactory;
    }

    // ---- 一次性加载 ----

    /** 读 cordis.yml → 注册全部条目;任一条目失败(含 apply 抛错被吞) → 全部回滚(未注册)并抛出。 */
    public synchronized List<LoadedPlugin> load(Path yml) throws Exception {
        setConfig(yml);
        return loadAndRegister(EntryTree.parse(configFile).flatten());
    }

    /**
     * 直接加载一条 entry 列表(非 yml 源,M6-6 dsh profile 组合行):注册全部条目,
     * 任一条目失败 → 全部回滚(未注册)并抛出。{@code baseDir} 为相对引用/npm 说明符的
     * 解析基准(通常 = profile 目录)。无配置文件可热监听(entries 热更新是后续职责)。
     */
    public synchronized List<LoadedPlugin> loadEntries(List<Entry> entries, Path baseDir) throws Exception {
        this.configFile = null;
        this.baseDir = (baseDir == null) ? Path.of(".") : baseDir.toAbsolutePath().normalize();
        return loadAndRegister(entries);
    }

    /** 共享的"全部加载 + 全部注册 + 校验生效 + 刷新监听"流程(load / loadEntries 复用)。 */
    private List<LoadedPlugin> loadAndRegister(List<Entry> entries) throws Exception {
        List<LoadedPlugin> next = loadAll(entries);     // 先全部加载(失败 → 释放已建宿主)
        try {
            for (LoadedPlugin lp : next) {
                lp.register(ctx);
                verifyFiber(lp, ctx);   // 初始加载同样校验 apply 实际生效(apply 抛错被 Fiber.reload 吞掉 → 状态 FAILED)
            }
        } catch (Exception e) {
            for (LoadedPlugin lp : next) quietlyUnregister(lp, ctx);   // 注册期失败 → 卸载已注册的(close 抛错不掩盖根因)
            throw e;
        }
        loaded.clear();
        loaded.addAll(next);
        refreshConfigWatchers();
        return List.copyOf(loaded);
    }

    // ---- 替换/组合(重载) ----

    /** 重读配置 → 与已加载 diff → 新增注册 / 变更替换 / 移除 dispose;失败回滚旧态。 */
    public synchronized void reload(Path yml) throws Exception {
        setConfig(yml);
        applyDiff(EntryTree.parse(configFile).flatten());
        refreshConfigWatchers();
    }

    /** 重载当前配置(不换文件)。 */
    public synchronized void reload() throws Exception {
        if (configFile == null) throw new IllegalStateException("no config loaded yet");
        applyDiff(EntryTree.parse(configFile).flatten());
        refreshConfigWatchers();
    }

    // ---- 当前条目 / 句柄 ----

    /** 当前生效的条目(加载顺序)。 */
    public synchronized List<Entry> entries() {
        return loaded.stream().map(LoadedPlugin::entry).toList();
    }

    /** 当前已加载句柄。 */
    public synchronized List<LoadedPlugin> loaded() {
        return List.copyOf(loaded);
    }

    // ---- 自动热更新(poll) ----

    /**
     * 轮询一次:配置(含 include)或任一插件源文件变更 → 触发重载并返回 true;无变更返回 false。
     * 配置变更 → 全量 diff 重载;插件源变更 → 仅重载该插件(失败回滚旧实现)。
     */
    public synchronized boolean updateIfChanged() throws Exception {
        boolean configChanged = false;
        for (FileWatcher w : configWatchers.values()) {
            if (w.changed()) configChanged = true;
        }
        if (configChanged) {
            reload(configFile);
            return true;
        }
        for (int i = 0; i < loaded.size(); i++) {
            LoadedPlugin lp = loaded.get(i);
            if (lp.sourceChanged()) {
                reloadPlugin(lp, i);
                return true;
            }
        }
        return false;
    }

    /** 启动后台守护线程周期轮询 {@link #updateIfChanged()};{@link #dispose()}/{@link #close()} 自动停止。
     *  热更新失败(已回滚旧态)经 {@link #lastError()} / {@link #onReloadError(Consumer)} 上报,不再静默吞掉。 */
    public synchronized void startWatch(long intervalMillis) {
        if (watcherThread != null) return;
        watcherThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(intervalMillis);
                    try {
                        updateIfChanged();
                    } catch (Exception e) {
                        reportReloadError(e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dsh-loader-watch");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    /** 停止后台轮询线程。 */
    public synchronized void stopWatch() {
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }

    /** 注入热更新失败处理器:后台轮询线程每次重载失败(已回滚旧态)回调它;处理器内部异常被隔离,不影响轮询。 */
    public void onReloadError(Consumer<Throwable> handler) {
        this.onReloadError = handler;
    }

    /** 最近一次后台热更新失败;无失败返回 null。 */
    public Throwable lastError() {
        return lastError;
    }

    /** 清除记录的最近失败(便于连续观测)。 */
    public synchronized void clearError() {
        this.lastError = null;
    }

    private void reportReloadError(Throwable e) {
        this.lastError = e;
        Consumer<Throwable> handler = this.onReloadError;
        if (handler != null) {
            try {
                handler.accept(e);
            } catch (Throwable ignored) {
                // 处理器自身异常不得中断轮询
            }
        }
    }

    // ---- 卸载 ----

    /** 卸载全部已注册插件(dispose fiber + 关闭 JS 宿主)并停止监听。 */
    public synchronized void dispose() {
        stopWatch();
        List<LoadedPlugin> old = new ArrayList<>(loaded);
        loaded.clear();
        for (LoadedPlugin lp : old) lp.unregister(ctx);
        configWatchers.clear();
    }

    @Override
    public void close() {
        dispose();
    }

    // ---- 内部:加载 ----

    /** 全量加载 entries(不注册):任一失败 → 释放已建宿主并抛出(旧态未动)。 */
    private List<LoadedPlugin> loadAll(List<Entry> entries) throws Exception {
        List<LoadedPlugin> staged = new ArrayList<>();
        try {
            for (Entry e : entries) staged.add(loadPlugin(e));
            return staged;
        } catch (Exception ex) {
            for (LoadedPlugin lp : staged) closeQuietly(lp);
            throw ex;
        }
    }

    private LoadedPlugin loadPlugin(Entry entry) throws Exception {
        ResolvedEntry re = selector.select(entry, baseDir);
        return switch (re.kind()) {
            case JAVA -> loadJava(entry, re);
            case GRAAL, NODE -> loadJs(entry, re);
        };
    }

    private LoadedPlugin loadJava(Entry entry, ResolvedEntry re) throws Exception {
        String ref = re.ref();
        Plugin<?> plugin;
        Path source = null;
        ClassLoader cl = null;
        String lower = ref.toLowerCase(Locale.ROOT);
        if (re.abs() != null && Files.isDirectory(re.abs())) {
            // 源码目录(M6-7 java: 目录源):整目录编译 → 从编译产物扫描 Plugin 类 → 实例化
            Path classesDir = compiler.compileTree(re.abs(),
                    outputDir.resolve(re.abs().getFileName().toString() + ".classes"));
            cl = clFactory.create(List.of(classesDir), getClass().getClassLoader());
            try {
                plugin = instantiateFromClassesDir(cl, classesDir, re.abs());
            } catch (Exception e) {
                closeQuietly(cl);
                throw e;
            }
            source = re.abs();
        } else if (re.abs() != null && lower.endsWith(".java")) {
            // 源码:编译 → 隔离 ClassLoader → 从编译产物扫描 Plugin 类 → 实例化(复用 M3
            // PluginCompiler + PluginClassLoaderFactory;扫描支持带包名的单文件源,M6-7)
            Path classesDir = compiler.compile(re.abs(),
                    outputDir.resolve(re.abs().getFileName().toString() + ".classes"));
            cl = clFactory.create(List.of(classesDir), getClass().getClassLoader());
            try {
                plugin = instantiateFromClassesDir(cl, classesDir, re.abs());
            } catch (Exception e) {
                closeQuietly(cl);
                throw e;
            }
            source = re.abs();
        } else if (re.abs() != null && lower.endsWith(".class")) {
            // .class 文件:以所在目录为 classpath 根,按路径推导类名
            cl = clFactory.create(List.of(re.abs().getParent()), getClass().getClassLoader());
            plugin = instantiate(cl, classNameFromClassPath(re.abs()));
            source = re.abs();
        } else if (re.abs() != null && lower.endsWith(".jar")) {
            // jar 插件(design §5.2):URLClassLoader([jar], parent=核心 CL) + 类发现
            cl = clFactory.create(List.of(re.abs()), getClass().getClassLoader());
            try {
                plugin = instantiateFromJar(cl, entry, re.abs());
            } catch (Exception e) {
                closeQuietly(cl);
                throw e;
            }
            source = re.abs();
        } else {
            // 类名:从上下文 ClassLoader 按名加载
            cl = clFactory.create(List.of(), Thread.currentThread().getContextClassLoader());
            plugin = instantiate(cl, ref);
        }
        return new LoadedPlugin(entry, HostKind.JAVA, ref, plugin, null, source, cl);
    }

    private LoadedPlugin loadJs(Entry entry, ResolvedEntry re) throws Exception {
        Path abs = re.abs();
        if (abs == null || !Files.exists(abs)) {
            throw new IOException("plugin path not found: " + re.ref() + " (resolved from " + baseDir + ")");
        }
        Path entryFile = HostSelector.resolveJsEntry(abs);
        Path requireCwd = Files.isDirectory(abs) ? abs
                : (entryFile.getParent() != null ? entryFile.getParent() : abs);

        JsHost host;
        HostKind kind;
        JsPluginAdapter adapter;
        if (re.explicit()) {
            // 显式 node:/graaljs: 前缀 → 直接按宿主创建(不做自动检测/兜底)
            kind = re.kind();
            host = hostFactory.create(kind, requireCwd);
            try {
                adapter = new JsPluginAdapter(host, host.loadModule(entryFile));
            } catch (Exception e) {
                host.close();
                throw e;
            }
        } else {
            // 无前缀 → resolver 自动选宿主 + 运行时兜底(GraalJS 缺模块 → Node worker)
            resolver.invalidate(entryFile);
            ResolvedJsPlugin r = resolver.loadJs(entryFile, requireCwd);
            kind = r.kind();
            host = r.host();
            adapter = r.adapter();
        }
        return new LoadedPlugin(entry, kind, re.ref(), adapter, host, entryFile, null);
    }

    // ---- 内部:diff / 替换 ----

    /** diff 重载:加载新/变更(未注册)→ 提交(移除删除、替换变更、注册新增);提交期失败按插件回滚旧实现。 */
    private void applyDiff(List<Entry> nextEntries) throws Exception {
        Map<String, LoadedPlugin> byName = new HashMap<>();
        for (LoadedPlugin lp : loaded) byName.put(lp.entry().name(), lp);
        Set<String> nextNames = new HashSet<>();
        for (Entry e : nextEntries) nextNames.add(e.name());

        // Phase A:加载全部"新增或变更"条目(未注册)。任一失败 → 释放已建宿主,抛出,旧态保持。
        Map<String, LoadedPlugin> next = new LinkedHashMap<>();
        List<LoadedPlugin> staged = new ArrayList<>();
        try {
            for (Entry e : nextEntries) {
                LoadedPlugin old = byName.get(e.name());
                if (old != null && old.entry().equals(e)) {
                    next.put(e.name(), old);          // 未变更,复用
                    continue;
                }
                LoadedPlugin np = loadPlugin(e);
                staged.add(np);
                next.put(e.name(), np);
            }
        } catch (Exception ex) {
            for (LoadedPlugin lp : staged) closeQuietly(lp);
            throw ex;
        }

        // Phase B:提交 — 两段提交,整批原子性。
        // B1:解除被移除条目的 runtime(释放服务名),再逐条替换/注册变更条目;注册后校验新 fiber
        //    实际生效(apply 抛错被 Fiber.reload 吞掉 → 状态 FAILED,registry.plugin() 不抛)。
        //    任一条失败 → 回滚整批(恢复所有被替换/移除条目的旧实现),loaded 与 registry 不分裂。
        // B2:全部成功 → 关闭被替换/移除条目的旧宿主,批量替换 loaded 引用。
        List<LoadedPlugin> removed = new ArrayList<>();
        for (LoadedPlugin cur : loaded) {
            if (!nextNames.contains(cur.entry().name())) {
                cur.unregisterRuntime(ctx);   // 腾出服务名,宿主保留以便整批回滚
                removed.add(cur);
            }
        }
        List<LoadedPlugin> registered = new ArrayList<>();   // 已注册成功的新句柄
        LoadedPlugin pending = null;                          // 当前处理中的句柄(未入 registered,用于回滚)
        try {
            for (LoadedPlugin np : next.values()) {
                LoadedPlugin old = byName.get(np.entry().name());
                if (np == old) continue;                      // 复用
                pending = np;
                if (old != null) old.unregisterRuntime(ctx);  // 替换:先解除旧 runtime,宿主保留
                np.register(ctx);
                verifyFiber(np, ctx);
                registered.add(np);
            }
            pending = null;
        } catch (Exception ex) {
            rollbackBatch(byName, removed, registered, pending, ctx);
            throw ex;
        }
        // 提交:新实现生效 — 关闭被替换/移除条目的旧宿主(逐个守卫,失败记日志不中断;
        // 新实现已在 registry 生效,旧宿主关闭仅清理,不得阻断 loaded 引用替换)。
        for (LoadedPlugin np : next.values()) {
            LoadedPlugin old = byName.get(np.entry().name());
            if (np != old && old != null) closeQuietly(old);
        }
        for (LoadedPlugin cur : removed) closeQuietly(cur);
        loaded.clear();
        loaded.addAll(next.values());
    }

    /** 仅重载一个插件(源文件变更):加载新实现 → 替换旧;失败回滚旧实现。
     *  新实现注册后校验其 fiber 实际生效(apply 抛错被 Fiber.reload 吞掉 → 状态 FAILED),
     *  校验失败则释放新句柄并恢复旧实现(旧宿主保留至提交前)。 */
    private void reloadPlugin(LoadedPlugin old, int index) throws Exception {
        LoadedPlugin np = loadPlugin(old.entry());
        try {
            old.unregisterRuntime(ctx);          // 腾出服务名;保留旧宿主以便回滚
            np.register(ctx);
            verifyFiber(np, ctx);
        } catch (Exception ex) {
            quietlyUnregister(np, ctx);          // 释放新 handle(fiber + 宿主;close 抛错不掩盖 apply 根因)
            try { old.register(ctx); } catch (Exception ignored) { }
            throw ex;
        }
        closeQuietly(old);                       // 提交:新实现生效,关闭旧宿主(close 抛错不阻断 loaded 替换)
        loaded.set(index, np);
    }

    /** 校验新句柄注册后其 fiber 实际生效:状态非 FAILED 且无 apply 错误。apply 抛错被
     *  {@link Fiber#reload()} 吞掉并记入 {@link Fiber#error()}(状态转 FAILED),此时
     *  {@code registry.plugin()} 不抛 —— 未生效视为注册失败,须回滚而非提交坏实现。 */
    private static void verifyFiber(LoadedPlugin np, Context ctx) throws Exception {
        Plugin.Runtime rt = ctx.registry.get(np.plugin());
        if (rt == null) throw new IllegalStateException("plugin not registered: " + np.entry().name());
        Fiber latest = null;
        for (Fiber f : rt.fibers) latest = f;      // 最新 fiber = 本次注册
        if (latest == null) throw new IllegalStateException("plugin has no fiber: " + np.entry().name());
        Throwable err = latest.error();
        if (latest.state == FiberState.FAILED || err != null) {
            throw new IllegalStateException("plugin apply failed: " + np.entry().name()
                    + " (" + (err != null ? err.getMessage() : latest.state) + ")", err);
        }
    }

    /** 关闭旧宿主并吞掉关闭期异常(记日志),供提交期清理使用。 */
    private void closeQuietly(LoadedPlugin lp) {
        try {
            lp.close();
        } catch (Throwable t) {
            ctx.logger().error(t);
        }
    }

    /** 关闭刚创建的插件 ClassLoader 并吞掉关闭期异常(记日志)——jar 加载失败时释放文件句柄。 */
    private void closeQuietly(ClassLoader cl) {
        if (!(cl instanceof AutoCloseable ac)) return;
        try {
            ac.close();
        } catch (Throwable t) {
            ctx.logger().error(t);
        }
    }

    /** 卸载已注册句柄并吞掉卸载期异常(记日志),供注册/apply 失败清理使用 —— close 抛错不得掩盖根因异常。 */
    private static void quietlyUnregister(LoadedPlugin lp, Context ctx) {
        try {
            lp.unregister(ctx);
        } catch (Throwable t) {
            ctx.logger().error(t);
        }
    }

    /** 整批回滚:释放已注册/注册失败的新句柄,恢复所有被替换与被移除条目的旧实现(宿主保留中)。 */
    private static void rollbackBatch(Map<String, LoadedPlugin> byName, List<LoadedPlugin> removed,
                                      List<LoadedPlugin> registered, LoadedPlugin pending, Context ctx) {
        if (pending != null) quietlyUnregister(pending, ctx);            // 未通过校验/注册失败的新句柄
        for (LoadedPlugin np : registered) quietlyUnregister(np, ctx);   // 已注册成功的新句柄
        Set<LoadedPlugin> restore = new LinkedHashSet<>(removed);
        for (LoadedPlugin np : registered) {
            LoadedPlugin old = byName.get(np.entry().name());
            if (old != null) restore.add(old);
        }
        if (pending != null) {
            LoadedPlugin old = byName.get(pending.entry().name());
            if (old != null) restore.add(old);
        }
        for (LoadedPlugin old : restore) {
            try { old.register(ctx); } catch (Exception ignored) { }
        }
    }

    // ---- 内部:小工具 ----

    private void setConfig(Path yml) {
        this.configFile = yml.toAbsolutePath().normalize();
        this.baseDir = configFile.getParent();
        if (baseDir == null) baseDir = Path.of(".");
    }

    private void refreshConfigWatchers() {
        configWatchers.clear();
        if (configFile == null) return;     // 非 yml 源(profile 组合行)无配置文件可监听
        try {
            for (Path p : EntryTree.parse(configFile).sources()) {
                configWatchers.put(p, new FileWatcher(p));
            }
        } catch (IOException e) {
            configWatchers.clear();     // 重解析失败 → 停用配置热更新(插件源热更新不受影响)
        }
    }

    @SuppressWarnings("unchecked")
    private static Plugin<?> instantiate(ClassLoader cl, String className) throws Exception {
        Class<?> cls = cl.loadClass(className);
        if (!Plugin.class.isAssignableFrom(cls)) {
            throw new IllegalStateException("not a Cordis plugin: " + className);
        }
        return (Plugin<?>) cls.getDeclaredConstructor().newInstance();
    }

    /**
     * 从 jar 发现并实例化插件(design §5.2):{@code mainClass} 显式 &gt; ServiceLoader
     * ({@code META-INF/services/dev.dsh.cordis.Plugin}) &gt; 扫描 {@code implements Plugin} 类。
     *
     * <p>扫描用 {@link Class#forName(String, boolean, ClassLoader)} 的 {@code initialize=false}
     * 避免发现期触发静态初始化;无关/依赖缺失类(链接错误)跳过。
     */
    @SuppressWarnings("unchecked")
    private static Plugin<?> instantiateFromJar(ClassLoader cl, Entry entry, Path jar) throws Exception {
        String mainClass = entry.mainClass();
        if (mainClass != null && !mainClass.isBlank()) {
            return instantiate(cl, mainClass);
        }
        // ServiceLoader:jar 内 META-INF/services/dev.dsh.cordis.Plugin
        try {
            for (Plugin<?> p : ServiceLoader.load(Plugin.class, cl)) {
                return p;
            }
        } catch (ServiceConfigurationError ignored) {
            // 服务描述损坏/缺失 → 落到扫描
        }
        // 扫描 jar 条目
        List<Class<?>> candidates = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (!name.endsWith(".class")) continue;
                if (name.startsWith("META-INF/") || name.contains("module-info")) continue;
                String cn = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                try {
                    Class<?> cls = Class.forName(cn, false, cl);
                    if (Plugin.class.isAssignableFrom(cls) && !cls.isInterface()
                            && !Modifier.isAbstract(cls.getModifiers())) {
                        candidates.add(cls);
                    }
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // 无关类或依赖缺失(链接期,含 NoClassDefFoundError),跳过
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("no Cordis plugin found in jar: " + jar
                    + " (declare mainClass or META-INF/services/dev.dsh.cordis.Plugin)");
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("ambiguous plugins in jar: " + jar + " -> " + candidates
                    + " (declare mainClass)");
        }
        return (Plugin<?>) candidates.get(0).getDeclaredConstructor().newInstance();
    }

    /**
     * 从编译产物目录扫描并实例化唯一的 {@code implements Plugin} 类(M6-7 源码目录源)。
     * 扫描用 {@link Class#forName(String, boolean, ClassLoader)} 的 {@code initialize=false}
     * 避免发现期触发静态初始化;依赖缺失/链接错误的类跳过。零候选或多余一个 → 报错。
     */
    @SuppressWarnings("unchecked")
    private static Plugin<?> instantiateFromClassesDir(ClassLoader cl, Path classesDir, Path sourceRoot) throws Exception {
        List<Class<?>> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = classesDir.relativize(p).toString().replace('\\', '/');
                String cn = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                try {
                    Class<?> cls = Class.forName(cn, false, cl);
                    if (Plugin.class.isAssignableFrom(cls) && !cls.isInterface()
                            && !Modifier.isAbstract(cls.getModifiers())) {
                        candidates.add(cls);
                    }
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // 无关类或依赖缺失(链接期,含 NoClassDefFoundError),跳过
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("no Cordis plugin found in compiled source dir: " + sourceRoot);
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("ambiguous plugins in source dir: " + sourceRoot + " -> " + candidates
                    + " (refactor to a single plugin class per source dir)");
        }
        return (Plugin<?>) candidates.get(0).getDeclaredConstructor().newInstance();
    }

    /** {@code foo/Bar.class} → {@code foo.Bar}(相对 classpath 根的包路径)。 */
    private static String classNameFromClassPath(Path classFile) {
        String name = classFile.toString().replace('\\', '/');
        int dot = name.lastIndexOf('/');
        name = dot >= 0 ? name.substring(dot + 1) : name;
        if (name.endsWith(".class")) name = name.substring(0, name.length() - ".class".length());
        return name;
    }

    private static Path defaultOutputDir() {
        try {
            return Files.createTempDirectory("dsh-loader-out");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create plugin output dir", e);
        }
    }
}
