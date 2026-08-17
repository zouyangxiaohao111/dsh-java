package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.GraalJsHost;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.JsCtxBridge;
import dev.dsh.cordis.js.NodeWorkerJsHost;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5 §2 PluginLoaderService:读 cordis.yml → 条目树 → 注册进 registry。
 *
 * <p>覆盖:混排 Java + JS、跨语言组合(Java provide → JS inject 调用)、替换/组合 diff、移除、
 * 加载失败全量回滚、重载失败保留旧实现、自动热更新(配置 / 插件源)、dispose 卸载。
 */
class PluginLoaderServiceTest {

    @TempDir
    Path tmp;

    // ---- 混排加载 ----

    @Test
    void loadMixedJavaAndJsRegistersAll() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                  - name: greeter
                    path: ./greeter.js
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(2);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.JAVA);
            assertThat(loaded.get(0).plugin().getClass().getName()).isEqualTo("dev.dsh.demo.CounterPlugin");
            assertThat(loaded.get(1).kind()).isEqualTo(HostKind.GRAAL);
            assertThat(loaded.get(1).host()).isNotNull();
            assertThat((Object) root.get("counter")).isNotNull();
            assertThat(greet(root)).isEqualTo("v1");
            assertThat(loader.entries()).extracting(Entry::name).containsExactly("counter", "greeter");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void javaProvidesJsInjectsAndCalls() throws Exception {
        // JS 插件 inject counter(Java provide)→ 调 c.next() → emit 回 Java(design §4.3 组合)
        Files.writeString(tmp.resolve("consumer.js"), """
                module.exports = { name: 'js-consumer', inject: ['counter'], apply(ctx) {
                  ctx.on('app/ready', () => { const c = ctx.get('counter'); ctx.emit('done', c.next()); });
                } }
                """);
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                  - name: js-consumer
                    path: ./consumer.js
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        AtomicReference<String> done = new AtomicReference<>();
        root.on("done", (c, args) -> { done.set(String.valueOf(args[0])); return null; });
        try {
            loader.load(yml);
            root.emit("app/ready", "started");
            assertThat(done.get()).isEqualTo("1");
            root.emit("app/ready", "again");
            assertThat(done.get()).isEqualTo("2");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void loadIncludesBaseConfig() throws Exception {
        Files.writeString(tmp.resolve("base.yml"), """
                plugins:
                  - name: greeter
                    source: graaljs:./greeter.js
                """);
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                include:
                  - ./base.yml
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat((Object) root.get("counter")).isNotNull();
            assertThat(greet(root)).isEqualTo("v1");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void loadsRealDshEchoPluginViaPath() throws Exception {
        // design §2.1:path = dsh 写法(node_modules/@koishijs/plugin-echo,真实包)
        Path echoDir = Path.of("src/test/resources/echo/node_modules/@koishijs/plugin-echo").toAbsolutePath();
        assertThat(echoDir).isDirectory();
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: echo\n    path: " + echoDir.toString().replace("\\", "/") + "\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.GRAAL);
            assertThat(loaded.get(0).plugin().name()).isEqualTo("echo");
            // Java 触发 echo 命令,捕获回复(design §4.2,与 EchoPluginTest 同 await 语义)
            GraalJsHost host = (GraalJsHost) loaded.get(0).host();
            Object raw = new JsCtxBridge(host, root).dispatchCommand("echo hello world");
            assertThat(awaitJs(raw, host)).contains("hello world");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void loadsClasspathProfile() throws Exception {
        var url = getClass().getClassLoader().getResource("cordis.yml");
        assertThat(url).isNotNull();
        Path yml = Path.of(url.toURI());
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat((Object) root.get("counter")).isNotNull();
            assertThat(loader.entries()).extracting(Entry::name).containsExactly("counter");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- 替换 / 组合(改配置不重启) ----

    @Test
    void reloadSwapsPluginOnSourceChange() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        writeGreeter(tmp, "greeter2.js", "v2", "greeter2");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(greet(root)).isEqualTo("v1");

            // 改配置:同一条目换 source(graaljs:./greeter2.js)→ 旧 fiber dispose、新插件生效
            Files.writeString(yml, "plugins:\n  - name: greeter\n    source: graaljs:./greeter2.js\n");
            loader.reload(yml);
            assertThat(greet(root)).isEqualTo("v2");
            assertThat(loader.loaded()).hasSize(1);
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void reloadUnchangedEntryIsReused() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            LoadedPlugin first = loader.loaded().get(0);
            loader.reload(yml);          // 无变更 → 复用同一句柄
            assertThat(loader.loaded().get(0)).isSameAs(first);
            assertThat(greet(root)).isEqualTo("v1");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void reloadRemovesDroppedEntry() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                  - name: greeter
                    path: ./greeter.js
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat((Object) root.get("counter")).isNotNull();

            // 移除 counter 条目 → dispose;greeter 保留
            Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
            loader.reload(yml);
            assertThat((Object) root.get("counter")).isNull();
            assertThat(greet(root)).isEqualTo("v1");
            assertThat(loader.entries()).extracting(Entry::name).containsExactly("greeter");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- 失败回滚 ----

    @Test
    void loadWithBrokenEntryRegistersNothing() throws Exception {
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                  - name: missing
                    path: ./nope.js
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            assertThatThrownBy(() -> loader.load(yml)).isInstanceOf(Exception.class)
                    .hasMessageContaining("nope.js");
            assertThat((Object) root.get("counter")).isNull();       // 全量回滚:一条失败 → 一条未注册
            assertThat(loader.loaded()).isEmpty();
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void loadWithBadJavaClassNameFails() throws Exception {
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: bad\n    source: java:dev.dsh.demo.NoSuchPlugin\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            assertThatThrownBy(() -> loader.load(yml)).isInstanceOf(Exception.class)
                    .hasMessageContaining("NoSuchPlugin");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void reloadWithBrokenEntryKeepsOld() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(greet(root)).isEqualTo("v1");

            // 新配置指向缺失文件 → 加载失败 → 旧实现保留
            Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./nope.js\n");
            assertThatThrownBy(() -> loader.reload(yml)).isInstanceOf(Exception.class)
                    .hasMessageContaining("nope.js");
            assertThat(greet(root)).isEqualTo("v1");
            assertThat(loader.loaded()).hasSize(1);
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- dispose ----

    @Test
    void disposeUnloadsAllPlugins() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        loader.load(yml);
        assertThat(greet(root)).isEqualTo("v1");
        loader.dispose();
        assertThat(greet(root)).isNull();
        assertThat(loader.loaded()).isEmpty();
        root.fiber.dispose().join();
    }

    // ---- 自动热更新(poll) ----

    @Test
    void updateIfChangedReloadsOnConfigChange() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        writeGreeter(tmp, "greeter2.js", "v2", "greeter2");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(greet(root)).isEqualTo("v1");
            sleepMtime();

            Files.writeString(yml, "plugins:\n  - name: greeter\n    source: graaljs:./greeter2.js\n");
            sleepMtime();
            assertThat(loader.updateIfChanged()).isTrue();
            assertThat(greet(root)).isEqualTo("v2");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void updateIfChangedReloadsChangedPluginSource() throws Exception {
        Path js = writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(greet(root)).isEqualTo("v1");
            sleepMtime();

            Files.writeString(js, greeterSource("v2", "greeter"));
            sleepMtime();
            assertThat(loader.updateIfChanged()).isTrue();   // 插件源变更 → 仅重载该插件
            assertThat(greet(root)).isEqualTo("v2");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void updateIfChangedIsNoopWhenNothingChanged() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(loader.updateIfChanged()).isFalse();
            assertThat(greet(root)).isEqualTo("v1");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void backgroundWatchReloadsOnConfigChange() throws Exception {
        writeGreeter(tmp, "greeter.js", "v1", "greeter");
        writeGreeter(tmp, "greeter2.js", "v2", "greeter2");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    path: ./greeter.js\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(greet(root)).isEqualTo("v1");
            loader.startWatch(50);
            Files.writeString(yml, "plugins:\n  - name: greeter\n    source: graaljs:./greeter2.js\n");
            Thread.sleep(500);
            assertThat(greet(root)).isEqualTo("v2");
        } finally {
            loader.dispose();        // stopWatch + 卸载
            root.fiber.dispose().join();
        }
    }

    // ---- Node worker 宿主 ----

    @Test
    void explicitNodePrefixLoadsViaNodeWorker() throws Exception {
        assumeNode();
        Files.writeString(tmp.resolve("esm.mjs"), """
                export function apply(ctx) {
                  ctx.on('go', () => { ctx.emit('done', 'node-ok'); });
                }
                """);
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: esm\n    source: node:./esm.mjs\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.NODE);
            assertThat(loaded.get(0).host()).isInstanceOf(NodeWorkerJsHost.class);
            root.emit("go");
            assertThat(got.get()).isEqualTo("node-ok");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void autoDetectRoutesEsmToNode() throws Exception {
        assumeNode();
        Files.writeString(tmp.resolve("esm.mjs"), """
                export function apply(ctx) {
                  ctx.on('go', () => { ctx.emit('done', 'auto-node'); });
                }
                """);
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: esm\n    path: ./esm.mjs\n");
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.NODE);
            root.emit("go");
            assertThat(got.get()).isEqualTo("auto-node");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- 小工具 ----

    private Path writeGreeter(Path dir, String fileName, String value, String name) throws Exception {
        Path js = dir.resolve(fileName);
        Files.writeString(js, greeterSource(value, name));
        return js;
    }

    private static String greeterSource(String value, String name) {
        return "module.exports = { name: '" + name + "', provide: ['greet'], apply(ctx) {"
                + " ctx.provide('greet', '" + value + "'); } }";
    }

    private static void sleepMtime() throws InterruptedException {
        Thread.sleep(30);   // 保证 mtime 变化被 FileWatcher 捕获(Windows 粒度)
    }

    /** JS provide 的 'greet' 是 polyglot Value,经 toString 取文本;未提供时返回 null。 */
    private static String greet(Context root) {
        Object v = root.get("greet");
        return v == null ? null : String.valueOf(v);
    }

    /** await 一个 JS Promise(echo action 是 async)。非 Promise 直接取最终值(与 EchoPluginTest 同)。 */
    private static String awaitJs(Object raw, GraalJsHost host) throws Exception {
        if (!(raw instanceof Value v)) return String.valueOf(raw);
        if (!v.hasMember("then")) return v.asString();
        CompletableFuture<Value> settled = new CompletableFuture<>();
        ProxyExecutable onResolve = args -> {
            settled.complete(args[0]);
            return null;
        };
        v.invokeMember("then", onResolve);
        host.evalValue("0");
        Value result = settled.get(5, TimeUnit.SECONDS);
        return result.isString() ? result.asString() : String.valueOf(result);
    }

    /** 无 node 时 skip(与 NodeWorkerJsHost 同探测;NodeEnv 是 js 包包私有,这里内联)。 */
    private static void assumeNode() {
        String cmd = System.getenv("NODE");
        if (cmd == null || cmd.isBlank()) cmd = "node";
        boolean ok = false;
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            ok = p.waitFor() == 0;
        } catch (Exception ignored) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "skipped: no node executable on PATH (set NODE to override)");
    }
}
