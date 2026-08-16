package dev.dsh.cordis.reload;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/** 插件 ClassLoader 工厂 seam(M3 决策:URLClassLoader 起步,ModuleLayer 后续可换)。 */
public interface PluginClassLoaderFactory {
    /** 为插件的 class 目录/文件创建一个新的隔离 ClassLoader。 */
    URLClassLoader create(List<Path> pluginClassRoots, ClassLoader parent);
}
