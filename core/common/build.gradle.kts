plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // 纯 JVM 模块只依赖 core；Main dispatcher 的 Android actual 由 :app 的 coroutines-android 在运行时提供
    implementation(libs.coroutines.core)
}
