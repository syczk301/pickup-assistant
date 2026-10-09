import { useEffect, useState } from 'react';
import { ArrowDown, ArrowUpRight, Check, ChevronDown, Download } from 'lucide-react';
import { SiGithub } from 'react-icons/si';
import bundledRelease from './release.json';

const REPOSITORY = 'https://github.com/syczk301/pickup-assistant';
const asset = (name) => `${import.meta.env.BASE_URL}assets/${name}`;

function validRelease(value) {
  return value?.packageName === 'com.local.pickup'
    && value.channel === 'stable'
    && /^\d+\.\d+\.\d+$/.test(value.versionName)
    && Number.isSafeInteger(value.versionCode)
    && value.versionCode >= bundledRelease.versionCode
    && Number.isSafeInteger(value.minSdk) && value.minSdk >= 26
    && /^https:\/\/github\.com\/syczk301\/pickup-assistant\/releases\/download\/v[\d.]+\/pickup-assistant-[\d.]+\.apk$/.test(value.apkUrl);
}

function useRelease() {
  const [release, setRelease] = useState(bundledRelease);
  useEffect(() => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 6000);
    // The bundled stable release remains usable if GitHub cannot be reached.
    fetch('https://api.github.com/repos/syczk301/pickup-assistant/contents/update.json', {
      headers: { Accept: 'application/vnd.github.raw+json' },
      signal: controller.signal,
    })
      .then((response) => response.ok ? response.json() : Promise.reject())
      .then((value) => { if (validRelease(value)) setRelease(value); })
      .catch(() => {})
      .finally(() => clearTimeout(timeout));
    return () => { controller.abort(); clearTimeout(timeout); };
  }, []);
  return release;
}

function DownloadLink({ release, className = '' }) {
  return <a className={`download-button ${className}`} href={release.apkUrl}
    aria-label={`下载拾件簿 ${release.versionName} Android 正式版 APK`}>
    <Download size={25} strokeWidth={2.1} aria-hidden="true" />
    <span>下载 Android 版</span>
  </a>;
}

const questions = [
  ['需要登录吗？图片会上传吗？', '无需注册或登录。短信、图片识别和取件记录在本机处理，图片不会上传。联网用于 GitHub 版本检查和安装包下载。'],
  ['支持哪些手机？怎么安装？', '支持 Android 8.0 及以上系统。下载 APK 后打开安装文件；如系统提示，请为当前浏览器允许“安装未知应用”，再继续安装。'],
  ['一张截图里有多个取件码怎么办？', '选择图片识别后，可切换逐条核对取件码、快递公司和驿站，再点“全部添加”一次保存。已有的待取记录会自动跳过。'],
  ['更新会丢失已有记录吗？', '使用本项目正式版安装包直接覆盖安装即可保留记录，无需卸载。也可以先在应用设置中导出 JSON 备份。'],
];

export function App() {
  const release = useRelease();
  return <>
    <a className="skip-link" href="#main">跳到主要内容</a>
    <div className="hero-shell" id="top">
      <div className="hero-art">
        <img className="hero-scene" src={asset('hero-scene.png')} width="1476" height="1066"
          alt="拾件簿包裹清单、桌面小组件和取件纸条，摆放在明亮的桌面上" fetchPriority="high" />
      </div>
      <header className="site-header">
        <a className="brand" href="#top" aria-label="拾件簿首页">
          <img src={asset('shijianbu-icon.svg')} width="48" height="48" alt="" />
          <span className="brand-name">拾件簿</span>
          <span className="brand-tagline">让生活少一点等待</span>
        </a>
        <nav aria-label="主导航">
          <a href="#how-it-works">使用方式</a>
          <span className="nav-divider" aria-hidden="true" />
          <a className="github-link" href={REPOSITORY} target="_blank" rel="noopener noreferrer" aria-label="GitHub 源代码（新窗口打开）">
            <SiGithub size={29} aria-hidden="true" /><span>GitHub</span>
            <span className="sr-only">（新窗口打开）</span>
          </a>
        </nav>
      </header>
      <main id="main" className="hero-copy">
        <p className="eyebrow">让取件简单一点</p>
        <h1>取件这件小事，<br />交给<span className="title-name">拾件簿</span><span className="blue-period">。</span></h1>
        <p className="hero-description"><span className="desktop-description">自动收集短信中的取件码，也能识别截图里的取件码。<br />
          图片在本机识别，取件记录留在本地，不上传服务器。</span>
          <span className="mobile-description">短信取件码，自动收好。<br />截图本机识别，多码一次添加。<br />取件记录留在本地，不上传服务器。</span></p>
        <DownloadLink release={release} />
        <p className="release-meta"><span>{release.versionName}</span><span aria-hidden="true"> · </span>Android 8.0 及以上</p>
      </main>
      <nav className="hero-features" aria-label="应用功能">
        <a href="#sms">短信提取</a><span aria-hidden="true">/</span>
        <a href="#images">图片识别</a><span aria-hidden="true">/</span>
        <a href="#widgets">桌面小组件</a>
      </nav>
    </div>
    <section className="how-section section-wrap" id="how-it-works" aria-labelledby="how-title">
      <div className="how-intro">
        <p className="section-label">从收到，到取走</p>
        <h2 id="how-title">三步，整理好取件码。</h2>
        <p className="section-description">不必在消息和截图里来回翻找。<br />打开拾件簿，待取包裹就在眼前。</p>
        <a className="text-link" href="#download">开始使用 <ArrowDown size={17} aria-hidden="true" /></a>
      </div>
      <ol className="steps">
        <li id="sms"><span className="step-number" aria-hidden="true">01</span><div>
          <h3>把取件码收进来</h3><p>开启短信识别，自动提取新收到的取件码。也可以粘贴短信，或手动添加一件包裹。</p>
        </div></li>
        <li id="images"><span className="step-number" aria-hidden="true">02</span><div>
          <h3>一张截图，多件一起添加</h3><p>选择图片，在本机识别。逐条核对取件码、快递公司和驿站，确认后一次保存。</p>
        </div></li>
        <li id="widgets"><span className="step-number" aria-hidden="true">03</span><div>
          <h3>到驿站，抬眼就能看到</h3><p>把小组件放在桌面，按空间选择小卡、横条或清单。取完点一下状态圆圈，下一件继续。</p>
        </div></li>
      </ol>
      <figure className="app-preview">
        <img src={asset('batch-import.png')} width="922" height="2048" loading="lazy"
          alt="实际应用界面：图片识别出三个取件码，可以逐条核对并全部添加" />
        <figcaption>实际界面 · 多码逐条核对</figcaption>
      </figure>
    </section>
    <section className="faq-section section-wrap" aria-labelledby="faq-title">
      <div><p className="section-label">关于拾件簿</p><h2 id="faq-title">你可能还想知道</h2>
        <p className="section-description">简单的小工具，也把细节交代清楚。</p>
      </div>
      <div className="faq-list">{questions.map(([question, answer]) =>
        <details key={question}><summary>{question}<ChevronDown size={20} aria-hidden="true" /></summary><p>{answer}</p></details>
      )}</div>
    </section>
    <section className="download-section" id="download" aria-labelledby="download-title">
      <img src={asset('shijianbu-icon.svg')} width="64" height="64" alt="" loading="lazy" />
      <h2 id="download-title">下一件包裹，轻松取走。</h2>
      <p>一个留在手机里的取件小帮手。</p>
      <DownloadLink release={release} />
      <p className="download-details"><Check size={16} aria-hidden="true" />{release.versionName} 正式版<span>·</span>约 {Math.round(release.sizeBytes / 1024 / 1024)} MB<span>·</span>Android 8.0+</p>
      <a className="text-link" href={`${REPOSITORY}/releases/tag/v${release.versionName}`} target="_blank" rel="noopener noreferrer">查看版本说明 <ArrowUpRight size={16} aria-hidden="true" /><span className="sr-only">（新窗口打开）</span></a>
    </section>
    <footer className="site-footer section-wrap">
      <div><a className="footer-brand" href="#top">拾件簿</a><p>让生活少一点等待。</p></div>
      <div className="footer-links"><a href={REPOSITORY} target="_blank" rel="noopener noreferrer">源代码 <ArrowUpRight size={14} aria-hidden="true" /></a><a href={`${REPOSITORY}/issues`} target="_blank" rel="noopener noreferrer">问题反馈 <ArrowUpRight size={14} aria-hidden="true" /></a><a href="#top">回到顶部</a></div>
      <p className="footer-note">应用代码以 MIT 许可证开源 · 第三方品牌标识归各品牌所有</p>
    </footer>
  </>;
}
