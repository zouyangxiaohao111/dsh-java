package dev.dsh.cordis.js;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 按 {@link PluginRuntimeResolver} 的决策创建 JS 宿主(design §2.3)。
 *
 * <p>{@code GRAAL} → {@link GraalJsHost}(进程内),{@code NODE} → {@link NodeWorkerJsHost}
 * (进程外真 Node,构造需 spawn 进程,抛 {@link IOException})。{@code JAVA} 不走 JsHost,
 * 由 ClassLoader(PluginReloader)处理。
 */
public class JsHostFactory {

    /** 按决策创建宿主。{@code requireCwd} 为插件模块的 require 基准目录(可为空)。 */
    public JsHost create(HostKind kind, Path requireCwd) throws IOException {
        return switch (kind) {
            case GRAAL -> new GraalJsHost(requireCwd);
            case NODE -> new NodeWorkerJsHost(requireCwd);
            case JAVA -> throw new IllegalArgumentException(
                    "Java plugins are not loaded via JsHost; use ClassLoader (PluginReloader / reload path)");
        };
    }
}
