package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-4 裸模块解析基址 seam(bareModuleBaseUrl 等价物):NodeWorkerJsHost 的
 * {@code moduleBases} 让插件在其目录外也能 {@code require('裸包')}。基址是
 * <b>node_modules 目录本身</b>(模块根,如 vendor/dsh/node_modules、profile/node_modules);
 * Node 经 {@code NODE_PATH} 对每条基址做 {@code <base>/<specifier>} 拼接。CJS require 生效。
 */
class ModuleBasesTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    /** 在一个"模块根"基址(base 目录即 node_modules)下放一个包。 */
    private void writeModule(String name, String main) throws Exception {
        Path pkg = tmp.resolve("base").resolve(name);
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("package.json"),
                "{\"name\":\"" + name + "\",\"main\":\"index.js\"}");
        Files.writeString(pkg.resolve("index.js"), main);
    }

    @Test
    void bareModuleResolvesViaModuleBases() throws Exception {
        writeModule("hello-lib", "module.exports = { greet: () => 'hello-from-base' }");
        Path plugin = tmp.resolve("bases-plugin.cjs");
        Files.writeString(plugin, """
                const lib = require('hello-lib')
                module.exports = { name: 'bases-plugin', apply(ctx) {
                  ctx.emit('greet-done', lib.greet())
                } }
                """);
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("greet-done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        // requireCwd=tmp(无 node_modules),moduleBases=[tmp/base](模块根)——
        // 插件目录外也能 require hello-lib
        try (JsHost host = new NodeWorkerJsHost(tmp, List.of(tmp.resolve("base")))) {
            JsPluginAdapter adapter = new JsPluginAdapter(host, host.loadModule(plugin));
            assertThat(adapter.name()).isEqualTo("bases-plugin");
            root.plugin(adapter, null);   // apply 立即跑,emit greet-done
            assertThat(got.get()).isEqualTo("hello-from-base");
        }
        root.fiber.dispose().join();
    }

    @Test
    void withoutModuleBasesBareRequireFails() throws Exception {
        writeModule("hello-lib", "module.exports = { greet: () => 'hello' }");
        Path plugin = tmp.resolve("no-bases-plugin.cjs");
        Files.writeString(plugin, """
                const lib = require('hello-lib')
                module.exports = { name: 'no-bases', apply(ctx) {} }
                """);
        // 无 moduleBases:require('hello-lib') 找不到 → 加载即失败
        try (JsHost host = new NodeWorkerJsHost(tmp)) {
            assertThatThrownBy(() -> host.loadModule(plugin))
                    .isInstanceOf(NodeBridgeError.class)
                    .hasMessageContaining("Cannot find module");
        }
    }

    @Test
    void multipleBasesAreSearchedInOrder() throws Exception {
        writeModule("pick-me", "module.exports = { pick: () => 'first-base' }");
        // 第二个模块根也有 pick-me,但第一个(moduleBases 顺序在前)先命中
        Path second = tmp.resolve("second");
        Files.createDirectories(second.resolve("pick-me"));
        Files.writeString(second.resolve("pick-me/package.json"),
                "{\"name\":\"pick-me\",\"main\":\"index.js\"}");
        Files.writeString(second.resolve("pick-me/index.js"),
                "module.exports = { pick: () => 'second-base' }");

        Path plugin = tmp.resolve("order-plugin.cjs");
        Files.writeString(plugin, """
                const lib = require('pick-me')
                module.exports = { name: 'order-plugin', apply(ctx) {
                  ctx.emit('pick-done', lib.pick())
                } }
                """);
        Context root = new Context();
        AtomicReference<String> got = new AtomicReference<>();
        root.on("pick-done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });

        try (JsHost host = new NodeWorkerJsHost(tmp, List.of(tmp.resolve("base"), second))) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            assertThat(got.get()).isEqualTo("first-base");
        }
        root.fiber.dispose().join();
    }
}
