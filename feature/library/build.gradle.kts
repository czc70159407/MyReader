plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.myreader.feature.library"
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:document"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:storage"))
    implementation(project(":core:common"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)   // Description/Lock/Warning 状态图标（release minify 时 R8 裁剪）
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.runtime)      // inMemoryDatabaseBuilder（导入编排测试用真 Repository）
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.compose.ui.test.junit4)   // FolderTreeList Compose UI 测试（版本随 BOM）
    testImplementation(libs.compose.ui.test.manifest)
}
