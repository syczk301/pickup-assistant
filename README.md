# 取件助手

原生 Kotlin Android 应用，包名 `com.local.pickup`，最低 Android 8.0。当前版本 0.4.4，版本号 9，MIT 许可证。

应用的 11 个模块均使用 Kotlin；短信和取件记录在本机处理。支持短信提取、粘贴识别、记录管理、统计、提醒、小组件、JSON 备份和 GitHub 在线更新。

## 构建

需要 Python 3、JDK 17、Android SDK Platform 35、Build Tools 35.0.0、Kotlin 命令行编译器 2.1.20，以及 D8/R8 8.6 或更新版本。本次使用 R8 8.7.18。

设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`、`KOTLIN_HOME`（包含 bin/lib 的 kotlinc 目录）和 `D8_JAR`（r8.jar 的完整路径），运行 `python app\build.py`。APK 输出到上一级 `deliverables/`。

编译器：[Kotlin 2.1.20](https://github.com/JetBrains/kotlin/releases/tag/v2.1.20)。D8/R8：[8.7.18 JAR](https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar)。Android Studio 可导入 Gradle 配置；本次使用 SDK 脚本构建。

签名文件 `local-debug.jks` 仅保留在本地；后续升级必须使用原发布密钥。自行构建时脚本创建本地开发密钥，可用于新安装，不能更新原发布 APK。

## 测试

`tests/ParserTest.kt` 和 `UpdateProtocolTest.kt` 分别覆盖 17 项短信解析和 25 项更新协议检查。`tests/MigrationTest.kt` 验证旧版 JSON 字段、默认值、往返和无效记录拒绝。测试需要先构建，再设置 `JSON_TEST_JAR` 为 org.json:json:20240303 的 JAR 路径，运行 `python app\test.py`。Android 应用使用系统 JSON API。

测试全部使用 Kotlin。旧版模拟器操作和中文输入辅助脚本已移除。

## 在线更新

源码和发布仓库：https://github.com/syczk301/pickup-assistant

默认通过 GitHub API 直接读取根目录 `update.json`，失败时依次尝试 Raw、jsDelivr 和 Release 入口。旧版保存的内置地址自动走这些通道，自定义地址保持不变。发布新版时需同步根目录版本文件与 Release 资产，确保版本号、大小和 SHA-256 一致。

在设置中检查并下载新版本，安装前校验大小、SHA-256、包名、版本和签名，再由安卓系统确认。原 Java 版本的包名、签名、SharedPreferences 名称和 JSON 格式保持兼容。

## 界面

采用清单布局：按日期分组、突出取件码、快速复制和取件状态切换。搜索、排序、筛选和右上角短信识别入口保留。桌面小组件根据高度显示 1 至 3 条记录，取件状态改变后自动刷新。设置页保留检查更新与自动检查开关，更新地址不在界面中显示。

界面图标来自 Google Material Icons / Material Symbols，使用 Apache 2.0 许可证，授权与来源见 `third_party/`。应用源码使用 MIT 许可证。

0.4.1 压缩首页顶部留白、标签/搜索区域和底部间距，将驿站与快递公司合并为单行。相同 360×800dp 测试屏幕由完整显示 3 条提升到 5 条；实际数量取决于字体、日期分组和屏幕尺寸。

0.4.2 将“粘贴短信识别”移到首页右上角，移除底部重复入口，释放清单空间。手动添加入口位于搜索框下方的工具栏。

0.4.3 修复更新检查单一 Release 入口超时的问题，增加备用通道、缩短单次网络等待并显示可读的网络错误。APK 安装前仍校验大小、SHA-256、包名、版本和签名。

0.4.4 将“标记已取”和“恢复待取”的图标与文字统一水平居中，保留原有点击范围和状态切换行为。
