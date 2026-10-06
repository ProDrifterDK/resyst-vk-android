#!/usr/bin/env python3
"""✦ Resyst VK — end-to-end check on a real device or emulator (adb + uiautomator, stdlib only).

What it proves: the debug APK installs, Android accepts it as an input method, and typing on
the drawn keyboard commits the expected text into another app's EditText via InputConnection.
Keys are located through the keyboard's accessibility tree (ExploreByTouchHelper virtual views),
so the run also proves TalkBack can see every key.

Artifacts (repeatable): build/e2e/results.json, build/e2e/*.png, build/e2e/*.xml

Usage: scripts/e2e.py [--serial SERIAL] [--apk PATH] [--keep-ime]
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
OUT = os.path.join(ROOT, 'build', 'e2e')
PKG = 'com.resyst.vk.debug'
IME = PKG + '/com.resyst.vk.ime.ResystImeService'
HOST = PKG + '/com.resyst.vk.debug.E2EHostActivity'
SETTINGS = PKG + '/com.resyst.vk.settings.SettingsActivity'

ADB = [os.path.join(os.environ.get('ANDROID_HOME', os.path.expanduser('~/Android/Sdk')), 'platform-tools', 'adb')]
results = []


def adb(*args, check=True, capture=True, binary=False):
    r = subprocess.run(ADB + list(args), capture_output=capture, timeout=120)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors='replace')


def sh(cmd, check=True):
    return adb('shell', cmd, check=check)


def record(name, ok, detail=''):
    results.append({'check': name, 'ok': bool(ok), 'detail': detail})
    print(('  ✓ ' if ok else '  ✗ ') + name + (f' — {detail}' if detail else ''))


def dump(name=None):
    for _ in range(4):
        out = sh('uiautomator dump --windows /sdcard/ui.xml 2>/dev/null || uiautomator dump /sdcard/ui.xml', check=False)
        xml = adb('exec-out', 'cat', '/sdcard/ui.xml', check=False)
        if xml.strip().startswith('<?xml'):
            if name:
                open(os.path.join(OUT, name + '.xml'), 'w', encoding='utf-8').write(xml)
            return ET.fromstring(xml.encode('utf-8'))
        time.sleep(0.6)
    raise RuntimeError('uiautomator dump failed: ' + out)


def bounds(node):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2


def center(node):
    x1, y1, x2, y2 = bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2


def keys(tree):
    """content-desc → node, for nodes that belong to our package (IME window)."""
    out = {}
    for n in tree.iter('node'):
        if n.get('package') == PKG and n.get('content-desc') and n.get('class') == 'android.widget.Button':
            out.setdefault(n.get('content-desc'), n)
    return out


def field_text(tree):
    for n in tree.iter('node'):
        if n.get('class') == 'android.widget.EditText' and n.get('package') == PKG:
            t = n.get('text', '')
            return '' if t == 'e2e' else t
    return None


def tap(node, hold_ms=None):
    x, y = center(node)
    if hold_ms:
        sh(f'input swipe {x} {y} {x} {y} {hold_ms}')
    else:
        sh(f'input tap {x} {y}')
    time.sleep(0.25)


def type_word(word, km=None):
    km = km or keys(dump())
    for ch in word:
        k = km.get(ch)
        if k is None:  # not `or`: a leaf Element is falsy (no children)
            k = km.get(ch.upper())
        if k is None:
            raise RuntimeError(f'key {ch!r} not found; have {sorted(km)[:60]}')
        tap(k)
    return km


def shot(name):
    png = adb('exec-out', 'screencap', '-p', binary=True)
    open(os.path.join(OUT, name + '.png'), 'wb').write(png)


def ensure_ime():
    # Force-stopping our package (am start -S, pm clear) makes Android fall back to the
    # default keyboard, so re-assert the selection before every scenario.
    if sh('settings get secure default_input_method').strip() != IME:
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')


def open_host(kind):
    ensure_ime()
    # NEW_TASK | CLEAR_TASK: fresh activity without force-stopping the IME's process.
    sh(f'am start -W -f 0x10008000 -n {HOST} --es kind {kind}')
    time.sleep(1.6)
    tree = dump()
    km = keys(tree)
    if not km:  # IME may need a tap on the field to show
        for n in tree.iter('node'):
            if n.get('class') == 'android.widget.EditText':
                tap(n)
        time.sleep(1.0)
        tree = dump()
        km = keys(tree)
    return tree, km


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial')
    ap.add_argument('--apk', default=os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--keep-ime', action='store_true', help='leave Resyst VK selected afterwards')
    a = ap.parse_args()
    if a.serial:
        ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)

    started = time.time()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk})')
    previous_ime = sh('settings get secure default_input_method').strip()

    try:
        adb('install', '-r', '-t', a.apk)
        record('apk installs', True, os.path.basename(a.apk))
        sh(f'pm clear {PKG}', check=False)
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        listed = sh('ime list -s')
        record('android lists Resyst VK as an input method', IME in listed)
        record('Resyst VK is the selected keyboard', sh('settings get secure default_input_method').strip() == IME)

        # 1 — plain text: auto-cap + letters + ñ
        tree, km = open_host('text')
        shot('01-keyboard-noche')
        dump('01-keyboard-noche')
        record('keyboard window is shown with accessible keys', len(km) >= 30, f'{len(km)} keys')
        for need in ['ñ', 'Mayúsculas', 'Borrar', 'Espacio', 'Símbolos', 'á', '¿']:
            if need not in km and need.upper() not in km:
                record(f'key present: {need}', False)
        type_word('hola', km)
        record('auto-capitalized word committed via InputConnection', field_text(dump()) == 'Hola', repr(field_text(dump())))
        km = keys(dump())
        tap(km['Espacio'])
        km = keys(dump())
        type_word('niño', km)
        record('ñ key commits ñ', field_text(dump()) == 'Hola niño', repr(field_text(dump())))

        # 2 — long-press variant (default = first variant, é)
        tap(keys(dump())['Espacio'])
        km = keys(dump())
        tap(km['e'], hold_ms=900)
        txt = field_text(dump())
        record('long-press e commits é (default variant)', txt == 'Hola niño é', repr(txt))
        km = keys(dump())
        tap(km['Borrar'])
        record('backspace deletes one char', field_text(dump()) == 'Hola niño ', repr(field_text(dump())))

        # 3 — shift once then lower
        km = keys(dump())
        tap(km['Mayúsculas'])
        km = keys(dump())
        type_word('ña', km)
        record('shift-once applies to one character', field_text(dump()) == 'Hola niño Ña', repr(field_text(dump())))

        # 4 — suggestions strip (lexicon): type "cancio" → tap first suggestion
        tap(keys(dump())['Espacio'])
        type_word('cancio')
        time.sleep(0.6)
        tree = dump('04-suggestions')
        shot('04-suggestions')
        sugg = [d for d in keys(tree) if d.startswith('Sugerencia: ')]
        record('suggestions shown for accent-less prefix', any('canción' in s for s in sugg), ', '.join(sugg))
        if sugg:
            tap(keys(tree)[sugg[0]])
            txt = field_text(dump())
            record('picking a suggestion replaces the word + space', txt.endswith(sugg[0].split(': ', 1)[1] + ' '), repr(txt))

        # 5 — symbols layer
        km = keys(dump())
        tap(km['Símbolos'])
        km = keys(dump())
        tap(km['@']); tap(km['#'])
        km2 = keys(dump())
        record('symbols layer commits @ #', field_text(dump()).endswith('@#'), repr(field_text(dump())))
        tap(km2['Letras'])

        # 6 — email field: @ replaces comma on the letters bottom row
        tree, km = open_host('email')
        record('email field shows @ on letters layer', 'arroba' in km and 'coma' not in km)

        # 7 — numeric field opens the numpad
        tree, km = open_host('number')
        shot('07-numpad')
        has_digits = all(str(d) in km for d in range(10))
        record('number field opens numpad', has_digits and 'q' not in km)
        type_word('2026', km)
        record('numpad commits digits', field_text(dump()) == '2026', repr(field_text(dump())))

        # 8 — search action: Enter performs IME_ACTION_SEARCH (3)
        sh('logcat -c')
        tree, km = open_host('search')
        type_word('resyst', km)
        km = keys(dump())
        tap(km['Buscar'])
        time.sleep(0.6)
        log = adb('logcat', '-d', '-s', 'ResystE2E:I')
        record('enter performs the editor action (search)', 'editorAction=3' in log, log.strip().splitlines()[-1] if log.strip() else '')

        # 9 — multi-line: Enter inserts newline
        tree, km = open_host('multiline')
        type_word('uno', km)
        km = keys(dump())
        tap(km['Nueva línea'])
        type_word('dos')
        record('enter inserts a newline in multi-line fields', field_text(dump()) == 'Uno\nDos', repr(field_text(dump())))

        # 10 — profile cycle: tap ✦ chip → each profile, screenshot each theme
        tree, km = open_host('text')
        names = []
        for i in range(4):
            tree = dump()
            chip = next((n for d, n in keys(tree).items() if d.startswith('Perfil ')), None)
            if chip is None:
                break
            names.append(chip.get('content-desc').split('.')[0].replace('Perfil ', ''))
            shot(f'10-profile-{i + 1}')
            tap(chip)
            time.sleep(0.8)
        record('✦ chip cycles the 4 profiles', names == ['Noche', 'Día', 'Juego', 'Escritura'], ' → '.join(names))
        tree = dump()
        chip = next((d for d in keys(tree) if d.startswith('Perfil ')), '')
        record('cycle wraps back to Noche', chip.startswith('Perfil Noche'), chip)

        # 11 — settings screen renders
        sh(f'am start -W -f 0x10008000 -n {SETTINGS}')
        time.sleep(1.5)
        shot('11-settings')
        st = dump('11-settings')
        texts = [n.get('text', '') for n in st.iter('node')]
        record('settings screen shows setup status + profiles', any('activo' in t for t in texts) and 'Escritura' in texts)
    finally:
        if not a.keep_ime and previous_ime and previous_ime != 'null' and previous_ime != IME:
            sh(f'ime set {previous_ime}', check=False)
        passed = sum(r['ok'] for r in results)
        summary = {
            'device': model, 'api': sdk, 'apk': os.path.relpath(a.apk, ROOT),
            'passed': passed, 'total': len(results), 'seconds': round(time.time() - started, 1),
            'checks': results,
        }
        json.dump(summary, open(os.path.join(OUT, 'results.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {passed}/{len(results)} checks passed → build/e2e/results.json')
    return 0 if results and all(r['ok'] for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
