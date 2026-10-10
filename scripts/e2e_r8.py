#!/usr/bin/env python3
"""✦ Resyst VK — round 8 E2E: emoji key + panel, Instagram-style field correction, day/night chip.

Drives the debug E2E host on one device via the accessibility tree and writes
build/e2e-r8/r8-e2e.json (one row per check, each able to fail) + screenshots.

Usage: scripts/e2e_r8.py --serial SERIAL
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, center, IME, HOST, PKG  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r8')
rows = []


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + detail if detail else ''))


def tap_xy(x, y):
    sh(f'input tap {x} {y}')
    time.sleep(0.35)


def tap_desc(tree, desc_prefix):
    k = keys(tree)
    node = next((n for d, n in k.items() if d.startswith(desc_prefix)), None)
    if node is None:
        return False
    x, y = center(node)
    tap_xy(x, y)
    return True


def field_text():
    t = dump()
    return e2e.field_text(t) or ''


def open_field(kind):
    e2e.open_host(kind)


def type_letters(word):
    e2e.type_word(word)  # handles the auto-cap "Q" label


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


PROFILES = 'shared_prefs/resyst_vk_profiles.xml'


def set_phone(key, value):
    """One phone-wide setting in the v2 store (as e2e_r10_privacy); returns the previous value."""
    sh(f'am force-stop {PKG}')
    xml = sh(f"run-as {PKG} cat {PROFILES}", check=False)
    if '<map' not in xml:
        xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
    pat = re.compile(r'<string name="' + re.escape(key) + r'">([^<]*)</string>')
    m = pat.search(xml)
    line = f'<string name="{key}">{value}</string>'
    xml = pat.sub(line, xml) if m else xml.replace('</map>', f'    {line}\n</map>')
    local = os.path.join(OUT, 'profiles.xml')
    open(local, 'w', encoding='utf-8').write(xml)
    adb('push', local, '/data/local/tmp/r8p.xml')
    sh(f'run-as {PKG} cp /data/local/tmp/r8p.xml {PROFILES}', check=False)
    sh('rm -f /data/local/tmp/r8p.xml', check=False)
    return m.group(1) if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    sh('logcat -c')

    # 1 — emoji key present in a text field, opens the panel, commits an emoji, ABC returns
    open_field('text')
    tree = dump()
    k = keys(tree)
    check('emoji key in bottom row', 'Emojis' in k, ','.join(sorted(d for d in k if len(d) > 1))[:160])
    shot('r8-emoji-key.png')
    if 'Emojis' in k:
        tap_desc(tree, 'Emojis')
        time.sleep(0.6)
        tree = dump()
        k = keys(tree)
        check('panel opens with tabs + ABC', 'Volver al teclado' in k and any(d.startswith('Caras') or d.startswith('Recientes') for d in k),
              ','.join(list(k)[:12]))
        shot('r8-emoji-panel.png')
        if not tap_desc(tree, 'Caras'):
            pass
        tree = dump()
        ok = tap_desc(tree, '😂')
        time.sleep(0.4)
        check('tapping 😂 commits it', ok and '😂' in field_text(), repr(field_text()))
        tree = dump()
        tap_desc(tree, 'Borrar')
        time.sleep(0.3)
        check('panel ⌫ deletes the whole emoji (surrogate-safe)', '😂' not in field_text() and '\ufffd' not in field_text(), repr(field_text()))
        tree = dump()
        tap_desc(tree, 'Recientes')
        tree = dump()
        check('recents now hold 😂', '😂' in keys(tree), ','.join(list(keys(tree))[:14]))
        tap_desc(tree, 'Volver al teclado')
        time.sleep(0.4)
        km = keys(dump())
        check('ABC returns to the letters', 'q' in km or 'Q' in km)

    # 2 — no emoji key in email / password fields
    for kind in ('email', 'password'):
        open_field(kind)
        check(f'no emoji key in {kind} field', 'Emojis' not in keys(dump()))

    # 3 — Instagram-style composer (prose + NO_SUGGESTIONS) corrects exactly like a plain field;
    #     typos valid in either keyboard language (the emulator may be on ES or EN)
    def typed_then_space(kind, word):
        open_field(kind)
        type_letters(word)
        k = keys(dump())
        x, y = center(next(n for d, n in k.items() if d.startswith('Espacio')))
        tap_xy(x, y)
        time.sleep(0.4)
        return field_text().strip()
    for word in ('becuase', 'tambein'):
        plain = typed_then_space('text', word)
        social = typed_then_space('social', word)
        handle = typed_then_space('handle', word)
        if plain.lower() != word:
            check(f'social field corrects "{word}" like a plain field', social == plain, f'plain={plain!r} social={social!r}')
            check(f'handle field leaves "{word}" alone', handle == word, repr(handle))
    log = adb('logcat', '-d', '-s', 'ResystVK:I')
    # API 37 sets an extra high bit (0x2a4001 on the Pixel 6): match the low 20 bits, not the exact value
    def low_bits(l):
        m = re.search(r'inputType=0x([0-9a-f]+)', l)
        return int(m.group(1), 16) & 0xfffff if m else -1
    fl = [l.split('ResystVK:')[-1].strip() for l in log.splitlines() if 'field: pkg=' in l and low_bits(l) == 0xa4001]
    check('social field logged as prose + optedOut', bool(fl) and 'suggestions=true' in fl[-1] and 'optedOut=true' in fl[-1], fl[-1] if fl else 'no log')

    # 5 — day/night chip flips the theme. Since r10 (ad7e37c) the chip is opt-in (Teclas e idioma →
    # chip día/noche, default off; the quick panel has the tile): turn it on for this section only.
    was_chip = set_phone('phone.dayNightChip', 'true')
    open_field('text')
    tree = dump()
    k = keys(tree)
    chip = next((d for d in k if d.startswith('Cambiar a tema')), None)
    check('day/night chip in the strip', chip is not None, str(chip))
    if chip:
        shot('r8-daynight-before.png')
        tap_desc(tree, chip)
        time.sleep(0.8)
        after = next((d for d in keys(dump()) if d.startswith('Cambiar a tema')), None)
        check('chip flips dark→light', chip == 'Cambiar a tema claro' and after == 'Cambiar a tema oscuro', f'{chip} → {after}')
        shot('r8-daynight-after.png')
        tap_desc(dump(), after or '')
        time.sleep(0.8)
        back = next((d for d in keys(dump()) if d.startswith('Cambiar a tema')), None)
        check('second flip returns', back == chip, str(back))
    set_phone('phone.dayNightChip', was_chip or 'false')

    passed = sum(r['ok'] for r in rows)
    json.dump({'device': sh('getprop ro.product.model').strip(), 'api': sh('getprop ro.build.version.sdk').strip(),
               'passed': passed, 'total': len(rows), 'rows': rows},
              open(os.path.join(OUT, 'r8-e2e.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(f'{passed}/{len(rows)}')
    sys.exit(0 if passed == len(rows) else 1)


if __name__ == '__main__':
    main()
