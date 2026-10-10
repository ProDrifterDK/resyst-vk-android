#!/usr/bin/env python3
"""✦ Resyst VK — round 9 E2E: startup update check + update chip + settings card.

Simulating an available update: install a debug build stamped OLDER than the live manifest
(`./gradlew assembleDebug -Pvk.versionCode=3 -Pvk.versionName=0.3.0`; kv.resyst.cl/release.json
advertises 0.4.0-alpha / code 4). The check is the real one: same Updater.check(), real HTTPS GET.
With --uptodate-apk (a normal 0.4.0-alpha build) the up-to-date path is checked too.

Each `update check: GET release.json` log line is one network request (Updater logs it right
before the fetch), so "once per process" is counted from logcat, per IME process (pid).

Writes build/e2e-r9/r9-e2e.json (one row per check, each able to fail) + screenshots.

Usage: scripts/e2e_r9.py --serial SERIAL [--uptodate-apk PATH] [--offline]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, center, PKG, SETTINGS  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r9')
PREFS = f'/data/data/{PKG}/shared_prefs/resyst_vk_update.xml'
rows = []


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + detail if detail else ''))


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def pid():
    return sh(f'pidof {PKG}', check=False).strip()


def log_lines():
    return [l for l in adb('logcat', '-d', '-v', 'brief', '-s', 'ResystVK:I').splitlines() if 'update' in l]


def gets(lines):
    return [l for l in lines if 'update check: GET release.json' in l]


def fresh_process():
    """Kill the app process; the next field focus re-creates the IME (onCreate → one auto-check)."""
    sh(f'am force-stop {PKG}')
    time.sleep(0.8)
    sh('logcat -c')


def strip(tree):
    k = keys(tree)
    chip = next((d for d in k if d.endswith('disponible. Toca para actualizar')), None)
    x = next((d for d in k if d.startswith('Descartar el aviso')), None)
    gear = next((d for d in k if d.startswith('Ajustes de Resyst VK')), None)
    return k, chip, x, gear


def key_bounds(k):
    n = k.get('q')
    if n is None:  # not `or`: a leaf Element is falsy
        n = k.get('Q')
    sp = next((n2 for d, n2 in k.items() if d.startswith('Espacio')), None)
    return (bounds(n) if n is not None else None, bounds(sp) if sp is not None else None)


def wait_for(fn, timeout=12.0, step=0.8):
    end = time.time() + timeout
    v = fn()
    while v is None and time.time() < end:
        time.sleep(step)
        v = fn()
    return v


def settings_texts():
    t = dump()
    return [((n.get('text') or '') or (n.get('content-desc') or '')).strip() for n in t.iter('node') if n.get('package') == PKG], t


def open_settings_update():
    sh(f'am start -W -n {SETTINGS} --es com.resyst.vk.section update')
    time.sleep(2.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--uptodate-apk', help='a build at the published version (up-to-date path)')
    ap.add_argument('--offline', action='store_true', help='also check the silent offline failure (airplane mode)')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    installed = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}')).group(1)
    print('installed', installed)
    sh(f'run-as {PKG} rm -f {PREFS}', check=False)  # no dismissed version from earlier runs

    # 1 — a fresh process: the IME's onCreate runs exactly one check, the chip appears on a fresh field
    fresh_process()
    e2e.open_host('text')
    p = pid()
    tree = wait_for(lambda: (lambda t: t if strip(t)[1] else None)(dump()), timeout=15)
    if tree is None:
        tree = dump()
    k, chip, x, gear = strip(tree)
    lines = log_lines()
    check('one GET at IME start (auto=true)', len(gets(lines)) == 1 and 'auto=true' in gets(lines)[0], ' | '.join(l.split('ResystVK')[-1] for l in lines)[:300])
    check('chip announces the published version', chip == 'Resyst VK 0.4.0-alpha disponible. Toca para actualizar', str(chip))
    dens = int(re.search(r'(\d+)\s*$', sh('wm density').strip().splitlines()[-1]).group(1)) / 160
    check('chip has a separate ≥48 dp ✕ target', x is not None and (bounds(k[x])[2] - bounds(k[x])[0]) >= 48 * dens - 2, f'{x} {bounds(k[x]) if x else None} dens={dens}')
    if chip:
        cb = bounds(k[chip])
        check('chip sits in the strip (leading, above the keys)', cb[1] < key_bounds(k)[0][1], f'chip={cb} q={key_bounds(k)[0]}')
    with_chip = key_bounds(k)
    time.sleep(0.5)
    shot('r9-chip.png')
    lp = sh(f'run-as {PKG} cat {PREFS}', check=False)
    check('lastAutoCheckAt recorded in prefs', 'name="autoAt"' in lp, re.sub(r'\s+', ' ', lp)[:160])

    # 2 — typing steps the chip down to the ⚙ dot; keys never move (N4, N5)
    e2e.type_word('ho', k)
    time.sleep(0.6)
    k2, chip2, x2, gear2 = strip(dump())
    check('typing swaps the chip for the ⚙ dot', chip2 is None and gear2 and 'Actualización 0.4.0-alpha disponible' in gear2, f'chip={chip2} gear={gear2}')
    check('keys do not move when the chip leaves (N5)', key_bounds(k2) == with_chip, f'{with_chip} → {key_bounds(k2)}')
    shot('r9-badge.png')

    # 3 — a second field in the same process: chip again, still ONE GET (no re-check per input view)
    e2e.open_host('multiline')
    k3, chip3, _, _ = strip(dump())
    check('fresh field shows the chip again', chip3 is not None, str(chip3))
    check('still one GET in this process (new input view ≠ new check)', len(gets(log_lines())) == 1 and pid() == p, f'pid {p}→{pid()}')

    # 4 — tap the chip → settings opens on the update card, populated WITHOUT a new GET (A4)
    if chip3:
        e2e.tap(k3[chip3])
        time.sleep(2.2)
        texts, t = settings_texts()
        card = [s for s in texts if s.startswith('Nueva versión disponible')]
        top = sh('dumpsys activity activities | grep -m1 topResumedActivity', check=False).strip()
        check('chip opens settings on the update card', 'SettingsActivity' in top and bool(card), f'{top[-70:]} {card}')
        check('card: version + download button', 'Nueva versión disponible: 0.4.0-alpha' in texts and any(s == 'Descargar e instalar 0.4.0-alpha' for s in texts), ', '.join(s for s in texts if 'versión' in s.lower() or 'Descargar' in s)[:200])
        check('card says where the state came from', any(s.startswith('Comprobado al iniciar el teclado') for s in texts), next((s for s in texts if s.startswith('Comprobado')), ''))
        check('toggle "Buscar actualizaciones al iniciar" shown ON', any(s.startswith('Buscar actualizaciones al iniciar, activado') for s in texts))
        check('settings reused the state: no new GET', len(gets(log_lines())) == 1, str(len(gets(log_lines()))))
        shot('r9-settings-card.png')
        sh('input keyevent KEYCODE_BACK')
        time.sleep(0.8)

    # 5 — ✕ dismisses for this version; persists across processes; ONE new GET in the new process
    e2e.open_host('text')
    k5, chip5, x5, _ = strip(dump())
    if x5:
        e2e.tap(k5[x5])
        time.sleep(0.8)
    k5b, chip5b, _, gear5b = strip(dump())
    check('✕ removes the chip and the dot', chip5b is None and gear5b == 'Ajustes de Resyst VK', f'chip={chip5b} gear={gear5b}')
    check('keys do not move on dismiss (N5)', key_bounds(k5b) == key_bounds(k5), f'{key_bounds(k5)} → {key_bounds(k5b)}')
    lp = sh(f'run-as {PKG} cat {PREFS}', check=False)
    check('dismissedVersion stored', 'name="dismissed">0.4.0-alpha<' in lp, re.sub(r'\s+', ' ', lp)[:200])
    shot('r9-dismissed.png')
    fresh_process()
    e2e.open_host('text')
    time.sleep(3.0)
    k6, chip6, _, gear6 = strip(dump())
    lines = log_lines()
    check('new process: one new GET', len(gets(lines)) == 1, str(len(gets(lines))))
    check('same version stays dismissed across restarts', chip6 is None and gear6 == 'Ajustes de Resyst VK', f'chip={chip6} gear={gear6}')
    open_settings_update()
    texts, _ = settings_texts()
    check('settings still offers the update after a keyboard dismiss', 'Nueva versión disponible: 0.4.0-alpha' in texts)
    sh('input keyevent KEYCODE_BACK')
    sh(f'run-as {PKG} rm -f {PREFS}', check=False)

    # 6 — toggle OFF → a fresh process makes no request at all
    open_settings_update()
    texts, t = settings_texts()
    tog = next((n for n in t.iter('node') if (n.get('content-desc') or '').startswith('Buscar actualizaciones al iniciar, activado')), None)
    if tog is not None:
        e2e.tap(tog)
        time.sleep(0.8)
    texts, t = settings_texts()
    check('toggle turns off', any(s.startswith('Buscar actualizaciones al iniciar, desactivado') for s in texts))
    check('privacy line follows the toggle', any(s.startswith('No busca actualizaciones por sí solo') for s in texts))
    shot('r9-settings-toggle-off.png')
    fresh_process()
    e2e.open_host('text')
    time.sleep(3.5)
    k7, chip7, _, _ = strip(dump())
    check('toggle off: zero GETs at IME start', len(gets(log_lines())) == 0 and chip7 is None, f'gets={len(gets(log_lines()))} chip={chip7}')
    open_settings_update()
    texts, t = settings_texts()
    check('toggle off: settings is idle (button, no fetch on open)', 'Buscar actualizaciones' in texts and len(gets(log_lines())) == 0)
    tog = next((n for n in t.iter('node') if (n.get('content-desc') or '').startswith('Buscar actualizaciones al iniciar, desactivado')), None)
    if tog is not None:
        e2e.tap(tog)  # restore ON
        time.sleep(0.6)
    sh('input keyevent KEYCODE_BACK')

    # 7 — offline: silent (no chip, settings idle — no error text), keyboard types normally
    if a.offline:
        # airplane mode alone keeps Wi-Fi on when the phone remembers "Wi-Fi in airplane mode"
        sh('cmd connectivity airplane-mode enable')
        sh('svc wifi disable')
        sh('svc data disable')
        time.sleep(5)
        down = sh('ping -c1 -W2 kv.resyst.cl >/dev/null 2>&1 && echo UP || echo DOWN', check=False).strip()
        check('offline precondition: kv.resyst.cl unreachable', down == 'DOWN', down)
        try:
            fresh_process()
            e2e.open_host('text')
            time.sleep(4)
            k8, chip8, _, gear8 = strip(dump())
            lines = log_lines()
            silent = [l for l in lines if 'update auto-check: silent' in l]
            check('offline: one GET attempted, failure silent in the log', len(gets(lines)) == 1 and bool(silent), ' | '.join(l.split('ResystVK')[-1] for l in lines)[:300])
            check('offline: no chip, no dot', chip8 is None and gear8 == 'Ajustes de Resyst VK', f'{chip8} {gear8}')
            e2e.type_word('hola', k8)
            time.sleep(0.4)
            check('offline: keyboard types normally', 'hola' in (e2e.field_text(dump()) or '').lower())
            open_settings_update()
            texts, _ = settings_texts()
            errors = ('Sin conexión a internet.', 'El servidor no respondió a tiempo.', 'No se pudo conectar.', 'No se pudo establecer una conexión segura.')
            check('offline: settings shows the idle button, no error', 'Buscar actualizaciones' in texts and not any(s in errors or s.startswith('El servidor respondió') for s in texts), ', '.join(texts)[:200])
            shot('r9-offline-settings.png')
            sh('input keyevent KEYCODE_BACK')
        finally:
            sh('cmd connectivity airplane-mode disable')
            sh('svc wifi enable')
            sh('svc data enable')
            time.sleep(8)

    # 8 — up to date (a build at the published version): no chip, settings says so without a tap
    if a.uptodate_apk:
        adb('install', '-r', '-d', a.uptodate_apk)
        e2e.ensure_ime()
        sh('logcat -c')
        e2e.open_host('text')
        time.sleep(4)
        k9, chip9, _, gear9 = strip(dump())
        check('up to date: no chip, no dot', chip9 is None and gear9 == 'Ajustes de Resyst VK', f'{chip9} {gear9}')
        check('up to date: one GET', len(gets(log_lines())) == 1)
        open_settings_update()
        texts, _ = settings_texts()
        up = next((s for s in texts if s.startswith('✓ Ya tienes la última versión')), '')
        check('settings: "Ya tienes la última versión" without tapping', up == '✓ Ya tienes la última versión (0.4.0-alpha).', up)
        check('up to date: settings did not fetch again', len(gets(log_lines())) == 1)
        shot('r9-settings-uptodate.png')
        sh('input keyevent KEYCODE_BACK')

    passed = sum(r['ok'] for r in rows)
    json.dump({'device': sh('getprop ro.product.model').strip(), 'api': sh('getprop ro.build.version.sdk').strip(),
               'installed_at_start': installed, 'manifest': 'https://kv.resyst.cl/release.json (live)',
               'passed': passed, 'total': len(rows), 'rows': rows},
              open(os.path.join(OUT, 'r9-e2e.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(f'{passed}/{len(rows)}')
    sys.exit(0 if passed == len(rows) else 1)


if __name__ == '__main__':
    main()
