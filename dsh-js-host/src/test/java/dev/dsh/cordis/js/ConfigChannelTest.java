package dev.dsh.cordis.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-5 配置通道:worker 侧(apply 之前)schemastery/zod static Config 默认化 + {@code !!js} 求值。
 *
 * <p>覆盖:① zod Config(config=null → 默认化对象);② schemastery Config(可调用 schema,同理);
 * ③ 无 Config → config 原样;④ Config 抛错 → 回退原 config 不挂加载;⑤ config 里的
 * {@code !!js} 标记值在 worker 求值正确(scope = process.env + dshHomePath,经 env seam 注入)。
 *
 * <p>前置:系统需有 {@code node} 可执行(测试用,构建不依赖)。
 */
class ConfigChannelTest {

    @TempDir
    Path tmp;

    /** 无 node 可执行时整个测试类 skip(P3 可移植性;构建在无 Node 环境仍全绿)。 */
    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private Path writePlugin(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    /** 插件 apply 经 ctx.emit('applied', JSON.stringify(config)) 汇报收到的 config。 */
    private AtomicReference<String> capture(Context root) {
        AtomicReference<String> got = new AtomicReference<>();
        root.on("applied", (c, args) -> {
            got.set(String.valueOf(args[0]));
            return null;
        });
        return got;
    }

    @Test
    void zodConfigDefaultsNullToObject() throws Exception {
        // 模仿 @deepseek-ai/dsh-jobs-local 的 zod Config(static Config = z.object({...}))
        Path plugin = writePlugin("zod-plugin.cjs", """
                module.exports = {
                  name: 'zod-plugin',
                  Config: {
                    parse(raw) { return { maxConcurrentJobsPerOwner: 10, ...raw }; }
                  },
                  apply(ctx, config) { ctx.emit('applied', JSON.stringify(config)); }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        try (JsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            // config=null → zod .parse({}) → 默认化对象,且 raw 的显式覆盖仍生效
            assertThat(got.get()).isEqualTo("{\"maxConcurrentJobsPerOwner\":10}");
        }
        root.fiber.dispose().join();
    }

    @Test
    void schemasteryConfigDefaultsNullToObject() throws Exception {
        // schemastery 形状 = 可调用 schema(Config(raw) 返回带默认值的新对象)
        Path plugin = writePlugin("schemastery-plugin.cjs", """
                module.exports = {
                  name: 'schemastery-plugin',
                  Config(raw) { return { root: 'default-root', ...raw }; },
                  apply(ctx, config) { ctx.emit('applied', JSON.stringify(config)); }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        try (JsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            assertThat(got.get()).isEqualTo("{\"root\":\"default-root\"}");
        }
        root.fiber.dispose().join();
    }

    @Test
    void noConfigPluginPassesConfigAsIs() throws Exception {
        Path plugin = writePlugin("plain-plugin.cjs", """
                module.exports = {
                  name: 'plain-plugin',
                  apply(ctx, config) { ctx.emit('applied', JSON.stringify(config)); }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        try (JsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), Map.of("foo", 1, "bar", "x"));
            // config 原样(无 Config 不做默认化);字段断言不依赖 Map.of 的键序
            JsonNode tree = new ObjectMapper().readTree(got.get());
            assertThat(tree.path("foo").asInt()).isEqualTo(1);
            assertThat(tree.path("bar").asText()).isEqualTo("x");
        }
        root.fiber.dispose().join();
    }

    @Test
    void configSchemaThrowingFallsBackToRawWithoutHanging() throws Exception {
        // schemastery 可调用 Config 抛错 → 回退原 config(记日志),apply 仍跑,不挂加载
        Path plugin = writePlugin("throw-plugin.cjs", """
                module.exports = {
                  name: 'throw-plugin',
                  Config(raw) { throw new Error('boom: schema refused ' + JSON.stringify(raw)); },
                  apply(ctx, config) { ctx.emit('applied', config === null ? 'null' : JSON.stringify(config)); }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        try (JsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), Map.of("foo", 1));
            // Config 抛错 → worker 记日志回退原 config,apply 收到 raw,不挂掉加载
            assertThat(got.get()).isEqualTo("{\"foo\":1}");
        }
        root.fiber.dispose().join();
    }

    @Test
    void jsExpressionInConfigEvaluatedInWorkerWithProcessEnv() throws Exception {
        // env seam 注入 DSH_TEST_HOME + DSH_HOME;config 值带 !!js 标记对象 {$dshJs: expr} →
        // worker 侧求值(scope = process.env + dshHomePath)
        Path plugin = writePlugin("js-plugin.cjs", """
                module.exports = {
                  name: 'js-plugin',
                  apply(ctx, config) { ctx.emit('applied', JSON.stringify(config)); }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        Map<String, String> env = Map.of(
                "DSH_TEST_HOME", tmp.toString(),
                "DSH_HOME", tmp.toString());
        try (JsHost host = new NodeWorkerJsHost(Path.of(""), List.of(), env)) {
            Object config = Map.of(
                    "envRoot", Map.of("$dshJs", "process.env.DSH_TEST_HOME + '/x'"),
                    "homeRoot", Map.of("$dshJs", "dshHomePath('sessions')"));
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), config);
            JsonNode tree = new ObjectMapper().readTree(got.get());
            assertThat(tree.path("envRoot").asText()).isEqualTo(tmp.toString() + "/x");
            assertThat(tree.path("homeRoot").asText()).isEqualTo(tmp.resolve("sessions").toString());
        }
        root.fiber.dispose().join();
    }

    @Test
    void jsExpressionEvalFailureKeepsOriginalAndApplies() throws Exception {
        // M7-5 遗留:config 里 !!js 求值失败(非法表达式)→ 保留表达式原文(不裸传/不抛),
        // apply 仍跑,不挂加载。
        Path plugin = writePlugin("js-broken.cjs", """
                module.exports = {
                  name: 'js-broken',
                  apply(ctx, config) { ctx.emit('applied', JSON.stringify(config)); }
                }
                """);
        Context root = new Context();
        AtomicReference<String> got = capture(root);
        Map<String, String> env = Map.of(
                "DSH_TEST_HOME", tmp.toString(),
                "DSH_HOME", tmp.toString());
        try (JsHost host = new NodeWorkerJsHost(Path.of(""), List.of(), env)) {
            Object config = Map.of("bad", Map.of("$dshJs", "process.env.DSH_TEST_HOME +"));
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), config);
            JsonNode tree = new ObjectMapper().readTree(got.get());
            // 求值失败 → worker 记日志 + 保留表达式原文,apply 收到字符串,不挂加载
            assertThat(tree.path("bad").asText()).isEqualTo("process.env.DSH_TEST_HOME +");
        }
        root.fiber.dispose().join();
    }

    // ---- M7-6 disabled 通道:evalJs(scope = process + dshHomePath,与 config 通道同函数)----

    @Test
    void evalJsScopeMatchesConfigChannel() throws Exception {
        Map<String, String> env = Map.of("DSH_HOME", tmp.toString());
        try (NodeWorkerJsHost host = new NodeWorkerJsHost(Path.of(""), List.of(), env)) {
            // process.platform 求值(disabled 通道的典型表达式:platform 相关)
            assertThat(host.evalJs("process.platform !== 'zzz-never'")).isEqualTo(true);
            assertThat(host.evalJs("process.platform === 'zzz-never'")).isEqualTo(false);
            // dshHomePath scope(与 config 通道 evalJsMarkers 同函数,同求值面)
            assertThat(host.evalJs("dshHomePath('sessions')")).isEqualTo(tmp.resolve("sessions").toString());
        }
    }

    @Test
    void evalJsSyntaxErrorThrows() throws Exception {
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            assertThatThrownBy(() -> host.evalJs("process.platform +"))
                    .isInstanceOf(NodeBridgeError.class);
        }
    }
}
