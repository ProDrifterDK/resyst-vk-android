#!/usr/bin/env python3
"""Dump keyboard window geometry: IME window frame + our view nodes."""
import re
import subprocess
import sys

SER = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
ADB = ['adb', '-s', SER]


def sh(c):
    return subprocess.run(ADB + ['shell', c], capture_output=True, text=True, timeout=60).stdout


sh('uiautomator dump --windows /sdcard/ui.xml')
x = subprocess.run(ADB + ['exec-out', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    if 'com.resyst.vk' not in n:
        continue
    cls = re.search(r'class="([^"]*)"', n).group(1)
    desc = re.search(r'content-desc="([^"]*)"', n).group(1)
    b = re.search(r'bounds="([^"]*)"', n).group(1)
    if cls != 'android.widget.Button' or desc.startswith(('Espacio', 'Perfil', 'Ajustes', 'Letras', 'Símbolos', 'Intro', 'Listo')):
        print(cls, desc[:40], b)
w = sh('dumpsys window windows')
blk = re.search(r'Window\{[^}]*InputMethod\}:.*?Frames:[^\n]*', w, re.S)
if blk:
    print(blk.group(0).splitlines()[-1].strip())
print('navmode', sh('settings get secure navigation_mode').strip())
