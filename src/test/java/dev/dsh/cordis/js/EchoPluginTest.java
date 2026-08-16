package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计 §4.2:加载真实 {@code @koishijs/plugin-echo@2.2.5},Java 模拟消息触发命令,捕获回复。
 *
 * <p>echo 的 action 是 {@code async ({ options, session }, message) => [...]}:主路径
 * {@code echo hello world} 返回数组 {@code ["hello world"]}(包装在 Promise 中)。
 * {@code dispatchCommand} 返回该 Promise,测试经 {@link #awaitJs(Object, JsHost)}
 * 挂 {@code then} 等其 settle,再提取回复文本。
 */
class EchoPluginTest {
    @Test
    void loadRealKoishiPluginAndTrigger() throws Exception {
        Context root = new Context();
        try (GraalJsHost host = new GraalJsHost(echoNodeModules())) {
            // 加载 echo(对象-with-apply,经 require 解析到真实 lib/index.js)
            PluginModule echo = host.loadModule(echoLib());
            root.plugin(new JsPluginAdapter(host, echo), null);

            // Java 模拟 session 消息触发命令;echo action 为 async,返回 Promise,需 await
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            Object raw = bridge.dispatchCommand("echo hello world");
            String reply = awaitJs(raw, host);
            assertThat(reply).contains("hello world");
        }
        root.fiber.dispose().join();
    }

    /**
     * await 一个 JS Promise(echo action 是 async)。raw 若为 Promise Value,
     * 挂 {@code then}(经 ProxyExecutable 可靠转成 JS 回调),等 settle 后提取文本。
     * 若 raw 不是 Promise(已是最终值),直接转换。
     */
    private String awaitJs(Object raw, GraalJsHost host) throws Exception {
        if (!(raw instanceof Value v)) return String.valueOf(raw);
        if (!v.hasMember("then")) return valueText(v);   // 非 Promise:直接取最终值

        CompletableFuture<Value> settled = new CompletableFuture<>();
        ProxyExecutable onResolve = args -> {
            settled.complete(args[0]);
            return null;
        };
        v.invokeMember("then", onResolve);
        // 实验确认:GraalJS 在 invokeMember 返回宿主时已冲刷 microtask,此 eval 仅作防御。
        host.evalValue("0");
        Value result = settled.get(5, TimeUnit.SECONDS);
        return valueText(result);
    }

    /** 把 JS 值转成回复文本:字符串直取;数组拼接元素;{text} 取 text;host 对象 toString。 */
    private static String valueText(Value sv) {
        if (sv.isString()) return sv.asString();
        if (sv.isHostObject()) return String.valueOf(sv.asHostObject());
        if (sv.hasArrayElements()) {
            // echo 主路径返回 [message]
            StringBuilder sb = new StringBuilder();
            for (long i = 0; i < sv.getArraySize(); i++) {
                if (i > 0) sb.append(' ');
                Value e = sv.getArrayElement(i);
                sb.append(e.isString() ? e.asString() : String.valueOf(e));
            }
            return sb.toString();
        }
        if (sv.hasMembers()) {
            if (sv.hasMember("text")) {
                Value t = sv.getMember("text");
                return t.isString() ? t.asString() : String.valueOf(t);
            }
            return String.valueOf(sv);
        }
        return String.valueOf(sv);
    }

    private java.nio.file.Path echoLib() {
        return java.nio.file.Path.of("src/test/resources/echo/node_modules/@koishijs/plugin-echo/lib/index.js").toAbsolutePath();
    }

    private java.nio.file.Path echoNodeModules() {
        return java.nio.file.Path.of("src/test/resources/echo/node_modules").toAbsolutePath();
    }
}
