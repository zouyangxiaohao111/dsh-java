package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.NodeRef;
import dev.dsh.cordis.loader.LoadedPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M7-1 — dsh profile 消费闭环(核心侧):一个由真实 dsh CLI 装出的 profile(manifest
 * {@code dsh.profile.bundles} + 用户 cordis.patch.yml 层 + node_modules 里的 bundle 包)
 * 被 ProfileBoot 经 {@code DshProfileReader} 消费 → bundle 包 patch 组合 → PluginLoaderService
 * 经 Node 桥加载,插件注册进 Java 核心。
 *
 * <p>本测试构造的是"真实 dsh CLI 产物"的等价形状(manifest + bundle 包 + 用户层),锁定
 * 消费路径;真实 CLI 安装动作本身由 {@link RealDshCliInstallConsumeTest}(需 pnpm)与手工
 * e2e 证据覆盖。fixture 是一个最小真实 dsh bundle 包(m7-1-demo-bundle):声明
 * {@code dsh.bundle.patch},插件入口 {@code import { Service } from '@deepseek-ai/cordis'}
 * (桥 resolve-hook 拦到 Java 桥 shim)→ {@code super(ctx, name)} 注册进 Java 核心。
 *
 * <p>前置:node 可执行(桥是 Node worker)。缺 → 测试跳过。
 */
class DemoProfileConsumptionTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void assumeNode() {
        assumeTrue(nodeAvailable(), "skipped: no node executable on PATH (set NODE to override)");
    }

    /** 探测可执行 node(与 dsh-js-host 的 NodeEnv 同逻辑;测试用工具放主源码不合适,就地复刻)。 */
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

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"), "..").toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    /** 拷贝 fixture bundle 到 profile 的 node_modules(dsh CLI pnpm add 的等价物)。 */
    private static void copyBundle(Path fixture, Path dest) throws Exception {
        try (Stream<Path> walk = Files.walk(fixture)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path rel = fixture.relativize(p);
                Path out = dest.resolve(rel);
                Files.createDirectories(out.getParent());
                Files.copy(p, out);
            }
        }
    }

    @Test
    void consumesInstalledDshBundleViaBridgeIntoJavaCore() throws Exception {
        Path repo = repoRoot();
        Path fixture = repo.resolve("dsh-host/src/test/resources/m7-1-demo-bundle");
        assumeTrue(Files.isRegularFile(fixture.resolve("package.json")),
                "m7-1-demo-bundle fixture missing: " + fixture);

        // 真实 dsh CLI 装出的 demo profile 形状:$DSH_HOME/profiles/demo/
        Path home = tmp.resolve("home");
        Path profileDir = home.resolve("profiles/demo");
        Path bundlePkg = profileDir.resolve("node_modules/@dshj/demo-bundle");
        Files.createDirectories(bundlePkg);
        copyBundle(fixture, bundlePkg);
        // manifest:reconcile 后的 dsh.profile.bundles = 该 bundle(dsh CLI 装它即入层)
        Files.writeString(profileDir.resolve("package.json"),
                "{\"name\":\"dsh-profile-demo\",\"private\":true,"
                        + "\"dependencies\":{\"@dshj/demo-bundle\":\"link:...\"},"
                        + "\"dsh\":{\"profile\":{\"bundles\":[\"@dshj/demo-bundle\"]}}}");
        // 用户 patch 层:initProfile 写出的空层
        Files.writeString(profileDir.resolve("cordis.patch.yml"), "[]\n");

        ProfileBoot boot = new ProfileBoot(home.resolve("profiles"), repo);
        try (ProfileBoot.Handle handle = boot.bootOnce("demo",
                new PrintStream(System.out, true, StandardCharsets.UTF_8))) {

            // 断言一:boot 走的是 dsh profile 路径(组合 bundle),插件列表含 demo-bundle[NODE]
            assertThat(handle.startupLog()).anyMatch(l -> l.contains("dsh profile"));
            LoadedPlugin db = handle.loaded().stream()
                    .filter(lp -> "demo-bundle".equals(lp.entry().name()))
                    .findFirst().orElseThrow(() -> new AssertionError("demo profile did not load demo-bundle plugin"));
            assertThat(db.kind()).isEqualTo(HostKind.NODE);
            assertThat(db.ref()).contains("@dshj/demo-bundle");

            // 断言二:fixture 的 '@deepseek-ai/cordis' import 被拦到桥 shim,Service 注册进 Java 核心
            Object svc = handle.ctx().get("demo-bundle");
            assertThat(svc).isInstanceOf(Map.class);
            Map<String, Object> demoBundle = map(svc);
            assertThat(demoBundle.get("name")).isEqualTo("demo-bundle");
            // 公开方法绑成 own 属性 → 跨桥变 fn 句柄
            assertThat(demoBundle.get("greet")).isInstanceOf(NodeRef.class);
            assertThat(((NodeRef) demoBundle.get("greet")).kind()).isEqualTo("fn");

            // 断言三:用户 patch 层组合正确 —— 启动日志把 patch 声明的 config 带进加载
            assertThat(handle.startupLog()).anyMatch(l -> l.contains("@dshj/demo-bundle"));
        }
    }

    @Test
    void installAnchorFallbackResolvesFromProfileNodeModules() throws Exception {
        // 当 dsh 安装(install anchor)解析不到 bundle 时,从 profile 自身 node_modules 解析
        Path repo = repoRoot();
        Path fixture = repo.resolve("dsh-host/src/test/resources/m7-1-demo-bundle");
        assumeTrue(Files.isRegularFile(fixture.resolve("package.json")), "fixture missing");

        Path home = tmp.resolve("home2");
        Path profileDir = home.resolve("profiles/demo");
        Path bundlePkg = profileDir.resolve("node_modules/@dshj/demo-bundle");
        Files.createDirectories(bundlePkg);
        copyBundle(fixture, bundlePkg);
        Files.writeString(profileDir.resolve("package.json"),
                "{\"name\":\"dsh-profile-demo\",\"private\":true,"
                        + "\"dsh\":{\"profile\":{\"bundles\":[\"@dshj/demo-bundle\"]}}}");

        ProfileBoot boot = new ProfileBoot(home.resolve("profiles"), repo);
        try (ProfileBoot.Handle handle = boot.bootOnce("demo",
                new PrintStream(System.out, true, StandardCharsets.UTF_8))) {
            assertThat(handle.loaded()).extracting(lp -> lp.entry().name())
                    .contains("demo-bundle");
        }
    }

    @Test
    void composedUserLayerDisabledRowsStayOut() throws Exception {
        // 用户层可用 dsh patch 机制(id 定向 disabled)把 bundle 层里跑不了的行钉掉 ——
        // 正是 M7-1 demo 对 dsh-base 全量核心树做的边界裁剪。此处用两个 bundle 验证:
        // 用户层禁掉第一个 bundle 的 heavy 行,仅保留可跑的 demo-bundle。
        Path repo = repoRoot();
        Path fixture = repo.resolve("dsh-host/src/test/resources/m7-1-demo-bundle");
        assumeTrue(Files.isRegularFile(fixture.resolve("package.json")), "fixture missing");

        Path home = tmp.resolve("home3");
        Path profileDir = home.resolve("profiles/demo");
        // bundle 一:mini 但带两个 entry(一个"heavy"、一个 demo-bundle)
        Path bundlePkg = profileDir.resolve("node_modules/@dshj/demo-bundle");
        Files.createDirectories(bundlePkg);
        copyBundle(fixture, bundlePkg);
        Files.writeString(bundlePkg.resolve("cordis.patch.yml"), """
                - insert:
                    - id: heavy-needs-seam
                      name: '@dshj/does-not-exist'
                    - id: demo-bundle
                      name: '@dshj/demo-bundle'
                """);
        Files.writeString(profileDir.resolve("package.json"),
                "{\"name\":\"dsh-profile-demo\",\"private\":true,"
                        + "\"dsh\":{\"profile\":{\"bundles\":[\"@dshj/demo-bundle\"]}}}");
        // 用户层把 heavy 行钉掉(真实 dsh 用户对 dsh-base 全量树的做法)
        Files.writeString(profileDir.resolve("cordis.patch.yml"), """
                - id: heavy-needs-seam
                  disabled: true
                """);

        ProfileBoot boot = new ProfileBoot(home.resolve("profiles"), repo);
        try (ProfileBoot.Handle handle = boot.bootOnce("demo",
                new PrintStream(System.out, true, StandardCharsets.UTF_8))) {
            List<LoadedPlugin> loaded = handle.loaded();
            assertThat(loaded).extracting(lp -> lp.entry().name())
                    .containsExactly("demo-bundle");
        }
    }
}
