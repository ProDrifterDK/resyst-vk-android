#!/usr/bin/env python3
"""✦ Resyst VK — r12 E2E (0.8.1, the minor details) on a real device. Every row can fail on eb86c98.

  P  provisional space after a suggestion pick (PS1–PS4): pick + '.', pick + ?123 + '?', pick + ','
     + Espacio, pick + letter, pick + Espacio — read back from the app's field
  G  emoji glyphs off the UI thread (EG1–EG3): a fresh IME process, the panel opened and Banderas
     tapped at once; logcat must show every tab measured on a thread that is not main, and the
     switch's UI-thread cost; `dumpsys gfxinfo` frames around the switch are recorded (before/after)
  S  settings never parses the emoji asset on main (SA1/SA2): a fresh process with one kept tone,
     settings opened straight on privacidad / datos; the parse logs its thread, the rows count 1 tono
  U  update origin across a restart + orphan autoAt (UR1–UR3): needs --old-apk (this code stamped
     below the live release). A MANUAL check finds the update; after a process restart the card must
     not say "al abrir el teclado"; a manifest stored without the flag (0.8.0 data) restores neutral;
     an automatic one restores as "al abrir el teclado"; `autoAt` is gone and nothing else is.
     At most 2 GETs of kv.resyst.cl/release.json; no KLIPY request.

Writes build/e2e-r12/r12-e2e.json + PNGs. Debug package only (com.resyst.vk.debug).
Usage: scripts/e2e_r12.py --serial SERIAL [--only P,G,S,U] [--old-apk PATH --apk PATH] [--tag NAME]
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r10_bilingual as bl  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, center, PKG, IME, SETTINGS  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r12')
UPD = 'shared_prefs/resyst_vk_update.xml'
CLOCK = PKG + '/com.resyst.vk.debug.E2EUpdateClock'
rows = []
TAG = ''


def check(section, name, ok, detail=''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:500]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:220] if detail else ''), flush=True)


def shot(name):
    open(os.path.join(OUT, f'{TAG}{name}'), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def km():
    return keys(dump())


def field():
    return e2e.field_text(dump()) or ''


def bar(k=None):
    k = k if k is not None else km()
    return [d[len('Sugerencia: '):] for d in k if d.startswith('Sugerencia: ')]


def press(prefix, wait=0.35):
    k = km()
    n = k.get(prefix)
    n = n if n is not None else next((v for d, v in k.items() if d.startswith(prefix)), None)
    if n is None:
        raise RuntimeError(f'no key {prefix!r}; have {sorted(k)[:50]}')
    e2e.tap(n)
    time.sleep(wait)


def run_as(cmd, data=None):
    r = subprocess.run(e2e.ADB + ['shell', f"run-as {PKG} sh -c '{cmd}'"], input=data, capture_output=True, timeout=60)
    return r.stdout.decode(errors='replace')


def logcat(*tags):
    return adb('logcat', '-d', '-v', 'time', '-s', *tags)


def fresh_process():
    sh(f'am force-stop {PKG}')
    time.sleep(0.8)
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    time.sleep(0.5)


# ── P: provisional space after a pick ─────────────────────────────────────
def pick_after(prefix):
    """Fresh field, type [prefix], pick the first suggestion that completes it; returns the word."""
    e2e.open_host('text')
    e2e.type_word(prefix)
    time.sleep(0.6)
    b = bar()
    w = next((x for x in b if x.lower().startswith(prefix) and x.lower() != prefix), None)
    if w is None:
        raise RuntimeError(f'no completion of {prefix!r} on the bar: {b}')
    press('Sugerencia: ' + w, 0.6)
    return w


def section_p():
    e2e.open_host('text')
    bl.set_lang('es')
    w = pick_after('hol')
    check('P', 'a pick commits the word + its space', field() == w + ' ', repr(field()))
    press('punto')
    check('P', 'pick then "." → "Hola. " (no space before the period, one after it)', field() == w + '. ', repr(field()))
    shot('r12-P1-pick-period.png')

    w = pick_after('hol')
    press('Símbolos')
    press('?')
    check('P', 'pick then ?123 then "?" → "Hola? " (the layer key keeps the provisional space)', field() == w + '? ', repr(field()))
    shot('r12-P2-pick-question.png')
    press('Letras')

    w = pick_after('hol')
    press('coma')
    press('Espacio')
    check('P', 'pick, ",", Espacio → "Hola, " (the space after the comma is not doubled)', field() == w + ', ', repr(field()))
    press('Espacio')
    check('P', '… and a second Espacio is a normal space', field() == w + ',  ', repr(field()))

    w = pick_after('hol')
    e2e.type_word('y')
    check('P', 'pick then a letter → "Hola y" (nothing removed)', field() == w + ' y', repr(field()))
    press('coma')
    check('P', '… and a later "," is committed as typed', field() == w + ' y,', repr(field()))

    w = pick_after('hol')
    press('Espacio')
    check('P', 'pick then Espacio → "Hola " (no double space, no ". ")', field() == w + ' ', repr(field()))
    shot('r12-P5-pick-space.png')

    w = pick_after('hol')
    press('punto')
    press('Borrar')
    check('P', 'pick, ".", ⌫ → "Hola." (⌫ deletes one character, no invented undo)', field() == w + '.', repr(field()))


# ── G: glyphs measured off the UI thread ─────────────────────────────────
def gfx():
    out = sh(f'dumpsys gfxinfo {PKG}', check=False)
    def num(label):
        m = re.search(label + r':\s*(\d+)', out)
        return int(m.group(1)) if m else None
    def pct(p):
        m = re.search(p + r'th percentile:\s*(\d+)ms', out)
        return int(m.group(1)) if m else None
    return {'frames': num('Total frames rendered'), 'janky': num('Janky frames'), 'slow_ui': num('Number Slow UI thread'),
            'p90': pct('90'), 'p99': pct('99')}


def tab_node(prefix):
    for d, n in km().items():
        if d.startswith(prefix):
            return n
    return None


def section_g():
    run_as('rm -f files/personal/emoji_recents.txt')  # the panel opens on Caras: Banderas is not the first tab
    # geometry of the Banderas tab, from a throwaway open (same layout every time)
    e2e.open_host('text')
    press('Emojis', 0.9)
    n = tab_node('Banderas')
    check('G', 'Banderas tab found', n is not None)
    if n is None:
        return
    x, y = center(n)
    fresh_process()
    sh('logcat -c')
    e2e.open_host('text')
    time.sleep(0.5)
    sh(f'dumpsys gfxinfo {PKG} reset', check=False)
    press('Emojis', 0.0)
    sh(f'input tap {x} {y}')  # Banderas at once, before any dump
    time.sleep(1.5)
    g = gfx()
    shot('r12-G1-banderas-first.png')
    flags = [d for d in km() if d[:1] == '🏁' or (d and 0x1F1E6 <= ord(d[0]) <= 0x1F1FF)]
    check('G', 'Banderas shows its flags (🏁 / country flags in the first screen of the grid)', len(flags) >= 6, flags[:6])
    log = logcat('ResystVK:I')
    # one ordered stream of 'tab measured' / 'switch' events (EG2 is about their order)
    events = [('tab', a) if a else ('switch', b) for a, b in re.findall(r'emoji: (?:tab (\S+) shown=|switch tab=(\S+) )', log)]
    tabs = re.findall(r'emoji: tab (\S+) shown=(\d+)/(\d+) ms=(\d+)(?: thread=(\S+))?', log)
    switch = re.findall(r'emoji: switch tab=(\S+) ready=(\w+) ui_us=(\d+) thread=(\S+)', log)
    detail = {t[0]: f'{t[1]}/{t[2]} {t[3]}ms thread={t[4] or "?"}' for t in tabs}
    check('G', 'all 9 tabs measured, each on a thread that is not main (EG1)',
          len(tabs) == 9 and all(t[4] and t[4] != 'main' for t in tabs), detail)
    order = [t[0] for t in tabs]
    seq = [('switch:' if k.startswith('switch') else '') + v for k, v in events]
    sw = seq.index('switch:banderas') if 'switch:banderas' in seq else -1
    measured_before = [x for x in seq[:sw] if not x.startswith('switch:')] if sw >= 0 else []
    after = [x for x in seq[sw + 1:] if not x.startswith('switch:')] if sw >= 0 else []
    # the tab on screen first; once Banderas is asked for, it is the next one measured (unless already done)
    ok2 = order[:1] == ['caras'] and sw >= 0 and ('banderas' in measured_before or after[:1] == ['banderas'])
    check('G', 'measuring starts with the tab on screen (Caras); Banderas is next once tapped, if not done yet (EG2)', ok2, seq)
    fl = next((s for s in switch if s[0] == 'banderas'), None)
    check('G', 'the Banderas switch ran on main and cost < 8 ms of UI thread (no glyph measured there)',
          fl is not None and fl[3] == 'main' and int(fl[2]) < 8000, switch)
    check('G', 'gfxinfo around the switch recorded (frames / janky / slow UI thread)', g['frames'] is not None, g)
    json.dump({'tabs': detail, 'switch': switch, 'gfxinfo': g, 'tap': [x, y]},
              open(os.path.join(OUT, f'{TAG}r12-G-glyphs.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    # EG6: re-opening and switching never measures again in the same process
    sh('logcat -c')
    e2e.open_host('text')
    press('Emojis', 0.6)
    n = tab_node('Personas')
    if n is not None:
        e2e.tap(n)
        time.sleep(0.6)
    again = re.findall(r'emoji: tab (\S+) shown=', logcat('ResystVK:I'))
    check('G', 'reopening + switching in the same process measures nothing again (EG6)', again == [], again)


# ── S: settings parses the emoji asset off main ──────────────────────────
def settings_texts():
    out = []
    for n in dump().iter('node'):
        if n.get('package') == PKG:
            for a in ('text', 'content-desc'):
                if (n.get(a) or '').strip():
                    out.append(n.get(a).strip())
    return out


def scroll_find(pred, swipes=10):
    for _ in range(swipes):
        tx = settings_texts()
        hit = next((t for t in tx if pred(t)), None)
        if hit is not None:
            return hit, tx
        sh('input swipe 540 1700 540 900 300')
        time.sleep(0.6)
    return None, settings_texts()


def section_s():
    run_as('mkdir -p files/personal; cat > files/personal/emoji_tones.txt', data='👍\t👍🏽\n'.encode('utf-8'))
    for page, pred, name in [
        ('privacidad', lambda t: 'nada sale del teléfono' in t and ('tono' in t or 'Nada aprendido' in t), 'privacy wipe row'),
        ('datos', lambda t: t.startswith('Tonos de piel elegidos'), '"Lo que sé de ti" tones'),
    ]:
        fresh_process()
        sh('logcat -c')
        sh(f'am start -W -f 0x10008000 -n {SETTINGS} --es com.resyst.vk.page {page}')
        time.sleep(2.0)
        hit, tx = scroll_find(pred)
        shot(f'r12-S-{page}.png')
        log = logcat('ResystVK:I')
        parsed = re.findall(r'emoji catalog parsed: (\d+) ms=(\d+) thread=(\S+)', log)
        check('S', f'{page}: the emoji asset was parsed off the main thread (SA1)',
              bool(parsed) and all(p[2] != 'main' for p in parsed), parsed or 'no parse log line')
        if page == 'privacidad':
            check('S', 'privacy: the wipe row counts the kept tone ("1 tono"), never "Nada aprendido" (SA2)',
                  hit is not None and '1 tono' in hit and not hit.startswith('Nada aprendido'), hit)
        else:
            check('S', 'datos: "Tonos de piel elegidos · 1" with its chip (SA2)',
                  hit == 'Tonos de piel elegidos · 1' and any(t.startswith('Olvidar el tono elegido para 👍') for t in tx), hit)
    run_as('rm -f files/personal/emoji_tones.txt')


# ── U: update origin across a restart, orphan autoAt ─────────────────────
def prefs():
    return run_as(f'cat {UPD}')


def write_prefs(xml):
    sh(f'am force-stop {PKG}')
    time.sleep(0.6)
    run_as(f'mkdir -p shared_prefs; cat > {UPD}', data=xml.encode('utf-8'))


def about_card():
    sh(f'am start -W -f 0x10008000 -n {SETTINGS} --es com.resyst.vk.page acerca')
    time.sleep(2.0)
    hit, tx = scroll_find(lambda t: t.startswith('Comprobado') or t.startswith('Buscar actualizaciones'), swipes=8)
    return hit, tx


def gets(log):
    return re.findall(r'update check: GET release.json \(auto=(\w+)\)', log)


def section_u(old_apk, apk):
    if not old_apk:
        check('U', 'U needs --old-apk (this code stamped below the live release)', False)
        return
    live = json.loads(subprocess.run(['curl', '-s', '-A', 'ResystVK-E2E', 'https://kv.resyst.cl/release.json'],
                                     capture_output=True, timeout=30).stdout or b'{}')
    adb('install', '-r', '-d', old_apk)
    ver = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}')).group(1)
    print(f'  · live {live.get("version")} ({live.get("versionCode")}), installed {ver}')
    # a 0.7.0-era prefs file: the orphan autoAt + a dismissed (older) version that must survive
    write_prefs("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                "    <long name=\"autoAt\" value=\"1760000000000\" />\n    <string name=\"dismissed\">0.0.1</string>\n</map>\n")
    sh('logcat -c')
    hit, tx = about_card()
    btn = next((n for n in dump().iter('node') if (n.get('text') or n.get('content-desc') or '') == 'Buscar actualizaciones'), None)
    check('U', 'settings shows "Buscar actualizaciones" (no stored state)', btn is not None, hit)
    if btn is None:
        return
    e2e.tap(btn)
    time.sleep(4.0)
    hit, tx = about_card()
    log = logcat('ResystVK:I')
    shot('r12-U1-manual.png')
    check('U', 'a MANUAL check found the live release: "Comprobado · …" (1 GET, auto=false)',
          hit is not None and hit.startswith('Comprobado ·') and gets(log) == ['false'], {'card': hit, 'gets': gets(log)})
    p = prefs()
    check('U', 'prefs: availAuto=false stored next to availManifest (UR1)',
          'name="availAuto" value="false"' in p and 'availManifest' in p, re.sub(r'\s+', ' ', p)[:300])
    check('U', 'prefs: the orphan autoAt is gone, dismissed + the attempt clock are kept (UR3)',
          'autoAt' not in p and 'name="dismissed">0.0.1<' in p and 'attemptAt' in p, re.sub(r'\s+', ' ', p)[:300])
    # process restart inside the throttle interval: the announcement is restored without a request
    fresh_process()
    sh('logcat -c')
    hit, tx = about_card()
    log = logcat('ResystVK:I')
    shot('r12-U2-restored-manual.png')
    check('U', 'after a process restart the manual answer is restored as "Comprobado · …", not "al abrir el teclado" (UR1)',
          hit is not None and hit.startswith('Comprobado ·') and gets(log) == [] and 'by=user' in log, {'card': hit, 'gets': gets(log)})
    # 0.8.0 data: the stored manifest without the flag
    p = prefs()
    write_prefs(re.sub(r'\s*<boolean name="availAuto" value="\w+" />', '', p))
    sh('logcat -c')
    hit, tx = about_card()
    log = logcat('ResystVK:I')
    shot('r12-U3-restored-legacy.png')
    check('U', 'a manifest stored by 0.8.0 (no availAuto) restores as the neutral "Comprobado · …" (UR2)',
          hit is not None and hit.startswith('Comprobado ·') and 'by=unknown' in log, {'card': hit, 'p': 'availAuto' in prefs()})
    # an automatic answer: the keyboard-open check, once due (the stored attempt moved back 13 h)
    fresh_process()
    sh('logcat -c')
    e2e.open_host('text')
    sh(f'am broadcast -n {CLOCK} --el by_ms {13 * 3_600_000}')
    time.sleep(0.6)
    e2e.open_host('number')  # a new field = a keyboard show: due → one automatic GET
    time.sleep(4.0)
    log = logcat('ResystVK:I', 'ResystE2E:I')
    p = prefs()
    check('U', 'the keyboard-open check stored availAuto=true (1 GET, auto=true)',
          gets(log) == ['true'] and 'name="availAuto" value="true"' in p, {'gets': gets(log), 'p': re.sub(r'\s+', ' ', p)[:200]})
    fresh_process()
    sh('logcat -c')
    hit, tx = about_card()
    shot('r12-U4-restored-auto.png')
    check('U', 'restored after a restart as "Comprobado al abrir el teclado · …"', hit is not None and hit.startswith('Comprobado al abrir el teclado ·'), hit)
    if apk:
        adb('install', '-r', '-d', apk)
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        print('  · restored', re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}')).group(1))


def main():
    global TAG
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--only', default='P,G,S,U')
    ap.add_argument('--old-apk')
    ap.add_argument('--apk')
    ap.add_argument('--tag', default='', help='prefix for the artifacts (e.g. base-)')
    a = ap.parse_args()
    TAG = a.tag
    e2e.ADB.extend(['-s', a.serial])
    e2e.OUT = OUT
    os.makedirs(OUT, exist_ok=True)
    started = time.time()
    model = sh('getprop ro.product.model').strip()
    pkg = sh(f'dumpsys package {PKG}')
    ver = re.search(r'versionName=(\S+)', pkg).group(1)
    upd = re.search(r'lastUpdateTime=(.+)', pkg).group(1).strip()
    print(f'✦ {model}: {PKG} {ver} (lastUpdateTime {upd})')
    only = set(a.only.split(','))
    try:
        for s, fn in [('P', section_p), ('G', section_g), ('S', section_s)]:
            if s in only:
                try:
                    fn()
                except Exception as ex:  # a crashed section is a failed row, the others still run
                    check(s, f'section {s} ran to the end', False, repr(ex))
        if 'U' in only:
            try:
                section_u(a.old_apk, a.apk)
            except Exception as ex:
                check('U', 'section U ran to the end', False, repr(ex))
    finally:
        passed = sum(r['ok'] for r in rows)
        out = {'device': model, 'package': PKG, 'version': ver, 'lastUpdateTime': upd, 'passed': passed, 'total': len(rows),
               'seconds': round(time.time() - started, 1), 'rows': rows}
        json.dump(out, open(os.path.join(OUT, f'{TAG}r12-e2e.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {passed}/{len(rows)} → build/e2e-r12/{TAG}r12-e2e.json')
    return 0 if rows and all(r['ok'] for r in rows) else 1


if __name__ == '__main__':
    sys.exit(main())
