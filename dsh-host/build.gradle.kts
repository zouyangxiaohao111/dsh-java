// dsh-host — 聚合(m6-design §3):依赖全部模块,一键引入整个 harness。
// M6-4 应用层:CLI ./dshj(application 插件,mainClass = dev.dsh.host.cli.DshCli)。
plugins {
    application
}

dependencies {
    implementation(project(":dsh-cordis"))
    implementation(project(":dsh-js-host"))
    implementation(project(":dsh-reload"))
    implementation(project(":dsh-loader"))
}

application {
    mainClass = "dev.dsh.host.cli.DshCli"
}

tasks.named<JavaExec>("run") {
    // CLI 以仓库根为工作目录:profiles/ 与 vendor/dsh 都相对仓库根解析。
    // 也让 ./dshj 的 `--args` 里的相对路径(user 输入)以仓库根为基准。
    workingDir = rootProject.projectDir
    // 强制 UTF-8 输出:Windows 控制台默认 GBK 会把非 ASCII(如 ——)打成乱码。
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
