package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M6-5b — 真实 dsh 插件(子模块 vendor/dsh)经 Node 桥加载,ctx 双向到 Java 核心。
 *
 * <p>与 M4 {@link SystemPromptFusionTest} 的区别:那次用的 agent-fusion overlay 里
 * {@code @deepseek-ai/cordis} 是手写的 shim;这次直接加载 vendor/dsh 子模块里的真实
 * {@code @deepseek-ai/dsh-system-prompt} 包 —— 它的 {@code @deepseek-ai/cordis} import 被
 * node-bridge.js 的 resolve 钩子拦到 Java 桥 shim(node-resolve-hook.cjs),其余 dsh 依赖
 * (schemastery / dsh-scope / cosmokit)从子模块自身的 node_modules(pnpm workspace 符号链接)
 * 原生解析。load 的插件模块默认导出 = {@code SystemPrompt extends Service}:bridge 的 apply
 * 按 cordis 类插件语义 {@code new SystemPrompt(ctx, config)} 实例化,构造器里
 * {@code super(ctx, 'systemPrompt')}(shim Service)把服务注册进 Java 核心。
 *
 * <p><b>前置</b>:node 可执行 + vendor/dsh 子模块已拉取 + 依赖 lib 已构建
 * ({@code ./setup.sh} 或 {@code node scripts/strip-dsh-libs.mjs})。缺任一 → 测试跳过并给出提示。
 */
class RealDshPluginBridgeTest {

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private static Path repoVendor() {
        return Path.of(System.getProperty("user.dir"), "..", "vendor", "dsh").toAbsolutePath().normalize();
    }

    /** 子模块里 system-prompt 包(入口 lib/index.js,exports main→lib)。 */
    private static Path systemPromptLib() {
        return repoVendor().resolve("packages/core/system-prompt/lib/index.js");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object v) {
        return (List<Map<String, Object>>) v;
    }

    /** 真实 SystemPrompt 经桥注册进 Java registry + Java 触发 assemble 拿回合并结果。 */
    @Test
    void realSystemPromptRegistersIntoJavaCoreAndAssembles() throws Exception {
        Path lib = systemPromptLib();
        assumeTrue(Files.isRegularFile(lib),
                "vendor/dsh/packages/core/system-prompt/lib/index.js not present — run ./setup.sh (or node scripts/strip-dsh-libs.mjs) first");

        Path pkgDir = lib.getParent().getParent();          // .../system-prompt
        Path vendorNodeModules = repoVendor().resolve("node_modules");

        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost(pkgDir, List.of(vendorNodeModules))) {
            try {
                root.plugin(new JsPluginAdapter(host, host.loadModule(lib)), Map.of(
                        "includeHarnessIdentity", true,
                        "includeRuntimeContext", false,
                        "persona", "You are the deployment persona."));

                // 断言一(ctx → Java):真实 SystemPrompt 经 shim Service 注册进 Java 核心。
                // 只有 Java 桥 shim 的 Service 构造器会 ctx.provide(name, self)(真实 cordis 不会),
                // 故该值存在即证明 '@deepseek-ai/cordis' 被拦到了 shim。
                Object svc = root.get("systemPrompt");
                assertThat(svc).isNotNull().isInstanceOf(Map.class);
                Map<String, Object> systemPrompt = map(svc);
                assertThat(systemPrompt.get("name")).isEqualTo("systemPrompt");
                // shim Service 把 public 原型方法绑成 own 属性 → 跨桥变成 fn 句柄。
                assertThat(systemPrompt.get("assemble")).isInstanceOf(NodeRef.class);
                assertThat(((NodeRef) systemPrompt.get("assemble")).kind()).isEqualTo("fn");
                assertThat(systemPrompt.get("section")).isInstanceOf(NodeRef.class);
                assertThat(systemPrompt.get("variable")).isInstanceOf(NodeRef.class);

                // 断言二(Java → ctx → Java):Java 触发真实 assemble()(真实包代码在 worker 里跑),
                // 合并结果跨桥回到 Java。
                Object result = host.invokeFn((NodeRef) systemPrompt.get("assemble"), List.of());
                Map<String, Object> assembly = map(result);
                List<Map<String, Object>> sections = list(assembly.get("sections"));
                // harness identity(-100)→ persona(0) 按 order 排好;includeRuntimeContext=false → 无 contexts。
                assertThat(sections).extracting(s -> s.get("name"))
                        .containsExactly("harness:identity", "deployment:persona");
                assertThat(sections).anySatisfy(s -> {
                    if ("harness:identity".equals(s.get("name"))) {
                        assertThat(s.get("text")).isEqualTo("You are an AI agent powered by DeepSeek Harness.");
                    }
                });
                assertThat(sections).anySatisfy(s -> {
                    if ("deployment:persona".equals(s.get("name"))) {
                        assertThat(s.get("text")).isEqualTo("You are the deployment persona.");
                    }
                });
                assertThat(assembly.get("contexts")).asList().isEmpty();
            } finally {
                root.fiber.dispose().join();
            }
        }
    }
}
