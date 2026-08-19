package dev.dsh.host.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M10-1 dev 模式纯库(clientLibrary)resolve 回归。
 *
 * <p>dev 管线的 client bundle(tsdown watch 重建的 {@code lib/client.js})是浏览器经
 * {@code /plugins/<id>/client.js} 实际 fetch 的产物;它们的 static external require 一律
 * 走 shell 注入的冻结模块表({@code window.__ModuleLoader__}),resolve 顺序为种子词 →
 * 静态注册 → 已注册 factory(含 {@code /client} 后缀规范化)。clientLibrary 包
 * (ui-slots/ui-primitives/schema-form/ui-attachment/web-react)无 {@code lib/client.js}
 * (tsdown clientLibrary 面只产 node 半),但作为<b>平台种子词</b>被 shell 静态导入进模块表,
 * 无需 bundle 即可被其它 client bundle require —— 这就是 M10-1 DevPipeline 阶段确认的
 * "天然解,无需 serve 端补模块"。本测试把该边界固化成可自动回归的 Java 侧检查。
 *
 * <p>单一来源(上游 vendor/dsh,测试 pin 住契约防漂移):
 * <ul>
 *   <li>平台种子词:<code>vendor/dsh/packages/client/web/src/platform.ts</code> 的
 *       {@code PLATFORM_MODULES}(tsdown {@code CLIENT_EXTERNALS = [...PLATFORM_MODULES, ...]})。</li>
 *   <li>图行:每个产 {@code lib/client.js} 的 client 包 = 一个 clientBundle 图行(行 id = 包名)。</li>
 *   <li>规范化:loader 的 {@code stripClientSuffix} 把 {@code <id>/client} 归一到行 id
 *       (clientBundle 包对其它包的 {@code /client} subpath require 即此路径)。</li>
 * </ul>
 *
 * <p>前置:client bundle 已构建(scripts/setup.sh 或 build:lib:client);缺 → 跳过
 * (与 RealDshWebProfileTest 同模式)。无 boot、无 HTTP —— 纯磁盘扫描,快且确定。
 */
class DevClientBundleResolutionTest {

    /** 平台种子词(冻结模块表键)—— 单一来源:vendor/dsh/packages/client/web/src/platform.ts 的 PLATFORM_MODULES。 */
    private static final Set<String> PLATFORM_SEED = Set.of(
            "react",
            "react/jsx-runtime",
            "react-dom",
            "react-dom/client",
            "@deepseek-ai/cordis",
            "@deepseek-ai/dsh-client-ui-slots",
            "@deepseek-ai/dsh-client-web-react",
            "@deepseek-ai/dsh-client-ui-primitives",
            "@deepseek-ai/dsh-client-ui-attachment",
            "@deepseek-ai/dsh-client-schema-form");

    /** clientLibrary 纯库包(有 lib/index.js、无 lib/client.js;tsdown clientLibrary 面只产 node 半)。 */
    private static final List<String> PURE_LIBRARIES = List.of(
            "ui-slots", "web-react", "ui-primitives", "ui-attachment", "schema-form");

    /** web 包是 shell 自身(承载种子表、被 vite 打进前端壳),不是种子词、也不是 bundle 图行。 */
    private static final String WEB_SHELL = "web";

    /** client 包目录 → 图行 id(@deepseek-ai/dsh-client-<dir>)。 */
    private static String packageId(String dir) {
        return "@deepseek-ai/dsh-client-" + dir;
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"), "..").toAbsolutePath().normalize();
    }

    private static Path clientDir() {
        return repoRoot().resolve("vendor/dsh/packages/client");
    }

    private static boolean hasBundle(Path pkgDir) {
        return Files.isRegularFile(pkgDir.resolve("lib/client.js"));
    }

    private static boolean hasLib(Path pkgDir) {
        return Files.isRegularFile(pkgDir.resolve("lib/index.js"));
    }

    private static List<String> subDirs(Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            return children.map(p -> p.getFileName().toString())
                    .filter(name -> !name.startsWith("."))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * loader 的 {@code stripClientSuffix}:client bundle 的 {@code <id>/client} subpath
     * 与裸图行 id 指向同一份 exports,查表时归一后缀。
     */
    private static String stripClientSuffix(String spec) {
        return spec.endsWith("/client") ? spec.substring(0, spec.length() - "/client".length()) : spec;
    }

    @Test
    void pureLibrariesArePlatformSeedsWithoutBundles() throws Exception {
        Path client = clientDir();
        assumeTrue(hasBundle(client.resolve("runtime")),
                "client bundles not built — run ./setup.sh (or build:lib:client) first");

        // 每个 clientLibrary 纯库:lib/index.js 存在(可被 shell 静态导入)→ 且无 lib/client.js
        // (tsdown clientLibrary 面不产 client bundle,图行里没有它们)。这 5 个全部是平台种子词。
        for (String pure : PURE_LIBRARIES) {
            Path pkg = client.resolve(pure);
            assertThat(hasLib(pkg))
                    .as("%s must ship its node-half lib (the shell statically imports it as a seed)", pure)
                    .isTrue();
            assertThat(hasBundle(pkg))
                    .as("%s is a clientLibrary (no lib/client.js) — it resolves via the platform seed, not a bundle row", pure)
                    .isFalse();
            assertThat(PLATFORM_SEED)
                    .as("clientLibrary %s must be a platform seed word (tsdown CLIENT_EXTERNALS pins it external)", packageId(pure))
                    .contains(packageId(pure));
        }

        // web 是 shell 自身:vite 打进前端壳,既不是种子词也非 bundle 图行(但它承载种子表)。
        assertThat(PLATFORM_SEED).as("web shell is not a module-table word").doesNotContain(packageId(WEB_SHELL));
        assertThat(hasBundle(client.resolve(WEB_SHELL))).as("web shell has no client bundle row").isFalse();
    }

    /**
     * 核心不变量:dev 浏览器 fetch 的每个 client bundle,其全部 static external require
     * 都必须落在 {@code __ModuleLoader__} 模块表可 resolve 的集合里 ——
     * 种子词 ∪ 图行 id(含 /client 规范化)。任一 external miss 都会在 materialize 时
     * 抛 "missed the module table",该 ui-* 插件就永远进不了 active(因 slots 缺 pending)。
     */
    @Test
    void everyClientBundleExternalResolvesInTheModuleTable() throws Exception {
        Path client = clientDir();
        assumeTrue(hasBundle(client.resolve("runtime")),
                "client bundles not built — run ./setup.sh (or build:lib:client) first");

        List<String> graphRows = new ArrayList<>();
        for (String dir : subDirs(client)) {
            if (hasBundle(client.resolve(dir))) graphRows.add(packageId(dir));
        }

        // 可 resolve 集合 = 种子词 ∪ 图行 id ∪ 图行 id/client(loader 归一后缀)
        Set<String> resolvable = new TreeSet<>(PLATFORM_SEED);
        for (String row : graphRows) {
            resolvable.add(row);
            resolvable.add(row + "/client");
        }

        // dev-web 只 watch 声明 dsh.client 的 clientBundle 包 —— 扫描全部产 bundle 的包
        // 正是浏览器 fetch 的全集(含 dev patch 层解 pin 的 client-hmr)。
        Pattern external = Pattern.compile("require\\(\"([^\"]*)\"\\)");
        List<String> misses = new ArrayList<>();
        for (String dir : subDirs(client)) {
            Path bundle = client.resolve(dir).resolve("lib/client.js");
            if (!Files.isRegularFile(bundle)) continue;
            String body = Files.readString(bundle);
            Matcher m = external.matcher(body);
            while (m.find()) {
                String spec = m.group(1);
                if (spec.contains("${")) continue; // 动态 spec(loader 错误消息里的模板字面量),非 static external
                if (!resolvable.contains(spec) && !resolvable.contains(stripClientSuffix(spec))) {
                    misses.add(dir + "/lib/client.js  require(\"" + spec + "\")");
                }
            }
        }
        assertThat(misses)
                .as("every client bundle external must be resolvable by __ModuleLoader__ (seed word ∪ graph row ∪ /client); "
                        + "a miss here means the bundle throws on materialization and its ui-* plugin stays pending")
                .isEmpty();

        // 该不变量不是空的:至少 4 个 bundle 确实 external require ui-slots(纯库种子)——
        // 这就是 M10-1 之前 32 个 ui-* pending 的根因路径,现在由种子词天然解。
        List<String> slotsRequiringBundles = new ArrayList<>();
        for (String dir : subDirs(client)) {
            Path bundle = client.resolve(dir).resolve("lib/client.js");
            if (Files.isRegularFile(bundle) && Files.readString(bundle).contains("require(\"@deepseek-ai/dsh-client-ui-slots\")")) {
                slotsRequiringBundles.add(dir);
            }
        }
        assertThat(slotsRequiringBundles)
                .as("ui-slots is the pure-lib every clientBundle requires through the seed (the dev-mode resolve path under test)")
                .contains("runtime", "ui-conversation", "ui-settings-general", "ui-settings-plugins");
    }
}
