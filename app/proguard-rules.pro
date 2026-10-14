# 安全审计 M3：release 混淆规则。注意：AGP 8 默认 R8 full mode，改动后必须
# `.\gradlew.bat :app:assembleRelease` + 真机冒烟（备份恢复 / epub / pdf / txt / 加密文档）再发布。

# ---- MuPDF（JNI 按名称回调 Java 侧，如 MuPDFCore.pageToBookmark/bookmarkToPage）----
-keep class com.artifex.mupdf.** { *; }
-dontwarn com.artifex.mupdf.**

# ---- kotlinx.serialization（BackupCodec DTO / EpubProgressCodec payload 反射式序列化）----
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.myreader.**$$serializer { *; }
-keepclassmembers class com.myreader.** {
    *** Companion;
}
-keepclasseswithmembers class com.myreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 崩溃栈可读性：保留行号与源文件名 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
