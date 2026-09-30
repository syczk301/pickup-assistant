# 取件助手

原生 Java Android 取件码管理应用，支持 Android 8.0 及以上。当前版本 **0.2.1**，源码采用 [MIT 许可证](LICENSE)。

[下载 APK](https://github.com/syczk301/pickup-assistant/releases/download/v0.2.1/pickup-assistant-0.2.1.apk) · [发布版本](https://github.com/syczk301/pickup-assistant/releases) · [应用源码](app/src/com/local/pickup)

## 功能

- 短信识别取件码、粘贴提取、手动添加。
- 待取和已取管理、搜索筛选、统计、提醒、小组件。
- JSON 导入导出；短信和取件记录在本机处理。
- GitHub 在线更新：检查版本、后台下载、文件大小及 SHA-256 校验、包名及签名校验，再由安卓系统确认安装。

## 源码与构建

`app/` 包含 Manifest、Java 源码、XML 资源、Gradle 配置、SDK 构建脚本和测试。没有外部应用运行时依赖。

准备 Python 3、Java 17、Android SDK Platform 35、Build Tools 35.0.0。设置 `JAVA_HOME` 和 `ANDROID_SDK_ROOT`，在仓库根目录执行：

```powershell
python app\build.py
```

APK 输出到 `deliverables/`。脚本使用 Android SDK 工具构建和签名，也可在 Android Studio 中导入 `app/` 的 Gradle 项目；本次验证使用 SDK 构建脚本。

签名密钥未公开。自行构建时脚本会创建本地开发密钥，所生成 APK 可全新安装。要升级已有发布 APK，必须使用原发布密钥签名。

## 测试

短信解析测试在 `app/` 目录运行：

```powershell
javac -encoding UTF-8 -d build/tests src/com/local/pickup/SmsParser.java tests/ParserTest.java
java -cp build/tests ParserTest
```

更新协议测试 `tests/UpdateProtocolTest.java` 需将 `org.json` 的 JAR 加入编译和运行 classpath；Android 应用本身直接使用系统 JSON API。模拟器辅助代码位于 `tests/runtime_qa.py` 和 `tests/FillText.java`。

短信解析 17 项、更新协议 15 项和真实 GitHub 请求通过。API 26 模拟器已完成 0.2.0 → 0.2.1 在线下载安装，确认数据保留。见 [在线更新验证](在线更新验证.md)。

## 在线更新

在“设置 → 应用更新 → 检查更新”中下载并安装新版本。默认按 24 小时间隔在进入应用时自动检查，可关闭或修改 HTTPS 地址。首次更新需要允许取件助手安装应用，并在系统安装界面确认。

默认版本文件：https://github.com/syczk301/pickup-assistant/releases/latest/download/update.json

后续发布方式和版本文件格式见 [在线更新说明](在线更新说明.md)。
