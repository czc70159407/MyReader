plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)   // M9 EpubProgressCodec（cfi 进度 payload 序列化）
}

android {
    namespace = "com.myreader.core.document"
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
    val composeBom = platform(libs.compose.bom)
    api(composeBom)                      // DocumentViewerProvider 的 @Composable/ImageBitmap 契约对外可见
    api(libs.compose.ui)
    api(project(":core:model"))          // ReaderMode（M7 迁入 core:model，偏好层与渲染层共用）
    implementation(libs.coroutines.android)
    implementation(libs.javax.inject)    // FileTypeKey @Qualifier
    implementation(libs.dagger)          // FileTypeKey @MapKey
    implementation(libs.kotlinx.serialization.json)   // M9 EpubProgressCodec（纯逻辑 payload 编解码）

    // FileTypesTest 等纯逻辑单测（无 Android/Robolectric 依赖）
    testImplementation(libs.junit)
}
