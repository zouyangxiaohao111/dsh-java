package dev.dsh.host.cli;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.js.JsHostFactory;
import dev.dsh.cordis.js.PluginRuntimeResolver;
import dev.dsh.cordis.loader.DshProfileReader;
import dev.dsh.cordis.loader.Entry;
import dev.dsh.cordis.loader.LoadedPlugin;
import dev.dsh.cordis.loader.PluginLoaderService;
import dev.dsh.cordis.reload.UrlPluginClassLoaderFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用层 profile boot(m6-design §4):解析 profile 目录 → 经 {@link PluginLoaderService}
 * 加载 cordis.yml → 注册进 root Context → 持有句柄供 CLI 阻塞运行。
 *
 * <p>profile 解析镜像 dsh:{@code $DSH_HOME/profiles/<name>}({@code DSH_HOME} 未设时回退
 * {@code <repo>/profiles})。{@code vendor/dsh} 子模块 node_modules 与 profile 自身 node_modules
 * 作为 Node worker 的裸模块解析基址(bareModuleBaseUrl 等价物,见 dsh-js-host NodeWorkerJsHost
 * 的 {@code moduleBases} seam)。M6-4 只启动 Java harness 插件树(dsh 完整 web/headless bundle
 * 是 M6-5/6)。
 */
public final class ProfileBoot {

    /** boot 失败(profile 不存在等),run 侧打印并返回 1。 */
    public static final class BootException extends RuntimeException {
        public BootException(String message) {
            super(message);
        }
    }

    /**
     * 一次成功 boot 的句柄:root Context + loader + 已加载插件,close 时 dispose。
     * 对 Java harness 而言 root fiber 是整棵插件树的宿主,dispose 即卸载全部。
     */
    public record Handle(Context ctx, PluginLoaderService loader, List<LoadedPlugin> loaded, Path yml)
            implements AutoCloseable {

        @Override
        public void close() {
            try {
                loader.dispose();
            } finally {
                ctx.fiber.dispose().join();
            }
        }
    }

    private final Path profilesRoot;
    private final Path repoRoot;

    public ProfileBoot() {
        this(defaultProfilesRoot(), defaultRepoRoot());
    }

    /** 测试 seam:显式指定 profile 根与仓库根(不依赖 user.dir / DSH_HOME)。 */
    public ProfileBoot(Path profilesRoot, Path repoRoot) {
        this.profilesRoot = profilesRoot.toAbsolutePath().normalize();
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    public Path profilesRoot() {
        return profilesRoot;
    }

    /**
     * 解析 profile 根:DSH_HOME(未设回退 repo/profiles),镜像 dsh 的 {@code resolveDshHome}。
     * M6-7 安装器复用同一解析,保证 {@code plugin add} 写的位置与 {@code boot} 读的一致。
     */
    public static Path defaultProfilesRoot() {
        String home = System.getenv("DSH_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home).resolve("profiles");
        }
        return defaultRepoRoot().resolve("profiles");
    }

    /** CLI 经 gradle run 以仓库根为工作目录(user.dir = 仓库根,见 dsh-host build)。 */
    static Path defaultRepoRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    /**
     * 加载一个 profile 的插件树(不阻塞;调用方持有句柄并决定运行/关闭)。
     *
     * @param profile profile 名(web/headless/cli 或任意 {@code profiles/<name>})
     * @param out     进度输出流
     * @return boot 句柄
     * @throws BootException profile 目录/配置不存在
     */
    public Handle bootOnce(String profile, PrintStream out) {
        if (profile == null || profile.isBlank()) {
            throw new BootException("--profile needs a name");
        }
        Path profileDir = profilesRoot.resolve(profile);
        Path yml = profileDir.resolve("cordis.yml");
        if (!Files.isRegularFile(yml) && !DshProfileReader.isDshProfile(profileDir)) {
            throw new BootException("profile '" + profile + "' not found under " + profilesRoot
                    + " (create " + profileDir + "/cordis.yml, a dsh profile package.json with"
                    + " dsh.profile.bundles, or set DSH_HOME)");
        }
        // 裸模块解析基址(seam):vendor/dsh 子模块 node_modules + profile node_modules
        List<Path> bases = new ArrayList<>();
        addIfDirectory(bases, repoRoot.resolve("vendor/dsh/node_modules"));
        addIfDirectory(bases, profileDir.resolve("node_modules"));

        Context root = new Context();
        PluginLoaderService loader = new PluginLoaderService(root,
                new PluginRuntimeResolver(new JsHostFactory(bases)),
                new UrlPluginClassLoaderFactory(), outputDir(), new JsHostFactory(bases));
        try {
            List<LoadedPlugin> loaded;
            if (Files.isRegularFile(yml)) {
                // M6-4 起始形状:cordis.yml(Java harness 插件树)
                loaded = loader.load(yml);
                out.println("dshj: profile '" + profile + "' booted (" + yml + "):");
            } else {
                // M6-6 dsh profile:读 manifest → 组合 bundle patch 层 → entries → loader 加载。
                // bundle 第一 anchor = vendor/dsh 安装(package.json);缺则仅 profile 自身。
                Path installAnchor = repoRoot.resolve("vendor/dsh/package.json");
                List<Entry> entries = new DshProfileReader().load(profileDir, installAnchor);
                loaded = loader.loadEntries(entries, profileDir);
                out.println("dshj: profile '" + profile + "' booted (dsh profile " + profileDir + "):");
            }
            for (LoadedPlugin lp : loaded) {
                out.println("  - " + lp.entry().name() + "  [" + lp.kind() + "]  " + lp.ref());
            }
            if (!bases.isEmpty()) {
                out.println("dshj: node module bases: " + bases);
            }
            return new Handle(root, loader, loaded, yml);
        } catch (Exception e) {
            try {
                loader.dispose();
            } finally {
                root.fiber.dispose().join();
            }
            throw new BootException("boot failed for profile '" + profile + "': " + e.getMessage());
        }
    }

    private static void addIfDirectory(List<Path> out, Path p) {
        if (Files.isDirectory(p)) out.add(p.toAbsolutePath().normalize());
    }

    private static Path outputDir() {
        try {
            return Files.createTempDirectory("dshj-out");
        } catch (IOException e) {
            throw new BootException("cannot create plugin output dir: " + e.getMessage());
        }
    }
}
