#!/usr/bin/env python3
"""✦ Resyst VK — round 10 E2E (bet 5, UI half): the edit panel (⚙ → Edición) and the ⌫ swipe.

Every check reads the real field text back through uiautomator, so the panel's buttons are proven
to reach the app through the InputConnection (cut/paste via the app's own context menu actions).
Writes build/e2e-r10-edit/r10-edit.json + screenshots.

Usage: scripts/e2e_r10_edit.py --serial SERIAL [--apk PATH] [--no-install]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, field_text, PKG  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r10-edit')
rows = []


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail else ''))


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def km():
    return keys(dump())


def text():
    return field_text(dump())


def gear(k):
    return next((d for d in k if d.startswith('Ajustes de Resyst VK')), None)


def node(k, prefix):
    return next((n for d, n in k.items() if d == prefix or d.startswith(prefix + ',')), None)


def open_host(kind):
    for _ in range(3):
        tree, k = e2e.open_host(kind)
        if k.get('q') is not None or k.get('Q') is not None:
            return tree, k
        time.sleep(1.5)
    return tree, k


def open_edit():
    k = km()
    e2e.tap(k[gear(k)])
    time.sleep(0.5)
    t = node(km(), 'Edición')
    e2e.tap(t)
    time.sleep(0.5)
    return km()


def press(k, prefix):
    n = node(k, prefix)
    if n is None:
        raise RuntimeError(f'{prefix!r} not found in {sorted(k)[:40]}')
    e2e.tap(n)
    time.sleep(0.35)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--no-install', action='store_true')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    model = sh('getprop ro.product.model').strip()
    dens = int(re.search(r'(\d+)\s*$', sh('wm density').strip().splitlines()[-1]).group(1)) / 160
    if not a.no_install:
        adb('install', '-r', '-t', a.apk)
        sh(f'pm clear {PKG}', check=False)
        time.sleep(1.0)
    e2e.ensure_ime()
    time.sleep(1.0)

    # 1 — the Edición tile is live and opens the panel
    tree, k = open_host('text')
    e2e.type_word('hola', k)
    e2e.tap(km()['Espacio'])
    e2e.type_word('mundo')
    time.sleep(0.4)
    check('typed "Hola mundo"', text() == 'Hola mundo', repr(text()))
    k = km()
    e2e.tap(k[gear(k)])
    time.sleep(0.5)
    tile = next((d for d in km() if d.startswith('Edición,')), None)
    check('quick panel: Edición tile is live', tile == 'Edición, Cursor y selección', tile)
    e2e.tap(node(km(), 'Edición'))
    time.sleep(0.5)
    k = km()
    names = ['Izquierda', 'Derecha', 'Arriba', 'Abajo', 'Inicio de línea', 'Fin de línea', 'Seleccionar',
             'Seleccionar todo', 'Copiar', 'Cortar', 'Pegar', 'Borrar palabra']
    missing = [n for n in names if node(k, n) is None]
    check('edit panel: 12 ops + ‹ Teclado', not missing and 'Volver al teclado' in k, missing)
    small = [d for d in k if d.split(',')[0] in names and min(bounds(k[d])[2] - bounds(k[d])[0], bounds(k[d])[3] - bounds(k[d])[1]) / dens < 47.5]
    check('every edit button ≥ 48 dp', not small, small)
    shot('01-edit-panel.png')

    # 2 — Borrar palabra deletes the word before the cursor
    press(k, 'Borrar palabra')
    check('Borrar palabra: "Hola mundo" → "Hola "', text() == 'Hola ', repr(text()))

    # 3 — arrows move the caret: ← ← then type "x" after closing
    press(km(), 'Izquierda')
    press(km(), 'Izquierda')
    press(km(), 'Volver al teclado')
    check('‹ Teclado closes the panel', km().get('q') is not None or km().get('Q') is not None)
    e2e.type_word('x')
    time.sleep(0.3)
    check('← ← moved the caret 2 left ("Holxa ")', text() == 'Holxa ', repr(text()))

    # 4 — Seleccionar latches; Todo + Cortar empties the field; Pegar brings it back
    k = open_edit()
    press(k, 'Seleccionar')
    sel = next((d for d in km() if d.startswith('Seleccionar,')), None)
    check('Seleccionar latches (TalkBack: activado)', sel == 'Seleccionar, activado', sel)
    check('arrows announce they extend the selection', node(km(), 'Izquierda') is not None and
          any(d == 'Izquierda, extiende la selección' for d in km()))
    shot('02-edit-selecting.png')
    press(km(), 'Seleccionar')
    press(km(), 'Seleccionar todo')
    press(km(), 'Cortar')
    time.sleep(0.4)
    check('Todo + Cortar empties the field', text() == '', repr(text()))
    press(km(), 'Pegar')
    time.sleep(0.4)
    check('Pegar restores it', text() == 'Holxa ', repr(text()))
    sel = next((d for d in km() if d.startswith('Seleccionar,')), None)
    check('after Pegar the selection mode is off', sel == 'Seleccionar, desactivado', sel)

    # 5 — hold ← repeats (a caret walk, not one step)
    n = node(km(), 'Izquierda')
    x, y = e2e.center(n)
    sh(f'input swipe {x} {y} {x} {y} 1100')
    time.sleep(0.3)
    press(km(), 'Volver al teclado')
    e2e.type_word('z')
    time.sleep(0.3)
    t = text()
    check('holding ← walks the caret to the start (repeat)', t in ('ZHolxa ', 'zHolxa '), repr(t))

    # 6 — ⌫ swipe left deletes the previous word
    tree, k = open_host('text')
    e2e.type_word('uno', k)
    e2e.tap(km()['Espacio'])
    e2e.type_word('dos')
    time.sleep(0.3)
    bs = km()['Borrar']
    x1, y1, x2, y2 = bounds(bs)
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    sh(f'input swipe {cx} {cy} {cx - int(90 * dens)} {cy} 160')
    time.sleep(0.5)
    check('⌫ swipe left deletes the word ("Uno dos" → "Uno ")', text() == 'Uno ', repr(text()))
    e2e.tap(km()['Borrar'])
    time.sleep(0.3)
    check('a plain ⌫ tap still deletes one char', text() == 'Uno', repr(text()))
    shot('03-after-swipe.png')

    # 7 — password field: Copiar / Cortar disabled and say why
    tree, k = open_host('password')
    e2e.type_word('abc', k)
    k = open_edit()
    cp = next((d for d in k if d.startswith('Copiar,')), None)
    ct = next((d for d in k if d.startswith('Cortar,')), None)
    check('password: Copiar disabled, says why', cp == 'Copiar, no disponible en contraseñas', cp)
    check('password: Cortar disabled, says why', ct == 'Cortar, no disponible en contraseñas', ct)
    shot('04-edit-password.png')
    press(km(), 'Volver al teclado')

    passed = sum(r['ok'] for r in rows)
    json.dump({'device': model, 'passed': passed, 'total': len(rows), 'checks': rows},
              open(os.path.join(OUT, 'r10-edit.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(f'✦ {passed}/{len(rows)} → build/e2e-r10-edit/r10-edit.json')
    return 0 if passed == len(rows) else 1


if __name__ == '__main__':
    sys.exit(main())
