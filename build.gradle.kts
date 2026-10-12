// 根构建文件：仅声明插件（apply false），各模块按需应用
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

// 第三方 MuPDF viewer 源码（submodule）自带 lint 报错（上游 minSdk 21 与 API 29 调用冲突），
// 属上游代码风格问题：不对第三方源码做 lint，也不修改 submodule 内容
subprojects {
    if (path == ":third-party:mupdf") {
        afterEvaluate {
            tasks.configureEach {
                if (name.startsWith("lint")) {
                    enabled = false
                }
            }
        }
    }
}
