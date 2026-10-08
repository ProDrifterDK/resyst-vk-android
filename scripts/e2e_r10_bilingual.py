#!/usr/bin/env python3
"""✦ Resyst VK — r10 bet 4 E2E: space flick ES ⇄ EN, bilingual suggestions, regional Spanish.

Drives the debug E2E host through the keyboard's accessibility tree (keys are virtual views):
  FL  a vertical flick on space switches the language (space label, layout: ñ appears/disappears,
      system subtype follows) and types nothing; a plain tap still types a space; a horizontal drag
      still moves the cursor (no language change).
  BL  on the Spanish keyboard, three English words switch the bar to English completions; English
      words are not "corrected" into Spanish by space; Spanish typos still are.
  RG  the default region (Chile) completes Chilean words (cach → cachai) and keeps them on space.

Writes build/e2e-r10-bilingual/r10-bilingual.json (one row per check, each able to fail) + PNGs.
Usage: scripts/e2e_r10_bilingual.py --serial SERIAL
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, center, PKG  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r10-bilingual')
PREFS = f'/data/data/{PKG}/shared_prefs/resyst_vk_profiles.xml'
rows = []


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + detail if detail else ''))


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def km():
    return keys(dump())


def space(k=None):
    k = k or km()
    return next((n for d, n in k.items() if d.startswith('Espacio')), None)


def node(k, ch):
    n = k.get(ch)
    return n if n is not None else k.get(ch.upper())


def space_label():
    """Which letter layout is drawn: ES puts a right under q (10-key home row with ñ), EN offsets
    the 9-key home row by half a key. (The accent top row has ñ in both, so ñ can't tell.)"""
    k = km()
    q, a_ = node(k, 'q'), node(k, 'a')
    if q is None or a_ is None:
        return '?'
    qx1, _, qx2, _ = bounds(q)
    ax1, _, _, _ = bounds(a_)
    return 'en' if ax1 - qx1 > (qx2 - qx1) / 4 else 'es'


def suggestions(k=None):
    k = k or km()
    return [d[len('Sugerencia: '):] for d in k if d.startswith('Sugerencia: ')]


def field():
    return e2e.field_text(dump()) or ''


def flick(dy_dp=-60, ms=90):
    n = space()
    x, y = center(n)
    d = float(sh('wm density').split()[-1]) / 160.0
    sh(f'input swipe {x} {y} {x} {int(y + dy_dp * d)} {ms}')
    time.sleep(0.9)


def tap_space():
    x, y = center(space())
    sh(f'input tap {x} {y}')
    time.sleep(0.4)


def type_text(word):
    e2e.type_word(word)
    time.sleep(0.5)


def phone_lang():
    xml = sh(f'run-as {PKG} cat {PREFS}', check=False)
    for line in xml.splitlines():
        if 'name="phone.lang"' in line:
            return line.split('>')[1].split('<')[0]
    return None


def subtype():
    return sh('settings get secure selected_input_method_subtype', check=False).strip()


def set_lang(target):
    """Flick until the keyboard shows [target] ('es' / 'en'); returns flicks used."""
    for i in range(3):
        if space_label() == target:
            return i
        flick()
    return 3


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)

    e2e.open_host('text')
    set_lang('es')
    check('start on the Spanish keyboard', space_label() == 'es', str(phone_lang()))
    shot('r10-bl-es.png')

    # FL1 — flick up: Spanish → English, nothing typed, setting + system subtype follow
    sub0 = subtype()
    before = field()
    flick(-60)
    check('FL1 flick up switches to English (home row offset)', space_label() == 'en')
    check('FL1 flick types nothing', field() == before, repr(field()))
    check('FL5 phone.lang saved as EN', phone_lang() == 'EN', str(phone_lang()))
    time.sleep(0.8)
    check('FL5 system subtype followed', subtype() != sub0, f'{sub0} → {subtype()}')
    shot('r10-bl-en.png')
    # flick down goes back
    flick(+60)
    check('FL1 flick down switches back to Spanish', space_label() == 'es')
    check('FL5 phone.lang saved as ES', phone_lang() == 'ES', str(phone_lang()))

    # FL3 — a tap is still a space; FL2 — a horizontal drag is the cursor, not a flick
    e2e.open_host('text')
    type_text('hola')
    tap_space()
    check('FL3 tap on space still types a space', field().lower() == 'hola ', repr(field()))
    n = space()
    x, y = center(n)
    x1, _, x2, _ = bounds(n)
    sh(f'input swipe {x} {y} {x1 + 10} {y} 400')
    time.sleep(0.6)
    check('FL2 horizontal drag keeps the language', space_label() == 'es')
    check('FL2 horizontal drag typed nothing', field().lower() == 'hola ', repr(field()))

    # BL2 — three English words on the Spanish keyboard → English completions
    e2e.open_host('text')
    set_lang('es')
    type_text('i')
    tap_space()
    for w in ('think', 'the'):
        type_text(w)
        tap_space()
    type_text('mee')
    s = suggestions()
    check('BL2 English completions after 3 English words (ES keyboard)', any(x.lower().startswith('mee') for x in s) and any(x.lower() in ('meet', 'meeting', 'meeting') for x in s), str(s))
    shot('r10-bl-bilingual-bar.png')

    # BL4 — an English word is not corrected into Spanish by space
    e2e.open_host('text')
    set_lang('es')
    type_text('problem')
    tap_space()
    check('BL4 "problem" stays (no → problema)', field().strip().lower() == 'problem', repr(field()))
    e2e.open_host('text')
    type_text('already')
    tap_space()
    check('BL4 "already" stays (no → ready)', field().strip().lower() == 'already', repr(field()))
    # Spanish typos are still fixed
    e2e.open_host('text')
    type_text('tambien')
    tap_space()
    check('BL5 Spanish typo still fixed (tambien → también)', field().strip().lower() == 'también', repr(field()))

    # RG3 — Chile by default: cach → cachai, cachai is never corrected away
    e2e.open_host('text')
    set_lang('es')
    type_text('cach')
    s = suggestions()
    check('RG3 "cach" completes "cachai"', any(x.lower() == 'cachai' for x in s), str(s))
    shot('r10-bl-cachai.png')
    type_text('ai')
    tap_space()
    check('RG3 "cachai" survives space', field().strip().lower() == 'cachai', repr(field()))
    e2e.open_host('text')
    type_text('polo')
    s = suggestions()
    check('RG3 "polo" offers "pololo"', any(x.lower() == 'pololo' for x in s), str(s))

    # leave the phone on Spanish
    e2e.open_host('text')
    set_lang('es')

    ok = sum(r['ok'] for r in rows)
    json.dump({'device': a.serial, 'passed': ok, 'total': len(rows), 'rows': rows},
              open(os.path.join(OUT, 'r10-bilingual.json'), 'w'), ensure_ascii=False, indent=1)
    print(f'{ok}/{len(rows)}  → {os.path.relpath(OUT, e2e.ROOT)}/r10-bilingual.json')
    sys.exit(0 if ok == len(rows) else 1)


if __name__ == '__main__':
    main()
