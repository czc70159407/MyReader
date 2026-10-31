import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.myreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.myreader"
        minSdk = 26
        targetSdk = 35
        // 版本与功能里程碑对齐（v1.4 = M11 epub 锚点稳健性 / 比例锚点）；versionCode 随内部分发递增
        versionCode = 3
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release 签名占位：密钥从本地 keystore.properties 读取（不入仓）；
    // 仅当 storeFile 指向的密钥库实际存在时才挂 signingConfig，否则 release 构建为未签名 APK（AGP 无空签名回退，直接挂会失败）
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }
    val storeFilePath = keystoreProperties.getProperty("storeFile")
    val hasReleaseSigning = keystorePropertiesFile.exists() &&
        storeFilePath != null && rootProject.file(storeFilePath).exists()

    // 安全审计 M5：无签名配置时本地仅警告（保持无密钥环境可构建）；
    // CI/发布构建传 -Pmyreader.requireReleaseSigning=true 强制失败，防误发未签名包
    if (!hasReleaseSigning) {
        if (project.findProperty("myreader.requireReleaseSigning") == "true") {
            throw GradleException(
                "release 签名配置缺失：请在仓库根提供 keystore.properties" +
                    "（storeFile/storePassword/keyAlias/keyPassword，不入仓）"
            )
        }
        logger.warn("release 未配置签名，assembleRelease 将产出未签名 APK（CI 请传 -Pmyreader.requireReleaseSigning=true 强制校验）")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // ABI 策略：debug 保留 x86_64（模拟器）+ arm64-v8a（真机）
            ndk {
                abiFilters += setOf("arm64-v8a", "x86_64")
            }
        }
        release {
            // 安全审计 M3：开启 R8 代码收缩 + 资源裁剪（此前 isMinifyEnabled=false，
            // proguardFiles 声明不生效，发布包可被直接反编译）；keep 规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            // ABI 策略：release 限 arm64-v8a + armeabi-v7a（减体积）
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)   // DatabaseModule 组装层直接构建 Room 数据库（无需 compiler）
    implementation(libs.datastore.preferences)   // RepositoryModule 提供 DataStore 实例（M6 排序偏好）

    // 数据层（M2）：Repository 接口 + Room；feature 层将只依赖这些 core 接口
    api(project(":core:data"))
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:document"))
    implementation(project(":core:storage"))

    // M3：书架 + MuPDF 引擎桥接（:app 仅做 DI 多绑定，不接触 MuPDF 具体类）
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:viewer-mupdf"))
    implementation(project(":feature:viewer-txt"))   // M9-F：TXT 引擎多绑定（app 仅 DI，不接触实现细节）

    debugImplementation(libs.compose.ui.tooling)

    // 真机验收插桩（M9 review）：业务层端到端，绕过 SAF UI 的稳定性复验
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.junit)
}
