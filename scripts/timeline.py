#!/usr/bin/env python3
"""Cold-start the IME and sample the space key's bounds + IME window frame over time."""
import re
import subprocess
import sys
import time

SER = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
PKG = sys.argv[2] if len(sys.argv) > 2 else 'com.resyst.vk.debug'
ADB = ['adb', '-s', SER]
IME = PKG + '/com.resyst.vk.ime.ResystImeService'
HOST = 'com.resyst.vk.debug/com.resyst.vk.debug.E2EHostActivity'


def sh(c):
    return subprocess.run(ADB + ['shell', c], capture_output=True, text=True, timeout=60).stdout


def sample(tag):
    sh('uiautomator dump --windows /sdcard/ui.xml')
    x = subprocess.run(ADB + ['exec-out', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
    sp = re.search(r'content-desc="Espacio[^"]*"[^>]*bounds="([^"]*)"', x)
    w = sh('dumpsys window windows')
    blk = re.search(r'Window\{[^}]*InputMethod\}:.*?Frames:[^\n]*', w, re.S)
    fr = re.search(r'frame=(\[[^ ]*\])', blk.group(0)).group(1) if blk else None
    print(f'{tag}: space={sp.group(1) if sp else None} imeFrame={fr}', flush=True)


sh('logcat -c')
sh(f'ime set {IME}')
sh(f'am force-stop {PKG}')
time.sleep(0.5)
sh(f'ime set {IME}')
t0 = time.time()
sh(f'am start -W -f 0x10008000 -n {HOST} --es kind text')
for _ in range(4):
    time.sleep(2.5)
    sample(f't+{time.time() - t0:.1f}s')
sh('input tap 500 300')
time.sleep(2)
sample('after-tap')
log = subprocess.run(ADB + ['logcat', '-d', '-s', 'ResystVK:I'], capture_output=True, text=True).stdout
for l in log.splitlines():
    if 'nav inset' in l:
        print(l.split('ResystVK:')[-1].strip())
