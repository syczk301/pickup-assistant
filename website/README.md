# 拾件簿官网

地址：https://syczk301.github.io/pickup-assistant/

采用用户选择的第 2 个视觉方案：暖白背景、居中标题、桌面静物、蓝色 Android 下载入口。使用 React + Vite，适配电脑、平板和手机，静态文件部署到 GitHub Pages。

## 本地开发

```powershell
npm ci
npm run dev
```

构建：`npm run build`，GitHub Pages 发布目录为 `dist/client`。Vite 使用相对资源路径，适用于 `/pickup-assistant/` 子目录。更新页面中文后，可运行 `node scripts/prepare-font.mjs` 重新生成本地字体子集；字体和图片随网站一起部署，访问时无需请求外部字体服务。

## 下载信息

构建时从仓库根目录 `update.json` 同步正式版信息。网页加载时也尝试读取同一份 GitHub 正式版清单；网络不可达时仍可使用随网站打包的已知正式版入口。不提供第三方镜像或第三方账号登录。

`.github/workflows/deploy-pages.yml` 在官网源文件、正式版清单变化时构建和部署，也支持手动执行。发布使用 GitHub Pages 的 `github-pages` 环境。由 GitHub Actions 默认令牌产生的其他工作流提交可能不再次触发 push 工作流，因此页面同时读取实时正式版清单。

## 素材

- `public/assets/shijianbu-icon.svg`：沿用项目已有品牌图标。
- `public/assets/hero-scene.png`：依据所选视觉方案生成的桌面静物素材，使用实际应用和小组件截图作为参考；网站标题、说明、按钮与版本文字均为可访问的 HTML。
- `public/assets/batch-import.png`：实际应用的多码核对截图。
- `public/assets/pickup-sans.woff2`：自托管 Noto Sans SC 可变字体子集，SIL OFL 1.1，许可证在同目录。
- UI 图标：Lucide / React Icons 的 Simple Icons。第三方快递品牌标识的权利归各品牌所有。

设计验收记录见 `design-qa.md`，浏览器截图见 `qa/`。此目录不包含 Android 签名密钥或安装包，下载入口指向 GitHub Release。
