"""Run Kotlin parser, update protocol and data migration regression tests."""
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent
JAVA_HOME = pathlib.Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Microsoft/jdk-17.0.18.8-hotspot'))
KOTLIN = pathlib.Path(os.environ.get('KOTLIN_HOME', str(ROOT.parent/'.tools/kotlin/kotlinc')))
SDK = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT', str(ROOT.parent/'.tools/sdk')))
JSON = pathlib.Path(os.environ.get('JSON_TEST_JAR', str(ROOT.parent/'.tools/json-test.jar')))
ANDROID = SDK/'platforms/android-35/android.jar'
if not ANDROID.exists():
    ANDROID = SDK/'android-35-ext15/android.jar'
OUT = ROOT/'build/kotlin-tests'
OUT.mkdir(parents=True, exist_ok=True)
CLASSES = ROOT/'build/classes'
if not (CLASSES/'com/local/pickup/SmsParser.class').exists():
    raise SystemExit('Build the Kotlin app first with build.py.')
if not JSON.exists():
    raise SystemExit('Set JSON_TEST_JAR to org.json:json:20240303 JAR.')
cp = os.pathsep.join(str(p) for p in [CLASSES, JSON, ANDROID, KOTLIN/'lib/kotlin-stdlib.jar'])
java = str(JAVA_HOME/'bin/java.exe')
subprocess.run([java, '-cp', str(KOTLIN/'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-kotlin-home', str(KOTLIN), '-no-reflect', '-jvm-target', '1.8', '-classpath', cp, '-d', str(OUT), *map(str, sorted((ROOT/'tests').glob('*.kt')))], check=True)
for test in ['ParserTestKt', 'UpdateProtocolTestKt', 'UpdateTransferTestKt', 'MigrationTestKt', 'ImageParcelParserTestKt', 'ImageParcelTextTestKt']:
    # Use TCP loopback for the HTTP fixture on Windows JDKs with a broken AF_UNIX pipe.
    options = ['-Djdk.net.unixdomain.tmpdir=' + str(OUT/'no-unix-sockets')] if os.name == 'nt' and test == 'UpdateTransferTestKt' else []
    subprocess.run([java, *options, '-cp', str(OUT)+os.pathsep+cp, test], check=True)
