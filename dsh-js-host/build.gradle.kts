// dsh-js-host — GraalJS + NodeWorker 桥(m6-design §3):依赖 dsh-cordis + GraalJS/Jackson。
dependencies {
    implementation(project(":dsh-cordis"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("org.graalvm.polyglot:polyglot:24.1.1")
    implementation("org.graalvm.polyglot:js:24.1.1")

    // 集成测试(M5ProfileTest / PluginRuntimeResolverTest)跨层使用 loader/reload:
    // 仅 test 配置反向引用,不进入主依赖图(js-host 主仍只依赖 cordis)。
    testImplementation(project(":dsh-loader"))
    testImplementation(project(":dsh-reload"))
}
