pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF 官方 Maven 仓库：fitz 预编译 AAR（含全 ABI libmupdf_java.so，免 NDK 源码编译）
        maven {
            url = uri("https://maven.ghostscript.com/")
            content {
                includeGroup("com.artifex.mupdf")
            }
        }
    }
}

rootProject.name = "MyReader"

include(":app")

// MuPDF viewer 库（git submodule，锁定 1.28.x commit；其 jni/ 子模块刻意不初始化 → lib 走官方 Maven fitz 预编译分支）
include(":third-party:mupdf")
project(":third-party:mupdf").projectDir = file("third-party/mupdf-android-viewer/lib")
