package dev.dsh.host.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * dshj 启动器参数解析(镜像 dsh {@code parseDshArgs})。
 *
 * <p>解析规则:
 * <ul>
 *   <li>{@code --profile <name>} / {@code -p <name>} / {@code --profile=<name>} — 目标 profile
 *       (boot 缺省 {@code web},{@code plugin} 必填);</li>
 *   <li>{@code --dev} / {@code --dev=<bool>} — dev 模式(启动器消费,不传给 app):boot 应用
 *       profile 的 dev 额外 patch 层,web profile 另起 tsdown/vite watcher;</li>
 *   <li>{@code --help}/{-h}、{@code --version}/{-V} — 帮助/版本(先于模式判定);</li>
 *   <li>首个位置参数决定模式:{@code boot} / {@code plugin} / profile 别名
 *       ({@code web}、{@code headless}、{@code cli})/ 其它;</li>
 *   <li>profile 别名可带 {@code boot} 命令词({@code dshj web boot}),也可省略
 *       ({@code dshj web "args"} = dsh 的 {@code dsh web "args"});</li>
 *   <li>已有 {@code --profile} 时首个位置参数不是已知命令/别名 → 按 dsh 语义视为
 *       booted app 的参数({@code dshj --profile tui "run the tests"})。</li>
 * </ul>
 */
public final class CliArgs {

    /** 已知 profile 别名(帮助文案展示;任意 profile 用 {@code --profile <name>})。 */
    static final List<String> KNOWN_PROFILES = List.of("web", "headless", "cli");

    /** 参数错误(用法问题),run 侧打印并返回退出码 2。 */
    static final class UsageError extends RuntimeException {
        UsageError(String message) {
            super(message);
        }
    }

    private CliArgs() {
    }

    /**
     * 解析 argv 为一个调用。
     *
     * @param argv       启动器参数(不含程序名)
     * @param profileOut 单元素出参:解析到的 {@code --profile} 值(未给时为 null)
     * @return 解析结果
     * @throws UsageError 用法错误(未知命令 / 缺参 / 冲突)
     */
    static CliInvocation parse(String[] argv, String[] profileOut) throws UsageError {
        List<String> profileOpt = new ArrayList<>();
        List<String> positional = new ArrayList<>();
        boolean help = false;
        boolean version = false;
        boolean dev = false;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("--help") || a.equals("-h")) {
                help = true;
            } else if (a.equals("--version") || a.equals("-V")) {
                version = true;
            } else if (a.equals("--profile") || a.equals("-p")) {
                if (i + 1 >= argv.length) throw new UsageError("--profile needs a name");
                profileOpt.add(argv[++i]);
            } else if (a.startsWith("--profile=")) {
                profileOpt.add(a.substring("--profile=".length()));
            } else if (a.equals("--dev")) {
                dev = true;
            } else if (a.startsWith("--dev=")) {
                String v = a.substring("--dev=".length()).trim();
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new UsageError("--dev needs true or false");
                }
                dev = Boolean.parseBoolean(v);
            } else {
                positional.add(a);
            }
        }
        if (profileOpt.size() > 1) {
            throw new UsageError("--profile given more than once");
        }
        String profile = profileOpt.isEmpty() ? null : profileOpt.get(0);
        if (profile != null && profile.isBlank()) {
            throw new UsageError("--profile needs a name");
        }
        profileOut[0] = profile;

        if (help) return new CliInvocation.Help();
        if (version) return new CliInvocation.Version();

        if (positional.isEmpty()) {
            if (profile != null) return new CliInvocation.Boot(profile, List.of(), dev);
            return new CliInvocation.Help();
        }
        String first = positional.get(0);
        List<String> rest = positional.subList(1, positional.size());
        switch (first) {
            case "boot" -> {
                String p = profile != null ? profile : "web";
                return new CliInvocation.Boot(p, rest, dev);
            }
            case "plugin" -> {
                if (profile == null) throw new UsageError("plugin needs --profile <name>");
                return new CliInvocation.Plugin(profile, rest);
            }
            case "web", "headless", "cli" -> {
                if (profile != null) {
                    throw new UsageError("'" + first + "' conflicts with --profile " + profile);
                }
                List<String> app = rest;
                if (!app.isEmpty() && app.get(0).equals("boot")) app = app.subList(1, app.size());
                return new CliInvocation.Boot(first, app, dev);
            }
            default -> {
                if (profile != null) {
                    // dsh 兼容:--profile <name> 后无子命令 → 其余参数交给 booted app
                    return new CliInvocation.Boot(profile, positional, dev);
                }
                throw new UsageError("unknown command '" + first + "'");
            }
        }
    }
}
