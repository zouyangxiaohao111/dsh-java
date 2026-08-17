package dev.dsh.cordis.loader;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Fiber;
import dev.dsh.cordis.FiberState;
import dev.dsh.cordis.Plugin;
import dev.dsh.cordis.js.HostKind;
import dev.dsh.demo.CounterPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5 验收 §4.2-4.4 验证:随时替换 / 组合 / 自动热更新(含失败回滚)。
 *
 * <p>与 {@link PluginLoaderServiceTest} 的覆盖互补 —— 这里专门钉死三条语义,它们不在既有
 * 测试的显式断言面上:
 * <ol>
 *   <li><b>随时替换不重启</b>:改配置把一条目换 source → 旧 fiber 状态 DISPOSED、registry
 *       除名、新实现生效;同 root Context 与稳定 Java 插件服务<b>实例不变</b>(整机未重启);
 *       未变更条目句柄复用。</li>
 *   <li><b>组合(Java 源码插件 → dsh JS 插件)</b>:Java 插件经 {@code java:./X.java}
 *       <b>源码编译</b>加载(与既有类名型组合互补),provide 服务;dsh JS 插件 inject 并经
 *       ctx 调用,结果回传 Java。</li>
 *   <li><b>自动热更新</b>:{@code updateIfChanged()} 对 <b>Java {@code .java} 源</b>文件变更
 *       重载(复用 M3 ClassLoader);改坏 JS 源码 → 重载失败 → 旧实现保留(回滚)。</li>
 * </ol>
 */
class M5SwapCombineHotUpdateTest {

    @TempDir
    Path tmp;

    // ---- ① 随时替换:改配置换插件 → 旧 fiber dispose、新生效、断言不重启 ----

    @Test
    void swapSourceDisposesOldFiberAndKeepsRestAlive() throws Exception {
        // 稳定 Java 插件(counter,类名)+ 可替换 JS 插件(swapped:提供 swappable)
        writeJs("swapped.js", swappedSource("v1"));
        writeJs("swapped2.js", swappedSource("v2"));
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: counter
                    source: java:dev.dsh.demo.CounterPlugin
                  - name: swapped
                    path: ./swapped.js
                """);

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(svc(root, "swappable")).isEqualTo("v1");

            // 稳定侧句柄与实例(不重启的判据)
            LoadedPlugin counterLp = loaded.get(0);
            CounterPlugin.Counter counterBefore = root.get("counter");
            assertThat(counterBefore).isNotNull();

            // 可替换侧:捕获旧 LoadedPlugin / 旧 registry runtime / 旧 fiber(断言 dispose)
            LoadedPlugin swappedLp = loaded.get(1);
            Plugin.Runtime swappedRuntime = root.registry.get(swappedLp.plugin());
            assertThat(swappedRuntime).isNotNull();
            Fiber swappedFiber = swappedRuntime.fibers.iterator().next();

            // 改配置:同条目换 source → 旧 fiber dispose、新插件生效
            Files.writeString(yml, """
                    plugins:
                      - name: counter
                        source: java:dev.dsh.demo.CounterPlugin
                      - name: swapped
                        source: graaljs:./swapped2.js
                    """);
            loader.reload(yml);

            // 新实现生效
            assertThat(svc(root, "swappable")).isEqualTo("v2");

            // 旧 fiber 已 dispose + 从 registry 除名
            assertThat(swappedFiber.state).isEqualTo(FiberState.DISPOSED);
            assertThat(root.registry.has(swappedLp.plugin())).isFalse();

            // 不重启:同一 root Context / 同一 counter 服务实例 / 未变更条目句柄复用
            CounterPlugin.Counter counterAfter = root.get("counter");
            assertThat(counterAfter).isSameAs(counterBefore);
            assertThat(counterAfter.next()).isEqualTo(1);               // 仍是那台计数器
            assertThat(loader.loaded().get(0)).isSameAs(counterLp);
            assertThat(loader.loaded().get(1)).isNotSameAs(swappedLp);  // 仅替换条目换了句柄
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- ② 组合:Java 源码插件 provide → dsh JS 插件 inject 并调用(经 ctx) ----

    @Test
    void javaSourcePluginProvidesAndJsInjectsViaCtx() throws Exception {
        Files.writeString(tmp.resolve("Greeter.java"), """
                import dev.dsh.cordis.*;
                public class Greeter implements Plugin<Void> {
                  public Object apply(Context ctx, Void cfg) {
                    ctx.provide("greet", new Greet("hello"));
                    return null;
                  }
                  public static class Greet {
                    public final String prefix;
                    public Greet(String prefix) { this.prefix = prefix; }
                    public String say(String name) { return prefix + ", " + name + "!"; }
                  }
                }
                """);
        Files.writeString(tmp.resolve("consumer.js"), """
                module.exports = { name: 'js-consumer', inject: ['greet'], apply(ctx) {
                  ctx.on('app/ready', () => {
                    const g = ctx.get('greet');
                    ctx.emit('done', g.say('world'));
                  });
                } }
                """);
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, """
                plugins:
                  - name: greeter
                    source: java:./Greeter.java
                  - name: js-consumer
                    path: ./consumer.js
                """);

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        AtomicReference<String> done = new AtomicReference<>();
        root.on("done", (c, args) -> { done.set(String.valueOf(args[0])); return null; });
        try {
            List<LoadedPlugin> loaded = loader.load(yml);
            assertThat(loaded).hasSize(2);
            assertThat(loaded.get(0).kind()).isEqualTo(HostKind.JAVA);
            assertThat(loaded.get(0).source()).isNotNull();             // 源码型:有可监听源文件
            assertThat(loaded.get(1).kind()).isEqualTo(HostKind.GRAAL);

            // Java 服务可见(反射调编译类 Greet.say 成员)
            assertThat(javaSay(root, "java")).isEqualTo("hello, java!");

            // dsh JS 插件 inject greet → ctx.get('greet').say('world') → 回传 Java
            root.emit("app/ready", "started");
            assertThat(done.get()).isEqualTo("hello, world!");
            root.emit("app/ready", "again");                            // 再次触发,仍在册
            assertThat(done.get()).isEqualTo("hello, world!");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- ③ 自动热更新:Java .java 源变更 → 复用 M3 重载 ----

    @Test
    void updateIfChangedReloadsJavaSource() throws Exception {
        Path src = tmp.resolve("GreeterPlugin.java");
        Files.writeString(src, javaGreeterSource("v1"));
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    source: java:./GreeterPlugin.java\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(javaGreet(root)).isEqualTo("v1");
            sleepMtime();

            Files.writeString(src, javaGreeterSource("v2"));
            sleepMtime();
            assertThat(loader.updateIfChanged()).isTrue();     // Java 源码变更 → 仅重载该插件
            assertThat(javaGreet(root)).isEqualTo("v2");
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- ③ 自动热更新:改坏源码 → 重载失败 → 旧实现保留(回滚) ----

    @Test
    void updateIfChangedWithBrokenSourceKeepsOld() throws Exception {
        Path js = writeJs("greeter.js", "module.exports = { provide: ['greet'], apply(ctx) { ctx.provide('greet', 'v1'); } }");
        Path yml = tmp.resolve("cordis.yml");
        Files.writeString(yml, "plugins:\n  - name: greeter\n    source: graaljs:./greeter.js\n");

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root);
        try {
            loader.load(yml);
            assertThat(svc(root, "greet")).isEqualTo("v1");
            sleepMtime();

            // 写坏源码(语法错误)→ updateIfChanged 加载新实现失败 → 抛出且旧实现保留
            Files.writeString(js, "module.exports = { name: 'greeter', provide: ['greet'] apply(ctx) { ctx.provide('greet', 'broken'); } }");
            sleepMtime();
            assertThatThrownBy(() -> loader.updateIfChanged()).isInstanceOf(Exception.class);
            assertThat(svc(root, "greet")).isEqualTo("v1");
            assertThat(loader.loaded()).hasSize(1);
        } finally {
            loader.dispose();
            root.fiber.dispose().join();
        }
    }

    // ---- 小工具 ----

    /** 可替换插件源码:provide 'swappable' 为固定文本。 */
    private static String swappedSource(String value) {
        return "module.exports = { name: 'swapped', provide: ['swappable'], apply(ctx) {"
                + " ctx.provide('swappable', '" + value + "'); } }";
    }

    /** Java 源码插件:编译为独立类,provide 'greet' 一个带 greet() 成员的对象。 */
    private static String javaGreeterSource(String value) {
        return "import dev.dsh.cordis.*;\n"
                + "public class GreeterPlugin implements Plugin<Void> {\n"
                + "  public Object apply(Context ctx, Void cfg) {\n"
                + "    ctx.provide(\"greet\", new Greeter(\"" + value + "\"));\n"
                + "    return null;\n"
                + "  }\n"
                + "  public static class Greeter { private final String v; public Greeter(String v) { this.v = v; }"
                + " public String greet() { return v; } }\n"
                + "}\n";
    }

    private Path writeJs(String name, String source) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, source);
        return p;
    }

    /** 读任一服务,经 toString 取文本(JS provide 的 polyglot Value 兼容);未提供时返回 null。 */
    private static String svc(Context root, String name) {
        Object v = root.get(name);
        return v == null ? null : String.valueOf(v);
    }

    /** 反射调编译类服务的 greet()(类在隔离 ClassLoader,不 import)。 */
    private static String javaGreet(Context root) {
        Object svc = root.get("greet");
        if (svc == null) return null;
        try {
            Method m = svc.getClass().getMethod("greet");
            return (String) m.invoke(svc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 反射调编译类 Greet.say(String)(类在隔离 ClassLoader,不 import)。 */
    private static String javaSay(Context root, String name) {
        Object svc = root.get("greet");
        if (svc == null) return null;
        try {
            Method m = svc.getClass().getMethod("say", String.class);
            return (String) m.invoke(svc, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleepMtime() throws InterruptedException {
        Thread.sleep(30);   // 保证 mtime 变化被 FileWatcher 捕获(Windows 粒度)
    }
}
