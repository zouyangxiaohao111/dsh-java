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
