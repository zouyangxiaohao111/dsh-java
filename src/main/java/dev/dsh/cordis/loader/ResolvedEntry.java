package dev.dsh.cordis.loader;

import dev.dsh.cordis.js.HostKind;

import java.nio.file.Path;

/**
 * {@link HostSelector} 的产出:一条声明解析后的宿主 + 目标引用 + 归一化绝对路径。
 *
 * <ul>
 *   <li>{@code kind} — 最终宿主({@code JAVA}/{@code GRAAL}/{@code NODE});</li>
 *   <li>{@code explicit} — 是否由前缀 / 显式 host 指定;{@code false} 表示经
 *       {@link dev.dsh.cordis.js.PluginRuntimeResolver} 自动检测(JS 加载时保留运行时兜底);</li>
 *   <li>{@code ref} — 前缀剥离后的引用(java: 时为类名 / .java 源,js 时为插件路径);</li>
 *   <li>{@code abs} — 相对 {@code baseDir} 归一化的绝对路径;纯类名 Java 引用为 null。</li>
 * </ul>
 */
public record ResolvedEntry(Entry entry, HostKind kind, boolean explicit, String ref, Path abs) {
}
