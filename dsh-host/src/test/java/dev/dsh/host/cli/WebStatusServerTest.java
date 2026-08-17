package dev.dsh.host.cli;

import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.loader.LoadedPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-5a web HTTP 可见效果:web profile boot 后启动 JDK HttpServer,GET / 返回状态页。
 *
 * <p>断言的是"web boot 起服务 + GET / 200 且含 harness/插件列表":boot 句柄来自真实
 * {@link ProfileBoot#bootOnce}(web profile,加载 counter[JAVA]),再启动
 * {@link WebStatusServer},用 JDK HttpClient 拉页面断言内容 —— 页面数据来自注册表/
 * 已加载插件/桥基址/启动日志的实时查询,不是写死。
 */
class WebStatusServerTest {

    @TempDir
    Path tmp;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** boot 一个最小 web profile(只含 Java counter 插件 + 空 node_modules 桥基址,不依赖 Node),返回句柄。 */
    private ProfileBoot.Handle bootWebProfile() throws Exception {
        Path root = tmp.resolve("profiles");
        Files.createDirectories(root.resolve("web/node_modules"));
        Files.writeString(root.resolve("web").resolve("cordis.yml"), """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                """);
        return new ProfileBoot(root, tmp)
                .bootOnce("web", new PrintStream(System.out, true, StandardCharsets.UTF_8));
    }

    @Test
    void bootServesStatusPageWithLiveData() throws Exception {
        try (ProfileBoot.Handle handle = bootWebProfile();
             WebStatusServer server = new WebStatusServer("web", handle, 0)) {
            server.start();

            String html = get("/", server.port(), 200);

            // harness 名 + profile 名
            assertThat(html).contains("<h1>dshj</h1>").contains("dsh-java harness");
            assertThat(html).contains("<code>web</code>");
            // 已加载插件列表(名称 + 宿主)来自实时查询
            assertThat(html).contains("loaded plugins").contains("<code>counter</code>")
                    .contains("host: Java");
            // 桥基址:profile node_modules(临时目录,与仓库无关)
            assertThat(html).contains("bridge base").contains("node_modules");
            // 启动日志片段
            assertThat(html).contains("startup log").contains("booted");
        }
    }

    @Test
    void livePluginsReflectsRegistryAndHost() throws Exception {
        try (ProfileBoot.Handle handle = bootWebProfile()) {
            assertThat(handle.loaded()).hasSize(1);
            LoadedPlugin lp = handle.loaded().get(0);
            assertThat(lp.entry().name()).isEqualTo("counter");
            assertThat(lp.kind()).isEqualTo(HostKind.JAVA);

            WebStatusServer server = new WebStatusServer("web", handle, 0);
            assertThat(server.livePlugins())
                    .extracting(WebStatusServer.PluginRow::name)
                    .contains("counter");
            assertThat(server.livePlugins())
                    .extracting(WebStatusServer.PluginRow::host)
                    .contains("Java");
            // 启动日志已捕获(非空),桥基址含 profile 自身 node_modules
            assertThat(handle.startupLog()).isNotEmpty();
            assertThat(handle.moduleBases()).isNotEmpty();
        }
    }

    @Test
    void bindsToRequestedPort() throws Exception {
        int port = freePort();
        try (ProfileBoot.Handle handle = bootWebProfile();
             WebStatusServer server = new WebStatusServer("web", handle, port)) {
            server.start();
            assertThat(server.port()).isEqualTo(port);
            get("/", server.port(), 200);   // 服务可达
        }
    }

    @Test
    void unknownPathReturns404() throws Exception {
        try (ProfileBoot.Handle handle = bootWebProfile();
             WebStatusServer server = new WebStatusServer("web", handle, 0)) {
            server.start();
            get("/nope", server.port(), 404);
        }
    }

    @Test
    void hostLabelMapsAllHosts() {
        assertThat(WebStatusServer.hostLabel(HostKind.JAVA)).isEqualTo("Java");
        assertThat(WebStatusServer.hostLabel(HostKind.NODE)).isEqualTo("Node");
        assertThat(WebStatusServer.hostLabel(HostKind.GRAAL)).isEqualTo("GraalJS");
        assertThat(WebStatusServer.hostLabel(null)).isEqualTo("—");
    }

    @Test
    void escEscapesHtml() {
        assertThat(WebStatusServer.esc("<counter&\"x\">")).isEqualTo("&lt;counter&amp;&quot;x&quot;&gt;");
        assertThat(WebStatusServer.esc(null)).isEmpty();
    }

    private static String get(String path, int port, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return response.body();
    }
}
