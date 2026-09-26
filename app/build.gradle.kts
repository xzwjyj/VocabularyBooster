// app — Android 壳模块（ARCHITECTURE §1：UI + 平台装配，零业务逻辑）
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.vocabularybooster.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vocabularybooster"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // 本地 JVM 单测：未 mock 的 android.jar 方法（android.util.Log 等）返回默认值而非抛异常
    // （WordPronouncer 失败路径仅记日志——测试关注协程不崩溃，不测日志本身）
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Kotlin↔Java 字节码版本一致（AGP compileOptions 17 ↔ Kotlin jvmTarget 17）
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.datetime) // Phase 6：shared 域模型公开 API 暴露 Instant（Achievement.earnedAt 等），app main 消费需在编译类路径（与既有 test 源集同版本目录项）

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.kotlinx.coroutines.test) // ViewModel 测试：Dispatchers.setMain + 虚拟时间（Phase 4 Step 4）
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.sqldelight.android.driver)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.kotlinx.datetime)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// app 模块合法使用 Android API → ForbiddenImport 关闭（detekt-common.yml 仅用于 :shared）
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt-app.yml"))
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}
