// dsh-java — 根聚合工程(M6-1 多模块拆分)。
//
// 公共配置(Java 25 toolchain / JUnit / AssertJ / testLogging)在 subprojects 下沉到各模块,
// 各模块在自身 build.gradle.kts 声明模块特有依赖。模块图(m6-design §3):
//   dsh-cordis(只依赖 JDK + Jackson)← dsh-js-host(GraalJS)← dsh-reload ← dsh-loader ← dsh-host
plugins {
    java
}

group = "dev.dsh"
version = "0.1.0"

subprojects {
    apply(plugin = "java")

    group = "dev.dsh"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.11.4"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testImplementation("org.assertj:assertj-core:3.27.3")
    }

    tasks.test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = true
        }
    }
}
