package dev.dsh.cordis.js;

import dev.dsh.cordis.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M7-7:cordis-shim Service 构造器 getter 求值 bug 修复。
 *
 * <p>根因:shim Service 构造器的原型方法绑定循环用 {@code typeof self[key]} 判断"是否是
 * 方法",该表达式会<b>求值</b>原型上的 accessor(getter)。子类 getter 若依赖
 * {@code super()} 之后才赋值的字段(真实 pwsh-local 的 {@code get config()} 读
 * {@code this.source()},而 {@code source} 在子类构造器里 {@code super} 之后赋值)→
 * 构造期提前触发 getter → {@code this.source is not a function}(win32 上 pwsh-sandbox /
 * tool-pwsh apply 即此炸点)。修复:绑定循环改用属性描述符,只绑定数据属性里的方法,
 * accessor 一律跳过、不求值(getter 经原型链自然可达,不需也不该在构造期固化)。
 *
 * <p>本类:① 等价最小复现(pwsh 的 getter-after-super 形状)不再崩;② settings/permission
 * 形(getter 返回值)构造不崩;③ 真实 pwsh-local 包(vendor 子模块,lib 已构建时)apply 不再报
 * {@code this.source is not a function}。
 */
class CordisShimGetterTest {

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

    /** pwsh 形 getter-after-super:getter 读 super() 之后才赋值的字段。修复前 apply 崩。 */
    private Path writePwshShapedPlugin() throws Exception {
        return writePlugin("executor-plugin.cjs", """
                const { Service } = require('@deepseek-ai/cordis')
                class Executor extends Service {
                  // 构造器里 super(ctx, 'shell') 之后才赋值 this.source;getter 构造期求值即崩。
                  get config() { return this.source() }
                  constructor(ctx, config) {
                    super(ctx, 'shell')
                    this.source = () => config
                    this.resolvedPath = (config && config.execPath) || 'pwsh'
                  }
                  describe() { return 'exec:' + this.config.mode + '@' + this.resolvedPath }
                }
                module.exports = {
                  name: 'executor',
                  apply(ctx, config) {
                    ctx.provide('executor', new Executor(ctx, config || { mode: 'local' }))
                  }
                }
                """);
    }

    // ---- 1) pwsh 形最小复现:构造不再抛 this.source is not a function ----

    @Test
    void pwshShapedGetterAfterSuper_noLongerThrows() throws Exception {
        Path plugin = writePwshShapedPlugin();
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);

            // Service 构造器在 apply 内 new Executor(ctx, config) 时跑绑定循环:getter 被跳过,
            // this.source 未赋值也不被触发 → 注册成功(super(ctx, 'shell') 注册在 'shell' 名下)。
            Object svc = root.get("shell");
            assertThat(svc).isNotNull().isInstanceOf(Map.class);
            Map<String, Object> executor = map(svc);
            assertThat(executor.get("name")).isEqualTo("shell");

            // 构造完成后 getter 正常工作(读 super 之后赋值的 source)。
            Object describe = executor.get("describe");
            assertThat(describe).isInstanceOf(NodeRef.class);
            assertThat(host.invokeFn((NodeRef) describe, List.of())).isEqualTo("exec:local@pwsh");
        }
        root.fiber.dispose().join();
    }

    // ---- 2) settings/permission 形:getter 返回值 / abstract getter 构造不崩 ----

    @Test
    void settingsLikeValueGetters_constructWithoutCrash() throws Exception {
        Path plugin = writePlugin("settings-shim.cjs", """
                const { Service } = require('@deepseek-ai/cordis')
                class SettingsProvider extends Service {
                  get documentPath() { return this.path || undefined }
                  get writable() { return this._writable }
                  constructor(ctx) {
                    super(ctx, 'settings')
                    this._writable = true
                    this.path = '/tmp/settings.json'
                  }
                  namespace() { return 'ns:' + (this.documentPath ? 'file' : 'mem') + ':' + this.writable }
                }
                module.exports = {
                  name: 'settings-shim',
                  // Service 构造器(super(ctx, 'settings'))已把服务注册进 Java 核心,apply 只负责
                  // 实例化(与真实 SettingsProvider 的注册路径一致);不再显式 provide,避免重注册。
                  apply(ctx) {
                    new SettingsProvider(ctx)
                  }
                }
                """);
        Context root = new Context();
        try (NodeWorkerJsHost host = new NodeWorkerJsHost()) {
            root.plugin(new JsPluginAdapter(host, host.loadModule(plugin)), null);
            Map<String, Object> settings = map(root.get("settings"));
            assertThat(settings.get("name")).isEqualTo("settings");
            Object ns = settings.get("namespace");
            assertThat(ns).isInstanceOf(NodeRef.class);
            // getter 组合读回(super 后赋值字段):documentPath → 'file'、writable → true。
            assertThat(host.invokeFn((NodeRef) ns, List.of())).isEqualTo("ns:file:true");
        }
        root.fiber.dispose().join();
    }

    // ---- 3) 真实 pwsh-local 包:win32 上 apply 不再报 this.source is not a function ----

    private static Path repoVendor() {
        return Path.of(System.getProperty("user.dir"), "..", "vendor", "dsh").toAbsolutePath().normalize();
    }

    @Test
    void realPwshLocalAppliesOnWin32_withoutSourceNotAFunction() throws Exception {
        Path lib = repoVendor().resolve("packages/shell/pwsh-local/lib/index.js");
        assumeTrue(Files.isRegularFile(lib),
                "vendor/dsh/packages/shell/pwsh-local/lib/index.js not present — run ./setup.sh (or node scripts/strip-dsh-libs.mjs) first");
        Path pkgDir = lib.getParent().getParent();                  // .../pwsh-local
        Path vendorNodeModules = repoVendor().resolve("node_modules");

        Context root = new Context();
        // PwshLocalExecutor 有 static inject = ['subprocess']:不提供该服务,Java 核心的
        // fiber 依赖门控让它保持 PENDING、apply 不跑 → 'shell' 不注册。提供哑服务激活它。
        root.provide("subprocess", new java.util.HashMap<>());
        try (NodeWorkerJsHost host = new NodeWorkerJsHost(pkgDir, List.of(vendorNodeModules))) {
            try {
                // 真实 PwshLocalExecutor extends ShellExecutor extends shim Service:
                // 构造器 super(ctx) 绑定循环此前求值 get config() → this.source is not a function。
                // 修复后 apply 成功,super(ctx, 'shell') 把服务注册进 Java 核心。
                root.plugin(new JsPluginAdapter(host, host.loadModule(lib)), Map.of());
                Object svc = root.get("shell");
                assertThat(svc).isNotNull().isInstanceOf(Map.class);
                Map<String, Object> shell = map(svc);
                assertThat(shell.get("name")).isEqualTo("shell");
                // 原型方法经绑定循环成为 own 属性跨桥成 fn 句柄(getter 不求值、不跨桥)。
                assertThat(shell.get("resolve")).isInstanceOf(NodeRef.class);
                // getter 不跨桥:config 是原型 accessor,序列化时不被求值、也不是 own 键。
                assertThat(shell.get("config")).isNull();
            } finally {
                root.fiber.dispose().join();
            }
        }
    }
}
