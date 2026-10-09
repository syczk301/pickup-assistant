import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const copy = await readFile(path.join(root, 'src/App.jsx'), 'utf8')
  + await readFile(path.join(root, 'index.html'), 'utf8')
  + '0123456789AndroidMBGitHub正式版';
const characters = [...new Set(copy)].sort().join('');
const query = new URLSearchParams({ family: 'Noto Sans SC:wght@100..900', display: 'swap', text: characters });
const cssResponse = await fetch(`https://fonts.googleapis.com/css2?${query}`, {
  headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36' },
});
if (!cssResponse.ok) throw new Error(`Font CSS failed: ${cssResponse.status}`);
const css = await cssResponse.text();
const url = css.match(/src:\s*url\((https:\/\/fonts\.gstatic\.com\/[^)]+)\)/)?.[1];
if (!url) throw new Error('No official font URL in Google Fonts response');
const response = await fetch(url);
if (!response.ok) throw new Error(`Font download failed: ${response.status}`);
const font = Buffer.from(await response.arrayBuffer());
if (font.subarray(0, 4).toString() !== 'wOF2') throw new Error('Expected WOFF2 font');
const assets = path.join(root, 'public/assets');
await mkdir(assets, { recursive: true });
await writeFile(path.join(assets, 'pickup-sans.woff2'), font);
const licenseResponse = await fetch('https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/OFL.txt');
if (!licenseResponse.ok) throw new Error('Unable to download the font license');
await writeFile(path.join(assets, 'NotoSansSC-OFL.txt'), await licenseResponse.text());
console.log(`Self-hosted Noto Sans SC variable subset: ${font.length} bytes, ${characters.length} characters.`);
