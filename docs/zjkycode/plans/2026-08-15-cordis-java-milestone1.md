# cordis 核心 Java 复刻(里程碑 1)实施计划

> **对于代理工作者:**必需的子技能:使用 zjkycode:subagent-driven-development(推荐)或 zjkycode:executing-plans 来逐任务实施此计划。步骤使用复选框(`- [ ]`)语法进行跟踪。

**目标:** 将 `deepseek-harness/vendor/cordis/src/*.ts` 全量 9 个文件忠实移植为 JDK 25 Java 核心库 `dev.dsh.cordis`,语义逐态一致(状态机/epoch/disposer 反序/事件 5 模式/隔离),并通过 6 条语义验收测试 + QuickStart 演示。

**架构:** 单 Gradle 模块 `dsh-cordis`。按文件映射移植:`Context`(容器)、`Fiber`(状态机+epoch)、`Events`(事件总线)、`Reflect`(服务存储)、`Registry`(插件注册表)、`Service`/`Logger`(服务)、`util`(DisposableList 等)。JS 的 Proxy/mixin/symbol 机制 Java 化为显式方法 + 命名键(行为契约不变)。

**技术栈:** JDK 25(Gradle 9.x wrapper,代理 `127.0.0.1:7897`)、Kotlin DSL、Jackson(JsonNode 作为 config 载体)、JUnit 5 + AssertJ。

**参考源(只读,勿改):** `D:\code\deepseek-harness\vendor\cordis\src\{context,registry,fiber,events,reflect,service,logger,utils,index}.ts`
**设计文档:** `docs/zjkycode/specs/2026-08-15-cordis-java-design.md`

**移植原则(每个任务必须遵守):**
- **忠实 = 行为契约**(状态机转换、epoch 重算、disposer 反序、事件 dispatch 语义、隔离过滤、inject 延迟启动),不是 TS 语法。
- symbols → 命名键/字段;Proxy 的 `get/set/has` 动态服务解析 → `Context.get(name)` 显式方法 + `walk`;mixin → Context 静态方法。
- 异步用 `CompletableFuture`(镜像 Promise)。
- 每任务完成后 `./gradlew compileJava`(或对应测试)必须通过,再提交。

---

### 任务 0:工程脚手架 + Gradle wrapper

**文件:**
- 创建:`D:\code\dsh-java\settings.gradle.kts`
- 创建:`D:\code\dsh-java\build.gradle.kts`
- 创建:`D:\code\dsh-java\gradle.properties`
- 创建:`D:\code\dsh-java\gradle\wrapper\gradle-wrapper.properties`
- 创建:`D:\code\dsh-java\.gitignore`
- 创建(测试脚手架):`D:\code\dsh-java\src\test\java\dev\dsh\cordis\ScaffoldTest.java`
- 创建:`D:\code\dsh-java\gradlew`(wrapper 引导,见步骤 4)

- [ ] **步骤 1:写工程文件**

`settings.gradle.kts`:
```kotlin
rootProject.name = "dsh-java"
```

`build.gradle.kts`:
```kotlin
plugins {
    java
}

group = "dev.dsh"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
```

`gradle.properties`(代理 7897 双保险,env + 此文件):
```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
org.gradle.jvmargs=-Xmx1g
```

`gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.0.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```
> 执行说明:JDK 25 需 Gradle 9.x 作为 daemon JVM。若 `gradle-9.0.0-bin.zip` 下载 404,改试最新 9.x 版本号(从 https://services.gradle.org/versions/current 读取)。

`.gitignore`:
```gitignore
build/
.gradle/
.idea/
*.iml
out/
```

- [ ] **步骤 2:引导 wrapper(无本地 gradle,直接拉 wrapper jar)**

```bash
cd /d/code/dsh-java
# 通过代理下载与 distributionUrl 版本匹配的 wrapper jar(从 gradle GitHub tag 拉取)
curl -x http://127.0.0.1:7897 -L -o gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v9.0.0/gradle/wrapper/gradle-wrapper.jar
```
> 若 v9.0.0 tag 的 jar 不存在,用 `https://raw.githubusercontent.com/gradle/gradle/v8.14.0/gradle/wrapper/gradle-wrapper.jar` 等最近版本,jar 与 properties 版本号可不同(wrapper jar 只负责下载 distributionUrl 指定的发行版)。

再写 `gradlew`(bash 脚本,Unix 换行)与 `gradlew.bat`(Windows)。这两个脚本内容固定,标准 gradle wrapper 模板——从 `https://raw.githubusercontent.com/gradle/gradle/v9.0.0/gradlew` 和 `.../gradlew.bat` 通过代理下载,放到仓库根目录并 `chmod +x gradlew`。

- [ ] **步骤 3:写脚手架测试(验证 JUnit + Jackson 就绪)**

`src/test/java/dev/dsh/cordis/ScaffoldTest.java`:
```java
package dev.dsh.cordis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScaffoldTest {
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jacksonAndJunitWork() throws Exception {
        JsonNode node = MAPPER.readTree("{\"a\":1}");
        assertThat(node.get("a").asInt()).isEqualTo(1);
    }
}
```

- [ ] **步骤 4:跑测试验证构建链**

运行:`cd /d/code/dsh-java && ./gradlew test --no-daemon`
预期:首次会下载 Gradle 发行版(经代理),`ScaffoldTest` PASSED,`BUILD SUCCESSFUL`。

- [ ] **步骤 5:提交**

```bash
git add -A
git commit -m "chore: gradle scaffold with junit+jackson, proxy 7897

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### 任务 1:util 层(DisposableList / Symbols / Disposable / 错误工具)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/util/Disposable.java`
- 创建:`src/main/java/dev/dsh/cordis/util/DisposableList.java`
- 创建:`src/main/java/dev/dsh/cordis/util/Symbols.java`
- 创建:`src/main/java/dev/dsh/cordis/util/Errors.java`
- 创建:`src/test/java/dev/dsh/cordis/util/DisposableListTest.java`

**参考源:** `utils.ts:1-40`(DisposableList)、`utils.ts:50-73`(symbols)、`utils.ts:240-288`(composeError/buildOuterStack)

**关键实现:**

`Disposable.java`(对应 `Effect`/disposer,镜像 Promise → CompletableFuture):
```java
package dev.dsh.cordis.util;

import java.util.concurrent.CompletableFuture;

/** A cleanup callback. May be async; disposal is awaited. */
@FunctionalInterface
public interface Disposable {
    CompletableFuture<Void> dispose();

    static Disposable of(Runnable run) {
        return () -> {
            try { run.run(); return CompletableFuture.completedFuture(null); }
            catch (Throwable t) { return CompletableFuture.failedFuture(t); }
        };
    }

    static Disposable none() {
        return () -> CompletableFuture.completedFuture(null);
    }
}
```

`DisposableList.java`(忠实移植 `utils.ts:5-40`,O(1) 按值删除,`clear()` 返回**反序**列表):
```java
package dev.dsh.cordis.util;

import java.util.*;

/** Ordered disposable collection with O(1) deletion and reverse-order clear. */
public final class DisposableList<T> implements Iterable<T> {
    private long sn = 0;
    private final Map<Long, T> map = new LinkedHashMap<>();
    private final Map<T, Long> index = new IdentityHashMap<>();

    public int length() { return map.size(); }

    /** Append and return a remover that deletes this entry. */
    public Runnable push(T value) {
        long id = ++sn;
        map.put(id, value);
        index.put(value, id);
        return () -> {
            map.remove(id);
            index.remove(value, id); // 只在该 id 仍映射此值时清除
        };
    }

    public boolean delete(T value) {
        Long id = index.remove(value);
        if (id == null) return false;
        return map.remove(id) != null; // 对齐 JS map.delete(sn) 返回值
    }

    /** Remove everything; returns values in REVERSE insertion order (for reverse cleanup). */
    public List<T> clear() {
        List<T> values = new ArrayList<>(map.values());
        map.clear();
        index.clear();
        Collections.reverse(values);
        return values;
    }

    @Override
    public Iterator<T> iterator() { return map.values().iterator(); }
}
```

`Symbols.java`(symbols → 命名键,仅保留需要身份标记的几处):
```java
package dev.dsh.cordis.util;

/** Named keys standing in for JS unique symbols (utils.ts:50-73). */
public final class Symbols {
    private Symbols() {}
    /** Marks an inject map as inherited from a superclass (checkProto). */
    public static final String CHECK_PROTO = "cordis.checkProto";
    /** Marker key under which a disposer exposes its EffectMeta tree. */
    public static final String EFFECT = "cordis.effect";
    /** Isolation scope map key. */
    public static final String ISOLATE = "cordis.isolate";
    /** Intercept config map key. */
    public static final String INTERCEPT = "cordis.intercept";
}
```

`Errors.java`(简化 composeError:给异步错误附带外层上下文;不移植 JS 的堆栈字符串拼接):
```java
package dev.dsh.cordis.util;

/** Error composition helpers (simplified port of utils.ts composeError). */
public final class Errors {
    private Errors() {}

    /** Run `body`; if it throws, surface it as a RuntimeException. */
    public static <T> T compose(FallibleSupplier<T> body) {
        try {
            return body.get();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface FallibleSupplier<T> {
        T get() throws Exception;
    }
}
```
> 移植说明:`composeError`/`buildOuterStack` 在 JS 中用于长堆栈诊断(fiber.ts 内大量调用)。M1 用 `Errors.compose` 保证"错误向上抛、不被吞"即可,堆栈拼接留待后续。

**测试:** `DisposableListTest.java` 验证 push/delete/clear 反序/iterator:
```java
package dev.dsh.cordis.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DisposableListTest {
    @Test
    void clearReturnsReverseOrder() {
        DisposableList<String> list = new DisposableList<>();
        list.push("a"); list.push("b"); list.push("c");
        assertThat(list.clear()).containsExactly("c", "b", "a");
        assertThat(list.length()).isZero();
    }

    @Test
    void deleteByValue() {
        DisposableList<String> list = new DisposableList<>();
        list.push("a");
        Runnable remove = list.push("b");
        assertThat(list.delete("a")).isTrue();
        assertThat(list.length()).isEqualTo(1);
        remove.run(); // remover also works
        assertThat(list.length()).isZero();
    }
}
```

- [ ] **步骤 1:创建上述 4 个 util 文件 + 测试**
- [ ] **步骤 2:运行 `./gradlew test --tests "dev.dsh.cordis.util.DisposableListTest"` — 预期通过**
- [ ] **步骤 3:提交** `chore: util layer (DisposableList, Symbols, Disposable, Errors)`

---

### 任务 2:FiberState / CordisError / ValidationError / ConfigValidator

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/FiberState.java`
- 创建:`src/main/java/dev/dsh/cordis/CordisError.java`
- 创建:`src/main/java/dev/dsh/cordis/ValidationError.java`
- 创建:`src/main/java/dev/dsh/cordis/ConfigValidator.java`
- 创建:`src/test/java/dev/dsh/cordis/CordisErrorTest.java`

**参考源:** `fiber.ts:147-174`(FiberState/CordisError)、`fiber.ts:16-62`(ValidationError/resolveConfig)

**关键实现:**

`FiberState.java`(枚举顺序必须与 `fiber.ts:147-154` 完全一致):
```java
package dev.dsh.cordis;

public enum FiberState { PENDING, LOADING, ACTIVE, FAILED, DISPOSED, UNLOADING }
```

`CordisError.java`(对应 `fiber.ts:156-174`):
```java
package dev.dsh.cordis;

/** Framework error with a stable machine-readable code. */
public class CordisError extends RuntimeException {
    public enum Code { INACTIVE_EFFECT }

    public final Code code;

    public CordisError(Code code) {
        this(code, null);
    }

    public CordisError(Code code, String message) {
        super(message != null ? message : code.name());
        this.code = code;
    }
}
```

`ValidationError.java`(对应 `fiber.ts:16-41`):
```java
package dev.dsh.cordis;

/** Error raised when plugin configuration fails validation. */
public class ValidationError extends RuntimeException {
    public record Issue(String message, String path) {}

    private final java.util.List<Issue> issues;

    public ValidationError(java.util.List<Issue> issues) {
        super(buildMessage(issues));
        this.issues = java.util.List.copyOf(issues);
    }

    private static String buildMessage(java.util.List<Issue> issues) {
        StringBuilder sb = new StringBuilder("invalid config:");
        for (Issue i : issues) {
            sb.append("\n  - ").append(i.message());
            if (i.path() != null) sb.append(" (at ").append(i.path()).append(')');
        }
        return sb.toString();
    }

    public java.util.List<Issue> issues() { return issues; }
}
```

`ConfigValidator.java`(对应 `resolveConfig` + schemastery 的 StandardSchema validate 面;config 载体为 Jackson JsonNode):
```java
package dev.dsh.cordis;

import com.fasterxml.jackson.databind.JsonNode;

/** Validates/normalizes plugin config before a fiber activates.
 *  Equivalent to `Plugin.Config['~standard'].validate` in cordis. */
@FunctionalInterface
public interface ConfigValidator<T> {
    T validate(JsonNode node);
}
```
> 移植说明:async validation(TS 中 throw "not supported")在 Java 中不适用,`validate` 为同步函数,抛出 `ValidationError` 表示失败。默认(插件无 Config)直接透传 JsonNode,见任务 7 `resolveConfig`。

**测试:** `CordisErrorTest.java`:
```java
package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CordisErrorTest {
    @Test
    void inactiveEffectCodeAndDefaultMessage() {
        CordisError e = new CordisError(CordisError.Code.INACTIVE_EFFECT);
        assertThat(e.code).isEqualTo(CordisError.Code.INACTIVE_EFFECT);
        assertThat(e.getMessage()).isEqualTo("INACTIVE_EFFECT");
    }

    @Test
    void validationErrorFormatsIssues() {
        ValidationError e = new ValidationError(java.util.List.of(
                new ValidationError.Issue("must be a string", "name")));
        assertThat(e.getMessage()).contains("invalid config:")
                .contains("- must be a string").contains("(at name)");
    }
}
```

- [ ] **步骤 1:创建 4 个类 + 测试**
- [ ] **步骤 2:`./gradlew test --tests "dev.dsh.cordis.CordisErrorTest"` — 预期通过**
- [ ] **步骤 3:提交** `feat: fiber state, cordis error, config validator`

---

### 任务 3:Plugin 契约 + Inject 依赖声明

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/Inject.java`
- 创建:`src/main/java/dev/dsh/cordis/Plugin.java`
- 创建:`src/main/java/dev/dsh/cordis/PluginSpec.java`
- 创建:`src/test/java/dev/dsh/cordis/PluginTest.java`

**参考源:** `registry.ts:19-89`(Inject/resolve)、`registry.ts:91-146`(Plugin shapes/Runtime)

**关键实现:**

`Inject.java`(数组形式 + 对象形式归一为 map;`resolve` 支持父类继承,对应 `checkProto`):
```java
package dev.dsh.cordis;

import java.util.*;

/** Service dependency declaration (registry.ts:19-20). */
public final class Inject {
    /** name → intercept config (null = plain dependency). */
    public final Map<String, Object> entries;

    private Inject(Map<String, Object> entries) { this.entries = entries; }

    public static Inject of(String... names) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String n : names) m.put(n, null);
        return new Inject(Collections.unmodifiableMap(m));
    }

    public static Inject config(Map<String, Object> nameToConfig) {
        return new Inject(Collections.unmodifiableMap(new LinkedHashMap<>(nameToConfig)));
    }

    /** Merge own entries over inherited ones (registry.ts:71-89). */
    public static Map<String, Object> resolve(Inject inject, Map<String, Object> inherited) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (inherited != null) result.putAll(inherited);
        if (inject != null) result.putAll(inject.entries);
        return result;
    }
}
```

`Plugin.java`(函数/类/对象三形态归一;TS 用 duck-typing,Java 用接口):
```java
package dev.dsh.cordis;

import java.util.Map;

/** Plugin entrypoint (registry.ts:92-146).
 *  Call `apply(ctx, config)` when all declared deps are available. */
public interface Plugin<T> {
    void apply(Context ctx, T config) throws Exception;

    /** Display name for fiber diagnostics and loggers. */
    default String name() { return null; }

    /** Services this plugin requires; it only loads while all are available. */
    default String[] inject() { return new String[0]; }

    /** Service name → intercept config dependencies (map form). */
    default Map<String, Object> injectConfig() { return Map.of(); }

    /** Service name(s) this plugin provides. */
    default String[] provide() { return new String[0]; }

    /** Config validator applied before activation. */
    default ConfigValidator<T> config() { return null; }
}
```

`PluginSpec.java`(fluent 构造器,镜像 JS `Object.assign(fn, {inject})`):
```java
package dev.dsh.cordis;

import java.util.*;

/** Mutable fluent plugin descriptor; identity is the registry key. */
public final class PluginSpec<T> implements Plugin<T> {
    private final PluginApply<T> entrypoint;
    private String name;
    private final List<String> inject = new ArrayList<>();
    private final List<String> provide = new ArrayList<>();
    private ConfigValidator<T> config;
    private final Map<String, Object> injectConfig = new LinkedHashMap<>();

    @FunctionalInterface
    public interface PluginApply<T> {
        void apply(Context ctx, T config) throws Exception;
    }

    private PluginSpec(PluginApply<T> apply) { this.entrypoint = apply; }

    public static <T> PluginSpec<T> of(PluginApply<T> apply) { return new PluginSpec<>(apply); }

    public PluginSpec<T> name(String name) { this.name = name; return this; }
    public PluginSpec<T> inject(String... names) { this.inject.addAll(Arrays.asList(names)); return this; }
    public PluginSpec<T> injectConfig(String name, Object config) { this.injectConfig.put(name, config); return this; }
    public PluginSpec<T> provide(String... names) { this.provide.addAll(Arrays.asList(names)); return this; }
    public PluginSpec<T> config(ConfigValidator<T> config) { this.config = config; return this; }

    @Override public String name() { return name; }
    @Override public String[] inject() { return inject.toArray(String[]::new); }
    @Override public String[] provide() { return provide.toArray(String[]::new); }
    @Override public ConfigValidator<T> config() { return config; }
    @Override public void apply(Context ctx, T config) throws Exception { entrypoint.apply(ctx, config); }
    @Override public Map<String, Object> injectConfig() {
        return Collections.unmodifiableMap(injectConfig);
    }
}
```
> 移植说明:类插件(`class X implements Plugin<T>` + `@Inject` 注解)在 M1 不做注解扫描——统一用 `PluginSpec.of` / `Plugin` 接口 + `inject()`/`injectConfig()`/`provide()`/`config()` 默认方法表达,类插件重写这些方法即可。注解形态(对应 `@Inject` 装饰器)留待后续。

**测试:** `PluginTest.java` 验证 fluent builder 元数据:
```java
package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PluginTest {
    @Test
    void specCarriesMetadata() {
        PluginSpec<Void> p = PluginSpec.of((ctx, cfg) -> {})
                .name("greeter").inject("counter").provide("out");
        assertThat(p.name()).isEqualTo("greeter");
        assertThat(p.inject()).containsExactly("counter");
        assertThat(p.provide()).containsExactly("out");
    }

    @Test
    void injectResolveMergesOwnOverInherited() {
        java.util.Map<String, Object> inherited = new java.util.LinkedHashMap<>();
        inherited.put("a", "old");
        java.util.Map<String, Object> merged = Inject.resolve(Inject.of("a", "b"), inherited);
        assertThat(merged).containsEntry("a", null); // own(null) 覆盖 inherited
        assertThat(merged).containsEntry("b", null);
    }

    @Test
    void injectConfigMapForm() {
        java.util.Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("a", 1);
        java.util.Map<String, Object> merged = Inject.resolve(Inject.config(cfg), java.util.Map.of());
        assertThat(merged).containsEntry("a", 1);
    }

    @Test
    void specAccumulatesInjectConfig() {
        PluginSpec<Void> p = PluginSpec.of((ctx, cfg) -> {}).injectConfig("a", 1).injectConfig("b", 2);
        assertThat(p.injectConfig()).containsEntry("a", 1).containsEntry("b", 2);
    }
}
```

- [ ] **步骤 1:创建 3 个类 + 测试**
- [ ] **步骤 2:`./gradlew test --tests "dev.dsh.cordis.PluginTest"` — 预期通过**
- [ ] **步骤 3:提交** `feat: plugin contract and inject declaration`

---

### 任务 4:Reflect(服务存储)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/Reflect.java`
- 创建:`src/test/java/dev/dsh/cordis/ReflectTest.java`

**参考源:** `reflect.ts:93-125`(Property/Impl)、`reflect.ts:208-337`(store/provide/notify)、`reflect.ts:345-390`(accessor/mixin)

**关键实现**(完整):

```java
package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;
import java.util.function.Predicate;

/** Service implementations and declared context properties (reflect.ts). */
public final class Reflect {
    /** A concrete service implementation record (reflect.ts:116-125). */
    public static final class Impl {
        public final String name;
        public Object value;
        public final Fiber fiber;
        public final Predicate<Object> check;
        public Impl(String name, Object value, Fiber fiber, Predicate<Object> check) {
            this.name = name; this.value = value; this.fiber = fiber; this.check = check;
        }
    }

    /** A declared context property (reflect.ts:93-113). */
    public abstract static class Property {
        public static final class Service extends Property {}
        public static final class Accessor extends Property {
            public final java.util.function.BiFunction<Context, Object, Object> get;
            public final java.util.function.BiFunction<Context, Object, Boolean> set;
            public Accessor(java.util.function.BiFunction<Context, Object, Object> get,
                            java.util.function.BiFunction<Context, Object, Boolean> set) {
                this.get = get; this.set = set;
            }
        }
    }

    public final Context ctx;

    /** Service impls keyed by isolation label. */
    public final Map<String, Impl> store = new HashMap<>();
    /** Declared context properties by name. */
    public final Map<String, Property> props = new HashMap<>();

    public Reflect(Context ctx) { this.ctx = ctx; }

    /** Effective isolation label for `name` from a caller's scope (JS prototype-chain isolate). */
    static String effectiveIsolate(Context caller, String name) {
        while (caller != null) {
            if (caller.isolate.containsKey(name)) return caller.isolate.get(name);
            caller = caller.parent;
        }
        return null;
    }

    /** Read a service by name without the inject requirement (reflect.ts:233-243). */
    @SuppressWarnings("unchecked")
    public <T> T get(Context caller, String name, boolean strict) {
        Impl impl = getImpl(caller, name, strict);
        return impl == null ? null : (T) impl.value;
    }

    Impl getImpl(Context caller, String name, boolean strict) {
        String key = effectiveIsolate(caller, name);
        Impl impl = key == null ? null : store.get(key);
        if (impl == null) return null;
        if (strict && impl.fiber.state != FiberState.ACTIVE) return null;
        return impl;
    }

    /** Overwrite a provided service's value (reflect.ts:254-265). */
    public void set(Context caller, String name, Object value) {
        String key = effectiveIsolate(caller, name);
        Impl impl = key == null ? null : store.get(key);
        if (impl == null) throw new IllegalStateException("cannot set property \"" + name + "\" without provide");
        if (impl.fiber != caller.fiber) throw new IllegalStateException("cannot set property \"" + name + "\" in multiple fibers");
        impl.value = value;
    }

    /** Register a service impl owned by the current fiber (reflect.ts:277-305). */
    public Disposable provide(Context caller, String name, Object value, Predicate<Object> check) {
        return caller.fiber.effect(() -> {
            Property existing = props.get(name);
            if (existing != null && !(existing instanceof Property.Service)) {
                throw new IllegalStateException("property \"" + name + "\" is already declared as accessor");
            }
            props.putIfAbsent(name, new Property.Service());
            caller.root.isolate.computeIfAbsent(name, k -> "\u0000" + k); // ensure root default label
            String resolved = effectiveIsolate(caller, name);
            String key = resolved != null ? resolved : name;
            Impl impl = new Impl(name, value, caller.fiber, check);
            if (store.containsKey(key)) {
                throw new IllegalStateException("service \"" + name + "\" has been registered at <" + store.get(key).fiber.name() + ">");
            }
            store.put(key, impl);
            if (caller.fiber.store != null) caller.fiber.store.put(name, impl);
            if (caller.fiber.state == FiberState.ACTIVE) notify(List.of(name));
            return Disposable.of(() -> {
                store.remove(key);
                if (caller.fiber.store != null) caller.fiber.store.remove(name);
                this.notify(List.of(name));
            });
        }, "ctx.provide(" + name + ")");
    }

    /** Re-evaluate every fiber that requires one of the given services (reflect.ts:314-336). */
    public void notify(List<String> names) {
        for (Plugin.Runtime runtime : this.ctx.registry.values()) {
            for (Fiber fiber : runtime.fibers) {
                boolean hasUpdate = false;
                for (String name : names) {
                    if (!fiber.inject.containsKey(name)) continue;
                    if (!isolateMatches(fiber.ctx, name)) continue;
                    hasUpdate = true;
                    fiber.checkImpl(name);
                }
                if (!hasUpdate) continue;
                fiber.refresh();
            }
        }
        // internal/service event 推迟到 Events 就绪(任务 10 回填)
    }

    private boolean isolateMatches(Context fiberCtx, String name) {
        return Objects.equals(effectiveIsolate(fiberCtx, name), effectiveIsolate(this.ctx, name));
    }

    /** Define a computed context property (reflect.ts:345-353). */
    public Disposable accessor(String name, Property.Accessor options) {
        return this.ctx.fiber.effect(() -> {
            if (props.containsKey(name)) throw new IllegalStateException("property \"" + name + "\" is already declared as " + (props.get(name) instanceof Property.Service ? "service" : "accessor"));
            props.put(name, options);
            return Disposable.of(() -> props.remove(name));
        }, "ctx.accessor(" + name + ")");
    }

    /** Expose selected members of a service directly on ctx (reflect.ts:364-390). */
    public Disposable mixin(String source, List<String> keys) {
        return this.ctx.fiber.effect(() -> Disposable.none(), "ctx.mixin(" + source + ")");
    }
}
```
> 移植说明:
> - `store` 键是隔离 label。JS 用 `Map<symbol, Impl>` + `Object.create(null)`;Java 用 `HashMap`。
> - `effectiveIsolate` 沿 parent 链找 name 的 label(等价 JS 原型链)。
> - `notify` 里 `internal/service` 事件的发出推迟到 Events 就绪(任务 5 后回填,见任务 8 收口)。
> - `mixin` 在 Java 中是静态转发(见任务 8),此处仅保留声明签名。

**测试:** `ReflectTest.java`(仅测不依赖 Fiber 激活的部分;深度语义测试在任务 12/13 集成后):
```java
package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Reflect 的完整行为依赖 Context/Fiber,此测试仅验证可独立部分;集成语义见 FiberTest。 */
class ReflectTest {
    @Test
    void placeHolderForIntegration() {
        assertThat(true).isTrue();
    }
}
```

- [ ] **步骤 1:创建 `Reflect.java`(以上代码)+ 占位测试**
- [ ] **步骤 2:`./gradlew compileJava` — 预期成功(注意:此时 `Fiber`/`Plugin.Runtime`/`Context` 尚未创建,需先创建任务 7/3 的最小桩,或本任务暂以"创建文件 + 编译器过"为准;实际执行顺序建议按 3→7→10→8 的依赖序,见计划末尾的"执行顺序注记")**
- [ ] **步骤 3:提交** `feat: reflect service store`

> **执行顺序注记(重要):** 核心类互有引用(Reflect→Fiber/Context,Context→Reflect/Events/Fiber,Registry→Fiber,Events→Context),Java 编译要求全部同时就绪。**建议的落地顺序:任务 0 → 1 → 2 → 3 → 4(建文件,编译闸延后)→ 6(Service)→ 7(Fiber,先建)→ 5(Events)→ 10(Context)→ 9(Registry)→ 8(回填)→ 11(demo)→ 12(验收测试)。** 即先按依赖序把类建齐,再统一编译 + 集成测试。以下任务编号为模块逻辑序,执行时可调换以解除编译依赖。

---

### 任务 5:Events(事件总线)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/Events.java`
- 创建:`src/test/java/dev/dsh/cordis/EventsTest.java`

**参考源:** `events.ts:131-319`(EventsService 全量)、`events.ts:329-352`(Events 类型)

**关键实现**(完整):

```java
package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Event bus with 5 dispatch modes and context filtering (events.ts). */
public final class Events {
    /** Listener callback; `ctx` is the dispatching context (thisArg), null for plain emits. */
    @FunctionalInterface
    public interface Listener {
        Object call(Context ctx, Object... args);
    }

    public record Hook(Context ctx, Listener callback, boolean prepend, boolean global) {}

    final Map<String, List<Hook>> hooks = new HashMap<>();

    public final Context ctx;

    public Events(Context ctx) { this.ctx = ctx; }

    /** Resolve listeners for one dispatch and apply context filtering (events.ts:165-175). */
    List<Listener> dispatch(String type, Context thisArg, Object[] args) {
        String name = (String) args[0];
        List<Hook> list = hooks.get(name);
        if (list == null) return List.of();
        List<Listener> out = new ArrayList<>();
        for (Hook hook : list) {
            if (hook.global() || thisArg == null || thisArg.filter == null || thisArg.filter.test(hook.ctx)) {
                out.add(hook.callback());
            }
        }
        return out;
    }

    /** Run listeners synchronously, ignoring return values (events.ts:194-196). */
    public void emit(String name, Object... args) {
        Object[] full = prepend(name, args);
        for (Listener cb : dispatch("emit", null, full)) cb.call(null, args);
    }

    /** Dispatch with an explicit dispatching context (used by internal events). */
    public void emit(Context thisArg, String name, Object... args) {
        Object[] full = prepend(name, args);
        for (Listener cb : dispatch("emit", thisArg, full)) cb.call(thisArg, args);
    }

    /** Run listeners concurrently and wait for all; aggregate failures (events.ts:183-187). */
    public CompletableFuture<Void> parallel(String name, Object... args) {
        Object[] full = prepend(name, args);
        List<Throwable> errors = new ArrayList<>();
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (Listener cb : dispatch("emit", null, full)) {
            try {
                Object r = cb.call(null, args);
                if (r instanceof CompletableFuture<?> cf) {
                    all.add(cf.handle((v, t) -> {
                        if (t != null) errors.add(t);
                        return null;
                    }));
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    if (!errors.isEmpty()) throw new AggregateError(errors);
                });
    }

    /** Run listeners in order, awaiting each, until one returns a bail value (events.ts:204-209). */
    public CompletableFuture<Object> serial(String name, Object... args) {
        Object[] full = prepend(name, args);
        List<Listener> cbs = dispatch("serial", null, full);
        return serialLoop(cbs, args);
    }

    private CompletableFuture<Object> serialLoop(List<Listener> cbs, Object[] args) {
        if (cbs.isEmpty()) return CompletableFuture.completedFuture(null);
        Listener cb = cbs.get(0);
        try {
            Object r = cb.call(null, args);
            if (r instanceof CompletableFuture<?> cf) {
                return cf.thenCompose(v -> isBailed(v)
                        ? CompletableFuture.completedFuture(v)
                        : serialLoop(cbs.subList(1, cbs.size()), args));
            }
            return isBailed(r) ? CompletableFuture.completedFuture(r) : serialLoop(cbs.subList(1, cbs.size()), args);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    /** Run listeners synchronously until one returns a bail value (events.ts:217-222). */
    public Object bail(String name, Object... args) {
        Object[] full = prepend(name, args);
        for (Listener cb : dispatch("bail", null, full)) {
            Object r = cb.call(null, args);
            if (isBailed(r)) return r;
        }
        return null;
    }

    /** Compose listeners around the final `next` callback (events.ts:234-243).
     *  Listeners receive (args..., next); calling `next` advances to the following
     *  listener, finally the innermost `inner` continuation. */
    public Object waterfall(String name, Object... args) {
        Object[] full = prepend(name, args);
        List<Listener> cbs = dispatch("waterfall", null, full);
        Listener inner = (Listener) args[args.length - 1];
        Object[] callArgs = new Object[args.length];
        System.arraycopy(args, 0, callArgs, 0, args.length - 1);
        java.util.function.Supplier<Object> next = () -> {
            Listener cb = cbs.isEmpty() ? null : cbs.remove(0);
            Listener target = cb != null ? cb : inner;
            return target.call(null, callArgs);
        };
        callArgs[args.length - 1] = next;
        return next.get();
    }

    /** Register a listener owned by the calling fiber (events.ts:254-302). */
    public Disposable on(Context caller, String name, Listener listener, EventOptions opts) {
        if (opts == null) opts = new EventOptions();
        final EventOptions options = opts;
        return caller.fiber.effect(() -> {
            List<Hook> list = hooks.computeIfAbsent(name, k -> new ArrayList<>());
            Hook hook = new Hook(caller, listener, options.prepend, options.global);
            if (options.prepend) list.add(0, hook); else list.add(hook);
            return Disposable.of(() -> {
                list.remove(hook);
                if (list.isEmpty()) hooks.remove(name);
            });
        }, "ctx.on(" + name + ")");
    }

    /** Register a listener that disposes itself after the first call (events.ts:312-318). */
    public Disposable once(Context caller, String name, Listener listener, EventOptions opts) {
        if (opts == null) opts = new EventOptions();
        final EventOptions options = opts;
        Disposable[] self = new Disposable[1];
        self[0] = on(caller, name, (ctx, args) -> { self[0].dispose(); return listener.call(ctx, args); }, options);
        return self[0];
    }

    /** Aggregates multiple listener failures (mirrors JS AggregateError). */
    public static final class AggregateError extends RuntimeException {
        private final List<Throwable> errors;
        public AggregateError(List<Throwable> errors) {
            super("parallel dispatch failed with " + errors.size() + " errors");
            this.errors = List.copyOf(errors);
        }
        public List<Throwable> getErrors() { return errors; }
    }

    public static boolean isBailed(Object value) {
        return value != null && !Boolean.FALSE.equals(value);
    }

    private static Object[] prepend(String name, Object[] args) {
        Object[] full = new Object[args.length + 1];
        full[0] = name;
        System.arraycopy(args, 0, full, 1, args.length);
        return full;
    }

    /** Listener options (events.ts:112-117). */
    public static final class EventOptions {
        public boolean prepend;
        public boolean global;
        public EventOptions prepend(boolean v) { this.prepend = v; return this; }
        public EventOptions global(boolean v) { this.global = v; return this; }
    }
}
```
> 移植说明:
> - `dispatch` 的 `thisArg` 过滤:JS `filter.call(thisArg, hook.ctx)`;Java 用 `thisArg.filter.test(hook.ctx)`(`Context.filter` 字段,见任务 10)。普通 emit 无 thisArg,跳过过滤。
> - `internal/dispatch` 诊断事件 M1 不移植(无消费者)。
> - `register` 的 `internal/listener` 特殊分支(`internal/update` 钩子收集)M1 精简:直接注册,见任务 12 的 waterfall 测试是否覆盖。若需要 `internal/update` 瀑布语义,回填到任务 8。

**测试:** `EventsTest.java`(可直接独立测,Events 只依赖 Context 的 `fiber`/`filter`,用最小 Context 桩或任务 10 后跑;先写编译通过的测试):
```java
package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** 完整语义依赖 Context;此测试在任务 10 后启用。 */
class EventsTest {
    @Test
    void dispatchOrderAndPrepend() {
        // 需要 Context —— 在任务 10 之后实现:
        // Context root = new Context();
        // AtomicInteger seq = new AtomicInteger();
        // root.on("e", (ctx, a) -> seq.addAndGet(1));
        // root.on("e", (ctx, a) -> seq.addAndGet(2), new Events.EventOptions().prepend(true));
        // root.emit("e");
        // assertThat(seq.get()).isEqualTo(3); // prepend 的先跑
    }
}
```

- [ ] **步骤 1:创建 `Events.java`(以上代码)+ 测试**
- [ ] **步骤 2:编译闸(见执行顺序注记,任务 10 后统一跑测试)**
- [ ] **步骤 3:提交** `feat: events bus with 5 dispatch modes`

---

### 任务 6:Service 基类 + Logger

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/Service.java`
- 创建:`src/main/java/dev/dsh/cordis/Message.java`
- 创建:`src/main/java/dev/dsh/cordis/Exporter.java`
- 创建:`src/main/java/dev/dsh/cordis/Logger.java`(facade)
- 创建:`src/main/java/dev/dsh/cordis/LoggerService.java`
- 创建:`src/test/java/dev/dsh/cordis/ServiceTest.java`
> 注意:每个 `public` 顶层类型一个文件(JLS §7.6)。Logger 相关类型拆为 4 个文件:`Message` / `Exporter` / `Logger` / `LoggerService`。

**参考源:** `service.ts:11-115`、`logger.ts:29-270`

**关键实现:**

`Service.java`(构造时自动 provide,随 fiber 卸载;`resolveConfig` 合并 intercept):
```java
package dev.dsh.cordis;

import java.util.*;

/** Base class for services exposing a named API on ctx (service.ts:11-115). */
public abstract class Service {
    protected final Context ctx;
    public final String name;

    protected Service(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
        ctx.provide(name, this, ignored -> check());
    }

    /** Availability predicate consulted before dependents may load. */
    protected boolean check() { return true; }

    /** Merge intercept config from ancestors with optional base and head (service.ts:86-102). */
    @SuppressWarnings("unchecked")
    protected <T> T resolveConfig(T base, T head) {
        List<Object> configs = new ArrayList<>();
        Context c = this.ctx;
        while (c != null) {
            if (c.intercept.containsKey(this.name)) configs.add(0, c.intercept.get(this.name));
            c = c.parent;
        }
        if (base != null) configs.add(0, base);
        if (head != null) configs.add(head);
        Object merged = null;
        for (Object cfg : configs) {
            if (cfg instanceof Map<?, ?> m) {
                Map<Object, Object> acc = merged instanceof Map<?, ?> am
                        ? new LinkedHashMap<>(am) : new LinkedHashMap<>();
                acc.putAll(m);
                merged = acc;
            } else {
                merged = cfg;
            }
        }
        return (T) merged;
    }
}
```
> 移植说明:JS 的 callable service(带 `[invoke]`)Java 化为"Context 上的便利方法"(如 `ctx.logger(name)`);`Service[extend]`/`joinPrototype`/`createCallable` 的代理追踪机制 M1 不移植,logger 名称直接从调用方 fiber 派生(见下)。

Logger 相关类型拆为 4 个文件(JLS §7.6,每文件一个 `public` 顶层类型),内容如下。

`Message.java`:
```java
package dev.dsh.cordis;

/** Structured log record (logger.ts:29-39). */
public record Message(long sn, long ts, String name, String type, int level, Object[] args) {}
```

`Exporter.java`:
```java
package dev.dsh.cordis;

/** Sink receiving structured log messages (logger.ts:41-47). */
@FunctionalInterface
public interface Exporter {
    void export(Message message);
}
```

`Logger.java`(facade):
```java
package dev.dsh.cordis;

/** Named logger facade (logger.ts:74-162). */
public final class Logger {
    public final String name;
    public final int level;
    private final LoggerService service;

    // WeakRef fiber 元数据 M1 从简,故无 meta 字段(对应 logger.ts 构造中的 meta: { fiber })
    Logger(String name, int level, LoggerService service) {
        this.name = name; this.level = level; this.service = service;
    }

    public void error(Object format, Object... args) { emit("error", 0, format, args); }
    public void info(Object format, Object... args) { emit("info", 1, format, args); }
    public void warn(Object format, Object... args) { emit("warn", 2, format, args); }
    public void debug(Object format, Object... args) { emit("debug", 3, format, args); }

    private void emit(String type, int level, Object format, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = format;
        System.arraycopy(args, 0, all, 1, args.length);
        service.emit(new Message(service.nextMessageSn(), System.currentTimeMillis(), name, type, level, all), this.level);
    }
}
```

`LoggerService.java`:
```java
package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;

/** Built-in logging service (logger.ts:194-270). Java-ization: callable → `get(name)`. */
public final class LoggerService extends Service {
    int snExporter = 0;
    int snMessage = 0;
    final Map<Integer, Exporter> exporters = new LinkedHashMap<>();
    final List<Message> buffer = new ArrayList<>();
    int bufferSize = 1000;

    public LoggerService(Context ctx) {
        super(ctx, "logger");
        exporter(m -> { buffer.add(m); while (buffer.size() > bufferSize) buffer.remove(0); });
    }

    /** Register an exporter disposed with the current fiber (logger.ts:232-237). */
    public Disposable exporter(Exporter exporter) {
        return ctx.fiber.effect(() -> {
            int id = ++snExporter;
            this.exporters.put(id, exporter);
            return Disposable.of(() -> this.exporters.remove(id));
        }, "ctx.logger.exporter()");
    }

    /** Monotonic per-service message sequence number (logger.ts:152). */
    int nextMessageSn() { return ++snMessage; }

    /** Named logger for a subsystem (logger.ts:251-261, invoke body).
     *  注意:M1 未消费 `ctx.intercept('logger', ...)` 的 name/level 配置(对应 logger.ts invoke 体的 _resolveConfig),如需请后续补。 */
    public Logger get(String name) {
        return new Logger(name, 1, this);
    }

    /** Logger derived from the calling fiber's name (default `ctx.logger()` behavior).
     *  注意:M1 未消费 `ctx.intercept('logger', ...)` 的 name/level 配置(对应 logger.ts invoke 体的 _resolveConfig),如需请后续补。 */
    public Logger current() {
        return get(ctx.fiber.name());
    }

    void emit(Message message, int fallbackLevel) {
        for (Exporter exporter : exporters.values()) {
            if (fallbackLevel < message.level) continue;
            exporter.export(message);
        }
    }
}
```
> 移植说明:
> - `LoggerService.exporter()` 返回一个注册了 exporter 且随当前 fiber 存活、可反注册的 Disposable:`ctx.fiber.effect(...)` 内注册 + 返回反注册 disposer(修正早期占位版"注册后立即 dispose"的缺陷)。
> - printf 格式化、ANSI 颜色、WeakRef fiber 元数据 M1 从简:`format` 拼字符串,颜色不做。

**测试:** `ServiceTest.java` 占位(完整语义任务 12):
```java
package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceTest {
    @Test
    void placeholderForIntegration() {
        assertThat(true).isTrue();
    }
}
```

- [ ] **步骤 1:创建 `Service.java` + `Message.java` + `Exporter.java` + `Logger.java` + `LoggerService.java` + 测试**
- [ ] **步骤 2:提交** `feat: service base and logger`

---

### 任务 7:Fiber(生命周期状态机 + effect + epoch 重载)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/Fiber.java`
- 创建:`src/test/java/dev/dsh/cordis/FiberTest.java`

**参考源:** `fiber.ts:184-753`(全量)。这是最关键的移植,逐段对照。

**关键实现**(核心方法完整,注释标注对应 TS 行):

```java
package dev.dsh.cordis;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dsh.cordis.util.Disposable;
import dev.dsh.cordis.util.DisposableList;
import dev.dsh.cordis.util.Errors;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Lifecycle state for one plugin fiber (fiber.ts:184-753). */
public final class Fiber {
    static final String INACTIVE = "__INACTIVE__";

    /** Unique id within the registry; 0 for root. */
    public final int uid;
    /** The context this fiber's plugin runs in. */
    public final Context ctx;
    /** The context the plugin was loaded from. */
    public final Context parent;
    /** Validated plugin config (updated by update()). */
    public Object config;
    /** Raw plugin config, re-resolved before each activation. */
    Object _config;
    /** Resolved dependency map (name → intercept config). */
    public final Map<String, Object> inject;
    /** Shared plugin runtime; null for root fiber. */
    public final Plugin.Runtime runtime;
    /** Current lifecycle state. */
    public volatile FiberState state = FiberState.PENDING;
    /** Snapshot of required service impls while loaded. */
    public Map<String, Reflect.Impl> store;
    /** In-flight load/unload transition. */
    public CompletableFuture<Void> inertia;
    /** Registered disposers. */
    final DisposableList<Disposable> _disposables = new DisposableList<>();

    Throwable _error;
    String epoch = INACTIVE;

    public Fiber(Context parent, Object config, Map<String, Object> inject, Plugin.Runtime runtime) {
        this.parent = parent;
        this._config = config;
        this.inject = inject;
        this.runtime = runtime;
        this.uid = runtime == null ? 0 : parent.registry.counter();
        this.ctx = runtime == null ? parent : parent.extend();
        this.ctx.fiber = this;   // rebind: plugin context's fiber is this fiber (fiber.ts:236)

        // constructor body follows fiber.ts:229-333
        if (runtime != null) {
            // intercept map shadowing (fiber.ts:239-245)
            for (var e : inject.entrySet()) {
                if (e.getValue() != null) this.ctx.intercept.put(e.getKey(), e.getValue());
            }
        } else {
            this.state = FiberState.ACTIVE;
            this.store = new HashMap<>();
        }
        // publication & dep resolution completed in Registry.plugin (task 9)
    }

    /** The plugin's display name, nearest named ancestor, else "root" (fiber.ts:336-343). */
    public String name() {
        Fiber fiber = this;
        do {
            if (fiber.runtime != null && fiber.runtime.name() != null) return fiber.runtime.name();
            fiber = fiber.parent.fiber;
        } while (fiber != fiber.parent.fiber);
        return "root";
    }

    /** Throw if disposed (fiber.ts:351-354). */
    public void assertActive() {
        if (this.uid == 0 || this.state != FiberState.DISPOSED) return;
        throw new CordisError(CordisError.Code.INACTIVE_EFFECT);
    }

    /** Register a cleanup-aware effect (fiber.ts:418-561). Simplified but faithful:
     *  execute runs now; returned disposer is collected; teardown runs disposers
     *  in reverse order and awaits async ones. */
    public Disposable effect(EffectBody body, String label) {
        assertActive();
        if (this.state == FiberState.UNLOADING) {
            throw new CordisError(CordisError.Code.INACTIVE_EFFECT);
        }
        List<Disposable> disposables = new ArrayList<>();
        Disposable dispose = () -> {
            CompletableFuture<Void> task = CompletableFuture.completedFuture(null);
            for (int i = disposables.size() - 1; i >= 0; i--) {
                Disposable d = disposables.get(i);
                task = task.thenCompose(v -> d.dispose());
            }
            disposables.clear();
            return task;
        };

        Object result;
        try {
            result = body.run();
        } catch (Throwable t) {
            throw t instanceof RuntimeException re ? re : new RuntimeException(t);
        }
        if (result instanceof Disposable d) {
            disposables.add(d);
            this._disposables.push(d);
        } else if (result instanceof CompletableFuture<?> cf) {
            ((CompletableFuture<Disposable>) cf).thenAccept(d -> {
                if (d != null) { disposables.add(d); this._disposables.push(d); }
            }).exceptionally(t -> { ctx.logger().error(t); return null; });
        } else if (result instanceof Iterable<?> it) {
            for (Object o : it) if (o instanceof Disposable d) { disposables.add(d); this._disposables.push(d); }
        } else if (result != null) {
            throw new IllegalArgumentException("Invalid effect");
        }

        Runnable remove = this._disposables.push(dispose);
        return () -> dispose.dispose();
    }

    /** Effect body accepted by Fiber.effect (fiber.ts:74-101). */
    @FunctionalInterface
    public interface EffectBody {
        /** Returns null | Disposable | CompletableFuture<Disposable> | Iterable<Disposable>. */
        Object run() throws Exception;
    }

    /** Metadata tree for effect diagnostics (fiber.ts:96-101). */
    public record EffectMeta(String label, List<EffectMeta> children) {}

    /** Return metadata for currently registered effects (fiber.ts:568-572). */
    public List<EffectMeta> getEffects() {
        return List.of();
    }

    // ---- dependency / epoch machinery (fiber.ts:597-696) ----

    void checkImpl(String name) {
        Reflect.Impl impl = this.ctx.reflect.getImpl(this.ctx, name, true);
        if (impl == null) { storeRemove(name); return; }
        try {
            if (impl.check != null && !impl.check.test(ctx)) { storeRemove(name); return; }
        } catch (Exception e) {
            impl.fiber.ctx.logger().error(e);
            storeRemove(name); return;
        }
        storePut(name, impl);
    }

    void refresh() {
        String epoch = "";
        for (String name : inject.keySet()) {
            Reflect.Impl impl = storeGet(name);
            if (impl == null) { epoch = INACTIVE; break; }
            epoch += ":" + impl.fiber.uid;
        }
        setEpoch(epoch);
    }

    private void setEpoch(String newEpoch) {
        String oldEpoch = this.epoch;
        if (Objects.equals(newEpoch, oldEpoch)) return;
        this.epoch = newEpoch;
        if (this.inertia != null) return;
        if (!Objects.equals(newEpoch, INACTIVE) && Objects.equals(oldEpoch, INACTIVE)) {
            this.state = FiberState.LOADING;
            this.reload();   // reload() 自己管理 this.inertia
        } else {
            this.state = FiberState.UNLOADING;
            this.unload();   // unload() 自己管理 this.inertia
        }
    }

    /** Load the plugin: resolve config, run apply (fiber.ts:646-673). */
    CompletableFuture<Void> reload() {
        this.store = storeSnapshot();
        String oldEpoch = this.epoch;
        try {
            this.config = resolveConfig(this._config);
            this.runtime.callback.apply(this.ctx, this.config);
            this._error = null;
        } catch (Throwable t) {
            this.ctx.logger().error(t);
            this._error = t;
            this.epoch = INACTIVE;
        }
        if (Objects.equals(this.epoch, oldEpoch)) {
            this.state = FiberState.ACTIVE;
            this.inertia = null;
            // fiber.ts:_updateState — notify dependents of services this fiber provides
            if (this.store != null) {
                List<String> provided = new ArrayList<>();
                for (Map.Entry<String, Reflect.Impl> e : this.store.entrySet()) {
                    if (e.getValue() != null && e.getValue().fiber == this) provided.add(e.getKey());
                }
                if (!provided.isEmpty()) this.ctx.reflect.notify(provided);
            }
        } else {
            this.state = FiberState.UNLOADING;
            this.unload();   // unload() 自己管理 this.inertia
        }
        return this.inertia;
    }

    /** Unload: run disposers in reverse order (fiber.ts:675-696). */
    CompletableFuture<Void> unload() {
        List<Disposable> toRun = this._disposables.clear();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Disposable d : toRun) {
            chain = chain.thenCompose(v -> safeDispose(d));
        }
        this.inertia = chain;   // 同步标记在飞(基链),覆盖异步窗口;终态 continuation 再置 null 或调 reload
        return chain.thenCompose(v -> {
            this.store = null;
            if (Objects.equals(this.epoch, INACTIVE)) {
                this.inertia = null;
                // fiber.ts:_getState — no pending reload: FAILED if errored, else PENDING
                this.state = this._error != null ? FiberState.FAILED : FiberState.PENDING;
                return CompletableFuture.completedFuture(null);
            } else {
                this.state = FiberState.LOADING;
                this.reload();   // reload() 自己管理 this.inertia
                return this.inertia != null ? this.inertia : CompletableFuture.completedFuture(null);
            }
        });
    }

    private CompletableFuture<Void> safeDispose(Disposable d) {
        try {
            return d.dispose();
        } catch (Throwable t) {
            ctx.logger().error(t);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Validate config against the runtime's Config validator (fiber.ts:50-62, 641-644). */
    @SuppressWarnings("unchecked")
    Object resolveConfig(Object rawConfig) {
        if (runtime == null || runtime.config() == null) return rawConfig;
        if (rawConfig instanceof JsonNode node) {
            return runtime.config().validate(node);
        }
        return rawConfig;
    }

    /** Wait for lifecycle work and rethrow startup errors (fiber.ts:704-710). */
    public CompletableFuture<Fiber> await() {
        CompletableFuture<Fiber> future = new CompletableFuture<>();
        awaitInternal().whenComplete((v, t) -> {
            if (this._error != null) future.completeExceptionally(this._error);
            else future.complete(this);
        });
        return future;
    }

    private CompletableFuture<Void> awaitInternal() {
        if (this.inertia != null) return this.inertia;
        return CompletableFuture.completedFuture(null);
    }

    /** Dispose and immediately reload with current config (fiber.ts:718-723). */
    public CompletableFuture<Void> restart() {
        assertActive();
        setEpoch(INACTIVE);  // 若 ACTIVE,先卸载旧 effects
        refresh();           // 重算依赖,可触发 reload
        return awaitInternal().thenApply(v -> null);
    }

    /** Validate and apply new config, then restart (fiber.ts:736-753). Simplified:
     *  no internal/update waterfall (deferred). */
    public CompletableFuture<Void> update(Object config, boolean noSave) {
        assertActive();
        this._config = config;
        if (this.state != FiberState.ACTIVE) {
            this._error = null;
            this.epoch = INACTIVE;
            this.refresh();
            return CompletableFuture.completedFuture(null);
        }
        this.config = resolveConfig(config);
        this._error = null;
        return restart();
    }

    /** Dispose this fiber: unload, then settle once cleanup finished. */
    public CompletableFuture<Void> dispose() {
        if (this.state == FiberState.DISPOSED) return CompletableFuture.completedFuture(null);
        this.epoch = INACTIVE;   // unload 不得重载 ACTIVE fiber
        CompletableFuture<Void> done = unload();
        return done.thenAccept(v -> {
            this.state = FiberState.DISPOSED;
            this._error = null;
            if (this.runtime != null) {
                this.runtime.fibers.delete(this);   // 除名,防止 notify 复活(fiber.ts:266-275)
            }
        });
    }

    // store helpers (abstracted so unload can snapshot)
    private Map<String, Reflect.Impl> storeSnapshot() {
        return this.store == null ? new HashMap<>() : new HashMap<>(this.store);
    }
    private Reflect.Impl storeGet(String n) { return this.store == null ? null : this.store.get(n); }
    private void storePut(String n, Reflect.Impl i) { if (this.store == null) this.store = new HashMap<>(); this.store.put(n, i); }
    private void storeRemove(String n) { if (this.store != null) this.store.remove(n); }
}
```
> 移植说明(重要差异,均已 Java 化):
> - TS 用 `_runner.epoch`/`_runner.execute`;Java 用 `this.epoch` + 直接调用 `runtime.callback.apply`。
> - TS 的 `effect()` 含 setup barrier/双保险清理(大量 Promise 编排);Java 简化为"同步收集 disposer + 反序链式 dispose",**语义(反序、await、幂等)保留,竞态精修留待集成测试暴露后再补**。
> - `_setEpoch` 的 `inertia` 重入守卫保留。
> - `internal/status`、`internal/config`、`internal/update` 事件:状态转换事件 M1 精简为直接赋值;waterfall 钩子在任务 12 确认是否需要(设计文档 §3.2 已要求保留,若测试需要则回填,见任务 8 注记)。
> - `Plugin.Runtime` 在任务 3 定义?否——在任务 9(Registry)内定义 `record Runtime(String name, ConfigValidator<?> config, DisposableList<Fiber> fibers, ...)`,并在任务 3 的 `Plugin.java` 里放一个引用(见下)。

**需要同步修改 `Plugin.java`(任务 3 的文件),加入 `Runtime` 引用占位:**
```java
// 追加到 Plugin.java:
    /** Mutable registry record shared by all fibers of one plugin (registry.ts:136-145). */
    final class Runtime {
        public final String name;
        public final Plugin<?> callback;
        public final dev.dsh.cordis.util.DisposableList<Fiber> fibers = new dev.dsh.cordis.util.DisposableList<>();
        public final ConfigValidator<?> config;
        public Runtime(String name, Plugin<?> callback, ConfigValidator<?> config) {
            this.name = name; this.callback = callback; this.config = config;
        }
        public String name() { return name; }
        public ConfigValidator<?> config() { return config; }
    }
```
> 注:`Plugin.Runtime` 放 `Plugin.java` 内(保持与 `registry.ts` 的归属一致),Fiber 引用它。

**测试:** `FiberTest.java` 占位(核心状态机测试在任务 12 集成后完整实现):
```java
package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FiberTest {
    @Test
    void placeholderForIntegration() { assertThat(true).isTrue(); }
}
```

- [ ] **步骤 1:创建 `Fiber.java` + 修改 `Plugin.java` 追加 Runtime + 占位测试**
- [ ] **步骤 2:提交** `feat: fiber lifecycle with epoch reload`

---

### 任务 8:Context(容器)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/Context.java`

**参考源:** `context.ts:42-146`、`reflect.ts:135-206`(proxy handler 的服务解析)

**关键实现**(完整):

```java
package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Root and child dependency containers for plugins (context.ts). */
public final class Context {
    /** Parent context; null for root. */
    public final Context parent;
    /** The root context (every child shares it). */
    public final Context root;
    /** Base URL for resolving relative plugin specifiers. */
    public String baseUrl;

    /** Isolation map: name → scope label. */
    public final Map<String, String> isolate = new HashMap<>();
    /** Intercept map: name → config merged into that service's per-plugin config. */
    public final Map<String, Object> intercept = new HashMap<>();

    /** Listener filter consulted on event dispatch (reflect.ts proxy filter). */
    public Predicate<Context> filter;

    /** The fiber owning this context (rebound to the plugin fiber by the Fiber ctor). */
    public Fiber fiber;
    public final Reflect reflect;
    public final Registry registry;
    public final Events events;
    public final LoggerService logger;

    /** Tracing shadow (JS symbols.shadow); M1 保留字段,不实现完整追踪。 */
    Context shadow;
    Object receiver;

    /** Create the root context and install built-in services (context.ts:71-84). */
    public Context() {
        this.parent = null;
        this.root = this;
        this.fiber = new Fiber(this, null, Map.of(), null);
        this.reflect = new Reflect(this);
        this.registry = new Registry(this);
        this.events = new Events(this);
        this.logger = new LoggerService(this);
        // root fiber disposables already registered by services
    }

    private Context(Context parent, Map<String, String> isolate, Map<String, Object> intercept) {
        this.parent = parent;
        this.root = parent.root;
        this.baseUrl = parent.baseUrl;
        if (isolate != null) this.isolate.putAll(isolate);
        if (intercept != null) this.intercept.putAll(intercept);
        this.reflect = parent.reflect;    // shared, root-level
        this.registry = parent.registry;  // shared
        this.events = parent.events;      // shared
        this.logger = parent.logger;      // shared
        this.fiber = parent.fiber;        // replaced when plugin creates child (see Registry.plugin)
        this.filter = parent.filter;
    }

    /** Create a child context inheriting from this one (context.ts:99-107). */
    public Context extend() {
        return new Context(this, null, null);
    }

    /** Create a child with an independent service scope for `name` (context.ts:121-125). */
    public Context isolate(String name) {
        Map<String, String> iso = new HashMap<>();
        iso.put(name, name + "@" + System.identityHashCode(new Object()));
        return new Context(this, iso, null);
    }

    /** Add service-specific intercept config for plugins below (context.ts:139-145). */
    public Context intercept(String name, Object config) {
        Map<String, Object> ic = new HashMap<>();
        ic.put(name, config);
        return new Context(this, null, ic);
    }

    // ---- service resolution (reflect.ts:135-206 proxy handler, Java-ized) ----

    /** Read a service by name (proxy-get equivalent). */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        Reflect.Property prop = this.reflect.props.get(name);
        if (prop instanceof Reflect.Property.Accessor acc) {
            return (T) acc.get.apply(this, this.receiver);
        }
        if (this.fiber.runtime == null) {
            return this.reflect.get(this, name, false);
        }
        Context ctx = this.shadow != null ? this.shadow : this;
        Fiber f = ctx.fiber;
        String key = Reflect.effectiveIsolate(this, name);
        while (true) {
            Reflect.Impl impl = f.store == null ? null : f.store.get(name);
            if (impl != null) return (T) impl.value;
            if (f.inject.containsKey(name)) {
                throw new CordisError(CordisError.Code.INACTIVE_EFFECT,
                        "cannot get required service \"" + name + "\" in inactive context");
            }
            if (f.runtime == null) break;
            if (!Objects.equals(Reflect.effectiveIsolate(f.parent, name), key)) break;
            f = f.parent.fiber;
        }
        return this.reflect.get(this, name, false);
    }

    /** Overwrite a provided service's value. */
    public void set(String name, Object value) {
        this.reflect.set(this, name, value);
    }

    /** Register a service implementation owned by the current fiber (reflect.ts:277). */
    public Disposable provide(String name, Object value) {
        return provide(name, value, null);
    }

    public Disposable provide(String name, Object value, Predicate<Object> check) {
        return this.reflect.provide(this, name, value, check);
    }

    // ---- events (mixins made static; events.ts mixed onto ctx) ----

    public Disposable on(String name, Events.Listener listener) {
        return this.events.on(name, listener, new Events.EventOptions());
    }

    public Disposable on(String name, Events.Listener listener, Events.EventOptions opts) {
        return this.events.on(name, listener, opts);
    }

    public Disposable once(String name, Events.Listener listener, Events.EventOptions opts) {
        return this.events.once(name, listener, opts);
    }

    public void emit(String name, Object... args) {
        this.events.emit(name, args);
    }

    public CompletableFuture<Void> parallel(String name, Object... args) {
        return this.events.parallel(name, args);
    }

    public CompletableFuture<Object> serial(String name, Object... args) {
        return this.events.serial(name, args);
    }

    public Object bail(String name, Object... args) {
        return this.events.bail(name, args);
    }

    public Object waterfall(String name, Object... args) {
        return this.events.waterfall(name, args);
    }

    // ---- registry (mixins made static) ----

    public Fiber plugin(Plugin<?> plugin, Object config) {
        return this.registry.plugin(this, plugin, config);
    }

    public Fiber inject(Inject deps, PluginSpec.PluginApply<Void> callback) {
        return this.registry.inject(this, deps, callback);
    }

    // ---- effects ----

    public Disposable effect(Fiber.EffectBody body, String label) {
        return this.fiber.effect(body, label);
    }

    // ---- typed convenience ----

    public Logger logger(String name) {
        return this.logger.get(name);
    }

    public Logger logger() {
        return this.logger.current();
    }
}
```
> 移植说明:
> - `extend()` 在 JS 里是原型继承 + meta 覆盖;Java 里父级服务(reflect/registry/events/logger)共享,fiber 在插件创建时替换。
> - `isolate(name)` 的 label 用 `name + "@" + identityHash` 模拟唯一 symbol;两个 `isolate(name)` 相同 label = 合并作用域(JS 传 label 参数)——M1 只支持自动 label,显式 label 重载留待后续。
> - `get(name)` 完整实现了 proxy-get 的四步解析(accessor → root fallback → fiber.store 链 → reflect fallback),含 isolate 边界与 inactive inject 抛错。
> - `fiber.runtime` 对 root fiber 为 null → `get` 直接走 reflect(对应 JS `if (!ctx.fiber.runtime) return ctx.reflect.get(prop, false)`)。

**测试:** Context 无独立测试(依赖 Registry.plugin 完整装配),在任务 12 集成测试覆盖。

- [ ] **步骤 1:创建 `Context.java`(以上代码)**
- [ ] **步骤 2:提交** `feat: context container with service resolution`

---

### 任务 9:Registry(插件注册表 + plugin() 装配)

**文件:**
- 创建:`src/main/java/dev/dsh/cordis/Registry.java`

**参考源:** `registry.ts:195-337`

**关键实现**(完整):

```java
package dev.dsh.cordis;

import dev.dsh.cordis.util.Disposable;

import java.util.*;

/** Plugin registry installed as ctx.registry (registry.ts:195-337). */
public final class Registry {
    private int _counter = 0;
    private final Map<Plugin<?>, Plugin.Runtime> _internal = new IdentityHashMap<>();

    public final Context ctx;

    public Registry(Context ctx) { this.ctx = ctx; }

    /** Allocate the next fiber uid (registry.ts:207-209). */
    int counter() { return ++_counter; }

    public int size() { return _internal.size(); }

    /** Look up the runtime record for a plugin (registry.ts:236-239). */
    public Plugin.Runtime get(Plugin<?> plugin) { return _internal.get(plugin); }

    public boolean has(Plugin<?> plugin) { return _internal.containsKey(plugin); }

    /** Dispose every running fiber for a plugin and remove its runtime (registry.ts:258-267). */
    public void delete(Plugin<?> plugin) {
        Plugin.Runtime runtime = _internal.remove(plugin);
        if (runtime == null) return;
        for (Fiber fiber : runtime.fibers) {
            fiber.dispose();
        }
    }

    public Collection<Plugin.Runtime> values() { return _internal.values(); }

    /** Start a callback once requested dependencies are available (registry.ts:300-302). */
    public Fiber inject(Context caller, Inject deps, PluginSpec.PluginApply<Void> callback) {
        PluginSpec<Void> spec = PluginSpec.of(callback);
        spec.inject(deps.entries.keySet().toArray(String[]::new));
        for (var e : deps.entries.entrySet()) {
            if (e.getValue() != null) spec.injectConfig(e.getKey(), e.getValue());
        }
        return plugin(caller, spec, null);
    }

    /** Start a plugin in the current context and return its fiber (registry.ts:316-336). */
    public Fiber plugin(Context caller, Plugin<?> plugin, Object config) {
        caller.fiber.assertActive();

        Plugin.Runtime runtime = _internal.get(plugin);
        if (runtime == null) {
            runtime = new Plugin.Runtime(plugin.name(), plugin, plugin.config());
            _internal.put(plugin, runtime);
        }

        Map<String, Object> injectMap = new LinkedHashMap<>();
        for (String name : plugin.inject()) injectMap.put(name, null);
        injectMap.putAll(plugin.injectConfig());

        Fiber fiber = new Fiber(caller, config, injectMap, runtime);
        runtime.fibers.push(fiber);

        // 级联:父 fiber 卸载时 dispose 本插件 fiber(fiber.ts:265)
        caller.fiber.effect(() -> (Disposable) () -> fiber.dispose(), "ctx.plugin()");

        // publication + dependency resolution (fiber.ts:299-319)
        if (fiber.uid != 0 && caller.fiber.state != FiberState.UNLOADING) {
            for (String name : injectMap.keySet()) {
                fiber.checkImpl(name);
            }
            fiber.refresh();
        }
        return fiber;
    }
}
```
> 移植说明:
> - 注册表键 = 插件对象身份(`IdentityHashMap`),对应 JS 按回调函数为键。
> - `plugin()` 完成后即触发依赖解析:有 inject 的插件会 PENDING,待服务就绪由 `Reflect.notify` 唤醒。
> - `internal/plugin`/`internal/status` 事件 M1 不发出(无 loader/HMR 消费者),保留 fiber 状态字段供测试断言。
> - `registry.inject(deps, callback)` 的 fluent 形态用 `PluginSpec` 组装。

- [ ] **步骤 1:创建 `Registry.java`(以上代码)**
- [ ] **步骤 2:提交** `feat: plugin registry with caller-aware plugin entry`

---

### 任务 10:集成收口(编译 + 修正交叉引用)

**文件:**
- 修改:任务 4/5/6/7/8 中标注"回填"的位置
- 修改:`Reflect.notify` 发出 `internal/service` 事件(如 Events 已就绪)

**目的:** 让 `./gradlew compileJava` 全绿。按依赖序已创建全部类后,逐项检查:
- `Reflect` 引用 `Fiber`/`Plugin.Runtime`/`Context` 是否就绪;
- `Fiber` 引用 `Context.registry.counter`、`ctx.extend`、`ctx.intercept`、`ctx.logger` 是否签名一致;
- `Context` 构造中 `registry`/`events`/`logger` 的构造顺序;
- `LoggerService.exporter()` 的 effect 语义修正(见任务 6 注记)。

- [ ] **步骤 1:`./gradlew compileJava` — 修复所有编译错误直到成功**
- [ ] **步骤 2:提交** `fix: integration wiring (cross-references compile)`

---

### 任务 11:QuickStart 演示

**文件:**
- 创建:`src/test/java/dev/dsh/cordis/QuickStartTest.java`(以测试形式跑 README 示例)
- 可选创建:`src/main/java/dev/dsh/cordis/demo/Counter.java` + `demo/Main.java`(可运行 main)

**参考源:** `vendor/cordis/README.md` Quick Start

**关键实现:**

`QuickStartTest.java`:
```java
package dev.dsh.cordis;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 复刻 cordis README Quick Start: counter 服务 + greeter 插件 + app/ready 事件。 */
class QuickStartTest {
    static final class Counter {
        int value = 0;
        int next() { return ++value; }
    }

    @Test
    void quickStartScenario() throws Exception {
        Context root = new Context();

        PluginSpec<Void> counter = PluginSpec.of((ctx, cfg) -> {})
                .provide("counter");
        root.plugin(counter);
        root.provide("counter", new Counter());

        AtomicReference<String> seen = new AtomicReference<>();
        PluginSpec<Void> greeter = PluginSpec.of((ctx, cfg) ->
                ctx.on("app/ready", (c, args) -> {
                    Counter counterSvc = c.get("counter");
                    seen.set(args[0] + " #" + counterSvc.next());
                    return null;
                }))
                .inject("counter");
        root.plugin(greeter);

        root.emit("app/ready", "started");
        assertThat(seen.get()).isEqualTo("started #1");

        root.fiber.dispose().join();
    }
}
```
> 注:`Counter` 用 `root.provide("counter", new Counter())` 直接提供,不走插件 provide——与 README 的 `class Counter extends Service` 等价但更简;若需验证 Service 子类路径,可改用 `class CounterService extends Service`。

- [ ] **步骤 1:创建 `QuickStartTest.java`(以上代码)**
- [ ] **步骤 2:`./gradlew test --tests "dev.dsh.cordis.QuickStartTest"` — 预期:`started #1` 通过**
- [ ] **步骤 3:提交** `test: quickstart scenario`

---

### 任务 12:6 条语义验收测试(完整实现)

**文件:**
- 创建:`src/test/java/dev/dsh/cordis/acceptance/InjectDeferredTest.java`
- 创建:`src/test/java/dev/dsh/cordis/acceptance/ReactiveReloadTest.java`
- 创建:`src/test/java/dev/dsh/cordis/acceptance/DisposeOrderTest.java`
- 创建:`src/test/java/dev/dsh/cordis/acceptance/EventModesTest.java`
- 创建:`src/test/java/dev/dsh/cordis/acceptance/IsolateTest.java`
- 创建:`src/test/java/dev/dsh/cordis/acceptance/StateMachineTest.java`

**参考源:** 设计文档 §4;语义对照 `fiber.ts`/`reflect.ts`/`events.ts`

**每条验收标准(完整 JUnit,必须实现并断言):**

`InjectDeferredTest.java` — 先注册 greeter 再注册 counter,greeter 保持 PENDING,出现后自动激活:
```java
package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class InjectDeferredTest {
    @Test
    void dependentWaitsForProviderThenActivates() throws Exception {
        Context root = new Context();
        AtomicBoolean activated = new AtomicBoolean(false);

        Fiber greeter = root.plugin(PluginSpec.of((ctx, cfg) -> activated.set(true)).inject("svc"));
        assertThat(greeter.state).isEqualTo(FiberState.PENDING);

        root.plugin(PluginSpec.of((ctx, cfg) -> {}).provide("svc"));
        root.provide("svc", new Object());
        greeter.await().join();

        assertThat(activated.get()).isTrue();
        assertThat(greeter.state).isEqualTo(FiberState.ACTIVE);
        root.fiber.dispose().join();
    }
}
```

`ReactiveReloadTest.java` — 依赖变更自动重载:dispose 提供者 → 依赖方 unload;换实现 → 用新实现重载:
```java
package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ReactiveReloadTest {
    static final class ImplA { String tag() { return "A"; } }
    static final class ImplB { String tag() { return "B"; } }

    @Test
    void dependentsReloadWhenProviderSwaps() throws Exception {
        Context root = new Context();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger unloads = new AtomicInteger();

        root.provide("svc", new ImplA());
        Fiber consumer = root.plugin(PluginSpec.of((ctx, cfg) -> {
            loads.incrementAndGet();
            return Disposable.of(unloads::incrementAndGet);
        }).inject("svc"));
        consumer.await().join();
        assertThat(loads.get()).isEqualTo(1);

        // swap implementation: unprovide then provide new impl
        root.registry.delete(providerPlugin());
        root.provide("svc", new ImplB());
        consumer.await().join();

        assertThat(unloads.get()).isEqualTo(1);  // old fiber unloaded
        assertThat(loads.get()).isEqualTo(2);    // new fiber loaded with ImplB
        assertThat(consumer.ctx.<ImplB>get("svc")).isInstanceOf(ImplB.class);
        root.fiber.dispose().join();
    }

    private Plugin<?> providerPlugin() {
        return PluginSpec.of((ctx, cfg) -> {}).provide("svc");
    }
}
```

`DisposeOrderTest.java` — 反序清理:
```java
package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DisposeOrderTest {
    @Test
    void disposersRunInReverseOrder() throws Exception {
        Context root = new Context();
        List<String> order = new ArrayList<>();
        Fiber f = root.plugin(PluginSpec.of((ctx, cfg) -> {
            ctx.effect(() -> Disposable.of(() -> order.add("a")), "a");
            ctx.effect(() -> Disposable.of(() -> order.add("b")), "b");
            ctx.effect(() -> Disposable.of(() -> order.add("c")), "c");
        }));
        f.await().join();
        f.dispose().join();
        assertThat(order).containsExactly("c", "b", "a");
    }
}
```

`EventModesTest.java` — 5 种 dispatch 语义:
```java
package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class EventModesTest {
    @Test
    void bailStopsOnFirstBailValue() {
        Context root = new Context();
        AtomicInteger calls = new AtomicInteger();
        root.on("e", (ctx, args) -> { calls.incrementAndGet(); return "stop"; });
        root.on("e", (ctx, args) -> { calls.incrementAndGet(); return null; });
        Object result = root.bail("e");
        assertThat(result).isEqualTo("stop");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void waterfallComposesAroundNext() {
        Context root = new Context();
        StringBuilder trace = new StringBuilder();
        root.on("wf", (ctx, args) -> { trace.append("m1-before;"); ctx.waterfall("wf", "next"); trace.append("m1-after;"); return null; });
        root.on("wf", (ctx, args) -> { trace.append("m2-before;"); ctx.waterfall("wf", "next"); trace.append("m2-after;"); return null; });
        // inner "next" 在此测试直接实现为输出
        throw new UnsupportedOperationException("waterfall inner-next 语义需按 events.ts:234-243 精确实现后启用本断言");
    }
}
```
> 注:`EventModesTest.waterfall` 的完整断言依赖 `waterfall` 实现的 final `next` 回调注入方式,须先按任务 5 代码实现并在集成时校准;serial/parallel 类似。

`IsolateTest.java` — 子作用域服务互不污染:
```java
package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IsolateTest {
    @Test
    void isolatedScopeDoesNotSeeParentService() {
        Context root = new Context();
        root.provide("svc", "parent");
        Context child = root.isolate("svc");
        // 子作用域未提供 svc → get 返回 null(不继承父作用域 label)
        assertThat(child.<String>get("svc")).isNull();
        // 父作用域仍可见
        assertThat(root.<String>get("svc")).isEqualTo("parent");
    }
}
```

`StateMachineTest.java` — 状态机全转换 + 失败路径:
```java
package dev.dsh.cordis.acceptance;

import dev.dsh.cordis.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StateMachineTest {
    @Test
    void failedPluginOnApplyException() {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.of((ctx, cfg) -> { throw new RuntimeException("boom"); }));
        Throwable t = catchThrowable(() -> f.await().join());
        assertThat(t).isNotNull();
        assertThat(f.state).isEqualTo(FiberState.FAILED);
        assertThat(f.await()).isNotCompletedExceptionally();
    }

    @Test
    void activeThenDisposed() throws Exception {
        Context root = new Context();
        Fiber f = root.plugin(PluginSpec.of((ctx, cfg) -> {}));
        f.await().join();
        assertThat(f.state).isEqualTo(FiberState.ACTIVE);
        f.dispose().join();
        assertThat(f.state).isEqualTo(FiberState.DISPOSED);
    }
}
```

- [ ] **步骤 1:按上述 6 个文件完整实现所有断言(替换任务 4/5/6/7 的占位测试)**
- [ ] **步骤 2:`./gradlew test` — 全绿。若某条失败,回到对应类修正实现(以 `fiber.ts`/`events.ts`/`reflect.ts` 为规格)**
- [ ] **步骤 3:提交** `test: 6 acceptance tests for cordis semantics`

---

### 任务 13:构建验证 + 收尾

- [ ] **步骤 1:`./gradlew clean build` — 预期 BUILD SUCCESSFUL,所有测试通过**
- [ ] **步骤 2:确认 `.gitignore` 生效(`build/` 未入库),`git status` 干净**
- [ ] **步骤 3:提交** `chore: milestone 1 complete (core + java plugins)`

---

## 自我审查(执行前读一遍)

**1. 规范覆盖:** 设计文档 §3.2 文件映射全部覆盖(9 个 TS → 任务 1-9);§3.4/§3.5(Fiber/Events)覆盖;§4 验收 6 条 → 任务 12;QuickStart → 任务 11。缺口:设计文档 §3.3 提到的 `@Inject` 类式注解形态——M1 未做注解扫描,类插件通过重写 `inject()`/`provide()` 方法表达(已在任务 3 注记,属 YAGNI 裁剪,需在执行前向用户确认是否接受)。

**2. 占位符扫描:** 已标注的"占位测试/回填"均为集成依赖所致,有明确的启用任务(任务 10/12)。`EventModesTest.waterfall` 的未实现断言需在任务 12 前校准,不得遗留。

**3. 类型一致性:** `Fiber.inject` 为 `Map<String,Object>`(任务 7)与 `Registry.plugin` 组装一致;`Reflect.Impl.fiber.name()`(任务 4 引)与 `Fiber.name()`(任务 7)一致;`Plugin.Runtime` 定义在 `Plugin.java`(任务 3 追加),Fiber/Registry 引用一致。`Context.logger()` 返回 `Logger`(任务 6)与任务 7 `ctx.logger().error` 调用一致。

**4. 执行顺序:** 遵循任务 0 → 1 → 2 → 3 → 4 → 6 → 7 → 5 → 10 → 9 → 8 → 11 → 12 → 13 的依赖序(见任务 4"执行顺序注记"),避免编译断链。
