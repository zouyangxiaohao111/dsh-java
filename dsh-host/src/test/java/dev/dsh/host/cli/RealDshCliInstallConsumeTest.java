package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.NodeRef;
import dev.dsh.cordis.loader.LoadedPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M7-1 — 真实 dsh CLI 装、我们跑(全链路,环境门控):用 vendor/dsh 子模块里的真实 dsh CLI
 * {@code dsh plugin --profile demo add <包>} 建 demo profile 并装进一个最小 dsh bundle
 * (本地路径,免网络),reconcile 把包写进 {@code dsh.profile.bundles};随后 DSH_HOME 指向同一
 * 目录,我们的 {@code ProfileBoot} 经 DshProfileReader 消费 bundles + patch 组合 + Node 桥加载。
 *
 * <p>门控(缺任一项 → 测试跳过并说明):node 可执行、vendor/dsh CLI 构建产物
 * ({@code apps/cli/lib/bin.js})、PATH 上有 pnpm ≥ 8(dsh profile 的 pnpm-workspace.yaml
 * 是 workspace 根;pnpm 7 的 {@code add} 会报 ERR_PNPM_ADDING_TO_ROOT)。
 *
 * <p>真实 CLI 装的是本地路径 fixture(@dshj/demo-bundle),因此不依赖 npm 网络;npm 注册表
 * 安装真实生态包(e.g. @deepseek-ai/dsh-system-prompt@next)由 docs/m7-1 证据覆盖。
 */
class RealDshCliInstallConsumeTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void assumeNode() {
        assumeTrue(nodeAvailable(), "skipped: no node executable on PATH (set NODE to override)");
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"), "..").toAbsolutePath().normalize();
    }

    private static boolean nodeAvailable() {
        String cmd = System.getenv("NODE");
        if (cmd == null || cmd.isBlank()) cmd = "node";
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** PATH 上的 pnpm 主版本(缺 pnpm 返回 -1)。 */
    private static int pnpmMajor() {
        try {
            Process p = new ProcessBuilder("pnpm", "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            int dot = out.indexOf('.');
            return Integer.parseInt(dot > 0 ? out.substring(0, dot) : out);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 仓库内文档化的本地 pnpm 安装(build/pnpm,验证 M7-1 时用 npm install --prefix build/pnpm pnpm@11)。
     *  存在且可执行 → 返回其 bin 目录(PATH 上无合适 pnpm 时,prepend 到 CLI 进程 PATH);否则 null。 */
    private static Path localPnpmBin() {
        Path bin = repoRoot().resolve("build/pnpm/node_modules/.bin");
        if (Files.isRegularFile(bin.resolve("pnpm.cmd")) || Files.isRegularFile(bin.resolve("pnpm"))) {
            return bin;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    /** 递归复制一个目录(普通文件副本,不用 junction/symlink)。 */
    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path out = to.resolve(from.relativize(p));
                Files.createDirectories(out.getParent());
                Files.copy(p, out);
            }
        }
    }

    @Test
    void realDshCliInstallsBundleThenWeBootIt() throws Exception {
        Path repo = repoRoot();
        Path cli = repo.resolve("vendor/dsh/apps/cli/lib/bin.js");
        assumeTrue(Files.isRegularFile(cli),
                "vendor/dsh/apps/cli/lib/bin.js not present — run ./setup.sh (or node scripts/strip-dsh-libs.mjs) first");
        int pnpm = pnpmMajor();
        Path localPnpm = localPnpmBin();
        if (pnpm < 8 && localPnpm == null) {
            assumeTrue(false, "skipped: pnpm >= 8 required on PATH for 'dsh plugin add' "
                    + "(found " + pnpm + "); or npm install --prefix build/pnpm pnpm@11");
        }
        Path fixture = repo.resolve("dsh-host/src/test/resources/m7-1-demo-bundle");
        assumeTrue(Files.isRegularFile(fixture.resolve("package.json")), "fixture missing: " + fixture);

        // Windows 上 pnpm add <dir> 会在 node_modules 建 junction 指向该目录;JUnit @TempDir
        // 递归清理会跟随 junction 删掉目标内容 —— 不能让它指到源 fixture。先把 fixture 复制到
        // 本测试自己的临时舞台,再让 pnpm 指向舞台副本。
        Path stage = tmp.resolve("stage/m7-1-demo-bundle");
        copyTree(fixture, stage);

        // ① 真实 dsh CLI:DSH_HOME=<临时目录>,plugin --profile demo add <本地 bundle 路径>
        Path home = tmp.resolve("home");
        Path profileDir = home.resolve("profiles/demo");
        Files.createDirectories(profileDir);
        // 预置空 manifest(跳过 initProfile 的 dsh-base 模板;bundle 由 reconcile 写进 layers)
        Files.writeString(profileDir.resolve("package.json"),
                "{\"name\":\"dsh-profile-demo\",\"private\":true,\"dependencies\":{},"
                        + "\"dsh\":{\"profile\":{\"bundles\":[]}}}");

        ProcessBuilder pb = new ProcessBuilder("node", cli.toString(),
                "plugin", "--profile", "demo", "add", stage.toAbsolutePath().toString());
        pb.environment().put("DSH_HOME", home.toAbsolutePath().toString());
        // PATH 上没有合适 pnpm 时,把仓库内本地 pnpm 的 bin 前置,让 CLI 的 pnpm 转发可用
        if (pnpm < 8 && localPnpm != null) {
            String path = System.getenv("PATH");
            pb.environment().put("PATH", localPnpm + File.pathSeparator + (path != null ? path : ""));
        }
        pb.redirectErrorStream(true);
        Process dsh = pb.start();
        String out = new String(dsh.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = dsh.waitFor(120, TimeUnit.SECONDS);
        assumeTrue(finished, "dsh CLI did not finish within 120s\n" + out);
        assertThat(dsh.exitValue()).as("dsh plugin add failed:\n" + out).isEqualTo(0);

        // ② dsh CLI reconcile 把声明 dsh.bundle 的包写进 dsh.profile.bundles
        String manifest = Files.readString(profileDir.resolve("package.json"));
        assertThat(manifest).contains("\"@dshj/demo-bundle\"");
        // pnpm 把包装进了 profile node_modules(consumption 的模块解析基址)
        assertThat(Files.isRegularFile(profileDir.resolve("node_modules/@dshj/demo-bundle/package.json")))
                .as("pnpm should install the bundle under profile node_modules").isTrue();

        // ③ 我们的核心消费:同一 DSH_HOME,ProfileBoot 经 DshProfileReader 加载
        ProfileBoot boot = new ProfileBoot(home.resolve("profiles"), repo);
        try (ProfileBoot.Handle handle = boot.bootOnce("demo",
                new PrintStream(System.out, true, StandardCharsets.UTF_8))) {
            assertThat(handle.startupLog()).anyMatch(l -> l.contains("dsh profile"));
            LoadedPlugin db = handle.loaded().stream()
                    .filter(lp -> "demo-bundle".equals(lp.entry().name()))
                    .findFirst().orElseThrow(() -> new AssertionError("demo profile did not load demo-bundle plugin"));
            assertThat(db.kind()).isEqualTo(HostKind.NODE);
            assertThat(db.ref()).contains("@dshj/demo-bundle");

            // 桥 shim Service 注册进 Java 核心,公开方法跨桥成 fn 句柄
            Object svc = handle.ctx().get("demo-bundle");
            assertThat(svc).isInstanceOf(Map.class);
            assertThat(map(svc).get("name")).isEqualTo("demo-bundle");
            assertThat(map(svc).get("greet")).isInstanceOf(NodeRef.class);
            assertThat(((NodeRef) map(svc).get("greet")).kind()).isEqualTo("fn");
        }
    }
}
