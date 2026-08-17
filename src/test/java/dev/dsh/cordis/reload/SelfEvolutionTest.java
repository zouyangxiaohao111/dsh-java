package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自进化证明:agent 写/改插件源码 → 系统自动编译重载 → 新能力即时生效。
 * 这就是 dsh"自进化"的 Java 等价物——框架提供热重载,agent 提供新源码。
 */
class SelfEvolutionTest {

    /** 模拟"agent 写出的一个能力插件"源码。v1 只会 sayHello。 */
    private static final String V1 = """
            import dev.dsh.cordis.*;
            public class EvolverPlugin implements Plugin<Void> {
              public Object apply(Context ctx, Void cfg) {
                ctx.provide("evolver", new Evolver());
                return null;
              }
              public static class Evolver {
                public String sayHello(String name) { return "hello " + name; }
              }
            }
            """;

    /** 模拟"agent 自进化后写出的 v2":新增能力 sayBye,且 sayHello 行为升级。 */
    private static final String V2 = """
            import dev.dsh.cordis.*;
            public class EvolverPlugin implements Plugin<Void> {
              public Object apply(Context ctx, Void cfg) {
                ctx.provide("evolver", new Evolver());
                return null;
              }
              public static class Evolver {
                public String sayHello(String name) { return "你好 " + name + "(v2)"; }
                public String sayBye(String name) { return "再见 " + name; }
              }
            }
            """;

    @Test
    void agentWritesNewPluginSourceThenEvolves() throws Exception {
        Context root = new Context();
        Path src = Files.createTempDirectory("dsh-evolve").resolve("EvolverPlugin.java");
        Files.writeString(src, V1);

        PluginReloader reloader = new PluginReloader(root, new UrlPluginClassLoaderFactory(),
                Files.createTempDirectory("dsh-evolve-out"));

        // 阶段 1:agent 写出 v1 → 系统加载 → 能力可用
        Plugin<?> p1 = reloader.reload(src, null, null);
        assertThat(call(root, "sayHello", "dsh")).isEqualTo("hello dsh");

        // 阶段 2:agent 自进化——重写源码为 v2(升级 + 新增能力)
        Files.writeString(src, V2);
        Plugin<?> p2 = reloader.reload(src, p1, null);

        // v2 即时生效:旧行为升级 + 新能力出现
        assertThat(call(root, "sayHello", "dsh")).isEqualTo("你好 dsh(v2)");
        assertThat(call(root, "sayBye", "dsh")).isEqualTo("再见 dsh");

        root.fiber.dispose().join();
    }

    /** 反射调用 ctx.evolver 上的方法(跨 ClassLoader 的服务对象)。 */
    private String call(Context root, String method, String arg) {
        try {
            Object svc = root.get("evolver");
            Method m = svc.getClass().getMethod(method, String.class);
            return (String) m.invoke(svc, arg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
