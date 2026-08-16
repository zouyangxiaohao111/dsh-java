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

    /** 重载一个 Java 插件:源码 → 编译 → 新 CL 实例化 → registry.delete(旧) + registry.plugin(新)。 */
    @SuppressWarnings("unchecked")
    public <T> Plugin<T> reload(Path sourceFile, Plugin<?> oldPlugin, Object config) throws Exception {
        Path classesDir = compiler.compile(sourceFile, outputDir.resolve(sourceFile.getFileName().toString() + ".classes"));
        ClassLoader cl = loaderFactory.create(List.of(classesDir), getClass().getClassLoader());
        String className = pluginClassName(sourceFile);
        Class<?> cls = cl.loadClass(className);
        if (!Plugin.class.isAssignableFrom(cls)) {
            throw new IllegalStateException(className + " does not implement Plugin");
        }
        Plugin<Object> newPlugin = (Plugin<Object>) cls.getDeclaredConstructor().newInstance();
        if (oldPlugin != null) ctx.registry.delete(oldPlugin);
        ctx.registry.plugin(ctx, newPlugin, config);
        return (Plugin<T>) newPlugin;
    }

    private String pluginClassName(Path sourceFile) {
        return sourceFile.getFileName().toString().replace(".java", "");
    }
}
