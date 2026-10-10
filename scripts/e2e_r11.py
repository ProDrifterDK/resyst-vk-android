#!/usr/bin/env python3
"""✦ Resyst VK — r11 E2E: learned words that come back + the full emoji catalog.

Drives the debug E2E host through the keyboard's accessibility tree (same helpers as e2e.py;
every word is typed on the drawn keys, so the learning path is the real one). Synthetic words
only (not in either lexicon). Starts by wiping the DEBUG package's learned files.

  L1 teach-once: a word typed once ("kraviel") is offered for its prefix
  L2 kept by ⌫: a corrected word ("bastiono" → bastón) reverted with ⌫ is never corrected again
     and is offered for its prefix
  L3 kept by ↶: the same through the undo chip ("zorbitax" → órbita); the correction it undid is
     not left learned
  L4 bilingual: words learned while English was detected are offered / protected in Spanish
  L5 prose field with NO_SUGGESTIONS ("social"): learned words are offered, nothing is learned
  L6 password field: nothing offered, nothing learned
  L7 "Lo que sé de ti" marks kept words; forgetting one makes it correctable again
  E1 emoji tabs (9 Unicode groups + recents) with per-tab counts (logcat) and screenshots
  E2 spot-check list visible in the grid: 🥹 🫠 🫶 🥲 🤌 🫡 🫣 🫂 ❤️‍🔥 🇳🇱
  E3 skin tone: long-press 👍 → tones; pick one → committed, remembered as 👍's default, in recents;
     an incognito field neither commits a new default nor writes recents
  E4 "Lo que sé de ti" lists the chosen tone; "Borrar lo aprendido" wipes it (👍 opens plain again)

Artifacts: build/e2e-r11/r11-e2e.json (+ repro.json in --repro mode) and PNGs.
Usage: scripts/e2e_r11.py --serial SERIAL [--only learn|emoji] [--repro]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r10_bilingual as bl  # noqa: E402  (space flick → keyboard language)
from e2e import adb, sh, dump, keys, center, bounds, PKG, IME, SETTINGS  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r11')
e2e.OUT = OUT
PROFILES = 'shared_prefs/resyst_vk_profiles.xml'
WORDS = 'files/personal/words.json'
rows = []
bars = {}


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': str(detail)[:400]})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail)[:200] if detail else ''), flush=True)


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


def km():
    return keys(dump())


def bar(k=None):
    k = k if k is not None else km()
    return [d[len('Sugerencia: '):] for d in k if d.startswith('Sugerencia: ')]


def field():
    return e2e.field_text(dump()) or ''


def tap_node(n, wait=0.35):
    x, y = center(n)
    sh(f'input tap {x} {y}')
    time.sleep(wait)


def press(prefix):
    k = km()
    n = next((v for d, v in k.items() if d.startswith(prefix)), None)
    if n is None:
        raise RuntimeError(f'no key {prefix!r}; have {sorted(k)[:40]}')
    tap_node(n)


def space():
    press('Espacio')
    time.sleep(0.2)


def words_typed(*ws, end_space=True):
    """Types words separated by the space key (the last one followed by space if end_space)."""
    for i, w in enumerate(ws):
        e2e.type_word(w)
        if i < len(ws) - 1 or end_space:
            space()
    time.sleep(0.4)


def fresh(kind='text'):
    e2e.open_host(kind)
    time.sleep(0.3)


def words_json():
    """The learned store after a flush (switching fields flushes; the write is async)."""
    fresh('number')
    time.sleep(1.8)
    raw = run_as(f'cat {WORDS} 2>/dev/null')
    try:
        return json.loads(raw)
    except ValueError:
        return {}


def vocab(j, lang):
    return {e[0]: e for e in j.get('langs', {}).get(lang, {}).get('vocab', [])}


def set_phone(key, value):
    """Edit one phone-wide setting in the v2 profile store (same as e2e_r10_privacy)."""
    sh(f'am force-stop {PKG}')
    xml = run_as(f'cat {PROFILES}')
    if '<map' not in xml:
        xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
    pat = re.compile(r'<string name="' + re.escape(key) + r'">[^<]*</string>')
    line = f'<string name="{key}">{value}</string>'
    xml = pat.sub(line, xml) if pat.search(xml) else xml.replace('</map>', f'    {line}\n</map>')
    local = os.path.join(OUT, 'profiles.xml')
    open(local, 'w', encoding='utf-8').write(xml)
    adb('push', local, '/data/local/tmp/r11p.xml')
    run_as(f'cp /data/local/tmp/r11p.xml {PROFILES}')
    sh('rm -f /data/local/tmp/r11p.xml', check=False)


def spanish_keyboard():
    """The keyboard language follows the system subtype (a prefs edit is pulled back): flick."""
    fresh()
    bl.set_lang('es')
    return bl.space_label() == 'es'


def learned_words():
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    run_as('rm -rf files/personal')
    sh(f'am force-stop {PKG}')
    sh(f'ime set {IME}')
    time.sleep(0.8)
    check('L0 the run is on the Spanish keyboard (ES lexicon corrects)', spanish_keyboard(), bl.phone_lang())
    # Queries use another previous word ("ayer") than the teaching ("hoy"): a learned
    # continuation of the previous word is offered on any build and would hide H1.

    # L1 — teach once
    fresh(); words_typed('hoy', 'kraviel')
    check('L1 precondition: kraviel is not corrected', field() == 'Hoy kraviel ', repr(field()))
    fresh(); words_typed('ayer', 'krav', end_space=False)
    b = bar(); bars['L1 ayer krav'] = b
    shot('r11-L1-teach-once.png')
    check('L1 a word typed once is offered for its prefix', 'kraviel' in b, b)

    # L2 — kept by ⌫ after a space-correction
    fresh(); words_typed('hoy', 'bastiono')
    check('L2 precondition: space corrects bastiono → bastón', field() == 'Hoy bastón ', repr(field()))
    press('Borrar'); time.sleep(0.3)
    check('L2 ⌫ right after the correction restores bastiono', field() == 'Hoy bastiono', repr(field()))
    space()
    fresh(); words_typed('ayer', 'bastio', end_space=False)
    b = bar(); bars['L2 ayer bastio'] = b
    shot('r11-L2-kept-offered.png')
    check('L2 the kept word is offered for its prefix', 'bastiono' in b, b)
    fresh(); words_typed('ayer', 'bastiono')
    check('L2 the kept word is never corrected again', field() == 'Ayer bastiono ', repr(field()))

    # L3 — kept by the ↶ chip
    fresh(); words_typed('hoy', 'zorbitax')
    check('L3 precondition: space corrects zorbitax → órbita', field() == 'Hoy órbita ', repr(field()))
    b = bar(); bars['L3 after correction'] = b
    chip = next((w for w in b if w.startswith('↶')), None)
    check('L3 the ↶ chip is offered', chip is not None, b)
    if chip:
        press('Sugerencia: ' + chip); time.sleep(0.3)
        check('L3 ↶ restores zorbitax + space', field() == 'Hoy zorbitax ', repr(field()))
    fresh(); words_typed('ayer', 'zorbi', end_space=False)
    b = bar(); bars['L3 ayer zorbi'] = b
    check('L3 the word kept by ↶ is offered for its prefix', 'zorbitax' in b, b)
    fresh(); words_typed('ayer', 'zorbitax')
    check('L3 the word kept by ↶ is never corrected again', field() == 'Ayer zorbitax ', repr(field()))

    j = words_json()
    es = vocab(j, 'es')
    check('L3 the correction ↶ undid (órbita) is not left learned', 'órbita' not in es, sorted(es)[:20])
    check('L2/L3 words.json is v2 and marks the kept words', j.get('v') == 2 and
          all(len(es.get(w, [])) >= 5 and es[w][4] == 1 for w in ('bastiono', 'zorbitax')),
          {w: es.get(w) for w in ('bastiono', 'zorbitax', 'kraviel')} | {'v': j.get('v')})

    # L4 — bilingual: learned while English was detected, used while writing Spanish
    for _ in range(2):
        fresh(); words_typed('think', 'the', 'quorvex')
    fresh(); words_typed('think', 'the', 'vecinoz')
    check('L4 precondition: in English context vecinoz stays', field() == 'Think the vecinoz ', repr(field()))
    fresh(); words_typed('think', 'the', 'vecinoz')
    j = words_json()
    en = vocab(j, 'en')
    check('L4 precondition: quorvex + vecinoz were learned in the English table', 'quorvex' in en and 'vecinoz' in en and
          'quorvex' not in vocab(j, 'es'), {'en': sorted(en)[:12]})
    fresh(); words_typed('ayer', 'quor', end_space=False)
    b = bar(); bars['L4 ayer quor'] = b
    shot('r11-L4-bilingual.png')
    check('L4 writing Spanish, a word learned in English is offered', 'quorvex' in b, b)
    fresh(); words_typed('ayer', 'vecinoz')
    check('L4 writing Spanish, a word the user typed twice in English is not corrected', field() == 'Ayer vecinoz ', repr(field()))

    # L5 — prose field that opts out of suggestions (Instagram DM): offers, never learns
    for _ in range(2):
        fresh(); words_typed('hoy', 'zumbatron')
    fresh('social'); words_typed('ayer', 'zumba', end_space=False)
    b = bar(); bars['L5 social ayer zumba'] = b
    shot('r11-L5-social-offers.png')
    check('L5 NO_SUGGESTIONS prose field offers a learned word', 'zumbatron' in b, b)
    descs = list(km())
    check('L5 the field still says it does not learn (sin memoria)', any(d.startswith('Sin memoria') for d in descs),
          [d for d in descs if 'memoria' in d.lower()])
    fresh('social'); words_typed('hoy', 'plorvanto')
    fresh('social'); words_typed('hoy', 'plorvanto')
    fresh(); words_typed('ayer', 'plorva', end_space=False)
    b = bar(); bars['L5 text ayer plorva'] = b
    j = words_json()
    allv = set(vocab(j, 'es')) | set(vocab(j, 'en'))
    check('L5 nothing typed in the NO_SUGGESTIONS field is learned (store + a plain field\'s bar)', 'plorvanto' not in allv and 'plorvanto' not in b,
          {'bar': b, 'learned': 'plorvanto' in allv})

    # L6 — password: closed both ways
    fresh('password'); words_typed('zumba', end_space=False)
    b = bar(); bars['L6 password zumba'] = b
    check('L6 password field offers nothing', b == [], b)
    fresh('password'); words_typed('glimworp')
    j = words_json()
    allv = set(vocab(j, 'es')) | set(vocab(j, 'en'))
    check('L6 password text is never learned', 'glimworp' not in allv and 'zumba' not in allv, sorted(allv))


def settings_page(page):
    sh(f'am start -W -f 0x10008000 -n {SETTINGS} --es com.resyst.vk.page {page}')
    time.sleep(2.0)
    return dump()


def node_where(tree, pred):
    for n in tree.iter('node'):
        if n.get('package') == PKG and pred(n.get('content-desc') or '', n.get('text') or ''):
            return n
    return None


def know_and_forget():
    # L7 — "Lo que sé de ti": the kept word says so; ✕ forgets it and space corrects it again
    t = settings_page('datos')
    shot('r11-L7-know.png')
    texts = [n.get('text') or '' for n in t.iter('node') if n.get('package') == PKG]
    check('L7 "Lo que sé de ti" marks a kept word (conservada)', any('conservada' in x for x in texts), [x for x in texts if 'vez' in x or 'veces' in x][:8])
    x = node_where(t, lambda d, _: d == 'Borrar la palabra bastiono')
    check('L7 the kept word has its ✕', x is not None)
    if x is not None:
        tap_node(x, 2.5)
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.6)
    fresh(); words_typed('ayer', 'bastiono')
    check('L7 a forgotten kept word is corrected again', field() == 'Ayer bastón ', repr(field()))


def wipe_learned():
    t = settings_page('datos')
    for _ in range(6):
        if node_where(t, lambda d, _: d.startswith('Olvidar el tono elegido para')) is not None:
            break
        sh('input swipe 540 1700 540 900 300')
        time.sleep(0.6)
        t = dump()
    shot('r11-E4-know-tones.png')
    check('E4 "Lo que sé de ti" lists the chosen tone (👍 → 👍🏽)',
          node_where(t, lambda d, _: d == 'Olvidar el tono elegido para 👍') is not None,
          [n.get('content-desc') for n in t.iter('node') if 'tono' in (n.get('content-desc') or '')])
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.6)
    t = settings_page('privacidad')
    row = node_where(t, lambda d, _: d.startswith('Borrar lo aprendido'))
    check('E4 "Borrar lo aprendido" row present', row is not None, '')
    if row is None:
        return
    tap_node(row, 1.0)
    btn = node_where(dump(), lambda _, tt: tt.lower() == 'borrar')
    if btn is not None:
        tap_node(btn, 1.5)
    files = run_as('ls files/personal 2>/dev/null').split()
    check('E4 the wipe deletes the tone defaults and recents files', 'emoji_tones.txt' not in files and 'emoji_recents.txt' not in files, files)
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.6)
    emoji_open()
    select_tab('Personas')
    find_in_tab(['👍'])
    k = km()
    check('E4 after the wipe 👍 opens plain', '👍' in k and '👍🏽' not in k, [d for d in k if d.startswith('👍')])


# ── emoji ──────────────────────────────────────────────────────────────
SPOT = ['🥹', '🫠', '🫶', '🥲', '🤌', '🫡', '🫣', '🫂', '❤️‍🔥', '🇳🇱']


def panel_descs():
    return [d for d in km()]


def tabs():
    """Tab nodes: every tab desc is its label (+ ', seleccionada'); emoji cells are the emoji itself."""
    out = []
    for d, n in km().items():
        x1, y1, x2, y2 = bounds(n)
        out.append((d, n, y1))
    if not out:
        return []
    top = min(y for _, _, y in out)
    return [(d, n) for d, n, y in out if y == top]


def grid_box():
    k = km()
    tab = tabs()
    abc = k.get('Volver al teclado')
    if not tab or abc is None:
        return None
    ty = bounds(tab[0][1])[3]
    by = bounds(abc)[1]
    return ty, by


def scroll_grid(dy_frac=0.6):
    g = grid_box()
    if g is None:
        return
    top, bottom = g
    h = bottom - top
    x = 540
    y1 = int(bottom - h * 0.15)
    y2 = int(y1 - h * dy_frac)
    sh(f'input swipe {x} {y1} {x} {y2} 600')
    time.sleep(0.4)


def find_in_tab(targets, max_swipes=60):
    found = {}
    seen_last = None
    for i in range(max_swipes):
        d = set(km())
        for t in targets:
            if t not in found and t in d:
                found[t] = i
        if all(t in found for t in targets):
            break
        sig = tuple(sorted(d))
        if sig == seen_last:  # bottom reached
            break
        seen_last = sig
        scroll_grid()
    return found


def select_tab(label_prefix):
    for d, n in tabs():
        if d.startswith(label_prefix):
            tap_node(n, 0.6)
            return True
    return False


def emoji_open():
    fresh()
    press('Emojis')
    time.sleep(0.8)


def emoji():
    sh('logcat -c')
    run_as('rm -f files/personal/emoji_tones.txt files/personal/emoji_recents.txt')
    emoji_open()
    t = tabs()
    labels = [d.split(',')[0] for d, _ in t]
    shot('r11-E1-panel.png')
    expected = ['Recientes', 'Caras y emociones', 'Personas y cuerpo', 'Animales y naturaleza', 'Comida y bebida',
                'Viajes y lugares', 'Actividades', 'Objetos', 'Símbolos', 'Banderas']
    check('E1 tabs: recents + the 9 Unicode groups in Unicode order', labels == expected, labels)
    for i, lab in enumerate(expected[1:], 1):
        select_tab(lab)
        shot(f'r11-E1-tab{i:02d}.png')
    time.sleep(0.5)
    log = adb('logcat', '-d', '-s', 'ResystVK:I')
    counts = {}
    for line in log.splitlines():
        m = re.search(r'emoji: tab (\S+) shown=(\d+)/(\d+) ms=(\d+)', line)
        if m:
            counts[m.group(1)] = {'shown': int(m.group(2)), 'catalog': int(m.group(3)), 'ms': int(m.group(4))}
    json.dump(counts, open(os.path.join(OUT, 'tab-counts.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    total = sum(c['shown'] for c in counts.values())
    check('E1 per-tab counts logged for all 9 groups (device-drawable / catalog)', len(counts) == 9 and total >= 1800,
          {k: f"{v['shown']}/{v['catalog']} {v['ms']}ms" for k, v in counts.items()} | {'total': total})
    check('E1 no tab took more than 120 ms to measure (no jank on switch)', counts and max(c['ms'] for c in counts.values()) <= 120,
          {k: v['ms'] for k, v in counts.items()})

    # E2 — spot-check list, by tab
    where = {'Caras': ['🥹', '🫠', '🥲', '🫡', '🫣', '❤️‍🔥'], 'Personas': ['🫶', '🤌', '🫂'], 'Banderas': ['🇳🇱']}
    allfound = {}
    for tab, want in where.items():
        emoji_open()
        select_tab(tab)
        f = find_in_tab(want)
        allfound.update(f)
        shot(f'r11-E2-{tab.lower()}.png')
    missing = [e for e in SPOT if e not in allfound]
    check('E2 spot-check emoji are in the grid: ' + ' '.join(SPOT), not missing, {'missing': missing})

    # E3 — skin tone long-press + remembered default
    emoji_open()
    select_tab('Personas')
    find_in_tab(['👍'])
    k = km()
    thumb = k.get('👍')
    check('E3 👍 is in the Personas grid', thumb is not None)
    if thumb is None:
        return
    x, y = center(thumb)
    sh(f'input swipe {x} {y} {x} {y} 900')
    time.sleep(0.6)
    k = km()
    tones = [d for d in k if d.startswith('👍') and d != '👍']
    shot('r11-E3-tones.png')
    check('E3 long-press 👍 offers its 5 skin tones', len(set(tones) & {'👍🏻', '👍🏼', '👍🏽', '👍🏾', '👍🏿'}) == 5, tones)
    before = field()
    if '👍🏽' in k:
        tap_node(k['👍🏽'], 0.6)
    check('E3 picking 👍🏽 commits it', field() == before + '👍🏽', repr(field()))
    k = km()
    check('E3 the grid now shows 👍🏽 in 👍\'s place', '👍🏽' in k and '👍' not in k, [d for d in k if d.startswith('👍')])
    tones_file = run_as('cat files/personal/emoji_tones.txt 2>/dev/null')
    check('E3 the default is stored on-device (files/personal/emoji_tones.txt)', '👍🏽' in tones_file, repr(tones_file))
    select_tab('Recientes')
    check('E3 recents store the toned form', '👍🏽' in km(), [d for d in km() if len(d) <= 12][:12])
    # persists across a keyboard restart
    sh(f'am force-stop {PKG}')
    sh(f'ime enable {IME}'); sh(f'ime set {IME}')
    time.sleep(0.8)
    emoji_open()
    select_tab('Personas')
    find_in_tab(['👍🏽'])
    k = km()
    shot('r11-E3-remembered.png')
    check('E3 after a keyboard restart 👍 still opens as 👍🏽', '👍🏽' in k and '👍' not in k, [d for d in k if d.startswith('👍')])
    # incognito: a tone pick there commits but writes neither the default nor the recents
    fresh('incognito')
    press('Emojis'); time.sleep(0.8)
    select_tab('Personas')
    find_in_tab(['👍🏽'])
    k = km()
    n = k.get('👍🏽')
    if n is not None:
        x, y = center(n)
        sh(f'input swipe {x} {y} {x} {y} 900')
        time.sleep(0.6)
        k = km()
        if '👍🏿' in k:
            tap_node(k['👍🏿'], 0.6)
    check('E3 incognito: the pick is committed', field().endswith('👍🏿'), repr(field()))
    tones_file = run_as('cat files/personal/emoji_tones.txt 2>/dev/null')
    rec = run_as('cat files/personal/emoji_recents.txt 2>/dev/null')
    check('E3 incognito: neither the tone default nor the recents change', '👍🏿' not in tones_file and '👍🏿' not in rec and '👍🏽' in tones_file,
          {'tones': tones_file, 'recents': rec})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--only', choices=['learn', 'emoji'])
    ap.add_argument('--repro', action='store_true', help='write build/e2e-r11/repro.json (base-build reproduction)')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    previous = sh('settings get secure default_input_method').strip()
    started = time.time()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    version = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}', check=False))
    print(f'✦ device: {model} (API {sdk}) {PKG} {version.group(1) if version else "?"}', flush=True)
    try:
        if a.only in (None, 'learn'):
            learned_words()
            know_and_forget()
        if a.only in (None, 'emoji'):
            emoji()
            wipe_learned()
    except Exception as ex:  # a crash is a failed row, never a silent pass
        check('script ran to the end', False, repr(ex))
    finally:
        if previous and previous != 'null' and previous != IME:
            sh(f'ime set {previous}', check=False)
        passed = sum(r['ok'] for r in rows)
        out = {'device': model, 'api': sdk, 'package': PKG, 'version': version.group(1) if version else None,
               'passed': passed, 'total': len(rows), 'seconds': round(time.time() - started, 1),
               'rows': rows, 'bars': bars}
        name = 'repro.json' if a.repro else 'r11-e2e.json'
        json.dump(out, open(os.path.join(OUT, name), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {passed}/{len(rows)} → build/e2e-r11/{name}', flush=True)
    return 0 if rows and passed == len(rows) else 1


if __name__ == '__main__':
    sys.exit(main())
