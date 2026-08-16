package dev.dsh.cordis.reload;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/** 默认实现:每插件一个 URLClassLoader,parent = 核心 CL。 */
public final class UrlPluginClassLoaderFactory implements PluginClassLoaderFactory {
    @Override
    public URLClassLoader create(List<Path> pluginClassRoots, ClassLoader parent) {
        URL[] urls = pluginClassRoots.stream()
                .map(p -> {
                    try { return p.toUri().toURL(); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                })
                .toArray(URL[]::new);
        return new URLClassLoader(urls, parent);
    }
}
