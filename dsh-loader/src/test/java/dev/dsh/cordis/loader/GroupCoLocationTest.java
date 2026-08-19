package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.NodeWorkerJsHost;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9-1 进程组:同组 node 插件共享一个 NodeWorkerJsHost(同一 Node 进程)——route handler 与
 * node:http req/res 本地直传,消除跨 worker 路由死锁。不同组 / 无组仍每插件独立 worker(并行)。
 * 组宿主由 loader 统一回收(dispose),不随单个插件 close。
 */
class GroupCoLocationTest {

    @TempDir
    Path tmp;

    private void assumeNode() {
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
        Assumptions.assumeTrue(ok, "node not on PATH");
    }

    private Path writePlugin(String name, String tag) throws Exception {
        Path p = tmp.resolve(name + ".mjs");
        Files.writeString(p, """
                export function apply(ctx) {
                  ctx.on('go', () => ctx.emit('done', '%s'));
                }
                """.formatted(tag));
        return p;
    }

    @Test
    void sameGroupSharesOneNodeHost() throws Exception {
        assumeNode();
        writePlugin("a", "A");
        writePlugin("b", "B");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: a
                    source: node:./a.mjs
                    group: web
                  - name: b
                    source: node:./b.mjs
                    group: web
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(2);
            JsHost hostA = loaded.get(0).host();
            JsHost hostB = loaded.get(1).host();
            assertThat(hostA).isInstanceOf(NodeWorkerJsHost.class);
            // 同组 → 同一个 Node worker(共享宿主),close 不关 host(组回收)
            assertThat(hostB).isSameAs(hostA);
            assertThat(loaded.get(0).sharedHost()).isTrue();
            assertThat(loaded.get(1).sharedHost()).isTrue();
            // 两插件都在同一个 worker 里,事件都能触发
            for (LoadedPlugin lp : loaded) lp.register(root);
            root.emit("go");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void differentGroupsUseIndependentHosts() throws Exception {
        assumeNode();
        writePlugin("a", "A");
        writePlugin("b", "B");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: a
                    source: node:./a.mjs
                    group: g1
                  - name: b
                    source: node:./b.mjs
                    group: g2
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(2);
            assertThat(loaded.get(1).host()).isNotSameAs(loaded.get(0).host());
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    @Test
    void groupMemberChangeReloadsWholeGroupFreshHost() throws Exception {
        assumeNode();
        writePlugin("a", "A");
        writePlugin("b", "B");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: a
                    source: node:./a.mjs
                    group: web
                  - name: b
                    source: node:./b.mjs
                    group: web
                """);
        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> first = loader.load(yml);
            JsHost firstHost = first.get(0).host();
            // 组变更(a 的 config 变)→ 整组重载,全新宿主(非复用旧 worker)
            Files.writeString(yml, """
                    plugins:
                      - name: a
                        source: node:./a.mjs
                        group: web
                        config: { changed: true }
                      - name: b
                        source: node:./b.mjs
                        group: web
                    """);
            List<LoadedPlugin> second = loader.load(yml);
            assertThat(second.get(0).host()).isNotSameAs(firstHost);
            assertThat(second.get(1).host()).isSameAs(second.get(0).host());
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }
}
