// dsh-testkit — 插件作者测试骨架(M6 §5):仅依赖 dsh-cordis + junit-jupiter-api。
// 外部插件工程 `testImplementation("dev.dsh:dsh-testkit")` 引入 PluginTestKit 基类。
// 纯核心依赖,不引入 loader / js-host / GraalJS —— 插件在 @Test 内 new Context 直接测。
plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":dsh-cordis"))
    api("org.junit.jupiter:junit-jupiter-api:5.11.4")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
