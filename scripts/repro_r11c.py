#!/usr/bin/env python3
"""✦ Resyst VK — r11c reproduction: the automatic update check runs once per IME process.

On the installed build: start a fresh process, open the keyboard N times (new activity each time
= onStartInputView restarting=false), record the IME pid and count `update check: GET release.json`
lines in logcat. Then rewind every stored update timestamp to 0 and open N more times: on a
once-per-process build nothing changes (0 new GETs) — the bug Alan reported.

Writes build/e2e-r11c/repro-<versionName>.json.

Usage: scripts/repro_r11c.py --serial SERIAL [--opens 20]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import sh, PKG  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r11c')
PREFS = f'/data/data/{PKG}/shared_prefs/resyst_vk_update.xml'


def pid():
    return sh(f'pidof {PKG}', check=False).strip()


def gets():
    return [l for l in e2e.adb('logcat', '-d', '-v', 'time', '-s', 'ResystVK:I').splitlines()
            if 'update check: GET release.json' in l]


def rewind_all():
    """Every long in the updater prefs → 0 (as if the last check was in 1970)."""
    xml = sh(f'run-as {PKG} cat {PREFS}', check=False)
    new = re.sub(r'(<long name="(?!id")[^"]+" value=")-?\d+(")', r'\g<1>0\2', xml)
    local = os.path.join(OUT, '.prefs.xml')
    open(local, 'w', encoding='utf-8').write(new)
    tmp = '/data/local/tmp/r11c_prefs.xml'
    e2e.adb('push', local, tmp)
    sh(f'chmod 644 {tmp}')
    sh(f"run-as {PKG} sh -c 'cat {tmp} > {PREFS}'")
    return new


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--opens', type=int, default=20)
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    ver = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}')).group(1)
    sh(f'run-as {PKG} rm -f {PREFS}', check=False)
    sh(f'am force-stop {PKG}')
    time.sleep(0.8)
    sh('logcat -c')
    opens = []
    t0 = time.time()
    for i in range(a.opens):
        e2e.open_host('text' if i % 2 == 0 else 'multiline')
        opens.append({'i': i + 1, 'pid': pid(), 'gets': len(gets())})
    first = {'opens': len(opens), 'seconds': round(time.time() - t0, 1),
             'pids': sorted({o['pid'] for o in opens}), 'gets': len(gets()), 'get_lines': gets()}
    print('phase 1', json.dumps(first, ensure_ascii=False))
    prefs_before = sh(f'run-as {PKG} cat {PREFS}', check=False)
    # the stored timestamp says nothing to a once-per-process gate: rewind it and reopen
    # (the shared_prefs file is rewritten in place; a live process keeps its in-memory copy,
    # which is exactly why r9's `autoAt` was write-only)
    rewound = rewind_all()
    more = []
    for i in range(3):
        e2e.open_host('text')
        more.append({'i': i + 1, 'pid': pid(), 'gets': len(gets())})
    second = {'opens': len(more), 'pids': sorted({o['pid'] for o in more}), 'gets_total': len(gets())}
    print('phase 2', json.dumps(second, ensure_ascii=False))
    out = {'device': sh('getprop ro.product.model').strip(), 'serial': a.serial, 'installed': ver,
           'phase1_opens': first, 'per_open': opens, 'prefs_after_phase1': re.sub(r'\s+', ' ', prefs_before),
           'phase2_after_rewind': second, 'prefs_rewound': re.sub(r'\s+', ' ', rewound)}
    path = os.path.join(OUT, f'repro-{ver}.json')
    json.dump(out, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(path)


if __name__ == '__main__':
    main()
