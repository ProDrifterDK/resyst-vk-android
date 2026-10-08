#!/usr/bin/env python3
"""✦ Resyst VK — round 10 E2E (bet 2): the strip is for typing; the quick panel under ⚙.

Drives the debug build through the keyboard's accessibility tree (ExploreByTouchHelper virtual
views), like scripts/e2e.py. Each row below can fail; writes build/e2e-r10-quick/r10-quick.json +
screenshots (repeatable artifact).

Usage: scripts/e2e_r10_quick.py --serial SERIAL [--apk PATH]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, PKG  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r10-quick')
PREFS = f'/data/data/{PKG}/shared_prefs/resyst_vk_profiles.xml'
rows = []


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail else ''))


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def km():
    return keys(dump())


def key_top(k):
    """Top of the first key row (accents / digits / letters): everything above it is the strip."""
    return min(bounds(n)[1] for d, n in k.items() if len(d) == 1 or d in ('arroba', 'coma', 'punto'))


def strip_descs(k, _q_top=None):
    top = key_top(k)
    return sorted(d for d, n in k.items() if bounds(n)[3] <= top + 2)


def gear_of(k):
    """⚙ (its description carries the r9 update dot when an update is announced)."""
    return next((d for d in k if d.startswith('Ajustes de Resyst VK')), None)


UPDATE = ('Resyst VK ', 'Descartar el aviso', 'Pegar del portapapeles', 'Pegar imagen')  # QS1: the contextual paste chip is allowed


def letter(k, ch):
    n = k.get(ch)
    if n is None:
        n = k.get(ch.upper())
    return n


def prefs():
    return sh(f'run-as {PKG} cat {PREFS}', check=False)


def pref(name):
    m = re.search(r'name="%s">([^<]*)<' % re.escape(name), prefs())
    return m.group(1) if m else None


def open_host(kind):
    """e2e.open_host, retried: right after `pm clear` Android may still be falling back to the default keyboard."""
    for _ in range(3):
        tree, k = e2e.open_host(kind)
        if letter(k, 'q') is not None or kind in ('number', 'phone'):
            return tree, k
        time.sleep(1.5)
    return tree, k


def tile(k, label):
    return next(((d, n) for d, n in k.items() if d.startswith(label + ',')), (None, None))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    model = sh('getprop ro.product.model').strip()
    dens = int(re.search(r'(\d+)\s*$', sh('wm density').strip().splitlines()[-1]).group(1)) / 160
    adb('install', '-r', '-t', a.apk)
    sh(f'pm clear {PKG}', check=False)
    time.sleep(1.0)
    e2e.ensure_ime()
    time.sleep(1.0)

    # 1 — strip: suggestions + ⚙ only
    tree, k = open_host('text')
    q = letter(k, 'q')
    q_top = bounds(q)[1]
    strip = strip_descs(k, q_top)
    rest = [d for d in strip if not d.startswith('Ajustes de Resyst VK') and not d.startswith(UPDATE)]
    check('strip holds only ⚙ (+ the r9 update chip / «Pegar») on an empty field', gear_of(k) is not None and not rest, strip)
    check('no tema chip / clipboard / sun-moon in the strip',
          not any(d.startswith(('Perfil', 'Historial', 'Cambiar a tema')) for d in strip))
    gear = k.get(gear_of(k))
    gw = (bounds(gear)[2] - bounds(gear)[0]) / dens if gear is not None else 0
    check('⚙ is a ≥ 48 dp target', gw >= 47.5, f'{gw:.1f} dp')
    shot('01-strip.png')
    e2e.type_word('hol', k)
    time.sleep(0.6)
    k = km()
    strip = strip_descs(k, q_top)
    sugg = [d for d in strip if d.startswith('Sugerencia: ')]
    rest = [d for d in strip if not d.startswith(('Sugerencia: ', 'Ajustes de Resyst VK'))]
    check('typing: 3 suggestions + ⚙, nothing else', len(sugg) == 3 and gear_of(k) is not None and not rest, strip)
    shot('02-suggestions.png')

    # 2 — ⚙ tap opens the quick panel with 8 tiles + ‹ Teclado
    dotted = 'Actualización' in (gear_of(k) or '')  # the r9 chip stepped down to the ⚙ dot
    e2e.tap(k[gear_of(k)])
    time.sleep(0.6)
    k = km()
    labels = ['Día / noche', 'Tema', 'Modo', 'Portapapeles', 'Una mano', 'Altura', 'Edición', 'Ajustes']
    found = [tile(k, l)[0] for l in labels]
    check('quick panel: 8 tiles, each label + state', all(found), found)
    check('quick panel: ‹ Teclado closes', 'Volver al teclado' in k)
    upd = [d for d in k if d.startswith('Resyst VK') and 'disponible' in d]
    check('quick panel keeps the announced update reachable after typing (QP5)', bool(upd) == dotted, f'dot={dotted} line={upd}')
    small = [d for d in found if d and min(bounds(k[d])[2] - bounds(k[d])[0], bounds(k[d])[3] - bounds(k[d])[1]) / dens < 47.5]
    check('every tile ≥ 48 dp', not small, small)
    shot('03-quick-panel.png')

    # 3 — Día / noche flips the active tema's theme, stays in the panel
    before = pref('t.resyst.theme')
    d, n = tile(k, 'Día / noche')
    e2e.tap(n)
    time.sleep(0.8)
    after = pref('t.resyst.theme')
    k = km()
    check('Día / noche flips the theme (lab → paper)', before in (None, 'lab') and after == 'paper', f'{before} → {after}')
    check('panel stays open after a tile', tile(k, 'Tema')[0] is not None)
    shot('04-quick-day.png')
    e2e.tap(tile(k, 'Día / noche')[1])
    time.sleep(0.8)
    check('second tap returns to lab', pref('t.resyst.theme') == 'lab', pref('t.resyst.theme'))

    # 4 — Modo cycles Ninguno → Código; phone.* untouched by the mode
    k = km()
    phone_before = {m.group(1): m.group(2) for m in re.finditer(r'name="(phone\.[^"]+)">([^<]*)<', prefs())}
    e2e.tap(tile(k, 'Modo')[1])
    time.sleep(0.8)
    k = km()
    md = tile(k, 'Modo')[0]
    check('Modo → Código', md == 'Modo, Código' and pref('mode') == 'code', f'{md} mode={pref("mode")}')
    phone_after = {m.group(1): m.group(2) for m in re.finditer(r'name="(phone\.[^"]+)">([^<]*)<', prefs())}
    check('the mode never writes phone settings', not phone_before or phone_before == phone_after,
          {k2: (phone_before.get(k2), v) for k2, v in phone_after.items() if phone_before.get(k2) != v})
    shot('05-quick-mode-code.png')
    e2e.tap(tile(k, 'Modo')[1]); time.sleep(0.6)
    e2e.tap(tile(km(), 'Modo')[1]); time.sleep(0.6)
    check('Modo cycles back to Ninguno', tile(km(), 'Modo')[0] == 'Modo, Ninguno' and pref('mode') == 'none', pref('mode'))

    # 5 — Altura steps the tema height; keys move, the panel reflects it
    k = km()
    e2e.tap(tile(k, 'Altura')[1]); time.sleep(0.8)
    check('Altura 100 % → 110 %', tile(km(), 'Altura')[0] == 'Altura, 110 %' and pref('t.resyst.heightScale') == '1.1', pref('t.resyst.heightScale'))
    for _ in range(3):
        e2e.tap(tile(km(), 'Altura')[1]); time.sleep(0.6)
    check('Altura full turn → 100 %', tile(km(), 'Altura')[0] == 'Altura, 100 %', tile(km(), 'Altura')[0])

    # 6 — Edición is honest until bet 5 lands
    ed = tile(km(), 'Edición')[0]
    check('Edición tile present (disabled until the edit panel lands, or live)', ed is not None, ed)

    # 7 — Una mano: keys narrow to the right, a rail on the left; back to full width
    e2e.tap(tile(km(), 'Una mano')[1]); time.sleep(0.8)
    check('Una mano → Derecha', tile(km(), 'Una mano')[0] == 'Una mano, Derecha' and pref('phone.oneHanded') == 'RIGHT', pref('phone.oneHanded'))
    e2e.tap(km()['Volver al teclado']); time.sleep(0.6)
    k = km()
    q2 = letter(k, 'q')
    sw = int(re.search(r'(\d+)x(\d+)', sh('wm size')).group(1))
    left = bounds(q2)[0]
    rail = [d for d in k if d in ('Mover el teclado a la izquierda', 'Teclado a todo el ancho')]
    check('one-handed: keys start right of a rail', left / sw >= 0.12 and len(rail) == 2, f'q.left={left}/{sw} rail={rail}')
    rail_w = min((bounds(k[d])[2] - bounds(k[d])[0]) / dens for d in rail) if rail else 0
    check('one-handed rail buttons ≥ 48 dp wide', rail_w >= 47.5, f'{rail_w:.1f} dp')
    shot('06-one-hand-right.png')
    e2e.tap(k['Mover el teclado a la izquierda']); time.sleep(0.8)
    k = km()
    q3 = letter(k, 'q')
    check('rail moves the keys left', bounds(q3)[0] < left and 'Mover el teclado a la derecha' in k and pref('phone.oneHanded') == 'LEFT', f'q.left={bounds(q3)[0]}')
    shot('07-one-hand-left.png')
    e2e.tap(k['Teclado a todo el ancho']); time.sleep(0.8)
    k = km()
    check('rail restores full width', bounds(letter(k, 'q'))[0] == bounds(q)[0] and pref('phone.oneHanded') == 'OFF', f'q.left={bounds(letter(k, "q"))[0]} vs {bounds(q)[0]}')

    # 8 — Portapapeles tile: opens the history; closed in a password field
    e2e.tap(k[gear_of(k)]); time.sleep(0.6)
    e2e.tap(tile(km(), 'Portapapeles')[1]); time.sleep(0.6)
    k = km()
    check('Portapapeles tile opens the history panel', any('portapapeles' in d.lower() and 'Ajustes' not in d for d in k) and tile(k, 'Tema')[0] is None, [d for d in k if 'apeles' in d][:3])
    tree, k = open_host('password')
    e2e.tap(k[gear_of(k)]); time.sleep(0.6)
    k = km()
    cd = tile(k, 'Portapapeles')[0]
    check('password field: Portapapeles tile disabled, says why', cd == 'Portapapeles, No en contraseñas, no disponible', cd)
    shot('08-quick-password.png')
    e2e.tap(k['Volver al teclado']); time.sleep(0.4)

    # 9 — vector keys: shift / ⌫ / enter still described + work
    tree, k = open_host('search')
    check('enter (search) is a vector key described as Buscar', 'Buscar' in k)
    check('shift + ⌫ present', 'Mayúsculas' in k or any(d.startswith('Mayúsculas') for d in k) and 'Borrar' in k)
    shot('09-search-keys.png')

    # 10 — long-press ⚙ opens Settings
    gear = k.get(gear_of(k))
    e2e.tap(gear, hold_ms=800)
    time.sleep(1.6)
    top = sh('dumpsys activity activities | grep -m1 topResumedActivity', check=False)
    check('long-press ⚙ opens Settings', 'SettingsActivity' in top, top.strip()[-60:])
    shot('10-settings.png')
    sh('input keyevent KEYCODE_BACK')

    passed = sum(r['ok'] for r in rows)
    json.dump({'device': model, 'passed': passed, 'total': len(rows), 'checks': rows},
              open(os.path.join(OUT, 'r10-quick.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(f'✦ {passed}/{len(rows)} → build/e2e-r10-quick/r10-quick.json')
    return 0 if passed == len(rows) else 1


if __name__ == '__main__':
    sys.exit(main())
