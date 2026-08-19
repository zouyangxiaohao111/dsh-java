package dev.dsh.host.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M10-1 DevPipeline:web dev watcher 子进程管线的可测试面 —— 前置校验(prereqs)与
 * close 幂等。真正的 spawn + 热换链由集成证据覆盖(docs/m10)。
 */
class DevPipelineTest {

    @TempDir
    Path tmp;

    @Test
    void prereqsFalseWhenVendorMissing() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        // 空仓库根:三个前置全缺 → false + 每缺一条提示
        assertThat(DevPipeline.prereqs(tmp, out)).isFalse();
        String text = buf.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("vendor/dsh/node_modules missing");
        assertThat(text).contains("apps/web dist missing");
        assertThat(text).contains("web profile node_modules junctions missing");
    }

    @Test
    void prereqsTrueWhenAllPresent() throws Exception {
        Files.createDirectories(tmp.resolve("vendor/dsh/node_modules"));
        Files.createDirectories(tmp.resolve("vendor/dsh/apps/web/dist"));
        Files.createDirectories(tmp.resolve("profiles/web/node_modules/@deepseek-ai"));
        Files.writeString(tmp.resolve("vendor/dsh/apps/web/dist/index.html"), "<html></html>");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assertThat(DevPipeline.prereqs(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8))).isTrue();
        assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("missing");
    }

    @Test
    void closeIsSafeOnEmptyPipeline() {
        // 无子进程的 DevPipeline close 必须是无害的(永不 spawn 的实例 + 失败降级路径)
        DevPipeline pipeline = new DevPipeline(tmp);
        pipeline.close();
        pipeline.close();   // 幂等
    }

    @Test
    void spawnSpecsMatchDshDevPipeline() {
        // 两个 watcher 的命令/cwd 与真实 dsh dev 管线一致(tsdown watch + vite build --watch)。
        DevPipeline pipeline = new DevPipeline(tmp);
        var specs = pipeline.spawnSpecs();
        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).command()).containsExactly(
                "node", "--import", "tsx/esm", "scripts/dev-web.ts", "--poll");
        assertThat(specs.get(0).cwd()).isEqualTo(tmp.resolve("vendor/dsh"));
        assertThat(specs.get(1).command()).containsExactly(
                "node", "node_modules/vite/bin/vite.js", "build", "--watch");
        assertThat(specs.get(1).cwd()).isEqualTo(tmp.resolve("vendor/dsh/apps/web"));
    }

    @Test
    void closeDestroysSpawnedProcess() throws Exception {
        // 真实 spawn 一个长驻 node 进程 → close() 必须回收(否则测试会泄漏进程)。
        DevPipeline pipeline = new DevPipeline(tmp);
        pipeline.spawn("test-watcher", List.of("node", "-e", "setInterval(()=>{},1000)"), tmp);
        assertThat(pipeline.children()).hasSize(1);
        Process p = pipeline.children().get(0);
        assertThat(p.isAlive()).isTrue();
        pipeline.close();
        assertThat(p.isAlive()).isFalse();   // 已 destroy/force-kill 并回收
    }
}
