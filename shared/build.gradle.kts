// shared — KMP 核心模块（ARCHITECTURE §1：全部业务逻辑在此，100% 纯 Kotlin）
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.detekt)
}

val isMacOsHost = System.getProperty("os.name").lowercase().contains("mac")

kotlin {
    androidTarget()

    // Desktop JVM target：无设备/无模拟器即可在 Windows 上跑全部核心回归（ADR-04）
    jvm()

    // iOS targets：仅 macOS 宿主启用（链接需要 Apple 工具链，见 ARCHITECTURE §8）
    if (isMacOsHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    // 边界契约必须显式声明（ARCHITECTURE §4 铁律 2）
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.extensions)
                implementation(libs.koin.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.sqldelight.android.driver)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.koin.android)
            }
        }
        if (isMacOsHost) {
            iosMain {
                dependencies {
                    implementation("app.cash.sqldelight:native-driver:${libs.versions.sqldelight.get()}")
                }
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

android {
    namespace = "com.vocabularybooster.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin↔Java 字节码版本一致（AGP compileOptions 17 ↔ Kotlin jvmTarget 17）
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// DATABASE_SCHEMA §1：数据库名 VocabularyDatabase；.sq 位于 commonMain/sqldelight
sqldelight {
    databases {
        create("VocabularyDatabase") {
            packageName.set("com.vocabularybooster.db")
        }
    }
}

// detekt 只扫 commonMain/commonTest（androidMain 允许平台 import，由 KMP 编译本身守护）
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt-common.yml"))
    source.setFrom(files("src/commonMain/kotlin", "src/commonTest/kotlin"))
}

// 架构边界守护（ARCHITECTURE §4 铁律 1）：commonMain/commonTest 不得引用 android.* / java.* /
// dalvik.* / javax.* / com.apple.*。detekt ForbiddenImport 之外的硬性 Gradle 门禁。
val checkPlatformBoundaries by tasks.registering {
    val roots = listOf("src/commonMain/kotlin", "src/commonTest/kotlin").map { file(it) }
    val forbidden = Regex("""\b(android|java|dalvik|javax|com\.apple)\.""")
    doLast {
        val violations = buildList {
            roots.filter { it.isDirectory }.forEach { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { f ->
                        f.readLines().forEachIndexed { i, raw ->
                            val line = raw.trim()
                            if (line.startsWith("//") || line.startsWith("*") ||
                                line.startsWith("/*")
                            ) {
                                return@forEachIndexed
                            }
                            if (forbidden.containsMatchIn(line)) {
                                add("${f.relativeTo(projectDir).path.replace('\\', '/')}:${i + 1}: $line")
                            }
                        }
                    }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Platform-API boundary violations in commonMain/commonTest (ARCHITECTURE §4):\n" +
                    violations.joinToString("\n")
            )
        }
    }
}
tasks.named("check") { dependsOn(checkPlatformBoundaries) }
