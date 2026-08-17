package dev.dsh.host.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code dshj plugin --profile <name> <args...>} —— profile 插件管理(m6-design §4)。
 *
 * <p>M6-4 骨架:实现 {@code add <spec>} 的参数解析与校验,并打印"将生成的 cordis.yml 条目 /
 * 安装计划"。真正的 Java 侧安装(jar 从 Maven、源码从 GitHub)是 M6-7;dsh 侧
 * {@code pnpm add} 消费是 M6-6。移除/列出等其它子命令给出明确未实现提示。
 *
 * <p>{@code <spec>} 形式(与 cordis.yml {@code source}/{@code path} 对齐):
 * <ul>
 *   <li>{@code jar:<path|maven-coords>} — 外部 jar 插件(Java 宿主,M6-7);</li>
 *   <li>{@code java:<class|source>} — Java 源码/类(M6-7);</li>
 *   <li>{@code node:<path>} / {@code graaljs:<path>} — 显式 JS 宿主;</li>
 *   <li>裸包名 {@code @scope/pkg} / {@code pkg} — dsh JS 插件(pnpm,经桥,M6-6);</li>
 *   <li>{@code git+...} / {@code github:...} — git 源(M6-7)。</li>
 * </ul>
 */
public final class PluginCommand {

    private PluginCommand() {
    }

    /** 一个被解析校验的 {@code add <spec>}:前缀(可为 null)与目标。 */
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
            out.println("dshj plugin add: " + spec);
            out.println("  -> cordis.yml entry (to be written on install): source: " + parsed.entryValue());
            out.println("  -> target profile: " + profile);
            switch (parsed.prefix() == null ? "npm" : parsed.prefix()) {
                case "jar", "java" -> out.println("  -> Java plugin install (Maven / GitHub) is M6-7; M6-4 validates the shape only");
                case "node", "graaljs" -> out.println("  -> JS plugin via the bridge; dependency install via pnpm (M6-6)");
                default -> out.println("  -> dsh JS plugin: pnpm add into the profile, then loaded via the bridge (M6-6)");
            }
        }
        return ok ? 0 : 2;
    }

    /** 解析并校验一个 {@code <spec>};非法返回 null。 */
    static Spec parseSpec(String spec) {
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

    /** 本地 jar 路径是否真的存在(供 add 校验提示)。 */
    static boolean isExistingJar(String spec) {
        Spec parsed = parseSpec(spec);
        if (parsed == null || !"jar".equals(parsed.prefix())) return false;
        String t = parsed.target();
        return t.endsWith(".jar") && Files.isRegularFile(Path.of(t));
    }
}
