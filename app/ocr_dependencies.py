"""Fetch pinned offline OCR runtime/models and prepare both SDK and Gradle builds."""
import hashlib, json, os, pathlib, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parent

def prepare():
    cache = pathlib.Path(os.environ.get('OCR_CACHE', str(ROOT.parent / '.tools/ocr')))
    cache.mkdir(parents=True, exist_ok=True)
    entries = json.loads((ROOT / 'ocr-dependencies.json').read_text(encoding='utf-8'))
    files = {}
    for item in entries:
        path = cache / item['name']
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            with urllib.request.urlopen(item['url'], timeout=120) as response:
                body = response.read(40 * 1024 * 1024 + 1)
            if len(body) != item['size'] or hashlib.sha256(body).hexdigest() != item['sha256']:
                raise ValueError('OCR dependency checksum mismatch: ' + item['name'])
            path.write_bytes(body)
        files[item['name']] = path
    out = ROOT / 'build/ocr-runtime'; out.mkdir(parents=True, exist_ok=True)
    jni = ROOT / 'build/ocr-jni'; jni.mkdir(parents=True, exist_ok=True)
    assets = ROOT / 'build/ocr-assets/tessdata'; assets.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(files['tesseract4android-4.9.0.aar']) as source:
        jar = out / 'ocr.jar'; jar.write_bytes(source.read('classes.jar'))
        for name in source.namelist():
            if name.startswith('jni/') and name.endswith('.so'):
                target = jni / name.removeprefix('jni/')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read(name))
    for language in ('chi_sim', 'eng'):
        (assets / (language + '.traineddata')).write_bytes(files[language + '.traineddata'].read_bytes())
    licenses = assets.parent / 'licenses'; licenses.mkdir(exist_ok=True)
    for path in (ROOT / 'third_party').glob('*.txt'):
        (licenses / path.name).write_bytes(path.read_bytes())
    (licenses / 'carrier-logos.json').write_bytes((ROOT / 'carrier-logos.json').read_bytes())
    return jar, jni, assets.parent

if __name__ == '__main__':
    prepare()
