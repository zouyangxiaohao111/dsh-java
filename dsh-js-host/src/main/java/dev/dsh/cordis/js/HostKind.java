package dev.dsh.cordis.js;

/**
 * 插件宿主判定(design §2.3 三宿主动态选)。
 *
 * <ul>
 *   <li>{@link #JAVA} — ① Java 原生,ClassLoader 宿主(M3 已有,reload/PluginReloader);</li>
 *   <li>{@link #GRAAL} — ② 进程内 GraalJS(GraalJsHost);</li>
 *   <li>{@link #NODE} — ③ 进程外真 Node(NodeWorkerJsHost)。</li>
 * </ul>
 */
public enum HostKind {
    JAVA,
    GRAAL,
    NODE
}
