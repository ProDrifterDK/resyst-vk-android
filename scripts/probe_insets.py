#!/usr/bin/env python3
"""✦ Resyst VK — measure where the keyboard's bottom row sits per navigation mode.

For each navigation overlay (gestural / threebutton) it opens the E2E host text field, reads the
keyboard's own `nav inset` log line (what Android reported + the padding we reserved), the space
key's bounds from the accessibility tree and the screen height, and writes
build/e2e-r8/insets-<label>.json + a screenshot. The emulator's overlay is restored to the mode
it started in.

Usage: scripts/probe_insets.py --serial SERIAL --label before|after [--modes gestural,threebutton]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, IME, HOST  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r8')
OVERLAY = 'com.android.internal.systemui.navbar.'


def current_mode():
    for line in sh('cmd overlay list').splitlines():
        if line.strip().startswith('[x]') and OVERLAY in line:
            return line.split(OVERLAY)[1].strip()
    return None


def set_mode(mode):
    sh(f'cmd overlay enable-exclusive --category {OVERLAY}{mode}', check=False)
    time.sleep(3.0)


def measure(mode, label):
    sh('logcat -c')
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    sh(f'am force-stop {e2e.PKG}')
    time.sleep(0.5)
    sh(f'ime set {IME}')
    sh(f'am start -W -f 0x10008000 -n {HOST} --es kind text')
    time.sleep(3.0)
    log = adb('logcat', '-d', '-s', 'ResystVK:I')
    lines = [l for l in log.splitlines() if 'nav inset' in l]
    tree = dump()
    k = keys(tree)
    space = next((n for d, n in k.items() if d.startswith('Espacio')), None)
    sb = bounds(space) if space is not None else None
    w, h = map(int, re.findall(r'(\d+)x(\d+)', sh('wm size'))[-1])
    shot = f'insets-{label}-{mode}.png'
    open(os.path.join(OUT, shot), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))
    return {
        'mode': mode,
        'navigation_mode_setting': sh('settings get secure navigation_mode').strip(),
        'screen': [w, h],
        'nav_inset_log': lines[-1].split('ResystVK:')[-1].strip() if lines else None,
        'space_bounds': sb,
        'gap_below_space_px': (h - sb[3]) if sb else None,
        'shot': shot,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--label', required=True)
    ap.add_argument('--modes', default='gestural,threebutton')
    ap.add_argument('--apk')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    if a.apk:
        adb('install', '-r', '-t', a.apk)
    start = current_mode()
    out = []
    try:
        for m in a.modes.split(','):
            if current_mode() != m:
                set_mode(m)
            r = measure(m, a.label)
            print(json.dumps(r, ensure_ascii=False))
            out.append(r)
    finally:
        if start and current_mode() != start:
            set_mode(start)
    json.dump({'device': sh('getprop ro.product.model').strip(), 'api': sh('getprop ro.build.version.sdk').strip(),
               'results': out}, open(os.path.join(OUT, f'insets-{a.label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)


if __name__ == '__main__':
    main()
