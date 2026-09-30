# 取件助手

原生 Kotlin Android 取件码管理应用，支持 Android 8.0 及以上，MIT 许可证。当前版本 **0.3.0**，应用的 11 个模块已全部迁移至 Kotlin。

[下载 APK](https://github.com/syczk301/pickup-assistant/releases/download/v0.3.0/pickup-assistant-0.3.0.apk) · [发布版本](https://github.com/syczk301/pickup-assistant/releases) · [源码](app/src/com/local/pickup)

## 功能

- 短信识别取件码、粘贴提取、手动添加、编辑与删除。
- 待取和已取管理、搜索筛选、统计、提醒、小组件。
- JSON 导入导出；短信与取件记录在本机处理。
- GitHub 在线更新，安装前校验大小、SHA-256、包名、版本及签名。

迁移保持原包名、签名、SharedPreferences 名称和 JSON 格式，可直接升级原 Java 版本并保留数据。界面继续使用原生 View。

## 构建

`app/` 包含 Kotlin 源码、XML 资源、Gradle 配置、SDK 构建脚本和测试。

Windows 构建脚本需要 Python 3、JDK 17、Android SDK Platform 35、Build Tools 35.0.0、[Kotlin 命令行编译器 2.1.20](https://github.com/JetBrains/kotlin/releases/tag/v2.1.20)，以及 [D8/R8 8.7.18](https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar)。Kotlin 2.1 需要 D8/R8 8.6 或更新版本。

设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`、`KOTLIN_HOME`（含 bin/lib 的 kotlinc 目录）和 `D8_JAR`（R8 JAR 完整路径），在仓库根目录执行：

```powershell
python app\build.py
```

APK 输出到 `deliverables/`。Android Studio 可导入 `app/` 的 Gradle 项目；本次使用 SDK 脚本构建验证。

签名密钥未公开。自行构建时脚本创建开发密钥，生成的 APK 可新安装；更新已有发布版本必须使用原发布密钥。

## 测试

完成构建后，设置 `JSON_TEST_JAR` 为 [org.json:json:20240303](https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar) 的 JAR 路径，再运行：

```powershell
python app\test.py
```

原 Java 测试保留为调用 Kotlin 的回归测试：17 项短信解析、15 项更新协议。Kotlin `MigrationTest.kt` 验证旧 JSON 字段、默认值、往返与无效记录拒绝。Android 应用使用系统 JSON API。

模拟器辅助工具为 `tests/runtime_qa.py`（支持 `ADB_PATH`、`ANDROID_SERIAL`）和 `FillText.java`，不进入安装包。API 26 已验证 Java 版本数据保留，以及 Kotlin 版新增、状态切换、粘贴识别、短信接收、通知、统计、深色模式和 JSON 备份，以及 Kotlin 更新模块从 GitHub 下载、校验、系统安装 0.3.0 后保留全部 5 条记录。

## 在线更新

在“设置 → 应用更新 → 检查更新”中下载安装。默认按 24 小时间隔在进入应用时检查，可关闭或修改 HTTPS 地址。首次安装更新需允许本应用安装，并由用户在系统界面确认。

默认版本文件：https://github.com/syczk301/pickup-assistant/releases/latest/download/update.json

后续发布时增加 Manifest 和 Gradle 中的版本号，使用原密钥构建，创建正式 GitHub Release，上传 APK 与 `update.json` 并设为最新。

版本文件包含 `schemaVersion`（1）、`packageName`（`com.local.pickup`）、`versionCode`、`versionName`、`minSdk`、`apkUrl`（HTTPS）、`sizeBytes`、`sha256` 和 `releaseNotes`，文件大小与摘要取自实际 APK。
