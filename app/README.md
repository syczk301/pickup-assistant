# 取件助手

原生 Java Android 应用，包名 `com.local.pickup`，最低 Android 8.0。当前版本 0.2.1，版本号 3。短信和取件记录在本地处理；联网用于检查和下载更新。

主界面、短信接收、解析、数据存储、提醒和小组件分别位于 `src/com/local/pickup/` 中的同名 Java 文件。没有外部运行时依赖。

## 实际构建

需要 Java 17、Android SDK Platform 35、Build Tools 35.0.0。当前工作目录的 `.tools/sdk/` 已准备好构建组件。也可将 `ANDROID_SDK_ROOT` 指向标准 SDK，并设置 `JAVA_HOME`。

```powershell
python app\build.py
```

从其他目录使用源码时，也可以直接执行该脚本的绝对路径。APK 输出到源码目录上一级的 `deliverables/`。

`local-debug.jks` 是当前发布版本使用的签名密钥，保留在本地，未上传 GitHub，未包含在源码 ZIP。后续升级必须继续使用此密钥；换密钥会导致已有应用无法升级。脚本在密钥不存在时生成新密钥，仅能用于新安装。Android Studio 导入可使用现成 Gradle 文件，本次使用 SDK 脚本构建验证。

## 解析测试

在 `app/` 中运行：

```powershell
javac -encoding UTF-8 -d build/tests src/com/local/pickup/SmsParser.java tests/ParserTest.java
java -cp build/tests ParserTest
```

17 项覆盖不同取件码、地址、快递公司签名、多码及验证码误识别边界。

`tests/runtime_qa.py` 是本次模拟器 UI 验证的 ADB 工具辅助脚本；默认只连接 `emulator-5564`。`tests/FillText.java` 是 API 26 运行验证中使用的 Unicode 输入辅助类，使用旧版 UI Automator 测试环境，仅用于 QA，不参与应用编译或打包。完整操作与结果见交付目录中的验证记录。

## 在线更新

发布仓库：https://github.com/syczk301/pickup-assistant

应用源码已按 MIT 许可证开源，仓库的 `app/` 目录包含完整源码、资源、构建脚本和测试。

默认版本地址：https://github.com/syczk301/pickup-assistant/releases/latest/download/update.json

在设置中选择“检查更新”，发现新版本后下载并安装。默认每天自动检查一次，也可以关闭或修改 HTTPS 更新地址。后台下载由安卓下载管理器完成，安装前检查文件大小、SHA-256、包名、版本和签名。首次更新需要允许取件助手安装应用。

`UpdateProtocol.java` 定义版本文件及网络检查；`UpdateController.java` 管理下载和安装；`UpdateFiles.java` 校验 APK；`UpdateDownloadReceiver.java` 处理后台完成事件；`UpdateFileProvider.java` 向系统安装器提供只读文件。

版本协议 15 项检查及真实 GitHub 请求已通过。发布格式和验证结果见仓库根目录的 `在线更新说明.md` 和 `在线更新验证.md`。
