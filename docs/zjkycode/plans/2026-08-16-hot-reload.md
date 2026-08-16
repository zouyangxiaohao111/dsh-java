# M3 热重载实施计划

> **对于代理工作者:**必需的子技能:使用 zjkycode:subagent-driven-development(推荐)来逐任务实施此计划。步骤使用复选框(`- [ ]`)语法进行跟踪。**用户已授权直接执行。**

**目标:** 代码热重载——改插件源码/文件自动换实现。Java 插件走 ClassLoader,JS 插件重启上下文,复用 M1 fiber dispose/reload。

**架构:** `dev.dsh.cordis.reload` 包。`PluginClassLoaderFactory`(URLClassLoader seam)+ `PluginReloader`(WatchService + 编排)。Java 源码级重载用 `javax.tools.JavaCompiler`;JS 重启 JsHost。

**技术栈:** JDK 25(内置 WatchService/JavaCompiler)、GraalJS(已就位)、JUnit 5 + AssertJ。

**设计文档:** `docs/zjkycode/specs/2026-08-16-hot-reload-design.md`
**参考源(只读):** `D:\code\deepseek-harness\vendor\cordis\src\{fiber,registry,hmr}.ts`(fiber dispose/delete、HMR 回滚)

**执行顺序:** 任务 1 → 2 → 3 → 4 → 5 → 6(每任务 `./gradlew test` 全绿后提交)。

---

### 任务 1:PluginClassLoaderFactory seam + URLClassLoader 实现

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/reload/PluginClassLoaderFactory.java`(接口)
- 创建:`src/main/java/dev/dsh/cordis/reload/UrlPluginClassLoaderFactory.java`
- 创建:`src/test/java/dev/dsh/cordis/reload/ClassLoaderTest.java`

**关键实现:**

`PluginClassLoaderFactory.java`:
```java
package dev.dsh.cordis.reload;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/** 插件 ClassLoader 工厂 seam(M3 决策:URLClassLoader 起步,ModuleLayer 后续可换)。 */
public interface PluginClassLoaderFactory {
    /** 为插件的 class 目录/文件创建一个新的隔离 ClassLoader。 */
    URLClassLoader create(List<Path> pluginClassRoots, ClassLoader parent);
}
```

`UrlPluginClassLoaderFactory.java`:
```java
package dev.dsh.cordis.reload;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/** 默认实现:每插件一个 URLClassLoader,parent = 核心 CL。 */
public final class UrlPluginClassLoaderFactory implements PluginClassLoaderFactory {
    @Override
    public URLClassLoader create(List<Path> pluginClassRoots, ClassLoader parent) {
        URL[] urls = pluginClassRoots.stream()
                .map(p -> {
                    try { return p.toUri().toURL(); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                })
                .toArray(URL[]::new);
        return new URLClassLoader(urls, parent);
    }
}
```

**测试:** `ClassLoaderTest.java`:
```java
package dev.dsh.cordis.reload;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ClassLoaderTest {
    @Test
    void newLoaderLoadsClassAndParentResolvesCore() throws Exception {
        // 编译一个最小插件类到临时目录(用 JavaCompiler,任务 2 有完整封装,这里直接用编译器)
        Path dir = Files.createTempDirectory("dsh-cl");
        String src = "package t; import dev.dsh.cordis.*; public class P implements Plugin<Void> { " +
                "public Object apply(Context ctx, Void cfg) { return null; } }";
        Path file = dir.resolve("t/P.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, src);
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", dir.toString(),
                "-cp", System.getProperty("java.class.path"), file.toString());
        assertThat(rc).isZero();

        PluginClassLoaderFactory factory = new UrlPluginClassLoaderFactory();
        URLClassLoader cl = factory.create(java.util.List.of(dir), getClass().getClassLoader());
        Class<?> pClass = cl.loadClass("t.P");
        assertThat(dev.dsh.cordis.Plugin.class.isAssignableFrom(pClass)).isTrue();   // 父 CL 接口可见
        assertThat(pClass.getClassLoader()).isNotEqualTo(getClass().getClassLoader()); // 插件类在子 CL
    }
}
```
> 说明:测试里直接用 JavaCompiler 编译临时插件类,验证"子 CL 加载 + 父 CL 接口可见"。任务 2 会把编译封装进 `PluginCompiler`。

- [ ] **步骤 1:创建 2 个类 + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.reload.ClassLoaderTest"` — 预期 PASSED
- [ ] **步骤 3:提交** `feat: plugin classloader factory seam`

---

### 任务 2:PluginCompiler(源码→class)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/reload/PluginCompiler.java`
- 创建:`src/test/java/dev/dsh/cordis/reload/PluginCompilerTest.java`

**关键实现:**

`PluginCompiler.java`:
```java
package dev.dsh.cordis.reload;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 把插件 .java 源码编译为 .class 到输出目录(运行时重载用)。 */
public final class PluginCompiler {
    public Path compile(Path sourceFile, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        String classpath = System.getProperty("java.class.path");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>();
        args.add("-d"); args.add(outputDir.toString());
        args.add("-cp"); args.add(classpath);
        args.add(sourceFile.toString());
        int rc = javac.run(null, null, null, args.toArray(String[]::new));
        if (rc != 0) throw new IllegalStateException("plugin compilation failed: " + sourceFile);
        return outputDir;
    }
}
```

**测试:** `PluginCompilerTest.java`(写一个插件 .java → 编译 → 断言 .class 存在):
```java
package dev.dsh.cordis.reload;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PluginCompilerTest {
    @Test
    void compilesSourceToClass() throws Exception {
        Path src = Files.createTempDirectory("dsh-src").resolve("t/P.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package t; import dev.dsh.cordis.*; " +
            "public class P implements Plugin<Void> { public Object apply(Context ctx, Void cfg) { return null; } }");
        Path out = Files.createTempDirectory("dsh-out");
        new PluginCompiler().compile(src, out);
        assertThat(out.resolve("t/P.class")).exists();
    }
}
```

- [ ] **步骤 1:创建 PluginCompiler + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.reload.PluginCompilerTest"` — 预期 PASSED
- [ ] **步骤 3:提交** `feat: plugin compiler (source to class via JavaCompiler)`

---

### 任务 3:PluginReloader(Java 热重载编排)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/reload/PluginReloader.java`
- 创建:`src/test/java/dev/dsh/cordis/reload/JavaHotReloadTest.java`

**关键实现:**

`PluginReloader.java`:
```java
package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;
import dev.dsh.cordis.Registry;

import java.nio.file.Path;
import java.util.List;

/** 编排插件热重载:源码/文件变更 → 新 ClassLoader 加载 → registry 换插件。 */
public final class PluginReloader {
    private final Context ctx;
    private final PluginClassLoaderFactory loaderFactory;
    private final PluginCompiler compiler;
    private final Path outputDir;

    public PluginReloader(Context ctx, PluginClassLoaderFactory loaderFactory, Path outputDir) {
        this.ctx = ctx;
        this.loaderFactory = loaderFactory;
        this.compiler = new PluginCompiler();
        this.outputDir = outputDir;
    }

    /** 重载一个 Java 插件:源码 → 编译 → 新 CL 实例化 → registry.delete(旧) + registry.plugin(新)。 */
    @SuppressWarnings("unchecked")
    public <T> Plugin<T> reload(Path sourceFile, Plugin<?> oldPlugin, Object config) throws Exception {
        Path classesDir = compiler.compile(sourceFile, outputDir.resolve(sourceFile.getFileName().toString() + ".classes"));
        ClassLoader cl = loaderFactory.create(List.of(classesDir), getClass().getClassLoader());
        // 推断插件类名:从源文件名找 public class
        String className = pluginClassName(sourceFile);
        Class<?> cls = cl.loadClass(className);
        if (!Plugin.class.isAssignableFrom(cls)) {
            throw new IllegalStateException(className + " does not implement Plugin");
        }
        Plugin<Object> newPlugin = (Plugin<Object>) cls.getDeclaredConstructor().newInstance();
        ctx.registry.delete(oldPlugin);          // dispose 旧 fiber
        ctx.registry.plugin(newPlugin, config);  // 注册新 fiber
        return newPlugin;
    }

    private String pluginClassName(Path sourceFile) {
        String name = sourceFile.getFileName().toString().replace(".java", "");
        return name;   // 默认:源文件名 = 类名(public class);包名由源码内 package 决定——此处简化取文件名
    }
}
```
> 说明:插件类名推断简化(源文件名 = public 类名,忽略 package)。真实插件若有 package,任务 4 测试里固定用无包或同名,记录限制。

**测试:** `JavaHotReloadTest.java`:
```java
package dev.dsh.cordis.reload;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class JavaHotReloadTest {
    @Test
    void reloadSwapsPluginImplementation() throws Exception {
        Context root = new Context();
        Path srcDir = Files.createTempDirectory("dsh-plugin");
        Path src = srcDir.resolve("GreeterPlugin.java");
        // v1:返回 "v1"
        Files.writeString(src,
            "import dev.dsh.cordis.*;\n" +
            "public class GreeterPlugin implements Plugin<Void> {\n" +
            "  public Object apply(Context ctx, Void cfg) {\n" +
            "    ctx.provide(\"greeter\", new Object() { public String greet() { return \"v1\"; } });\n" +
            "    return null;\n" +
            "  }\n" +
            "}\n");
        PluginReloader reloader = new PluginReloader(root, new UrlPluginClassLoaderFactory(),
            Files.createTempDirectory("dsh-reload-out"));
        Plugin<?> p1 = reloader.reload(src, null, null);
        assertThat(root.<Object>get("greeter")).isNotNull();
        assertThat(callGreet(root)).isEqualTo("v1");

        // v2:改源码返回 "v2"
        Files.writeString(src,
            "import dev.dsh.cordis.*;\n" +
            "public class GreeterPlugin implements Plugin<Void> {\n" +
            "  public Object apply(Context ctx, Void cfg) {\n" +
            "    ctx.provide(\"greeter\", new Object() { public String greet() { return \"v2\"; } });\n" +
            "    return null;\n" +
            "  }\n" +
            "}\n");
        Plugin<?> p2 = reloader.reload(src, p1, null);
        assertThat(callGreet(root)).isEqualTo("v2");
        root.fiber.dispose().join();
    }

    private String callGreet(Context root) {
        Object svc = root.get("greeter");
        try { return (String) svc.getClass().getMethod("greet").invoke(svc); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
```
> 说明:测试验证 v1→v2 重载后 `ctx.get("greeter")` 返回新实现。匿名内部类 `new Object() {...}` 提供 `greet()` 方法,反射调用。若 `ctx.provide` 的 value 是匿名类有访问问题,改用命名类或 `ServiceProxy`(M2 深化任务 4),记录。**关键断言:重载后 root.get("greeter").greet() == "v2"、旧 fiber 已 dispose。**

- [ ] **步骤 1:创建 PluginReloader + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.reload.JavaHotReloadTest"` — 预期 PASSED(若匿名类/反射细节有差异,调整测试实现方式,记录)
- [ ] **步骤 3:提交** `feat: plugin reloader (java source hot reload)`

---

### 任务 4:JS 热重载(上下文重启)

**文件:**
- 修改:`src/main/java/dev/dsh/cordis/js/JsPluginAdapter.java`(支持 close/reload 语义,或新增 JsPluginHandle)
- 创建:`src/main/java/dev/dsh/cordis/reload/JsPluginReloader.java`
- 创建:`src/test/java/dev/dsh/cordis/reload/JsHotReloadTest.java`

**关键实现:**

`JsPluginReloader.java`(JS 插件重载:关旧 JsHost → 新 JsHost 加载 → registry 换):
```java
package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.GraalJsHost;
import dev.dsh.cordis.js.JsPluginAdapter;
import org.graalvm.polyglot.Value;

import java.nio.file.Files;
import java.nio.file.Path;

/** 编排 JS 插件热重载:文件变更 → 关旧 JsHost → 新 JsHost 加载 → registry 换。 */
public final class JsPluginReloader {
    private final Context ctx;

    public JsPluginReloader(Context ctx) { this.ctx = ctx; }

    public JsPluginAdapter reload(Path jsFile, JsPluginAdapter oldAdapter, Object config) throws Exception {
        // 关旧宿主
        JsHost oldHost = oldAdapter.host();
        if (oldHost != null) oldHost.close();
        // 新宿主加载新插件
        JsHost newHost = new GraalJsHost(jsFile.getParent());
        Value exports = newHost.loadModule(jsFile.toAbsolutePath());
        JsPluginAdapter newAdapter = new JsPluginAdapter(newHost, exports);
        ctx.registry.delete(oldAdapter);       // dispose 旧 fiber
        ctx.registry.plugin(newAdapter, config);  // 注册新 fiber
        return newAdapter;
    }
}
```
> 依赖:`JsPluginAdapter` 需暴露 `host()`(getter)与构造 `(JsHost, Value)`(已有)。JS 插件文件变更即重载(JS 无编译)。

**测试:** `JsHotReloadTest.java`:
```java
package dev.dsh.cordis.reload;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.JsHost;
import dev.dsh.cordis.js.GraalJsHost;
import dev.dsh.cordis.js.JsPluginAdapter;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class JsHotReloadTest {
    @Test
    void jsPluginReloadOnFileChange() throws Exception {
        Context root = new Context();
        Path dir = Files.createTempDirectory("dsh-js");
        Path file = dir.resolve("plugin.js");
        Files.writeString(file, "module.exports = (ctx) => { ctx.on('go', () => ctx.emit('done', 'v1')); }");

        JsHost host1 = new GraalJsHost(dir);
        JsPluginAdapter p1 = new JsPluginAdapter(host1, host1.loadModule(file.toAbsolutePath()));
        root.plugin(p1, null);
        AtomicReference<String> got = new AtomicReference<>();
        root.on("done", (c, args) -> { got.set(String.valueOf(args[0])); return null; });
        root.emit("go");
        assertThat(got.get()).isEqualTo("v1");

        // 改文件 → 重载
        Files.writeString(file, "module.exports = (ctx) => { ctx.on('go', () => ctx.emit('done', 'v2')); }");
        JsPluginReloader reloader = new JsPluginReloader(root);
        JsPluginAdapter p2 = reloader.reload(file, p1, null);

        root.emit("go");
        assertThat(got.get()).isEqualTo("v2");
        root.fiber.dispose().join();
    }
}
```
> 依赖:`GraalJsHost(Path)` 构造已存在(M2 任务 1 `JsHost(Path requireCwd)`);`JsPluginAdapter.host()` getter 需加。若 `root.emit("go")` 触发旧 listener(旧 fiber 未 dispose)导致双触发,检查 `registry.delete(oldAdapter)` 是否 dispose 旧 fiber 并清 listener。

- [ ] **步骤 1:JsPluginAdapter 加 host() getter + JsPluginReloader + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.reload.JsHotReloadTest"` — 预期 PASSED
- [ ] **步骤 3:提交** `feat: js plugin reloader (context restart)`

---

### 任务 5:WatchService 监听 + 回滚

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/reload/FileWatcher.java`
- 修改:`src/main/java/dev/dsh/cordis/reload/PluginReloader.java` + `JsPluginReloader.java`(失败回滚)
- 创建:`src/test/java/dev/dsh/cordis/reload/ReloadRollbackTest.java`

**关键实现:**

`FileWatcher.java`(简单轮询兜底,避免 WatchService 平台差异):
```java
package dev.dsh.cordis.reload;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** 监听单个文件变更(轮询 mtime,简单可靠;复杂递归 WatchService 留 M4)。 */
public final class FileWatcher {
    private final Path file;
    private volatile long lastModified;

    public FileWatcher(Path file) { this.file = file; this.lastModified = readMtime(); }

    public boolean changed() {
        long now = readMtime();
        if (now != lastModified) { lastModified = now; return true; }
        return false;
    }

    private long readMtime() {
        try { return Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException e) { return lastModified; }
    }
}
```

回滚:PluginReloader.reload 改为"先编译+加载新,再删除旧;失败则保留旧":
```java
    public <T> Plugin<T> reload(Path sourceFile, Plugin<?> oldPlugin, Object config) throws Exception {
        Path classesDir = compiler.compile(sourceFile, outputDir.resolve(sourceFile.getFileName().toString() + ".classes"));
        ClassLoader cl = loaderFactory.create(List.of(classesDir), getClass().getClassLoader());
        String className = pluginClassName(sourceFile);
        Class<?> cls;
        try {
            cls = cl.loadClass(className);
        } catch (Exception e) {
            throw new IllegalStateException("plugin load failed, keeping old: " + className, e);  // 旧保留
        }
        if (!Plugin.class.isAssignableFrom(cls)) throw new IllegalStateException("not a plugin: " + className);
        Plugin<Object> newPlugin = (Plugin<Object>) cls.getDeclaredConstructor().newInstance();
        if (oldPlugin != null) ctx.registry.delete(oldPlugin);
        try {
            ctx.registry.plugin(newPlugin, config);
        } catch (Exception e) {
            // 新插件注册失败:尝试恢复旧(若旧已删,重新注册旧)
            if (oldPlugin != null) { ctx.registry.plugin(oldPlugin, config); }
            throw e;
        }
        return newPlugin;
    }
```

**测试:** `ReloadRollbackTest.java`:
```java
package dev.dsh.cordis.reload;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReloadRollbackTest {
    @Test
    void failedReloadKeepsOldImplementation() throws Exception {
        Context root = new Context();
        Path src = Files.createTempDirectory("dsh-rb").resolve("P.java");
        Files.writeString(src, "import dev.dsh.cordis.*; public class P implements Plugin<Void> { " +
            "public Object apply(Context ctx, Void cfg) { ctx.provide(\"svc\", \"v1\"); return null; } }");
        PluginReloader reloader = new PluginReloader(root, new UrlPluginClassLoaderFactory(), Files.createTempDirectory("dsh-rb-out"));
        Plugin<?> p1 = reloader.reload(src, null, null);
        assertThat(String.valueOf(root.get("svc"))).isEqualTo("v1");

        // 改坏源码(语法错误)→ 编译失败 → 旧实现保留
        Files.writeString(src, "import dev.dsh.cordis.*; public class P implements Plugin<Void> { this is broken ");
        assertThatThrownBy(() -> reloader.reload(src, p1, null)).isInstanceOf(Exception.class);
        assertThat(String.valueOf(root.get("svc"))).isEqualTo("v1");   // 旧实现仍在
        root.fiber.dispose().join();
    }
}
```

- [ ] **步骤 1:FileWatcher + 回滚逻辑 + 测试**
- [ ] **步骤 2:运行** `./gradlew test --tests "dev.dsh.cordis.reload.ReloadRollbackTest"` — 预期 PASSED
- [ ] **步骤 3:提交** `feat: file watcher + reload rollback`

---

### 任务 6:构建验证 + 收尾

- [ ] **步骤 1:`./gradlew clean build` — 预期 BUILD SUCCESSFUL,全部测试通过**
- [ ] **步骤 2:确认 `git status` 干净**
- [ ] **步骤 3:提交** `chore: m3 hot reload complete`

---

## 自我审查(执行前读一遍)

**1. 规范覆盖:** 设计 §2.1(Java 热重载)→任务1-3、5;§2.2(JS 热重载)→任务4;§2.3(触发与编排)→任务3、5(FileWatcher);§2.4(测试)→各任务。缺口:设计 §2.3 的 `plugin/reloaded` 事件——未入计划(YAGNI,测试不需),记为 M4。设计 §2.1 的 WatchService 递归——用轮询兜底(任务5),真实递归 M4。

**2. 占位符扫描:** 无 TODO/TBD。插件类名推断简化、匿名类测试实现均标注"以实际为准/记录限制",非占位。

**3. 类型一致性:** `Plugin.apply` 返回 `Object`(M2 深化任务 1 已改)——本计划的插件源码示例用 `Object apply`;`PluginReloader.reload` 泛型 `Plugin<T>`;`JsPluginAdapter.host()` getter 任务 4 加;`GraalJsHost(Path)` 构造已存在。各任务签名一致。

**4. 执行顺序:** 任务 1(CL factory)→2(compiler)→3(Java reload)→4(JS reload)→5(watcher+rollback)→6。任务 3 依赖任务 1+2;任务 4 依赖 M2 深化任务 5(JsHost 接口化)。
