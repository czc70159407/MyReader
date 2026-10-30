plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.myreader.core.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // M10：MigrationTest（androidTest）运行器
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // MigrationTestHelper 从 androidTest assets 读取 schema JSON（1.json / 2.json）
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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

// Room schema JSON 导出（随仓库提交，见工作规则 3）
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":core:storage"))
    implementation(project(":core:document"))   // M9 备份恢复需校验 fileType 白名单（单一事实源 = FileTypes）

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)   // M8 备份快照序列化
    implementation(libs.javax.inject)    // PrefsStoreQualifiers @Qualifier（与 core:document FileTypeKey 同款）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)

    // M10：Room MigrationTest（真机/模拟器插桩，MigrationTestHelper 校验 v1→v2 schema）
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
