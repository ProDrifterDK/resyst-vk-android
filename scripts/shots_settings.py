#!/usr/bin/env python3
"""✦ Resyst VK — settings screenshots (before/after the r7/r8 settings IA).

Opens the settings activity and captures one screenshot per screen height while scrolling down,
then (if the build has pages) opens every page from home and captures it too. Artifacts go to
build/e2e-r7/<prefix>-NN.png plus <prefix>-index.json (what each shot shows: the visible
setting titles, read from the accessibility tree), so the comparison is checkable by text too.

Usage: scripts/shots_settings.py --serial SERIAL --prefix before|after [--apk PATH]
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, PKG, SETTINGS  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r7')


def visible_titles(tree):
    out = []
    for n in tree.iter('node'):
        if n.get('package') != PKG:
            continue
        t = (n.get('text') or '').strip()
        d = (n.get('content-desc') or '').strip()
        if t:
            out.append(t)
        elif d and n.get('clickable') == 'true':
            out.append(d)
    return out


def snap(path):
    png = adb('exec-out', 'screencap', '-p', binary=True)
    open(path, 'wb').write(png)


def scroll_capture(prefix, start, index, max_pages=14):
    last = None
    i = start
    for _ in range(max_pages):
        tree = dump()
        titles = visible_titles(tree)
        if titles == last:
            break
        name = f'{prefix}-{i:02d}.png'
        snap(os.path.join(OUT, name))
        index.append({'shot': name, 'titles': titles})
        last = titles
        i += 1
        sh('input swipe 540 1900 540 700 400')
        time.sleep(0.7)
    return i


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--prefix', required=True)
    ap.add_argument('--apk')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    if a.apk:
        adb('install', '-r', '-t', a.apk)
    sh(f'am force-stop {PKG}')
    sh(f'am start -W -f 0x10008000 -n {SETTINGS}')
    time.sleep(1.5)
    index = []
    i = scroll_capture(a.prefix + '-home', 0, index)
    # pages (r8 UI): rows whose content-desc starts with "Abrir " open a page
    sh(f'am start -W -f 0x10008000 -n {SETTINGS}')
    time.sleep(1.2)
    page_rows = []
    for _ in range(10):
        tree = dump()
        for n in tree.iter('node'):
            d = n.get('content-desc') or ''
            if n.get('package') == PKG and d.startswith('Abrir ') and d not in page_rows:
                page_rows.append(d)
        sh('input swipe 540 1900 540 900 300')
        time.sleep(0.5)
    for d in page_rows:
        sh(f'am start -W -f 0x10008000 -n {SETTINGS}')
        time.sleep(1.0)
        node = None
        for _ in range(10):
            tree = dump()
            node = next((n for n in tree.iter('node') if (n.get('content-desc') or '') == d), None)
            if node is not None:
                break
            sh('input swipe 540 1900 540 900 300')
            time.sleep(0.5)
        if node is None:
            continue
        e2e.tap(node)
        time.sleep(1.0)
        slug = d.removeprefix('Abrir ').split('.')[0].split(',')[0].strip().lower().replace(' ', '-')
        scroll_capture(f'{a.prefix}-page-{slug}', 0, index, max_pages=6)
        sh('input keyevent KEYCODE_BACK')
        time.sleep(0.6)
    json.dump({'pages': page_rows, 'shots': index}, open(os.path.join(OUT, a.prefix + '-index.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=2)
    print(f'✦ {len(index)} shots, {len(page_rows)} pages → build/e2e-r7/{a.prefix}-*')


if __name__ == '__main__':
    main()
