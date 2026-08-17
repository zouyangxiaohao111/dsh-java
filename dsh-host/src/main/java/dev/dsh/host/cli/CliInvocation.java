package dev.dsh.host.cli;

import java.util.List;

/**
 * 一次解析出的 dshj 调用(m6-design §4 应用层)。命令形状镜像 dsh 启动器:
 *
 * <pre>{@code
 *   dshj [--profile <name>] boot [appArgs...]    // 启动指定 profile(缺省 web)
 *   dshj web|headless|cli [boot] [appArgs...]    // profile 别名(--profile <name>)
 *   dshj plugin --profile <name> <args...>       // profile 插件管理(add/remove/...)
 *   dshj --help | -h                             // 帮助
 *   dshj --version | -V                          // 版本
 * }</pre>
 */
public sealed interface CliInvocation {

    /** 启动一个 profile。{@code appArgs} 是启动器 flag 之后的参数,原样交给 booted app。 */
    record Boot(String profile, List<String> appArgs) implements CliInvocation {}

    /** 插件管理。{@code args} 为子命令参数(如 {@code add <spec>});M6-4 实现 {@code add} 骨架。 */
    record Plugin(String profile, List<String> args) implements CliInvocation {}

    /** 打印帮助。 */
    record Help() implements CliInvocation {}

    /** 打印版本。 */
    record Version() implements CliInvocation {}
}
