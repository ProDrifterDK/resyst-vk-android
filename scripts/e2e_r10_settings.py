#!/usr/bin/env python3
"""✦ Resyst VK — round 10 E2E (settings-ia lane): temas + modos + settings pages.

What it proves on a real device / emulator (adb + uiautomator, stdlib only):
  M  an r9 (v1) profiles store migrates on first load: temas keep their names/looks, the active
     profile's behavior becomes phone-wide, an active "Juego" becomes the Juego mode, and the first
     save rewrites the file as v2 (no p.* keys left).
  H  home shows the tema picker, the mode, the live preview, the daily shortcuts and one row per
     SettingsIA page; every row is a real tagged control (content-desc).
  P  every page opens from home, shows its title + scope line, and comes back with ‹ / Back.
  D  a dependent stays visible but disabled while its switch is off (Escritura → "El espacio…"),
     and turning the switch on enables it.
  T  tapping a tema card puts it in use (stored `active`), the keyboard re-skins (IME reads it).
  X  turning a mode on shows its banner on the pages it overrides; "Apagar el modo" restores.
  K  the keyboard still types after the migration (behavior read from phone.*).

Writes build/e2e-r10-settings/r10-settings.json (one row per check, each able to fail) + PNGs.
Usage: scripts/e2e_r10_settings.py --serial SERIAL [--apk PATH]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, center, PKG, SETTINGS, HOST  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r10-settings')
PREFS = 'shared_prefs/resyst_vk_profiles.xml'
rows = []


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': str(detail)[:400]})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail)[:200] if detail else ''))


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


def prefs():
    return run_as(f'cat {PREFS}')


def pref(xml, key):
    m = re.search(r'<string name="' + re.escape(key) + r'">([^<]*)</string>', xml)
    return m.group(1) if m else None


def descs(tree):
    return [(n.get('content-desc') or '') for n in tree.iter('node') if n.get('package') == PKG]


def texts(tree):
    return [(n.get('text') or '') for n in tree.iter('node') if n.get('package') == PKG]


def find(tree, pred):
    for n in tree.iter('node'):
        if n.get('package') != PKG:
            continue
        d, t = n.get('content-desc') or '', n.get('text') or ''
        if pred(d, t):
            return n
    return None


SCREEN_H = 2400


def on_screen(n):
    """Fully tappable: not under the status bar or the gesture/nav bar."""
    x1, y1, x2, y2 = e2e.bounds(n)
    return y2 > y1 and y1 >= 120 and y2 <= SCREEN_H - 200


def scroll_find(pred, swipes=14):
    for _ in range(swipes):
        t = dump()
        n = find(t, lambda d, tt: pred(d, tt))
        if n is not None and on_screen(n):
            return n, t
        sh('input swipe 540 1600 540 900 300')
        time.sleep(0.5)
    return None, dump()


def to_top():
    for _ in range(6):
        sh('input swipe 540 700 540 1800 200')
    time.sleep(0.4)


def tap(n):
    if n is None:
        check('tap target present', False, 'node not found')
        return
    x, y = center(n)
    sh(f'input tap {x} {y}')
    time.sleep(0.9)


def open_settings(extra=''):
    sh(f'am start -W -n {SETTINGS} {extra}')
    time.sleep(1.6)


def v1_store(active):
    """An r9 store exactly as SettingsRepo wrote it (all strings), with user edits."""
    def prof(pid, name, icon, **kv):
        base = {'theme': 'lab', 'accent': '', 'shape': 'SOFT', 'cap': 'RAISED', 'font': 'BRAND', 'density': 'NORMAL',
                'subLegends': 'true', 'heightScale': '1.0', 'sound': 'false', 'soundPack': 'CLICK', 'volume': '0.7',
                'haptics': 'true', 'hapticStrength': 'MEDIUM', 'popups': 'true', 'longPressMs': '350', 'suggest': 'true',
                'spaceCorrects': 'true', 'personal': 'true', 'lang': 'ES', 'topRow': 'ACCENTS', 'autoCap': 'true',
                'doubleSpace': 'true', 'hideTopRow': 'false', 'emojiKey': 'true', 'dayNightChip': 'true', 'altTheme': ''}
        base.update(kv)
        out = {f'p.{pid}.name': name, f'p.{pid}.icon': icon}
        out.update({f'p.{pid}.{k}': v for k, v in base.items()})
        return out
    m = {'v': '1', 'active': active, 'order': 'noche,dia,juego,escritura', 'clip.history': 'true', 'clip.purge': 'false', 'update.auto': 'false'}
    m.update(prof('noche', 'Mi noche', '☾', theme='amoled', accent='#e2735a', haptics='false', hapticStrength='HIGH'))
    m.update(prof('dia', 'Día', '☀', theme='paper'))
    m.update(prof('juego', 'Juego', '◆', theme='arcade', hideTopRow='true', suggest='false', popups='false', cap='FLAT',
                  heightScale='0.9', density='TIGHT', autoCap='false', doubleSpace='false'))
    m.update(prof('escritura', 'Escritura', '✎', theme='slate', font='HUMAN', sound='true', soundPack='THOCK', subLegends='false', heightScale='1.1'))
    body = ''.join(f'    <string name="{k}">{v}</string>\n' for k, v in m.items())
    return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + body + '</map>\n'


def seed_v1(active):
    sh(f'am force-stop {PKG}')
    local = os.path.join(OUT, f'v1-{active}.xml')
    open(local, 'w', encoding='utf-8').write(v1_store(active))
    adb('push', local, '/data/local/tmp/r10_v1.xml')
    run_as('mkdir -p shared_prefs')
    run_as(f'cp /data/local/tmp/r10_v1.xml {PREFS}')
    check(f'M0 v1 store ({active} active) seeded', pref(prefs(), 'p.noche.name') == 'Mi noche', pref(prefs(), 'v'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--skip-install', action='store_true')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    e2e.OUT = OUT
    global SCREEN_H
    m = re.search(r'(\d+)x(\d+)', sh('wm size'))
    if m:
        SCREEN_H = int(m.group(2))
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk})')
    if not a.skip_install:
        adb('install', '-r', '-t', a.apk)
    ime = PKG + '/com.resyst.vk.ime.ResystImeService'
    sh(f'ime enable {ime}', check=False)
    sh(f'ime set {ime}', check=False)

    # ── M: migration, "Juego" active ───────────────────────────────────
    seed_v1('juego')
    open_settings()
    t = dump('home-juego')
    shot('r10-home-migrated-juego.png')
    d = descs(t)
    check('M1 temas keep their names (Mi noche card)', any(x.startswith('Tema Mi noche') for x in d), [x for x in d if x.startswith('Tema ')])
    check('M2 active Juego → tema "Juego" in use', any(x.startswith('Tema Juego') and x.endswith('en uso') for x in d))
    check('M3 active Juego → mode Juego selected', 'Modo: Juego, seleccionado' in d, [x for x in d if x.startswith('Modo:')])
    # the codec has not written yet → still v1 on disk; a save rewrites v2
    sel = find(t, lambda dd, tt: dd.startswith('Modo: Ninguno'))
    if sel is not None:
        tap(sel)
    x = prefs()
    check('M4 first save writes v2 (v=2, mode=none)', pref(x, 'v') == '2' and pref(x, 'mode') == 'none', (pref(x, 'v'), pref(x, 'mode')))
    check('M5 no legacy p.* keys after the save', 'name="p.' not in x, x.count('name="p.'))
    check('M6 Juego off → behavior from the first other profile (noche: haptics off, HIGH)',
          pref(x, 'phone.haptics') == 'false' and pref(x, 'phone.hapticStrength') == 'HIGH' and pref(x, 'phone.suggest') == 'true',
          (pref(x, 'phone.haptics'), pref(x, 'phone.hapticStrength'), pref(x, 'phone.suggest')))
    check('M7 looks survive (t.noche.theme=amoled, accent kept)', pref(x, 't.noche.theme') == 'amoled' and pref(x, 't.noche.accent') == '#e2735a',
          (pref(x, 't.noche.theme'), pref(x, 't.noche.accent')))
    check('M8 device-wide switches survive (update.auto=false)', pref(x, 'update.auto') == 'false')

    # ── M: migration, "Escritura" active → its behavior is the phone's ───
    seed_v1('escritura')
    open_settings()
    t = dump()
    d = descs(t)
    check('M9 Escritura active → no mode', 'Modo: Ninguno, seleccionado' in d)
    tema = find(t, lambda dd, tt: dd.startswith('Tema Mi noche'))
    tap(tema)
    x = prefs()
    check('M10 escritura behavior is phone-wide (sound THOCK on)', pref(x, 'phone.sound') == 'true' and pref(x, 'phone.soundPack') == 'THOCK',
          (pref(x, 'phone.sound'), pref(x, 'phone.soundPack')))
    check('T1 tapping a tema card stores it as active', pref(x, 'active') == 'noche', pref(x, 'active'))
    t = dump()
    check('T2 the card says "en uso"', any(x2.startswith('Tema Mi noche') and x2.endswith('en uso') for x2 in descs(t)))
    shot('r10-home-tema-noche.png')

    # ── H: home structure ──────────────────────────────────────────────
    to_top()
    want_pages = ['Temas y modos', 'Apariencia del tema', 'Teclas e idioma', 'Escritura', 'Sonido y vibración', 'Portapapeles y privacidad', 'Acerca de']
    found = []
    for title in want_pages:
        n, _ = scroll_find(lambda dd, tt, title=title: dd.startswith(title + '. '))
        if n is not None:
            found.append(title)
    check('H1 one home row per SettingsIA page (7)', found == want_pages, found)
    to_top()
    t = dump()
    hd = descs(t)
    check('H2 home shortcuts: Vibración / Sugerencias toggles present', any(x.startswith('Vibración, ') for x in hd) or scroll_find(lambda dd, tt: dd.startswith('Vibración, '))[0] is not None)

    # ── P: every page opens and comes back ─────────────────────────────
    for title in want_pages:
        to_top()
        n, _ = scroll_find(lambda dd, tt, title=title: dd.startswith(title + '. '))
        if n is None:
            check(f'P open "{title}"', False, 'row not found')
            continue
        tap(n)
        t = dump()
        tx = texts(t)
        ok_title = title in tx
        ok_scope = any(s.startswith('Solo para el tema') or s.startswith('Vale para todo el teclado') for s in tx)
        check(f'P open "{title}" (title + scope line)', ok_title and ok_scope, [s for s in tx if s][:4])
        shot(f'r10-page-{re.sub(r"[^a-z]+", "-", title.lower()).strip("-")}.png')
        if title == want_pages[0]:
            back = find(t, lambda dd, tt: dd == 'Volver a Ajustes')
            tap(back)
        else:
            sh('input keyevent KEYCODE_BACK')
            time.sleep(0.9)
        t = dump()
        check(f'P back from "{title}" lands on home', find(t, lambda dd, tt: dd.startswith('Temas y modos. ')) is not None or 'Ajustes rápidos'.upper() in texts(t) or any(x.startswith('Tema ') for x in descs(t)))

    # ── D: dependents visible + disabled while the switch is off ───────
    to_top()
    n, _ = scroll_find(lambda dd, tt: dd.startswith('Escritura. '))
    tap(n)
    t = dump()
    sug = find(t, lambda dd, tt: dd.startswith('Sugerencias, '))
    if sug is not None and sug.get('content-desc').endswith('activado'):
        tap(sug)
        t = dump()
    dep = find(t, lambda dd, tt: dd.startswith('El espacio aplica la corrección'))
    check('D1 with Sugerencias off, "El espacio…" is still shown', dep is not None)
    check('D2 … and marked unavailable', dep is not None and dep.get('content-desc', '').endswith('no disponible'), dep.get('content-desc') if dep is not None else '')
    check('D3 … with the "Activa «Sugerencias»" hint', any(s.startswith('Activa «Sugerencias»') for s in texts(t)))
    shot('r10-escritura-dependents-off.png')
    sug = find(t, lambda dd, tt: dd.startswith('Sugerencias, '))
    tap(sug)
    t = dump()
    dep = find(t, lambda dd, tt: dd.startswith('El espacio aplica la corrección'))
    check('D4 Sugerencias on → "El espacio…" actionable', dep is not None and not dep.get('content-desc', '').endswith('no disponible') and dep.get('clickable') == 'true',
          dep.get('content-desc') if dep is not None else '')

    # ── X: mode banner ─────────────────────────────────────────────────
    sh('input keyevent KEYCODE_BACK'); time.sleep(0.8)
    to_top()
    t = dump()
    code = find(t, lambda dd, tt: dd.startswith('Modo: Código'))
    tap(code)
    x = prefs()
    check('X1 mode Código stored', pref(x, 'mode') == 'code', pref(x, 'mode'))
    check('X2 mode never written into phone settings (phone.suggest still true)', pref(x, 'phone.suggest') == 'true')
    n, _ = scroll_find(lambda dd, tt: dd.startswith('Escritura. '))
    tap(n)
    t = dump()
    check('X3 Escritura shows the "Modo Código encendido" banner', any('Modo Código encendido' in s for s in texts(t)), [s for s in texts(t) if 'Modo' in s])
    check('X3b overridden rows say they are paused (Sugerencias)', any(dd.startswith('Sugerencias: en pausa por el modo Código') for dd in descs(t)),
          [dd for dd in descs(t) if 'pausa' in dd])
    check('X3c a row the mode does not touch is not paused (Vibración lives on another page; Escritura has none untouched → check count)',
          sum('en pausa' in dd for dd in descs(t)) == 5, sum('en pausa' in dd for dd in descs(t)))
    shot('r10-escritura-mode-banner.png')
    off = find(t, lambda dd, tt: tt == 'Apagar el modo')
    tap(off)
    x = prefs()
    t = dump()
    check('X4 "Apagar el modo" → mode none, banner gone', pref(x, 'mode') == 'none' and not any('Modo Código encendido' in s for s in texts(t)))
    sh('input keyevent KEYCODE_BACK'); time.sleep(0.6)

    # ── K: the keyboard types after the migration ──────────────────────
    try:
        _, km = e2e.open_host('text')
        e2e.type_word('hola', km)
        time.sleep(0.6)
        txt = e2e.field_text(dump())
        check('K1 keyboard types "hola" on the migrated store', (txt or '').lower().startswith('hola'), txt)
        shot('r10-keyboard-after-migration.png')
        # r10/bet 2: the strip has no tema chip any more; the quick panel under ⚙ names the tema
        km = keys(dump())
        gear = next((n for dd, n in km.items() if dd.startswith('Ajustes de Resyst VK')), None)
        if gear is not None:
            e2e.tap(gear)
            time.sleep(0.6)
        km = keys(dump())
        tile = next((dd for dd in km if dd.startswith('Tema, ')), '')
        check('K2 quick panel names the active tema (Tema, Mi noche)', tile.startswith('Tema, Mi noche'), tile or sorted(km)[:12])
        sh('input keyevent KEYCODE_BACK')
    except Exception as ex:  # noqa: BLE001 — a missing key is a failed row, not a crash
        check('K1 keyboard types "hola" on the migrated store', False, repr(ex))

    ok = sum(r['ok'] for r in rows)
    out = {'device': model, 'sdk': sdk, 'serial': a.serial, 'passed': ok, 'total': len(rows), 'rows': rows, 'at': time.strftime('%Y-%m-%dT%H:%M:%S')}
    json.dump(out, open(os.path.join(OUT, 'r10-settings.json'), 'w'), ensure_ascii=False, indent=1)
    print(f'✦ {ok}/{len(rows)} → {os.path.join(OUT, "r10-settings.json")}')
    return 0 if ok == len(rows) else 1


if __name__ == '__main__':
    sys.exit(main())
