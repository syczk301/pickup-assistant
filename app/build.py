"""Build Kotlin Android sources with Kotlin 2.1.20 and official SDK tools."""
import os
import pathlib
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent
SDK = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT', str(ROOT.parent / '.tools' / 'sdk')))
JAVA_HOME = pathlib.Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Microsoft/jdk-17.0.18.8-hotspot'))
JAVA = str(JAVA_HOME / 'bin' / 'java.exe')
JAVAC = str(JAVA_HOME / 'bin' / 'javac.exe')
KEYTOOL = str(JAVA_HOME / 'bin' / 'keytool.exe')
KOTLIN = pathlib.Path(os.environ.get('KOTLIN_HOME', str(ROOT.parent / '.tools' / 'kotlin' / 'kotlinc')))
STDLIB = KOTLIN / 'lib' / 'kotlin-stdlib.jar'
D8 = pathlib.Path(os.environ.get('D8_JAR', str(ROOT.parent / '.tools' / 'r8-8.7.18.jar')))
if not D8.exists():
    raise SystemExit('Set D8_JAR to D8/R8 8.6 or newer (Kotlin 2.1 metadata support required).')
if not STDLIB.exists():
    raise SystemExit('Set KOTLIN_HOME to a Kotlin 2.1.20 command-line compiler installation.')
TOOLS = SDK / 'android-15'
if not (TOOLS / 'aapt2.exe').exists():
    TOOLS = SDK / 'build-tools' / '35.0.0'
ANDROID = SDK / 'android-35-ext15' / 'android.jar'
if not ANDROID.exists():
    ANDROID = SDK / 'platforms' / 'android-35' / 'android.jar'
BUILD = ROOT / 'build'
OUT = ROOT.parent / 'deliverables'
BUILD.mkdir(exist_ok=True)
OUT.mkdir(exist_ok=True)

def run(*args):
    # aapt2 on Windows uses narrow path arguments; relative paths work in Chinese directories.
    command = [str(args[0])]
    for a in args[1:]:
        command.append(os.path.relpath(a, ROOT) if isinstance(a, pathlib.Path) else str(a))
    subprocess.run(command, check=True, cwd=ROOT)

run(TOOLS/'aapt2.exe', 'compile', '--dir', ROOT/'res', '-o', BUILD/'resources.zip')
run(TOOLS/'aapt2.exe', 'link', '-o', BUILD/'base.apk', '-I', ANDROID,
    '--manifest', ROOT/'AndroidManifest.xml', '--java', BUILD/'generated', BUILD/'resources.zip')
classes = BUILD/'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir()
sources = list((BUILD/'generated').rglob('*.java'))
boot = os.pathsep.join(os.path.relpath(p, ROOT) for p in [ANDROID, TOOLS/'core-lambda-stubs.jar'])
run(JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', boot,
    '-d', classes, *sources)
classpath = os.pathsep.join(os.path.relpath(p, ROOT) for p in [ANDROID, classes, STDLIB])
run(JAVA, '-cp', os.path.relpath(KOTLIN/'lib'/'*', ROOT),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-kotlin-home', KOTLIN,
    '-no-reflect', '-jvm-target', '1.8', '-classpath', classpath,
    '-d', classes, *list((ROOT/'src').rglob('*.kt')))
with zipfile.ZipFile(BUILD/'classes.jar', 'w') as z:
    for f in classes.rglob('*.class'):
        z.write(f, f.relative_to(classes).as_posix())
dex = BUILD/'dex'
dex.mkdir(exist_ok=True)
run(JAVA, '-cp', D8, 'com.android.tools.r8.D8', '--min-api', '26',
    '--lib', ANDROID, '--output', dex, BUILD/'classes.jar', STDLIB)
shutil.copy2(BUILD/'base.apk', BUILD/'unsigned.apk')
with zipfile.ZipFile(BUILD/'unsigned.apk', 'a') as z:
    z.write(dex/'classes.dex', 'classes.dex')
run(TOOLS/'zipalign.exe', '-f', '4', BUILD/'unsigned.apk', BUILD/'aligned.apk')
key = ROOT/'local-debug.jks'
if not key.exists():
    run(KEYTOOL, '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'replica', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
        '-dname', 'CN=Pickup Local Development')
version = ET.parse(ROOT/'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
apk = OUT/f'pickup-assistant-{version}.apk'
run(JAVA, '-jar', TOOLS/'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', 'pass:android',
    '--ks-key-alias', 'replica', '--out', apk, BUILD/'aligned.apk')
run(JAVA, '-jar', TOOLS/'lib/apksigner.jar', 'verify', '--verbose', apk)
run(TOOLS/'aapt.exe', 'dump', 'badging', apk)
print('APK:', apk)
