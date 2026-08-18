// dsh-loader — 配置驱动加载(m6-design §3):依赖 dsh-cordis + dsh-js-host + dsh-reload。
dependencies {
    implementation(project(":dsh-cordis"))
    implementation(project(":dsh-js-host"))
    implementation(project(":dsh-reload"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    // M7-5:patch YAML 的 !!js 标记解析用 SnakeYAML 自定义构造器(Jackson YAMLFactory 丢掉
    // 显式 tag,无法识别 !!js)。版本与 jackson-dataformat-yaml 2.18.2 传递依赖一致。
    implementation("org.yaml:snakeyaml:2.3")

    // PluginLoaderServiceTest 直接使用 GraalJS API(await JS Promise);引擎 jar 经
    // dsh-js-host 传递到运行时,这里只补 test 编译期的 API。
    testImplementation("org.graalvm.polyglot:polyglot:24.1.1")
}
