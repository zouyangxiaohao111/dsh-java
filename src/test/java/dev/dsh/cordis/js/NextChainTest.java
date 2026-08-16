package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JS 监听器 next 链:当监听器声明的形参数比 dispatch 实参多 1(如 (msg, next)),
 * bridge 追加 next 续延;调 next(newArgs) 委派到同一事件后续注册的监听器
 * (含 Java 监听器),且收到的是 next 传入的新参数,而非原始 dispatch 参数。
 */
class NextChainTest {
    /** next 委派到后续 JS 监听器:js1 调 next('from-next') → js2 收到 'from-next'。 */
    @Test
    void listenerNextDelegatesToFollowing() throws Exception {
        Context root = new Context();
        try (JsHost host = new GraalJsHost()) {
            AtomicReference<String> javaGot = new AtomicReference<>();
            // Java 监听器最先注册:主 dispatch 最先收到原始参数
            root.on("chain", (c, args) -> { javaGot.set("java-first:" + args[0]); return null; });

            org.graalvm.polyglot.Value fn = host.eval(
                    "(ctx) => { globalThis.trace = []; " +
                    "ctx.on('chain', (msg, next) => { trace.push('js1:' + msg); next('from-next'); }); " +
                    "ctx.on('chain', (msg) => { trace.push('js2:' + msg); }); }");
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            fn.execute(bridge.ctxShim());

            root.emit("chain", "x");
            String trace = host.eval("globalThis.trace.join(',')").asString();

            // 主 dispatch 顺序:java-first, js1, js2;js1 的 next 使 js2 额外收到 'from-next'
            assertThat(javaGot.get()).isEqualTo("java-first:x");
            assertThat(trace).contains("js2:from-next");
        }
        root.fiber.dispose().join();
    }

    /** next 委派到后续 Java 监听器:js1 调 next('from-next') → 之后注册的 Java 监听器收到新参数。 */
    @Test
    void nextDelegatesToJavaListenerRegisteredAfter() throws Exception {
        Context root = new Context();
        try (JsHost host = new GraalJsHost()) {
            AtomicReference<String> javaGot = new AtomicReference<>();

            org.graalvm.polyglot.Value fn = host.eval(
                    "(ctx) => { globalThis.trace = []; " +
                    "ctx.on('chain', (msg, next) => { trace.push('js1:' + msg); next('from-next'); }); }");
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            fn.execute(bridge.ctxShim());

            // Java 监听器在 JS 之后注册:next 应委派到它,并携带 next 修改后的参数
            root.on("chain", (c, args) -> {
                String v = String.valueOf(args[0]);
                if ("from-next".equals(v)) javaGot.set("java-last:" + v);
                return null;
            });

            root.emit("chain", "x");
            assertThat(javaGot.get()).isEqualTo("java-last:from-next");
        }
        root.fiber.dispose().join();
    }

    /** 不声明 next 的 JS 监听器照常收原始 dispatch 参数(regression 保护)。 */
    @Test
    void listenerWithoutNextReceivesPlainArgs() throws Exception {
        Context root = new Context();
        try (JsHost host = new GraalJsHost()) {
            AtomicReference<String> got = new AtomicReference<>();

            org.graalvm.polyglot.Value fn = host.eval(
                    "(ctx) => { ctx.on('plain', (msg) => { ctx.emit('plain2', msg); }); }");
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            fn.execute(bridge.ctxShim());

            root.on("plain2", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
            root.emit("plain", "hi");
            assertThat(got.get()).isEqualTo("hi");
        }
        root.fiber.dispose().join();
    }

    /** prepend 选项:JS 监听器即使注册在后,也排到同一事件监听器最前。 */
    @Test
    void prependOptionPutsJsListenerFirst() throws Exception {
        Context root = new Context();
        try (JsHost host = new GraalJsHost()) {
            Recorder rec = new Recorder();
            root.provide("rec", rec);
            root.on("p", (c, args) -> { rec.add("java"); return null; });

            org.graalvm.polyglot.Value fn = host.eval(
                    "(ctx) => { ctx.on('p', () => { ctx.get('rec').add('js'); }, { prepend: true }); }");
            JsCtxBridge bridge = new JsCtxBridge(host, root);
            fn.execute(bridge.ctxShim());

            root.emit("p", "x");
            assertThat(rec.seq).containsExactly("js", "java");
        }
        root.fiber.dispose().join();
    }

    /** 供 JS 经 {@code ctx.get('rec')} 调用,记录监听器执行顺序。 */
    public static final class Recorder {
        public final java.util.List<String> seq = new java.util.ArrayList<>();
        public void add(String s) { seq.add(s); }
    }
}
