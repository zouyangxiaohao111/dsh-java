// dsh-cordis — 核心(m6-design §3):现有顶层包 dev.dsh.cordis + util/annotation,只依赖 JDK + Jackson。
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
}
