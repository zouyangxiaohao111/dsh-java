package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.NodeRef;
import dev.dsh.cordis.loader.LoadedPlugin;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M6-5b 集成:真实 web profile(仓库 profiles/web/cordis.yml)boot 出真实 dsh 插件
 * ({@code @deepseek-ai/dsh-system-prompt})经 Node 桥注册进 Java 核心。
 *
 * <p>前置:node + vendor/dsh 子模块 + system-prompt lib 已构建(./setup.sh 或
 * {@code node scripts/strip-dsh-libs.mjs})。缺 → 测试跳过。
 */
class RealDshWebProfileTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @Test
    void webProfileBootsRealSystemPromptIntoJavaCore() throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"), "..").toAbsolutePath().normalize();
        Path lib = repo.resolve("vendor/dsh/packages/core/system-prompt/lib/index.js");
        assumeTrue(Files.isRegularFile(lib),
                "vendor/dsh/packages/core/system-prompt/lib/index.js not present — run ./setup.sh (or node scripts/strip-dsh-libs.mjs) first");

        ProfileBoot boot = new ProfileBoot(repo.resolve("profiles"), repo);
        try (ProfileBoot.Handle handle = boot.bootOnce("web",
                new PrintStream(System.out, true, StandardCharsets.UTF_8))) {

            // 插件列表里 system-prompt 是 NODE 宿主(经 NodeWorkerJsHost 桥)
            List<LoadedPlugin> loaded = handle.loaded();
            LoadedPlugin sp = loaded.stream()
                    .filter(lp -> "system-prompt".equals(lp.entry().name()))
                    .findFirst().orElseThrow(() -> new AssertionError("web profile did not load system-prompt plugin"));
            assertThat(sp.kind()).isEqualTo(HostKind.NODE);
            assertThat(sp.ref()).contains("vendor/dsh/packages/core/system-prompt");

            // ctx 双向:真实 SystemPrompt(extends 桥 shim Service)注册进了 Java 核心
            Object svc = handle.ctx().get("systemPrompt");
            assertThat(svc).isInstanceOf(Map.class);
            Map<String, Object> systemPrompt = map(svc);
            assertThat(systemPrompt.get("name")).isEqualTo("systemPrompt");
            assertThat(systemPrompt.get("assemble")).isInstanceOf(NodeRef.class);
            assertThat(((NodeRef) systemPrompt.get("assemble")).kind()).isEqualTo("fn");

            // counter[JAVA] 仍加载(Java harness 与真实 dsh 插件同 profile 混排)
            assertThat(loaded).anySatisfy(lp ->
                    assertThat(lp.entry().name()).isEqualTo("counter"));
        }
    }
}
