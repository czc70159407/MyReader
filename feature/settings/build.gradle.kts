plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.myreader.feature.settings"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 偏好读写（SettingsPrefs/LibraryPrefs 在 core:data；ReaderMode/ThemeMode/DocSort 在 core:model）。
    // 依赖 core:data 的理由：书架排序偏好 M6 已落 LibraryPrefs，同源读写避免双存储不一致与同步代码
    // （修订点见 docs/architecture.md「模块依赖关系」）；不依赖 core:document 与任何 viewer，MuPDF 边界不受影响
    implementation(project(":core:data"))
    implementation(project(":core:model"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)   // AutoMirrored.ArrowBack（release minify 时 R8 裁剪）
    implementation(libs.activity.compose)   // 备份/恢复 SAF launcher（CreateDocument/OpenDocument）
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.datastore.preferences)   // 真 DataStore 构造 prefs（纯 JVM File 落盘）

    // 备份测试需构造真实 BackupManager（inMemory Room + 临时 library 目录 + Robolectric 上下文）
    testImplementation(project(":core:storage"))
    testImplementation(libs.robolectric)
    testImplementation(libs.room.runtime)
    testImplementation(libs.androidx.test.core)
}
