import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = path.join(root, '..', 'update.json');
try {
  const value = JSON.parse(await readFile(manifest, 'utf8'));
  if (value.packageName !== 'com.local.pickup' || value.channel !== 'stable'
    || !/^\d+\.\d+\.\d+$/.test(value.versionName)
    || !value.apkUrl?.startsWith('https://github.com/syczk301/pickup-assistant/releases/download/')) {
    throw new Error('Invalid stable release manifest');
  }
  await writeFile(path.join(root, 'src/release.json'), `${JSON.stringify(value, null, 2)}\n`);
  console.log(`Website download fallback: stable ${value.versionName}`);
} catch (error) {
  if (error.code === 'ENOENT') console.log('Standalone website: using bundled stable release manifest');
  else throw error;
}
