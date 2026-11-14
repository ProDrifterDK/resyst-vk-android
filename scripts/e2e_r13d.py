#!/usr/bin/env python3
"""✦ Resyst VK — r13d E2E (0.11.0: the deterministic misses of live session s3, learned words read in
"Sin memoria" fields).

Types through the DRAWN keys (accessibility tree, like scripts/e2e_r13c.py) into the debug E2E host:
  F  the first word of an empty multi-line field: "auer" + Espacio → "Ayer " (FW1/FW2)
  T  "pof" + Espacio → "por" (T3-1)
  W  the touch says which neighbour (TB1/TB6, W5-1): "corriendo el " + i tapped on the i / k / u / j corner
     (just inside i's hit cell, a few px from the other three) + "ueho" + coma → "juego,"; the same with i
     tapped in its middle stays "iueho," (from the letters alone juego and luego tie)
  O  opted-out fields read, never write (OR1-OR3, OR6): GrapheneOS taught in a plain field (twice) and one
     strike stored (lentl → lento rejected once); then in a host field with FLAG_NO_SUGGESTIONS ("social":
     CAP_SENTENCES | MULTI_LINE | NO_SUGGESTIONS, like Google Keep's note body): the chip reads "No guarda lo
     que escribes aquí", "grapheneos" + Espacio → "GrapheneOS", "gtapheneos" + Espacio → "GrapheneOS",
     lentl → lento rejected there by Borrar, a new word typed twice; files/personal/words.json and
     rejections.json (read with run-as) are byte-identical before / after. An incognito field reads nothing.
  R  r13c's rows A-E (scripts/e2e_r13c.py sections, unchanged)
  L  latency (optional --before-apk = the 0.10.0 debug build): the IME's own "keytime" lines (kind + time,
     never text) on a text with five-letter words, before / after

Writes build/e2e-r13d/r13d-e2e.json + PNGs. Debug package only (com.resyst.vk.debug); the default input
method is restored at the end.
Usage: scripts/e2e_r13d.py --serial SERIAL [--apk PATH] [--before-apk PATH] [--only F,T,W,O,R,L]

Environment:
  VK_E2E_FORBIDDEN_SERIALS  comma-separated device serials this script must refuse to touch (your own
                            phones); default empty. A --serial in the list exits before any adb call.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r13 as r13  # noqa: E402
import e2e_r13c as r13c  # noqa: E402
from e2e import PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r13d')
FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
rows = []
VERSION = '0.11.0'


def sh(cmd, check=True) -> str:
    """adb shell, decoded (e2e.sh returns text unless asked for bytes)."""
    return str(e2e.sh(cmd, check=check))


def check(section, name, ok, detail: object = ''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:240] if detail else ''), flush=True)


def shot(name):
    r13c.shot(name)


def field():
    return r13c.field()


def fresh(kind='text'):
    return r13c.fresh(kind)


def type_seq(text, k=None):
    return r13c.type_seq(text, k)


def density():
    m = re.search(r'Override density: (\d+)', sh('wm density')) or re.search(r'Physical density: (\d+)', sh('wm density'))
    return int(m.group(1)) / 160.0


def node(k, letter):
    n = k.get(letter)
    return n if n is not None else k.get(letter.upper())


def corner_tap_i(k):
    """Taps i just inside its HIT cell, at the corner it shares with k, u and j (KeyboardView.boxAt: rows
    meet gap/2 below a drawn box, keys at the gap midpoints). Returns the point and the cells' distances."""
    i1, iy1, i2, iy2 = e2e.bounds(node(k, 'i'))
    u1, uy1, u2, uy2 = e2e.bounds(node(k, 'u'))
    k1, ky1, k2, ky2 = e2e.bounds(node(k, 'k'))
    dp = density()
    gap = (ky1 - iy2) - dp            # vertical gap between drawn boxes = gap + dp
    row_edge = iy2 + gap / 2          # i's row cell ends here (k's starts)
    col_edge = (u2 + i1) / 2          # u's / j's column cells end here (i's / k's start)
    x = i1 + 1
    y = int(iy2 + gap / 4)
    detail = {'tap': (x, y), 'to_k_cell_px': round(row_edge - y, 1), 'to_u_cell_px': round(x - col_edge, 1),
              'key_width_px': i2 - i1, 'dp': dp, 'gap_px': round(gap, 1)}
    sh(f'input tap {x} {y}')
    time.sleep(0.25)
    return detail


def personal_sha(name):
    out = sh(f'run-as {PKG} sh -c "cat files/personal/{name} 2>/dev/null || echo MISSING"', check=False)
    return hashlib.sha256(out.encode('utf-8')).hexdigest()[:16], out


# ── F: the first word of a field ─────────────────────────────────────────
def section_f():
    k = fresh('multiline')
    f0 = field()
    type_seq('auer ', k)
    f = field()
    shot('r13d-F-first-word.png')
    check('F', 'the field starts empty', f0 == '', repr(f0))
    check('F', 'auer + Espacio as the first word of an empty field → "Ayer "', f == 'Ayer ', repr(f))
    k = fresh('text')
    type_seq('te escribo porque auer ', k)
    f = field()
    check('F', 'mid-sentence too → ayer', f.lower() == 'te escribo porque ayer ', repr(f))


# ── T: three-letter words ────────────────────────────────────────────────
def section_t():
    k = fresh('text')
    type_seq('la idea era entender pof que ', k)
    f = field()
    shot('r13d-T-pof.png')
    check('T', 'pof + Espacio → por', f.lower() == 'la idea era entender por que ', repr(f))
    k = fresh('text')
    type_seq('pon fue sol ', k)
    f = field()
    check('T', 'real three-letter words stay (pon fue sol)', f.lower() == 'pon fue sol ', repr(f))


# ── W: the touch says which neighbour ────────────────────────────────────
def section_w():
    k = fresh('text')
    time.sleep(1.0)
    type_seq('corriendo el ', k)
    d = corner_tap_i(k)
    type_seq('ueho,', k)
    f = field()
    shot('r13d-W-corner.png')
    check('W', f'i tapped on the i/k/u/j corner + ueho + coma → juego, {d}', f.lower() == 'corriendo el juego,', repr(f))
    check('W', 'the corner tap committed i (inside its hit cell), within the border radius of k and u',
          d['to_k_cell_px'] > 0 and d['to_u_cell_px'] > 0 and max(d['to_k_cell_px'], d['to_u_cell_px']) < 0.12 * d['key_width_px'], d)
    k = fresh('text')
    type_seq('corriendo el iueho,', k)
    f = field()
    shot('r13d-W-middle.png')
    check('W', 'i tapped in its middle + ueho + coma stays iueho, (luego / juego tie)', f.lower() == 'corriendo el iueho,', repr(f))


# ── O: opted-out fields read, never write ────────────────────────────────
def chip_label():
    for n in e2e.dump().iter('node'):
        d = n.get('content-desc', '')
        if n.get('package') == PKG and d.startswith('Sin memoria'):
            return d
    return None


def section_o():
    sh(f'pm clear {PKG}', check=False)  # debug package only: a clean store
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    k = fresh('text')
    time.sleep(2.5)  # lexicons + valid sets load on the io thread
    check('O', 'a plain field shows no "Sin memoria" chip', chip_label() is None, chip_label())
    type_seq('uso GrapheneOS y GrapheneOS hoy y andaba tan lentl ', k)
    f = field()
    check('O', 'taught GrapheneOS twice; lentl corrected in the plain field', f.endswith('GrapheneOS y GrapheneOS hoy y andaba tan lento '), repr(f))
    type_seq('⌫ ', k)
    f = field()
    check('O', 'lentl rejected once there (a strike)', f.endswith('tan lentl '), repr(f))
    time.sleep(2.5)  # PersonalStore saves 1.5 s after the last change
    k = fresh('social')  # FLAG_NO_SUGGESTIONS prose: Google Keep's note body
    time.sleep(1.5)
    words0, wtext = personal_sha('words.json')
    rej0, rtext = personal_sha('rejections.json')
    open(os.path.join(OUT, 'r13d-O-words-before.json'), 'w').write(wtext)
    check('O', 'the plain field stored GrapheneOS with its casing', '"GrapheneOS"' in wtext, wtext[:200])
    check('O', 'the plain field stored one strike', '"strikes":[["lentl","lento"]]' in rtext, rtext[:200])
    label = chip_label()
    shot('r13d-O-chip.png')
    check('O', 'the opted-out field\'s chip says it does not store what is typed', label is not None and 'No guarda lo que escribes aquí' in label, label)
    type_seq('me gusta grapheneos ', k)
    f = field()
    check('O', 'grapheneos + Espacio → GrapheneOS (learned casing read)', f == 'Me gusta GrapheneOS ', repr(f))
    type_seq('y gtapheneos ', k)
    f = field()
    shot('r13d-O-learned.png')
    check('O', 'gtapheneos + Espacio → GrapheneOS (a learned word as a correction target)', f == 'Me gusta GrapheneOS y GrapheneOS ', repr(f))
    type_seq('andaba tan lentl ', k)
    f = field()
    check('O', 'lentl corrected here too (the strike is read, not final)', f.endswith('tan lento '), repr(f))
    type_seq('⌫ ', k)
    f = field()
    check('O', 'lentl rejected again here by Borrar', f.endswith('tan lentl '), repr(f))
    type_seq('y zorglub zorglub ', k)
    f = field()
    shot('r13d-O-typed.png')
    fresh('text')  # leave the field: onFinishInputView flushes anything pending
    time.sleep(3.0)
    words1, wtext1 = personal_sha('words.json')
    rej1, rtext1 = personal_sha('rejections.json')
    open(os.path.join(OUT, 'r13d-O-words-after.json'), 'w').write(wtext1)
    open(os.path.join(OUT, 'r13d-O-rejections.json'), 'w').write(rtext1)
    check('O', f'words.json byte-identical after typing in the opted-out field ({words0} → {words1})', words0 == words1 and 'zorglub' not in wtext1, words1)
    check('O', f'rejections.json byte-identical: the second rejection there was not stored ({rej0} → {rej1})', rej0 == rej1 and '"rejected":[]' in rtext1, rtext1[:200])
    k = fresh('incognito')
    time.sleep(1.0)
    type_seq('dice grapheneos y gtapheneos ', k)
    f = field()
    shot('r13d-O-incognito.png')
    check('O', 'an incognito field reads nothing (grapheneos / gtapheneos stay)', f.lower() == 'dice grapheneos y gtapheneos ' and 'GrapheneOS' not in f, repr(f))


# ── R: r13c's rows A-E ───────────────────────────────────────────────────
def section_r():
    before = len(r13c.rows)
    for fn in (r13c.section_abc, r13c.section_d, r13c.section_e):
        try:
            fn()
        except Exception as ex:
            r13c.check('r13c', f'{fn.__name__} ran', False, repr(ex))
    for r in r13c.rows[before:]:
        check('R/' + r['section'], r['check'], r['ok'], r['detail'])


# ── L: latency ───────────────────────────────────────────────────────────
TEXT = 'hoy quiero probar el teclado en serio, y ver si corrige calna. andaba tan lentl, que pillae. el gjsga juego y la tarde, sin romper cachai ni fome.\n'


def section_l(apk, before_apk):
    if not before_apk:
        return None
    r13c.TEXT = TEXT
    before = r13c.measure(before_apk, 'before', downgrade=True)
    after = r13c.measure(apk, 'after', downgrade=False)
    for kind in ('key', 'key space', 'key end', 'bar'):
        b, a = before.get(kind), after.get(kind)
        if b and a:
            check('L', f'{kind}: median {b["median_us"]} → {a["median_us"]} us, p90 {b["p90_us"]} → {a["p90_us"]} us',
                  a['median_us'] <= max(b['median_us'] * 1.5, b['median_us'] + 2000), {'before': b, 'after': a})
    return {'before': before, 'after': after, 'text': TEXT}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--before-apk')
    ap.add_argument('--only', default='F,T,W,O,R,L')
    a = ap.parse_args()
    if a.serial in FORBIDDEN:
        sys.exit(f'refusing to run on {a.serial}')
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    r13.OUT = OUT
    r13c.OUT = OUT
    only = set(a.only.split(','))
    previous = sh('settings get secure default_input_method').strip()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk}) previous IME={previous}')
    latency = None
    try:
        r13c.install('-r', '-t', '-d', a.apk)
        sh(f'pm clear {PKG}', check=False)  # debug package only
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        fresh()
        time.sleep(3.0)
        version = sh(f'dumpsys package {PKG} | grep -m1 versionName').strip()
        check('setup', f'the debug build under test is {VERSION}', VERSION in version, version)
        for sec, fn in (('F', section_f), ('T', section_t), ('W', section_w), ('O', section_o), ('R', section_r)):
            if sec in only:
                try:
                    fn()
                except Exception as ex:  # one broken section must not hide the others
                    check(sec, 'section ran', False, repr(ex))
        if 'L' in only:
            latency = section_l(a.apk, a.before_apk)
    finally:
        sh(f'ime set {previous}', check=False)
        restored = sh('settings get secure default_input_method').strip()
        check('teardown', 'default input method restored', restored == previous, restored)
        res = {'device': model, 'sdk': sdk, 'rows': rows, 'latency': latency,
               'passed': sum(r['ok'] for r in rows), 'failed': sum(not r['ok'] for r in rows)}
        json.dump(res, open(os.path.join(OUT, 'r13d-e2e.json'), 'w'), ensure_ascii=False, indent=1)
        print(f"✦ {res['passed']} passed, {res['failed']} failed → build/e2e-r13d/r13d-e2e.json")
    sys.exit(1 if any(not r['ok'] for r in rows) else 0)


if __name__ == '__main__':
    main()
