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

// 安全审计 L5：third-party:mupdf（submodule）以 `androidx.appcompat:appcompat:1.1.+` 动态版本
// 依赖 appcompat——构建不可复现且存在上游坐标投毒风险。在根工程统一 force 到确定版本
// （不改 submodule 内容；appcompat 1.7.0 与上游 API 向后兼容，compileSdk 35 满足其要求）
subprojects {
    configurations.configureEach {
        resolutionStrategy.force("androidx.appcompat:appcompat:1.7.0")
    }
}
