// dsh-cordis — 核心(m6-design §3):现有顶层包 dev.dsh.cordis + util/annotation,只依赖 JDK + Jackson。
// M6-2 发布:dev.dsh:dsh-cordis:0.1.0 → mavenLocal。jackson-databind 升为 api:
// ConfigValidator 公共面暴露 JsonNode,外部插件作者写 config validator 需编译期可见。
plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
