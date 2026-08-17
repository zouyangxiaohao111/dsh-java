package dev.dsh.cordis.reload;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    /**
     * 编译一个源码目录下的全部 {@code .java} 文件(M6-7 {@code java:} 目录源;javac 一次
     * 接收全部源文件,互相引用一并编译)。目录下无源文件 → 报错;编译失败抛出并保留输出目录
     * 现状(调用方负责清理/隔离)。
     */
    public Path compileTree(Path sourceRoot, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            List<String> sources = walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .map(Path::toString).toList();
            if (sources.isEmpty()) {
                throw new IllegalStateException("no .java source files under plugin source dir: " + sourceRoot);
            }
            String classpath = System.getProperty("java.class.path");
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            List<String> args = new ArrayList<>();
            args.add("-d"); args.add(outputDir.toString());
            args.add("-cp"); args.add(classpath);
            args.addAll(sources);
            int rc = javac.run(null, null, null, args.toArray(String[]::new));
            if (rc != 0) throw new IllegalStateException("plugin compilation failed: " + sourceRoot);
            return outputDir;
        }
    }
}
