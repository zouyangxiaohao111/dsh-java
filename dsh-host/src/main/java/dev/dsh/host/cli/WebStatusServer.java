package dev.dsh.host.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.cordis.loader.LoadedPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M6-5a web HTTP 可见效果:profile boot 后启动一个 JDK {@link HttpServer},{@code GET /}
 * 返回一个 HTML 状态页 —— harness 名(dshj)+ profile 名 + 已加载插件列表(名称+宿主
 * Java/Node/GraalJS)+ 桥基址(裸模块解析基址)+ 启动日志片段。
 *
 * <p>页面数据不写死:每次请求都从 boot 句柄实时查询 —— 已加载插件列表与注册表
 * ({@link ProfileBoot.Handle#loaded()} + {@code ctx.registry})、桥基址
 * ({@link ProfileBoot.Handle#moduleBases()})、启动日志({@link ProfileBoot.Handle#startupLog()})。
 *
 * <p>默认监听 {@code 127.0.0.1:8080}({@code --port} 覆盖由 {@link DshCli#parsePort} 解析);
 * 测试可指定端口 0 取随机空闲端口。
 */
public final class WebStatusServer implements AutoCloseable {

    private final String profile;
    private final ProfileBoot.Handle handle;
    private final HttpServer server;

    public WebStatusServer(String profile, ProfileBoot.Handle handle, int port) throws IOException {
        this(profile, handle, "127.0.0.1", port);
    }

    public WebStatusServer(String profile, ProfileBoot.Handle handle, String host, int port) throws IOException {
        this.profile = profile;
        this.handle = handle;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(null);   // 默认 executor:每连接一个处理线程
    }

    /** 启动监听(不阻塞;服务线程在后台运行)。 */
    public void start() {
        server.start();
    }

    /** 实际监听端口(传入 0 时为系统分配的随机端口)。 */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            respond(exchange, 404, "text/plain; charset=utf-8", "404 not found\n");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", renderHtml());
    }

    private static void respond(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---- 页面渲染:每次请求实时查询,不缓存 ----

    /** 一行插件展示数据(名称 + 宿主标签)。 */
    record PluginRow(String name, String host) {
    }

    /**
     * 实时查询注册表与已加载插件列表:先按注册表里实际注册的插件列(覆盖热加载/动态注册),
     * 再补上已加载但尚未出现在注册表的插件。宿主标签取 {@code LoadedPlugin} 的最终宿主。
     *
     * <p>注册表里 {@link dev.dsh.cordis.js.JsPluginAdapter} 等无 {@code name} 的运行条目被过滤
     * (跳过 → 稳定显示名来自 {@code handle.loaded()} 的 entry id),不再出现
     * {@code dev.dsh.cordis.js.JsPluginAdapter@...} 之类的 toString 噪音行。
     */
    List<PluginRow> livePlugins() {
        Map<String, HostKind> kinds = new LinkedHashMap<>();
        for (LoadedPlugin lp : handle.loaded()) {
            kinds.putIfAbsent(lp.entry().name(), lp.kind());
        }
        List<PluginRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        handle.ctx().registry.forEach((runtime, plugin) -> {
            String name = runtime.name();
            if (name == null) return;   // JsPluginAdapter 等无 name 运行条目 → 噪音,过滤
            seen.add(name);
            rows.add(new PluginRow(name, hostLabel(kinds.get(name))));
        });
        for (LoadedPlugin lp : handle.loaded()) {
            String name = lp.entry().name();
            if (!seen.contains(name)) {
                rows.add(new PluginRow(name, hostLabel(lp.kind())));
            }
        }
        return rows;
    }

    static String hostLabel(HostKind kind) {
        if (kind == null) return "—";   // switch 选择子为 null 会 NPE,先判空
        return switch (kind) {
            case JAVA -> "Java";
            case NODE -> "Node";
            case GRAAL -> "GraalJS";
        };
    }

    String renderHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<title>dshj · ").append(esc(profile)).append("</title>\n")
                .append("</head>\n<body>\n");
        sb.append("<h1>dshj</h1>\n")
                .append("<p class=\"muted\">dsh-java harness · status page (M6-5a)</p>\n")
                .append("<h2>profile</h2>\n")
                .append("<p><code>").append(esc(profile)).append("</code></p>\n");

        sb.append("<h2>loaded plugins (").append(livePlugins().size()).append(")</h2>\n")
                .append("<ul>\n");
        for (PluginRow row : livePlugins()) {
            sb.append("  <li><code>").append(esc(row.name())).append("</code> · host: ")
                    .append(esc(row.host())).append("</li>\n");
        }
        sb.append("</ul>\n");

        sb.append("<h2>bridge base (node module bases)</h2>\n")
                .append("<ul>\n");
        for (Path base : handle.moduleBases()) {
            sb.append("  <li><code>").append(esc(base.toString())).append("</code></li>\n");
        }
        sb.append("</ul>\n");

        sb.append("<h2>startup log</h2>\n").append("<pre>\n");
        for (String line : handle.startupLog()) {
            sb.append(esc(line)).append('\n');
        }
        sb.append("</pre>\n");

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /** HTML 转义(用户可见文本来自 profile/插件名/路径,统一转义防注入)。 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
