// dsh-host — 聚合(m6-design §3):依赖全部模块,一键引入整个 harness。
dependencies {
    implementation(project(":dsh-cordis"))
    implementation(project(":dsh-js-host"))
    implementation(project(":dsh-reload"))
    implementation(project(":dsh-loader"))
}
