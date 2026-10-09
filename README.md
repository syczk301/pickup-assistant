<div align="center">

<img src="https://raw.githubusercontent.com/syczk301/pickup-assistant/main/website/public/assets/shijianbu-icon.svg" width="80" height="80" alt="拾件簿图标">

# 拾件簿

**让取件简单一点。**

把短信和截图里的取件码收在一起，到驿站时打开就能看。

[访问官网](https://syczk301.github.io/pickup-assistant/) · [下载正式版](https://github.com/syczk301/pickup-assistant/releases/latest) · [查看更新](https://github.com/syczk301/pickup-assistant/releases) · [反馈问题](https://github.com/syczk301/pickup-assistant/issues)

Android 8.0+ · 原生 Kotlin · 本机图片识别 · MIT 开源

</div>

## 下载与安装

当前正式版：**0.5.20**，安装包约 **18.4 MiB**。

- [直接下载 Android APK](https://github.com/syczk301/pickup-assistant/releases/download/v0.5.20/pickup-assistant-0.5.20.apk)
- [正式版说明与安装包](https://github.com/syczk301/pickup-assistant/releases/latest)
- [官网](https://syczk301.github.io/pickup-assistant/)

下载后按系统提示安装；首次安装时，允许所用浏览器安装此来源的应用。已有官方版本可直接覆盖安装，保留取件记录，无需卸载。建议先在设置中导出一份 JSON 备份。

应用内可在 **设置 → 应用更新** 检查更新，也可切换测试渠道。测试版和正式版使用独立的更新清单；切换渠道不会自动降级。旧版若无法完成应用内下载，可通过上方链接下载 APK 覆盖安装。

<details>
<summary>0.5.20 安装包校验信息</summary>

- 包名：`com.local.pickup`
- 版本号：`37`
- 文件大小：`19,318,249` 字节
- SHA-256：`b33c916dd5770238fd9cac11396044f70c64e31e4f8a5ca7618361689f7b2a28`

</details>

## 能做什么

| 功能 | 使用方式 |
| --- | --- |
| 短信识别 | 自动提取新短信中的取件码，也可粘贴短信或扫描最近 30 天的历史短信 |
| 图片识别 | 在本机识别截图；一张图识别到多个码时，可逐条核对后一次加入 |
| 包裹清单 | 按日期查看待取与已取包裹，支持搜索、排序、筛选和状态切换 |
| 记录操作 | 点击或长按条目打开详情与操作菜单，可编辑、复制、删除或切换状态 |
| 桌面小组件 | 提供 2×2 小卡、4×1 横条、4×2 清单和 4×4 大清单四种入口 |
| 通知与提醒 | 可开启新包裹通知和每日提醒 |
| 统计与备份 | 查看取件统计，使用 JSON 导出和恢复记录 |

单击取件码不会自动复制；选择菜单中的“复制取件码”才会写入剪贴板。小组件按桌面实际尺寸和字体适配，清单最多展示 12 条记录；具体占用格数由手机桌面决定。

### 一张截图，多件一起添加

<div align="center">
<img src="https://raw.githubusercontent.com/syczk301/pickup-assistant/main/website/public/assets/batch-import.png" width="270" alt="实际应用界面：一张截图识别出三个取件码，可逐条核对并全部添加">
</div>

1. 点击主页右上角 **＋**，选择 **图片识别**，或粘贴取件短信。
2. 核对取件码、快递公司和驿站；多码结果可切换逐条修改。
3. 点击 **全部添加（数量）**。已有的待取记录会自动跳过；无效码会定位到对应条目，修改后再保存。
4. 到驿站后查看清单或桌面小组件，取走包裹后点击状态圆圈。

图片识别结果可以手动修改，完整识别原文可单独查看。识别截图中的图标、地图和复杂排版可能影响结果，保存前请核对取件信息。

## 隐私与权限

**不需要注册账号。短信和图片识别在本机完成，取件记录保存在本地，不上传服务器。** OCR 模型随安装包提供，图片识别无需联网。应用联网用于版本检查和安装包下载，更新数据来自 GitHub 及更新清单的备用读取通道。

| 权限 | 用途与申请时机 |
| --- | --- |
| 接收短信 | 开启自动识别时申请，用于识别新收到的取件短信 |
| 读取短信 | 主动扫描历史短信时申请 |
| 通知 | 开启新包裹通知或每日提醒时申请，Android 13+ 需要授权 |
| 开机广播 | 重启后恢复已开启的每日提醒 |
| 网络与前台服务 | 检查版本、下载更新，并在后台保持下载任务 |
| 安装未知应用 | 主动安装更新时，由系统要求授权 |

手动添加、粘贴和图片识别无需短信权限。应用不申请定位、联系人、相机或通用存储权限。备份文件由用户自行保存，可能包含取件记录，请妥善保管。

身份码入口会跳转淘宝、菜鸟或拼多多，由对应应用提供服务，可能需要在对应应用登录。拾件簿目前不提供菜鸟账号登录，也不自动同步账号下的包裹或取件码。

## 常见问题

**图片里有多个取件码，能一起加入吗？**  
可以。0.5.20 支持逐条核对并全部添加；请确认底部按钮显示“全部添加（数量）”。

**更新会丢失记录吗？**  
使用本项目发布的同签名 APK 覆盖安装会保留记录。卸载应用会删除应用内数据，请提前导出备份。

**为什么手机桌面的小组件格数和标注不一样？**  
不同桌面的网格和留白不同，标注尺寸用于选择样式；小组件会根据实际可用空间和字体调整内容。

**遇到问题如何反馈？**  
请在 [Issues](https://github.com/syczk301/pickup-assistant/issues) 描述操作步骤、应用版本、手机型号和 Android 版本，并提供必要截图。上传前请遮挡手机号、地址、运单号及有效取件码。

## 开发与构建

GitHub 仓库的主要目录：

```text
app/                       Android 应用源码、资源、构建脚本和测试
website/                   React + Vite 官网
update.json                正式渠道更新清单
update-beta.json           测试渠道更新清单
.github/workflows/         发布与 GitHub Pages 工作流
```

<details>
<summary>Android 构建与回归测试（Windows）</summary>

需要 Python 3、JDK 17、Android SDK Platform 35、Build Tools 35.0.0、Kotlin 命令行编译器 2.1.20，以及 D8/R8 8.6 或更新版本（现有脚本使用 8.7.18）。设置以下环境变量：

- `JAVA_HOME`：JDK 目录
- `ANDROID_SDK_ROOT`：Android SDK 目录
- `KOTLIN_HOME`：包含 `bin/` 和 `lib/` 的 Kotlin 编译器目录
- `D8_JAR`：`r8.jar` 的完整路径

从仓库根目录执行：

```powershell
python app\build.py
```

APK 输出到仓库根目录的 `deliverables/`。构建脚本自动准备本地 OCR 所需模型、JAR 和四种 ABI 的原生库，首次准备依赖需要联网。Android Studio 可导入 `app/` 下的 Gradle 配置。

先完成构建，再将 `JSON_TEST_JAR` 设置为 `org.json:json:20240303` 的 JAR 路径，运行：

```powershell
python app\test.py
```

Kotlin 回归测试覆盖短信解析、图片多码提取、识别原文保留、更新协议、HTTP 下载行为及 JSON 数据迁移。

发布签名密钥不包含在仓库中。自行构建时脚本会创建本地开发密钥，构建包可用于新安装，不能覆盖官方发布包。

</details>

<details>
<summary>官网开发</summary>

需要 Node.js 22。从仓库根目录执行：

```powershell
cd website
npm ci
npm run dev
```

`npm run build` 输出 GitHub Pages 使用的静态文件至 `dist/client/`。官网从根目录 `update.json` 同步正式版下载信息，部署由 GitHub Actions 完成。更多说明见 [官网 README](https://github.com/syczk301/pickup-assistant/blob/main/website/README.md)。

</details>

## 开源许可

应用代码采用 [MIT 许可证](https://github.com/syczk301/pickup-assistant/blob/main/LICENSE)。OCR、字体和图标等依赖遵循各自许可证，相关说明见 [第三方声明](https://github.com/syczk301/pickup-assistant/tree/main/app/third_party) 和官网资源目录。

第三方快递品牌标识的权利归各品牌所有，不属于本项目的 MIT 授权范围；本项目与相关品牌无隶属或背书关系。
