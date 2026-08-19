package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.loader.DshProfileReader;
import dev.dsh.cordis.loader.Entry;
import dev.dsh.cordis.loader.LoadedPlugin;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * M10-1:真实 web profile 的 dev patch 层组合(不 boot,快)。dev boot 时
     * {@code cordis.patch.dev.yml} 在用户层之后应用 —— client-hmr 解 pin + 并入 web 组;
     * 非 dev boot 保持用户层钉住(不产出 client-hmr)。
     */
    @Test
    void devPatchLayerEnablesClientHmrForRealWebProfile() throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"), "..").toAbsolutePath().normalize();
        Path profileDir = repo.resolve("profiles/web");
        Path installAnchor = repo.resolve("vendor/dsh/package.json");
        assumeTrue(Files.isRegularFile(profileDir.resolve("package.json")),
                "profiles/web is not the M8 dsh profile (package.json + dsh.profile.bundles)");
        assumeTrue(Files.isRegularFile(profileDir.resolve(DshProfileReader.DEV_PATCH_FILENAME)),
                "profiles/web/cordis.patch.dev.yml missing");

        DshProfileReader reader = new DshProfileReader();
        // 非 dev:client-hmr 被用户层钉住(disabled: true)→ 不产出
        List<Entry> normal = reader.load(profileDir, installAnchor, false);
        Map<String, Entry> normalById = normal.stream().collect(Collectors.toMap(Entry::name, e -> e));
        assertThat(normalById).doesNotContainKey("client-hmr");

        // dev:client-hmr 解 pin(产出)+ group: web(与 webserver/modules 同 worker)
        List<Entry> dev = reader.load(profileDir, installAnchor, true);
        Map<String, Entry> devById = dev.stream().collect(Collectors.toMap(Entry::name, e -> e));
        assertThat(devById).containsKey("client-hmr");
        Entry hmr = devById.get("client-hmr");
        assertThat(hmr.disabled()).isNull();
        assertThat(hmr.group()).isEqualTo("web");
    }
}
