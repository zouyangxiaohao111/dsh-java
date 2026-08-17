package dev.dsh.cordis.js;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 按 {@link PluginRuntimeResolver} 的决策创建 JS 宿主(design §2.3)。
 *
 * <p>{@code GRAAL} → {@link GraalJsHost}(进程内),{@code NODE} → {@link NodeWorkerJsHost}
 * (进程外真 Node,构造需 spawn 进程,抛 {@link IOException})。{@code JAVA} 不走 JsHost,
 * 由 ClassLoader(PluginReloader)处理。
 *
 * <p>M6-4:可携带裸模块解析基址({@code moduleBases},如 vendor/dsh/node_modules +
 * profile/node_modules),创建 Node 宿主时传入(见 {@link NodeWorkerJsHost} 的 seam)。
 */
public class JsHostFactory {

    private final List<Path> moduleBases;

    public JsHostFactory() {
        this(List.of());
    }

    /** 带裸模块解析基址的工厂(M6-4):新建的 Node worker 会把这些基址作为解析基址。 */
    public JsHostFactory(List<Path> moduleBases) {
        this.moduleBases = moduleBases == null ? List.of() : List.copyOf(moduleBases);
    }

    /** 当前工厂携带的裸模块解析基址。 */
    public List<Path> moduleBases() {
        return moduleBases;
    }

    /** 按决策创建宿主。{@code requireCwd} 为插件模块的 require 基准目录(可为空)。 */
    public JsHost create(HostKind kind, Path requireCwd) throws IOException {
        return switch (kind) {
            case GRAAL -> new GraalJsHost(requireCwd);
            case NODE -> new NodeWorkerJsHost(requireCwd, moduleBases);
            case JAVA -> throw new IllegalArgumentException(
                    "Java plugins are not loaded via JsHost; use ClassLoader (PluginReloader / reload path)");
        };
    }
}
