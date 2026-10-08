#!/usr/bin/env python3
"""✦ Resyst VK — round 10 E2E (privacy-visible lane, bet 3): the privacy you can see.

What it proves on a real device / emulator (adb + uiautomator, stdlib only), one row per check,
each able to fail:
  F  offensive-word filter: typing "mier" never offers "mierda" with the filter on (default);
     with it off (phone.profanityFilter=false) the r9 bar is back and offers it.
  L  "Lo que sé de ti": words the keyboard learned are listed with their count; × forgets one
     (gone from the page AND from words.json on disk), the others stay.
  C  "Libro de conexiones": a manual "Buscar actualizaciones" adds exactly one entry (logcat GET
     count == log delta), with reason "tú lo pediste"; the page shows the total.
  S  secret field (password): no key-preview bubble node shows while a key is held, the strip's
     "Sin memoria" mark is there with its reason, and nothing typed there is learned (words.json).
  N  incognito-free plain text field: no "Sin memoria" mark (memory is on there).

Writes build/e2e-r10-privacy/r10-privacy.json + screenshots.
Usage: scripts/e2e_r10_privacy.py --serial SERIAL [--apk PATH] [--skip-install] [--no-network]
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

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r10-privacy')
PROFILES = 'shared_prefs/resyst_vk_profiles.xml'
UPDATE = 'shared_prefs/resyst_vk_update.xml'
WORDS = 'files/personal/words.json'
rows = []


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': str(detail)[:400]})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail)[:200] if detail else ''))


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


def descs(tree):
    return [(n.get('content-desc') or '') for n in tree.iter('node') if n.get('package') == PKG]


def find(tree, pred):
    for n in tree.iter('node'):
        if n.get('package') != PKG:
            continue
        if pred(n.get('content-desc') or '', n.get('text') or ''):
            return n
    return None


def tap(n, wait=0.9):
    x, y = center(n)
    sh(f'input tap {x} {y}')
    time.sleep(wait)


SCREEN_H = 2400


def on_screen(n):
    x1, y1, x2, y2 = e2e.bounds(n)
    return y2 > y1 and y1 >= 120 and y2 <= SCREEN_H - 200


def scroll_find(pred, swipes=14):
    for _ in range(swipes):
        t = dump()
        n = find(t, pred)
        if n is not None and on_screen(n):
            return n, t
        sh('input swipe 540 1600 540 900 300')
        time.sleep(0.5)
    return None, dump()


def log_total(xml):
    """The connection log's total from the updater prefs XML (quotes are &quot;-escaped there)."""
    m = re.search(r'total(?:&quot;|")\s*:\s*(\d+)', xml or '')
    return int(m.group(1)) if m else 0


def suggestions(tree=None):
    t = tree if tree is not None else dump()  # not `or`: an Element without children is falsy
    return [d[len('Sugerencia: '):] for d in keys(t) if d.startswith('Sugerencia: ')]


def set_phone(key, value):
    """Edit one phone-wide setting in the v2 store (the IME reloads on the prefs listener)."""
    sh(f'am force-stop {PKG}')
    xml = run_as(f'cat {PROFILES}')
    if '<map' not in xml:  # fresh install: settings write the store only on the first edit
        xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
    pat = re.compile(r'<string name="' + re.escape(key) + r'">[^<]*</string>')
    line = f'<string name="{key}">{value}</string>'
    xml = pat.sub(line, xml) if pat.search(xml) else xml.replace('</map>', f'    {line}\n</map>')
    local = os.path.join(OUT, 'profiles.xml')
    open(local, 'w', encoding='utf-8').write(xml)
    adb('push', local, '/data/local/tmp/r10p.xml')
    run_as(f'cp /data/local/tmp/r10p.xml {PROFILES}')


def keyboard_lang():
    m = re.search(r'<string name="phone.lang">(\w+)</string>', run_as(f'cat {PROFILES}'))
    return m.group(1) if m else 'ES'


def type_in_host(kind, word, settle=1.2):
    tree, km = e2e.open_host(kind)
    e2e.type_word(word, km)
    time.sleep(settle)
    return dump()


def open_page(page):
    sh(f'am start -W -n {SETTINGS} --es com.resyst.vk.page {page}')
    time.sleep(1.6)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--skip-install', action='store_true')
    ap.add_argument('--no-network', action='store_true', help='skip the manual update check (C rows)')
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
    started = time.time()
    if not a.skip_install:
        adb('install', '-r', '-t', a.apk)
    # clean slate: no learned words, no logs, startup check off (only our manual check counts)
    sh(f'pm clear {PKG}', check=False)
    ime = PKG + '/com.resyst.vk.ime.ResystImeService'
    sh(f'ime enable {ime}', check=False)
    sh(f'ime set {ime}', check=False)
    open_page('acerca')  # first open writes the v2 store
    sh(f'am force-stop {PKG}')
    set_phone('update.auto', 'false')

    # ── F: offensive-word filter ───────────────────────────────────────
    # The emulator's system language can pull the keyboard to EN (subtype sync after a restart),
    # so each probe follows the language the keyboard runs at that moment and is re-typed if it
    # flipped meanwhile: "mier" → mierda (ES), "fuc" → fuck (EN).
    def probe_bar():
        for _ in range(3):
            lang = keyboard_lang()
            probe, bad = ('fuc', 'fuck') if lang == 'EN' else ('mier', 'mierda')
            t = type_in_host('text', probe)
            if keyboard_lang() == lang:
                return lang, probe, bad, t
        return lang, probe, bad, t

    lang, probe, bad, t = probe_bar()
    on = suggestions(t)
    shot('r10p-filter-on.png')
    check(f'F0 typed "{probe}" into the field (keyboard in {lang})', (e2e.field_text(t) or '').lower() == probe, e2e.field_text(t))
    check(f'F1 filter ON (default): "{probe}" does not offer {bad}', not any(s.lower() == bad for s in on), on)
    check('F2 filter ON: the bar still offers clean words', len(on) > 0, on)
    set_phone('phone.profanityFilter', 'false')
    lang, probe, bad, t = probe_bar()
    off = suggestions(t)
    check(f'F3 filter OFF: the r9 bar is back ({lang}: "{probe}" offers {bad})', any(s.lower() == bad for s in off), (e2e.field_text(t), off))
    set_phone('phone.profanityFilter', 'true')

    # ── L: learn two words, list them, forget one ──────────────────────
    tree, km = e2e.open_host('text')
    for w in ['ornitorrinco', 'ornitorrinco', 'cachalote', 'cachalote']:
        km = keys(dump())
        e2e.type_word(w, km)
        km = keys(dump())
        e2e.tap(km['Espacio'])
    time.sleep(2.5)  # PersonalStore debounce (1.5 s) → words.json
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.6)
    sh(f'am start -W -n {SETTINGS} --es com.resyst.vk.page datos')
    time.sleep(2.0)
    t = dump('know')
    shot('r10p-know.png')
    d = descs(t)
    has = lambda w: any(x == f'Borrar la palabra {w}' for x in d)
    check('L1 "Lo que sé de ti" lists learned words (×count)', has('ornitorrinco') and has('cachalote'), [x for x in d if x.startswith('Borrar ')][:8])
    check('L2 the page says where it lives', any('nunca se envía' in (n.get('text') or '') for n in t.iter('node')))
    x = find(t, lambda dd, tt: dd == 'Borrar la palabra ornitorrinco')
    if x is not None:
        tap(x)
    time.sleep(2.5)
    d2 = descs(dump())
    check('L3 × forgets that word on the page', not any(s == 'Borrar la palabra ornitorrinco' for s in d2) and any(s == 'Borrar la palabra cachalote' for s in d2))
    sh(f'am force-stop {PKG}')  # flush is debounced; force-stop after the debounce window
    words = run_as(f'cat {WORDS}')
    check('L4 forgotten on disk (words.json), the other word kept', 'ornitorrinco' not in words and 'cachalote' in words, len(words))

    # ── C: connection log ──────────────────────────────────────────────
    if not a.no_network:
        sh('logcat -c')
        open_page('acerca')
        n, t = scroll_find(lambda dd, tt: tt in ('Buscar actualizaciones', 'Buscar de nuevo', 'Reintentar'))
        tot_before = log_total(run_as(f'cat {UPDATE}'))
        if n is not None:
            tap(n, wait=4.0)
        gets = [l for l in adb('logcat', '-d', '-v', 'brief', '-s', 'ResystVK:I').splitlines() if 'update check: GET release.json' in l]
        after = run_as(f'cat {UPDATE}')
        tot_after = log_total(after)
        check('C1 one manual check = one GET in logcat', len(gets) == 1, gets)
        check('C2 the log grew by exactly the GETs made', tot_after - tot_before == len(gets), (tot_before, tot_after))
        check('C3 the entry says why: tú lo pediste', '&quot;user&quot;' in after or '"user"' in after, after[-200:])
        open_page('acerca')
        n, t = scroll_find(lambda dd, tt: tt.startswith('1 conexión a internet') or 'conexiones a internet desde la instalación' in tt)
        shot('r10p-connections.png')
        check('C4 Acerca de shows the total since install', n is not None, n.get('text') if n is not None else None)
        e = find(t, lambda dd, tt: dd.startswith('Consulta de versión') and 'tú lo pediste' in dd)
        check('C5 the entry is listed (what · when · why · outcome)', e is not None, e.get('content-desc') if e is not None else None)

    # ── S: secret field ────────────────────────────────────────────────
    tree, km = e2e.open_host('password')
    k = km.get('a') if km.get('a') is not None else km.get('A')
    shot('r10p-password.png')
    d = descs(tree)
    mark = [x for x in d if 'Sin memoria' in x]
    check('S1 password field shows the "Sin memoria" mark with its reason', any('contraseña' in x for x in mark), mark)
    e2e.type_word('zorzalito', km)
    e2e.tap(keys(dump())['Espacio'])
    time.sleep(2.5)
    sh(f'am force-stop {PKG}')
    learned = run_as(f'cat {WORDS}')
    check('S2 nothing typed in a password field is learned (words.json)', 'zorzalito' not in learned and 'cachalote' in learned, len(learned))

    # ── N: a normal field has no mark ──────────────────────────────────
    tree, km = e2e.open_host('text')
    shot('r10p-text.png')
    check('N1 plain text field: no "Sin memoria" mark (memory on)', not any(x.startswith('Sin memoria') for x in descs(tree)))

    passed = sum(1 for r in rows if r['ok'])
    out = {'device': model, 'sdk': sdk, 'serial': a.serial, 'seconds': round(time.time() - started, 1),
           'passed': passed, 'total': len(rows), 'rows': rows}
    json.dump(out, open(os.path.join(OUT, 'r10-privacy.json'), 'w'), ensure_ascii=False, indent=1)
    print(f'✦ {passed}/{len(rows)} → {os.path.relpath(OUT, e2e.ROOT)}/r10-privacy.json')
    sys.exit(0 if passed == len(rows) else 1)


if __name__ == '__main__':
    main()
