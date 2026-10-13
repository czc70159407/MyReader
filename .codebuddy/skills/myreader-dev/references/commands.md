# MyReader 命令清单与环境卡点

> Windows / PowerShell。所有 Gradle 命令一律 `.\gradlew.bat`（非 `gradle`）。

## 构建 / 测试
```powershell
.\gradlew.bat build                              # 全量构建 + 测试
.\gradlew.bat :core:data:test                    # 数据层单测
.\gradlew.bat :app:assembleDebug                 # 装配 APK（产物 app/build/outputs/apk/debug/app-debug.apk）
.\gradlew.bat :third-party:mupdf:assembleDebug   # 构建 MuPDF viewer 库（fitz 走官方 Maven 预编译，无 native 编译）
```

## 运行 / 真机验收
```powershell
# 安装 debug APK（必须带 -t，否则部分设备因 testOnly 拒装）
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
# 过滤 logcat（应用包名 + 错误）
adb logcat -s "com.myreader:*" "*:E"
# 杀进程重启验证偏好/进度持久化
adb shell am force-stop com.myreader
```

## 验收测试 PDF（tools/test_pdfs.py）
```powershell
python -m pip install pypdf reportlab          # 首次需安装依赖
python tools/test_pdfs.py generate              # 生成夹具到 tools/fixtures/（已 gitignore）
python tools/test_pdfs.py verify                # 离线断言页数/大纲/加密/损坏
python tools/test_pdfs.py selftest              # 临时目录生成并立即校验
```
夹具：`demo_test.pdf`(3 页) / `outline_test.pdf`(12 页+两级大纲) / `corrupt_test.pdf`(损坏) /
`encrypted_test.pdf`(密码 1234) / `long_test.pdf`(40 页)。

## Git
```powershell
git submodule update --init        # 克隆后初始化 MuPDF submodule（勿加 --recursive）
# git 不在 PATH，需全路径 D:\develop\Git\bin\git.exe 或临时加 PATH
```

## 已知卡点（勿误删）
- Java TLS 访问 services.gradle.org 被干扰：已预置 gradle 缓存绕过目录
  `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9`，
  **勿删**，否则需重新绕过。
- Android SDK：`D:\develop\Android\Sdk`（`local.properties` 已指向，`ANDROID_HOME` 用户级已设）。
- MuPDF 集成：fitz 官方 Maven `maven.ghostscript.com` 可达且 AAR 含全 4 ABI，免 NDK 编译。
