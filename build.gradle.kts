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
    alias(libs.plugins.detekt) apply false   // T7 静态检查（第一轮报告不阻断，见 CI build.yml）
    alias(libs.plugins.kover) apply false    // T7 覆盖率报告
}

// T7 质量工具链：业务模块应用 detekt（third-party:mupdf 为上游源码，噪音大不扫——纪律同 lint 禁用）；
// kover 全模块（仅 Kotlin，对 Java 模块天然 no-op）。第一轮「仅报告不阻断」：
// detekt 违规不挂 check，CI 中 continue-on-error 收集存量基线，第二轮按清单清理后再收紧
subprojects {
    if (path != ":third-party:mupdf") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
    }
    apply(plugin = "org.jetbrains.kotlinx.kover")
}

subprojects {
    if (path != ":third-party:mupdf") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            parallel = true
            // T7 第二轮（已收紧）：自定义豁免 + baseline 渐进清理存量（新代码违规即失败，
            // 挂 check 随 build 阻断）。多模块各持独立 baseline（官方惯例，合并单文件会相互覆盖）；
            // 存量削减：修一条从模块 baseline.xml 删一条，`detektBaseline` 重新生成
            config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
            baseline = file("$projectDir/detekt-baseline.xml")
        }
    }
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
