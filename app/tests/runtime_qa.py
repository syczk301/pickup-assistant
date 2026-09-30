"""ADB helpers for repeatable QA on a dedicated Android emulator (not a personal phone)."""
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')
ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = pathlib.Path(os.environ.get('ADB_PATH', str(ROOT/'.tools/sdk/platform-tools/adb.exe')))
SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5568')
SHOTS = ROOT/'deliverables/screenshots'

def adb(*args):
    return subprocess.check_output([str(ADB), '-s', SERIAL, *args])

def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/pickup-ui.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/pickup-ui.xml').decode('utf-8'))

def tap(label):
    nodes = [n for n in ui().iter('node') if n.get('text') == label or n.get('content-desc') == label]
    if not nodes:
        raise AssertionError('UI label missing: '+label)
    click(next((n for n in nodes if n.get('clickable') == 'true'), nodes[0]))

def click(n):
    a=list(map(int,re.findall(r'\d+',n.get('bounds'))))
    adb('shell','input','tap',str((a[0]+a[2])//2),str((a[1]+a[3])//2))

def exists(label):
    return any(n.get('text') == label for n in ui().iter('node'))

def screenshot(name):
    SHOTS.mkdir(exist_ok=True)
    time.sleep(0.5)
    (SHOTS/name).write_bytes(adb('exec-out','screencap','-p'))

def labels():
    return [n.get('text') for n in ui().iter('node') if n.get('text')]

def scroll_to(label):
    for _ in range(8):
        if any(n.get('text') == label or n.get('content-desc') == label for n in ui().iter('node')):
            return
        adb('shell','input','swipe','360','950','360','330','350')
    raise AssertionError('Cannot scroll to '+label)

def fill(value):
    time.sleep(0.5)
    out=adb('shell','uiautomator','runtest','/data/local/tmp/filltext.jar','-c','qa.FillText','-e','text',value).decode('utf-8','replace')
    if 'OK (1 test)' not in out:
        raise AssertionError(out)

if __name__=='__main__':
    print(json.dumps(labels(),ensure_ascii=False,indent=2))
