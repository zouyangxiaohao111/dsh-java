package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8:ctx.plugin(shim 挂 JS 子插件)+ 跨 worker 服务读(web-runtime 挂 frontend-static
 * 同形:worker A 提供 webServer,worker B apply 读 ctx.webServer.host 再经 ctx.plugin
 * 挂的 { apply } 子插件读 ctx.webServer.registerFallback)。
 */
class M8CtxPluginTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void assumeNode() {
        NodeEnv.assumeNode();
    }

    private Path writePlugin(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @Test
    void ctxPluginMountsSubPluginAndCrossWorkerServiceReadsWork() throws Exception {
        Path provider = writePlugin("webServer-provider.cjs", """
                const { Service } = require('@deepseek-ai/cordis')
                module.exports = class WebServer extends Service {
                  static inject = []
                  constructor(ctx, config) {
                    super(ctx, 'webServer')
                    this._port = (config && config.port) || 3080
                  }
                  get port() { return this._port }
                  get host() { return '127.0.0.1' }
                  registerFallback() { return () => {} }
                }
                """);
        Path consumer = writePlugin("webRuntime-consumer.cjs", """
                module.exports = {
                  name: 'web-runtime',
                  apply(ctx) {
                    const host = ctx.webServer.host
                    const sub = {
                      name: 'frontend-static',
                      apply(ctx2) {
                        const hasFallback = typeof ctx2.webServer.registerFallback === 'function'
                        ctx2.provide('frontendStaticProbe', { hasFallback })
                      },
                    }
                    ctx.plugin(sub, {})
                    ctx.provide('webRuntimeProbe', { host, hasPlugin: typeof ctx.plugin === 'function' })
                  },
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost providerHost = new NodeWorkerJsHost();
             NodeWorkerJsHost consumerHost = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(providerHost, providerHost.loadModule(provider)), null);
            root.plugin(new JsPluginAdapter(consumerHost, consumerHost.loadModule(consumer)), null);

            Map<String, Object> probe = map(root.get("webRuntimeProbe"));
            assertThat(probe.get("host")).isEqualTo("127.0.0.1");
            assertThat(probe.get("hasPlugin")).isEqualTo(true);

            Map<String, Object> sub = map(root.get("frontendStaticProbe"));
            assertThat(sub.get("hasFallback")).isEqualTo(true);
        } finally {
            root.fiber.dispose().join();
        }
    }
}
