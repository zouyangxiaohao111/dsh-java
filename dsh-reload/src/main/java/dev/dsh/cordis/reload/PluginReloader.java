package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;

import java.nio.file.Path;
import java.util.List;

/** 编排插件热重载:源码/文件变更 → 新 ClassLoader 加载 → registry 换插件。 */
public final class PluginReloader {
    private final Context ctx;
    private final PluginClassLoaderFactory loaderFactory;
    private final PluginCompiler compiler;
    private final Path outputDir;

    public PluginReloader(Context ctx, PluginClassLoaderFactory loaderFactory, Path outputDir) {
        this.ctx = ctx;
        this.loaderFactory = loaderFactory;
        this.compiler = new PluginCompiler();
        this.outputDir = outputDir;
    }

    /** 重载一个 Java 插件:源码 → 编译 → 新 CL 实例化 → registry.delete(旧) + registry.plugin(新)。
     *  回滚:先编译/加载新实现,成功才删旧;编译/加载/注册任一失败都保留旧实现。 */
    @SuppressWarnings("unchecked")
    public <T> Plugin<T> reload(Path sourceFile, Plugin<?> oldPlugin, Object config) throws Exception {
        Path classesDir = compiler.compile(sourceFile, outputDir.resolve(sourceFile.getFileName().toString() + ".classes"));
        ClassLoader cl = loaderFactory.create(List.of(classesDir), getClass().getClassLoader());
        String className = pluginClassName(sourceFile);
        Class<?> cls;
        try {
            cls = cl.loadClass(className);
        } catch (Exception e) {
            throw new IllegalStateException("plugin load failed, keeping old: " + className, e);
        }
        if (!Plugin.class.isAssignableFrom(cls)) throw new IllegalStateException("not a plugin: " + className);
        Plugin<Object> newPlugin = (Plugin<Object>) cls.getDeclaredConstructor().newInstance();
        if (oldPlugin != null) ctx.registry.delete(oldPlugin);
        try {
            ctx.registry.plugin(ctx, newPlugin, config);
        } catch (Exception e) {
            if (oldPlugin != null) ctx.registry.plugin(ctx, oldPlugin, config);
            throw e;
        }
        return (Plugin<T>) newPlugin;
    }

    private String pluginClassName(Path sourceFile) {
        return sourceFile.getFileName().toString().replace(".java", "");
    }
}
