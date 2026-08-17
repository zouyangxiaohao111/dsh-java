package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.reload.JsPluginReloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4 §2.3:PluginRuntimeResolver 三宿主动态选(design spec §2.3)。
 *
 * <p>覆盖:
 * ① 静态检测——纯 CJS JS → {@link HostKind#GRAAL};{@code .mjs}/{@code type:module} ESM →
 * {@link HostKind#NODE};native({@code .node}/binding.gyp)→ {@link HostKind#NODE};
 * 重 Node 源码(require child_process)→ {@link HostKind#NODE};Java class/源码 →
 * {@link HostKind#JAVA};
 * ② 运行时兜底——静态判定 GRAAL 但 GraalJS 加载缺模块 → dispose → Node worker 重试,
 *   并把缓存提升为 NODE;
 * ③ 缓存/失效——invalidate 后重选;热重载经 resolver 选宿主。
 *
 * <p>NODE 路径需系统 {@code node} 可执行(与 NodeWorkerJsHostTest 同前置)。
 */
class PluginRuntimeResolverTest {

    @TempDir
    Path tmp;

    // ---- 静态检测 ----

    @Test
    void pureCjsPluginDetectsGraalAndRuns() throws Exception {
        Path plugin = write("pure.cjs", """
                module.exports = { name: 'pure-cjs', apply(ctx, config) {
                  ctx.on('go', (msg) => { ctx.emit('done', 'graal:' + msg); });
                } }
                """);
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(plugin)).isEqualTo(HostKind.GRAAL);

        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        try (ResolvedJsPlugin r = resolver.loadJs(plugin)) {
            assertThat(r.kind()).isEqualTo(HostKind.GRAAL);
            assertThat(r.host()).isInstanceOf(GraalJsHost.class);
            assertThat(r.adapter().name()).isEqualTo("pure-cjs");
            root.plugin(r.adapter(), null);
            root.emit("go", "x");
            assertThat(got.get()).isEqualTo("graal:x");
        }
        root.fiber.dispose().join();
    }

    @Test
    void mjsPluginDetectsNodeAndRuns() throws Exception {
        NodeEnv.assumeNode();   // 实际 loadJs → spawn Node worker
        Path plugin = write("esm.mjs", """
                export const name = 'esm-p'
                export function apply(ctx, config) {
                  ctx.on('go', (x) => { ctx.emit('done', 'node:' + x); });
                }
                """);
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(plugin)).isEqualTo(HostKind.NODE);

        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        try (ResolvedJsPlugin r = resolver.loadJs(plugin)) {
            assertThat(r.kind()).isEqualTo(HostKind.NODE);
            assertThat(r.host()).isInstanceOf(NodeWorkerJsHost.class);
            assertThat(r.adapter().name()).isEqualTo("esm-p");
            root.plugin(r.adapter(), null);
            root.emit("go", "y");
            assertThat(got.get()).isEqualTo("node:y");
        }
        root.fiber.dispose().join();
    }

    @Test
    void typeModulePackageDetectsNode() throws Exception {
        Path dir = tmp.resolve("esmpkg");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"esmpkg\",\"type\":\"module\",\"main\":\"index.js\"}");
        Path entry = dir.resolve("index.js");
        Files.writeString(entry, "export const name = 'esmpkg'; export function apply(ctx, c) {}");

        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(dir)).isEqualTo(HostKind.NODE);
        assertThat(resolver.detect(entry)).isEqualTo(HostKind.NODE);
    }

    @Test
    void cjsFileInTypeModuleScopeDetectsGraal() throws Exception {
        // type:module 作用域内一个 CJS 源码文件(require/module.exports):GraalJS 按 CJS 加载可跑
        Path dir = tmp.resolve("misconfig");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{\"type\":\"module\"}");
        Path cjs = dir.resolve("legacy.js");
        Files.writeString(cjs, "module.exports = { apply: (ctx) => {} }");

        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(cjs)).isEqualTo(HostKind.GRAAL);
    }

    @Test
    void dualPackageWithCjsSiblingDetectsGraal() throws Exception {
        // type:module 但有 .cjs 替身(dual package)→ GraalJS 可跑 CJS 侧,不强制 Node
        Path dir = tmp.resolve("dual");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{\"type\":\"module\"}");
        Path esmEntry = dir.resolve("index.js");
        Files.writeString(esmEntry, "export const apply = (ctx) => {}");
        Files.writeString(dir.resolve("index.cjs"), "module.exports = { apply: (ctx) => {} }");

        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(dir)).isEqualTo(HostKind.GRAAL);
        assertThat(resolver.detect(esmEntry)).isEqualTo(HostKind.GRAAL);
    }

    @Test
    void nativeBindingGypDetectsNode() throws Exception {
        Path dir = tmp.resolve("nativ");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("binding.gyp"), "{ 'targets': [] }");
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(dir)).isEqualTo(HostKind.NODE);
    }

    @Test
    void nodeAddonFileDetectsNode() throws Exception {
        Path dir = tmp.resolve("nativ2");
        Files.createDirectories(dir);
        Files.write(dir.resolve("addon.node"), new byte[]{(byte) 0x7f, 'E', 'L', 'F'});
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(dir)).isEqualTo(HostKind.NODE);
    }

    @Test
    void heavyNodeRequireDetectsNode() throws Exception {
        Path plugin = write("heavy.cjs", """
                const cp = require('child_process');
                module.exports = { apply(ctx, config) { cp.exec('echo hi'); } }
                """);
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(plugin)).isEqualTo(HostKind.NODE);
    }

    @Test
    void nodeStreamRequireDetectsNode() throws Exception {
        Path plugin = write("stream.cjs", """
                const s = require('node:stream');
                module.exports = { apply(ctx, config) {} }
                """);
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(plugin)).isEqualTo(HostKind.NODE);
    }

    @Test
    void javaClassDetectsJava() throws Exception {
        Path classFile = tmp.resolve("MyPlugin.class");
        Files.write(classFile, new byte[0]);
        Path javaFile = tmp.resolve("MyPlugin.java");
        Files.writeString(javaFile, "class MyPlugin {}");
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(classFile)).isEqualTo(HostKind.JAVA);
        assertThat(resolver.detect(javaFile)).isEqualTo(HostKind.JAVA);
    }

    /**
     * TLA 模块经 resolver 加载并运行:异步 worker 可 await 动态 import() 的 top-level await
     * (原"TLA 限制"上报路径已解除;验证整体加载链仍工作)。
     */
    @Test
    void loadJsRunsTopLevelAwaitModule() throws Exception {
        NodeEnv.assumeNode();
        Path dir = tmp.resolve("tla");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{\"type\":\"module\"}");
        Files.writeString(dir.resolve("index.js"), """
                export const name = 'tla'
                await Promise.resolve()
                export function apply(ctx, config) {
                  ctx.on('go', () => { ctx.emit('done', 'tla-ok'); });
                }
                """);
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(dir.resolve("index.js"))).isEqualTo(HostKind.NODE);

        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        try (ResolvedJsPlugin r = resolver.loadJs(dir.resolve("index.js"))) {
            assertThat(r.kind()).isEqualTo(HostKind.NODE);
            root.plugin(r.adapter(), null);
            root.emit("go");
            assertThat(got.get()).isEqualTo("tla-ok");
        }
        root.fiber.dispose().join();
    }

    // ---- 运行时兜底 ----

    @Test
    void runtimeFallbackToNodeWhenGraalMissingModule() throws Exception {
        NodeEnv.assumeNode();   // 兜底路径 spawn Node worker
        // 静态检测漏掉(动态拼 specifier),GraalJS 加载缺模块 → dispose → Node worker 重试
        Path plugin = write("fb.cjs", """
                require('child_' + 'process')
                module.exports = { name: 'fb', apply(ctx, config) {
                  ctx.on('go', () => { ctx.emit('done', 'fb-ok'); });
                } }
                """);
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(plugin)).isEqualTo(HostKind.GRAAL);   // 静态判 GRAAL

        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        try (ResolvedJsPlugin r = resolver.loadJs(plugin)) {
            assertThat(r.kind()).isEqualTo(HostKind.NODE);   // 兜底到真 Node
            assertThat(r.host()).isInstanceOf(NodeWorkerJsHost.class);
            assertThat(r.adapter().name()).isEqualTo("fb");
            root.plugin(r.adapter(), null);
            root.emit("go");
            assertThat(got.get()).isEqualTo("fb-ok");
        }
        // 兜底成功后缓存提升为 NODE:后续直接走 Node,不再试 GraalJS
        assertThat(resolver.detect(plugin)).isEqualTo(HostKind.NODE);
        root.fiber.dispose().join();
    }

    @Test
    void loadJsThrowsWhenJavaDetected() throws Exception {
        Path javaFile = tmp.resolve("MyPlugin.java");
        Files.writeString(javaFile, "class MyPlugin {}");
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThatThrownBy(() -> resolver.loadJs(javaFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Java plugins");
    }

    // ---- 缓存 / 集成 ----

    @Test
    void invalidateForcesRedetect() throws Exception {
        Path file = tmp.resolve("p.js");
        Files.writeString(file, "module.exports = (ctx) => {}");
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(file)).isEqualTo(HostKind.GRAAL);

        Files.writeString(file, "export const apply = (ctx) => {}");
        assertThat(resolver.detect(file)).isEqualTo(HostKind.GRAAL);   // 缓存仍是旧判定
        resolver.invalidate(file);
        assertThat(resolver.detect(file)).isEqualTo(HostKind.NODE);    // 重选
    }

    @Test
    void clearResetsAllDecisions() throws Exception {
        Path file = tmp.resolve("p2.js");
        Files.writeString(file, "module.exports = (ctx) => {}");
        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        assertThat(resolver.detect(file)).isEqualTo(HostKind.GRAAL);
        resolver.clear();
        // 清缓存后仍能重新判定(内容未变 → 同结论)
        assertThat(resolver.detect(file)).isEqualTo(HostKind.GRAAL);
    }

    /** JsPluginReloader 经 resolver 选宿主:.mjs(ESM)→ Node worker,热重载仍成立。 */
    @Test
    void jsPluginReloaderRoutesEsmToNode() throws Exception {
        NodeEnv.assumeNode();   // loadJs → spawn Node worker
        Path file = tmp.resolve("p.mjs");
        Files.writeString(file, "export function apply(ctx) { ctx.on('go', () => ctx.emit('done', 'esm-v1')); }");
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        PluginRuntimeResolver resolver = new PluginRuntimeResolver();
        ResolvedJsPlugin r0 = resolver.loadJs(file);
        assertThat(r0.kind()).isEqualTo(HostKind.NODE);
        JsPluginAdapter p1 = r0.adapter();
        root.plugin(p1, null);
        root.emit("go");
        assertThat(got.get()).isEqualTo("esm-v1");

        // 修改源码 → reload:resolver.invalidate + loadJs → 仍 NODE,旧 host 关闭、新 worker 生效
        Files.writeString(file, "export function apply(ctx) { ctx.on('go', () => ctx.emit('done', 'esm-v2')); }");
        JsPluginReloader reloader = new JsPluginReloader(root, resolver);
        JsPluginAdapter p2 = reloader.reload(file, p1, null);
        assertThat(p2.host()).isInstanceOf(NodeWorkerJsHost.class);
        root.emit("go");
        assertThat(got.get()).isEqualTo("esm-v2");

        root.fiber.dispose().join();
        p2.host().close();   // 关闭新 worker(旧 host 已在 reload 中关闭);释放插件目录锁(Windows)
    }

    private Path write(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }
}
