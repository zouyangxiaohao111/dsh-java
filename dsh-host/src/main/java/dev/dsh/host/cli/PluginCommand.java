package dev.dsh.host.cli;

import dev.dsh.host.plugin.PluginInstaller;

import java.io.PrintStream;
import java.util.List;

/**
 * {@code dshj plugin --profile <name> <args...>} —— profile 插件管理(m6-design §4)。
 *
 * <p>M6-7:实现 {@code add <spec>} 的真实 Java 侧安装(jar 从 Maven/本地、源码从本地/GitHub,
 * 经 {@link PluginInstaller});JS 侧({@code node:}/{@code graaljs:}/裸 npm)提示走真实
 * dsh {@code plugin add}(pnpm,M6-6)。移除/列出等其它子命令给出明确未实现提示。
 *
 * <p>{@code <spec>} 形式(与 cordis.yml {@code source}/{@code path} 对齐):
 * <ul>
 *   <li>{@code jar:<path|maven-coords>} — 外部 jar 插件(Java 宿主,M6-7);</li>
 *   <li>{@code java:<class|source>} — Java 源码/类(M6-7,source 可含 github:/git+);</li>
 *   <li>{@code node:<path>} / {@code graaljs:<path>} — 显式 JS 宿主;</li>
 *   <li>裸包名 {@code @scope/pkg} / {@code pkg} — dsh JS 插件(pnpm,经桥,M6-6);</li>
 *   <li>{@code git+...} / {@code github:...} — Java 源码的 git 源(M6-7)。</li>
 * </ul>
 */
public final class PluginCommand {

    /** 安装器 seam:测试注入指向临时 profile 根的安装器,避免写真实仓库 profiles/。 */
    private static PluginInstaller installer = new PluginInstaller();

    static void setInstaller(PluginInstaller value) {
        installer = value;
    }

    private PluginCommand() {
    }

    /**
     * 一个被解析校验的 {@code add <spec>}:前缀(可为 null,如 {@code jar}/{@code java}/
     * {@code node}/{@code graaljs},或裸 npm 包名时 null)与目标。M6-7 安装器复用同一解析。
     */
    public record Spec(String prefix, String target) {
        /** 将生成的 cordis.yml 条目 source/path 值。 */
        public String entryValue() {
            return prefix == null ? target : prefix + ":" + target;
        }
    }

    /** 运行 plugin 子命令,返回进程退出码。 */
    public static int run(String profile, List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty()) {
            err.println("dshj plugin: expected a subcommand (e.g. add <spec>)");
            err.println("  dshj plugin --profile <name> add <spec>   add a plugin to the profile");
            return 2;
        }
        String sub = args.get(0);
        switch (sub) {
            case "add" -> {
                return add(profile, args.subList(1, args.size()), out, err);
            }
            case "remove", "rm" -> {
                err.println("dshj plugin remove: not implemented yet (M6-7); use the dsh CLI or edit cordis.yml directly");
                return 2;
            }
            case "list", "ls" -> {
                err.println("dshj plugin list: not implemented yet (M6-7)");
                return 2;
            }
            default -> {
                err.println("dshj plugin: unknown subcommand '" + sub + "' (supported: add)");
                return 2;
            }
        }
    }

    private static int add(String profile, List<String> specs, PrintStream out, PrintStream err) {
        if (specs.isEmpty()) {
            err.println("dshj plugin add: missing <spec>");
            err.println("  dshj plugin --profile " + profile + " add <spec>");
            err.println("  spec: jar:<path|coords> | java:<class|source> | node:<path> | graaljs:<path> | <npm-package>");
            return 2;
        }
        boolean ok = true;
        for (String spec : specs) {
            Spec parsed = parseSpec(spec);
            if (parsed == null) {
                err.println("dshj plugin add: invalid spec '" + spec + "'");
                ok = false;
                continue;
            }
            if (parsed.prefix() != null && parsed.target().isBlank()) {
                err.println("dshj plugin add: '" + spec + "' has an empty target after '" + parsed.prefix() + ":'");
                ok = false;
                continue;
            }
            // JS 侧(node:/graaljs:/裸 npm)与 Java 侧(jar:/java:/github:/git+)分流。
            // JS 侧走真实 dsh plugin add(pnpm);Java 侧是本命令的 M6-7 职责。
            boolean javaSide = switch (parsed.prefix() == null ? "" : parsed.prefix()) {
                case "jar", "java", "github", "git+" -> true;
                default -> false;
            };
            if (!javaSide) {
                // JS 侧不安装(交给真实 dsh plugin add/pnpm):退出码非零对齐"未安装"语义。
                out.println("dshj plugin add: " + spec);
                out.println("  -> target profile: " + profile);
                out.println("  -> not installed (JS plugin): run the real dsh plugin add (pnpm) into the profile, then it loads via the JS bridge");
                ok = false;
                continue;
            }
            try {
                PluginInstaller.Installed installed = installer.install(profile, spec, out, err);
                out.println("dshj plugin add: installed '" + spec + "'");
                out.println("  -> entry: name: " + installed.name() + ", source: " + installed.source());
                out.println("  -> profile: " + profile + " (" + installed.configFile() + ")");
            } catch (PluginInstaller.InstallException e) {
                err.println("dshj plugin add: " + e.getMessage());
                ok = false;
            }
        }
        return ok ? 0 : 1;
    }

    /** 解析并校验一个 {@code <spec>};非法返回 null。M6-7 安装器也用它。 */
    public static Spec parseSpec(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String trimmed = spec.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefix = trimmed.substring(0, colon);
            String target = trimmed.substring(colon + 1);
            if (isKnownPrefix(prefix)) {
                return new Spec(prefix, target.trim());
            }
            if (prefix.equals("git+") || prefix.equals("github") || prefix.equals("file") || prefix.equals("link")) {
                return new Spec(prefix, target.trim());
            }
        }
        // 无前缀:裸 npm 包名(@scope/pkg 或 pkg)或本地路径/URL —— 交给 pnpm 语义
        return new Spec(null, trimmed);
    }

    private static boolean isKnownPrefix(String prefix) {
        return switch (prefix) {
            case "jar", "java", "node", "graaljs", "graal" -> true;
            default -> false;
        };
    }
}
