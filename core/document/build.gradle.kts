plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
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
}
