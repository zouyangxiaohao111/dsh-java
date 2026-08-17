package dev.dsh.cordis.js;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;

/**
 * Node 相关测试的可移植性支持:系统无 {@code node} 可执行时,用 JUnit Assumptions
 * skip 依赖真 Node 的测试(与 {@link NodeWorkerJsHost#nodeExecutable()} 同探测逻辑),
 * 使构建在无 Node 环境下也能保持全绿(测试用,构建不依赖)。
 */
final class NodeEnv {
    private NodeEnv() {
    }

    /** 探测可执行的 node:优先 {@code NODE} 环境变量(与 NodeWorkerJsHost 同解析),否则 PATH。 */
    static boolean available() {
        String cmd = System.getenv("NODE");
        if (cmd == null || cmd.isBlank()) cmd = "node";
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 无 node 时抛 {@link org.opentest4j.TestAbortedException}(JUnit 计为 skipped)。 */
    static void assumeNode() {
        Assumptions.assumeTrue(available(), "skipped: no node executable on PATH (set NODE to override)");
    }
}
