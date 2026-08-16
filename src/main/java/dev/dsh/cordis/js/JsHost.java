package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import java.nio.file.Path;

/** JS 插件运行时抽象(M2 深化 §3.5)。GraalJS 实现 + 未来 Node worker 实现。 */
public interface JsHost extends AutoCloseable {
    Value eval(String script);
    Value require(String specifier);
    Value loadModule(Path file);
    org.graalvm.polyglot.Context graalContext();   // 桥互操作入口(GraalJS 专属)
    @Override void close();
}
