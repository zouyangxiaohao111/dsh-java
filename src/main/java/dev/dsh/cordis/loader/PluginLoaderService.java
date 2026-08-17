package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    public PluginLoaderService(Context ctx) {
        this(ctx, new PluginRuntimeResolver(), new UrlPluginClassLoaderFactory(), defaultOutputDir());
    }

    public PluginLoaderService(Context ctx, PluginRuntimeResolver resolver, PluginClassLoaderFactory clFactory, Path outputDir) {
        this.ctx = ctx;
        this.resolver = resolver;
        this.selector = new HostSelector(resolver);
        this.compiler = new PluginCompiler();
        this.clFactory = clFactory;
        this.outputDir = outputDir;
        this.hostFactory = new JsHostFactory();
    }

    // ---- 一次性加载 ----

    /** 读 cordis.yml → 注册全部条目;任一条目失败 → 全部回滚(未注册)并抛出。 */
    public synchronized List<LoadedPlugin> load(Path yml) throws Exception {
        setConfig(yml);
        List<Entry> entries = EntryTree.parse(configFile).flatten();
        List<LoadedPlugin> next = loadAll(entries);     // 先全部加载(失败 → 释放已建宿主)
        try {
            for (LoadedPlugin lp : next) lp.register(ctx);
        } catch (Exception e) {
            for (LoadedPlugin lp : next) lp.unregister(ctx);   // 注册期失败 → 卸载已注册的
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

    /** 启动后台守护线程周期轮询 {@link #updateIfChanged()};{@link #dispose()}/{@link #close()} 自动停止。 */
    public synchronized void startWatch(long intervalMillis) {
        if (watcherThread != null) return;
        watcherThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(intervalMillis);
                    try {
                        updateIfChanged();
                    } catch (Exception e) {
                        // 重载失败已回滚旧态;静默留待上层观察
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
            for (LoadedPlugin lp : staged) lp.close();
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
        String lower = ref.toLowerCase(Locale.ROOT);
        if (re.abs() != null && lower.endsWith(".java")) {
            // 源码:编译 → 隔离 ClassLoader → 实例化(复用 M3 PluginCompiler + PluginClassLoaderFactory)
            Path classesDir = compiler.compile(re.abs(),
                    outputDir.resolve(re.abs().getFileName().toString() + ".classes"));
            ClassLoader cl = clFactory.create(List.of(classesDir), getClass().getClassLoader());
            plugin = instantiate(cl, re.abs().getFileName().toString().replace(".java", ""));
            source = re.abs();
        } else if (re.abs() != null && lower.endsWith(".class")) {
            // .class 文件:以所在目录为 classpath 根,按路径推导类名
            ClassLoader cl = clFactory.create(List.of(re.abs().getParent()), getClass().getClassLoader());
            plugin = instantiate(cl, classNameFromClassPath(re.abs()));
            source = re.abs();
        } else {
            // 类名:从上下文 ClassLoader 按名加载
            ClassLoader cl = clFactory.create(List.of(), Thread.currentThread().getContextClassLoader());
            plugin = instantiate(cl, ref);
        }
        return new LoadedPlugin(entry, HostKind.JAVA, ref, plugin, null, source);
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
        return new LoadedPlugin(entry, kind, re.ref(), adapter, host, entryFile);
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
            for (LoadedPlugin lp : staged) lp.close();
            throw ex;
        }

        // Phase B:提交 — 先处置已移除条目,再替换/注册变更条目(顺序 = 配置顺序,提供者先于消费者)。
        for (LoadedPlugin cur : loaded) {
            if (!nextNames.contains(cur.entry().name())) cur.unregister(ctx);
        }
        for (LoadedPlugin np : next.values()) {
            LoadedPlugin old = byName.get(np.entry().name());
            if (np == old) continue;                  // 复用
            if (old != null) old.unregister(ctx);     // 替换:先处置旧实现
            try {
                np.register(ctx);
            } catch (Exception ex) {
                if (old != null) {                    // 回滚到旧实现
                    try { old.register(ctx); } catch (Exception ignored) { }
                }
                throw ex;
            }
        }
        loaded.clear();
        loaded.addAll(next.values());
    }

    /** 仅重载一个插件(源文件变更):加载新实现 → 替换旧;失败回滚旧实现。 */
    private void reloadPlugin(LoadedPlugin old, int index) throws Exception {
        LoadedPlugin np = loadPlugin(old.entry());
        old.unregister(ctx);
        try {
            np.register(ctx);
        } catch (Exception ex) {
            try { old.register(ctx); } catch (Exception ignored) { }
            np.close();
            throw ex;
        }
        loaded.set(index, np);
    }

    // ---- 内部:小工具 ----

    private void setConfig(Path yml) {
        this.configFile = yml.toAbsolutePath().normalize();
        this.baseDir = configFile.getParent();
        if (baseDir == null) baseDir = Path.of(".");
    }

    private void refreshConfigWatchers() {
        configWatchers.clear();
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
