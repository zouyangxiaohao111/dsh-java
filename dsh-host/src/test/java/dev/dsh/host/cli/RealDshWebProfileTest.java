package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.loader.LoadedPlugin;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M8 集成:真实 dsh web profile(profiles/web = dsh-base + dsh-web-app bundle)boot 出
 * 真实 dsh agent UI 后端 —— webserver(@deepseek-ai/dsh-host-webserver)在 worker 宿主,
 * api-gateway(@deepseek-ai/dsh-host-apiproxy)聚合 ctx 服务,前端 dist 由 web-runtime 的
 * frontend-static fallback serve。
 *
 * <p>前置(缺 → 跳过):node + vendor/dsh 子模块 + client lib(scripts 或 setup.sh 的
 * build:lib:client)+ apps/web dist(vite build)+ web profile node_modules junctions
 * (scripts/link-web-profile.mjs)。M8 起 ./dshj web boot 即真实 UI(不再是 M6-5a 状态页)。
 *
 * <p>注意:全量 boot 逐插件 spawn Node worker,较慢(分钟级);这是 M8 的实测成本。
 */
class RealDshWebProfileTest {

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> map(Object v) {
        return (java.util.Map<String, Object>) v;
    }

    @Test
    void webProfileBootsRealDshWebBackendIntoJavaCore() throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"), "..").toAbsolutePath().normalize();
        Path dist = repo.resolve("vendor/dsh/apps/web/dist/index.html");
        Path lib = repo.resolve("vendor/dsh/packages/core/system-prompt/lib/index.js");
        assumeTrue(Files.isRegularFile(dist),
                "apps/web dist not built — run setup.sh (or build:web) first");
        assumeTrue(Files.isRegularFile(lib),
                "system-prompt lib not present — run ./setup.sh (or node scripts/strip-dsh-libs.mjs) first");
        assumeTrue(Files.isDirectory(repo.resolve("profiles/web/node_modules/@deepseek-ai")),
                "web profile node_modules junctions missing — run node scripts/link-web-profile.mjs first");
        assumeTrue(Files.isRegularFile(repo.resolve("profiles/web/package.json")),
                "profiles/web is not the M8 dsh profile (package.json + dsh.profile.bundles)");

        ProfileBoot boot = new ProfileBoot(repo.resolve("profiles"), repo);
        try (ProfileBoot.Handle handle = boot.bootOnce("web",
                new PrintStream(System.out, true, StandardCharsets.UTF_8))) {

            // M8:真实 dsh web 后端全树宿主 —— webserver / api-gateway / client 模块系统 /
            // 前端静态 serve 插件都真实加载(逐插件 NODE worker,经桥注册进 Java 核心)
            List<LoadedPlugin> loaded = handle.loaded();
            assertThat(loaded).anySatisfy(lp ->
                    assertThat(lp.entry().name()).isEqualTo("webserver"));
            assertThat(loaded).anySatisfy(lp ->
                    assertThat(lp.entry().name()).isEqualTo("api-gateway"));
            assertThat(loaded).anySatisfy(lp ->
                    assertThat(lp.entry().name()).isEqualTo("modules"));
            assertThat(loaded).anySatisfy(lp ->
                    assertThat(lp.entry().name()).isEqualTo("web-runtime"));

            // base 树真实 dsh 插件经 Node 桥注册进 Java 核心(system-prompt)
            LoadedPlugin sp = loaded.stream()
                    .filter(lp -> "system-prompt".equals(lp.entry().name()))
                    .findFirst().orElseThrow(() -> new AssertionError("web profile did not load system-prompt"));
            assertThat(sp.kind()).isEqualTo(HostKind.NODE);

            Object svc = handle.ctx().get("systemPrompt");
            assertThat(svc).isInstanceOf(java.util.Map.class);
            assertThat(map(svc).get("name")).isEqualTo("systemPrompt");
        }
    }
}
