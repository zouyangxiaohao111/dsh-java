package dev.dsh.host;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * IDEA 一键启动入口(M10-2):从 IntelliJ 直接 Run 本类即启动 dsh-java harness。
 *
 * <p>相比 {@code ./dshj}(bash → gradle/installDist),本类省去工作目录配置:
 * 自动定位仓库根(沿 user.dir 向上找 {@code settings.gradle.kts},或
 * {@code -Ddshj.repoRoot=...} 覆盖),必要时把 {@code user.dir} 切到仓库根
 * (harness 的 profile/vendor/dsh 都相对它解析),再调 {@code dev.dsh.host.cli.DshCli.main}。
 *
 * <p>IDEA Run Configuration 建议:
 * <ul>
 *   <li>Main class: {@code dev.dsh.host.DshjLauncher}</li>
 *   <li>Program arguments: 空 = 默认 {@code web boot};(可写 {@code headless} / {@code --profile x}
 *       / {@code web --dev})</li>
 *   <li>VM options: {@code -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8}(Windows 中文不乱码)</li>
 *   <li>Working directory: 任意(本类自动切到仓库根)</li>
 * </ul>
 */
public final class DshjLauncher {

    private DshjLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Path repoRoot = resolveRepoRoot();
        // harness 用 user.dir 解析 profiles/ 与 vendor/dsh;切到仓库根再启动。
        System.setProperty("user.dir", repoRoot.toString());
        // 无参数默认 boot web profile(与 ./dshj web 等价)。
        String[] effective = (args == null || args.length == 0) ? new String[]{"web"} : args;
        dev.dsh.host.cli.DshCli.main(effective);
    }

    /** 定位仓库根:-Ddshj.repoRoot 覆盖,否则沿 user.dir 向上找 settings.gradle.kts。 */
    private static Path resolveRepoRoot() {
        String override = System.getProperty("dshj.repoRoot");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate dsh-java repo root (no settings.gradle.kts above user.dir);"
                    + " set -Ddshj.repoRoot=<repo>");
        }
        return dir;
    }
}
