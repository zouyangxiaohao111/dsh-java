package dev.dsh.cordis.js;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class JsHostTest {
    @Test
    void requireCommonJsModuleFile() throws Exception {
        // 写一个临时 CJS 模块文件,经 require 加载
        Path dir = Files.createTempDirectory("dsh-js-host");
        Path mod = dir.resolve("greeter.js");
        Files.writeString(mod, "module.exports = { greet: (n) => 'hi ' + n }");
        try (JsHost host = new GraalJsHost(dir)) {
            Value exports = host.loadModule(mod);
            Value greet = exports.getMember("greet");
            assertThat(greet.canExecute()).isTrue();
            assertThat(greet.execute("bob").asString()).isEqualTo("hi bob");
        }
    }
}
