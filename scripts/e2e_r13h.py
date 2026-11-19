#!/usr/bin/env python3
"""✦ Resyst VK — r13h E2E (0.12.1: space-bar gestures, merged touches, multi-offer apply).

Types through the DRAWN keys (accessibility tree, like scripts/e2e_r13f.py) into the debug E2E host:
  A  G1 (LF1-LF4): the switch "Cambiar idioma deslizando la barra espaciadora" is off after a clean install; a 70 dp
     swipe up on the space bar types nothing and keeps Spanish, a 25 dp drift types a space; switched on in Settings ›
     Teclas e idioma, the same swipe switches to English and back; switched off again (the default). The manual way:
     a long press on the space bar opens Android's keyboard picker, which lists this keyboard's Español / English; a tap
     on English switches the layout (the input method stays the debug build), and back to Español the same way
  B  G2 (JG1-JG5), a merged touch injected with `input`: (1) a teleport — DOWN on the space bar, ~200 ms still, one
     MOVE onto `a`, UP there (the firmware reporting the space finger's lift and the next finger's touch as one moving
     pointer): "hola" + it → "Hola a", and an `x` typed after it lands at the end (the caret did not move); (2) a 30 ms
     swipe from the space bar onto `q` (the smoothed jump of s4, ~3.4 key widths in 30 ms) → "Hola q" + x at the end;
     (3) `a` → the space bar in 30 ms: "hol" + it → "Hola " + x; (4) ⌫ → `n` in 30 ms: "hola" + it → "Holn" (⌫ and the
     letter, no word delete); the real gestures still work: (5) hold, then drag 12 dp: one cursor step; (6) a quick
     30 dp drag in 100 ms: three steps; (7) a slow 120 dp drag in 1.2 s: the caret walks many steps
  C  G3 (MO1-MO5): "paso por tu caza y esta muy bien " → the chip "2 cambios" (TalkBack "Cambiar caza por casa y esta
     por está") → a tap → "Paso por tu casa y está muy bien ", the bar offers "↶ caza, esta" → a tap restores "Paso por
     tu caza y esta muy bien "; again with an `a` typed after the tap: it lands at the end. The owner's example "paso
     por tu caza y esta bien " (esta → está scores Δ 5.73 there, under the frozen τ 6): the single chip "casa", as 0.12.0
  F  the r13f rows (scripts/e2e_r13f.py sections A, B, C, D with the flick switched on for D, W, E, O)

Writes build/e2e-r13h/r13h-e2e.json + PNGs. Debug package only (com.resyst.vk.debug); the default input method is
restored at the end.
Usage: scripts/e2e_r13h.py --serial SERIAL [--apk PATH] [--only A,B,C,F] [--out DIR] [--version 0.12.1]

Environment:
  VK_E2E_FORBIDDEN_SERIALS  comma-separated device serials this script must refuse to touch (your own phones);
                            default empty. A --serial in the list exits before any adb call.
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r10_bilingual as bl  # noqa: E402
import e2e_r13 as r13  # noqa: E402
import e2e_r13c as r13c  # noqa: E402
import e2e_r13d as r13d  # noqa: E402
import e2e_r13f as r13f  # noqa: E402
from e2e import PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r13h')
FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
PROFILES = 'shared_prefs/resyst_vk_profiles.xml'
FLICK_TITLES = ('Cambiar idioma deslizando la barra espaciadora, ', 'Switch language by swiping the space bar, ')
rows = []


def sh(cmd, check=True) -> str:
    return str(e2e.sh(cmd, check=check))


def check(section, name, ok, detail: object = ''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:240] if detail else ''), flush=True)


def shot(name):
    r13c.shot(name)


def field():
    return r13c.field()


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


def stored_flick():
    m = re.search(r'<string name="phone\.spaceFlick">([^<]*)</string>', run_as(f'cat {PROFILES}'))
    return m.group(1) if m else None


def key_xy(label):
    k = r13.km()
    n = bl.node(k, label) if len(label) == 1 else r13.find(k, label)
    if n is None:
        raise RuntimeError(f'no key {label!r}')
    return e2e.center(n)


def space_xy():
    return e2e.center(bl.space(r13.km()))


def swipe_dp(dx_dp, dy_dp, ms):
    x, y = space_xy()
    d = r13f.density()
    sh(f'input swipe {x} {y} {int(x + dx_dp * d)} {int(y + dy_dp * d)} {ms}')
    time.sleep(0.9)


def set_flick(on):
    """Settings › Teclas e idioma › the flick switch, through the screen (a tap on the switch)."""
    sh(f'am start -W -n {e2e.SETTINGS} --es com.resyst.vk.page teclas')
    time.sleep(1.8)
    node = None
    for _ in range(8):
        for n in e2e.dump().iter('node'):
            d = n.get('content-desc') or ''
            if n.get('package') == PKG and d.startswith(FLICK_TITLES):
                node = n
        if node is not None:
            break
        sh('input swipe 540 1500 540 900 300')
        time.sleep(0.6)
    if node is None:
        raise RuntimeError('no flick switch on the Teclas e idioma page')
    desc = node.get('content-desc') or ''
    was = desc.endswith('activado') and not desc.endswith('desactivado')
    shot('r13h-A-settings-switch-on.png' if on else 'r13h-A-settings-switch-before.png')
    if was != on:
        e2e.tap(node)
        time.sleep(1.2)
    stored = stored_flick()
    sh('input keyevent 4', check=False)  # back
    time.sleep(0.8)
    return was, stored


def picker_rows():
    """The system keyboard picker: its rows [text, checked, node, group] in screen order; group = the index of the
    input method header ("Resyst VK ✦", "Gboard"...) above the row."""
    out, headers = [], []
    for n in e2e.dump().iter('node'):
        if n.get('package') != 'com.android.systemui':
            continue
        t = n.get('text') or ''
        if n.get('checkable') == 'true':
            out.append([t, n.get('checked') == 'true', n, len(headers) - 1])
        elif n.get('class') == 'android.widget.TextView' and t:
            if out and out[-1][0] == '' and e2e.bounds(n)[1] < e2e.bounds(out[-1][2])[3]:
                out[-1][0] = t  # the row's own label
            else:
                headers.append(t)
    return out


def pick_language(name):
    """Long press on the space bar → Android's picker → [name] ("English (Resyst)") in the group of the checked row
    (the current input method: this debug build)."""
    x, y = space_xy()
    sh(f'input swipe {x} {y} {x} {y} 900')
    time.sleep(1.5)
    rws = picker_rows()
    texts = [r[0] for r in rws]
    shot(f'r13h-A-picker-{name.split()[0].lower()}.png')
    cur = next((r for r in rws if r[1]), None)
    target = next((r for r in rws if cur is not None and r[3] == cur[3] and r[0] == name), None)
    if target is not None:
        e2e.tap(target[2])
        time.sleep(2.0)
    else:
        sh('input keyevent 4', check=False)
        time.sleep(0.8)
    return texts, target is not None


def set_lang(target):
    """The shared helpers (r13c.fresh, r13f W) put Spanish back with the space flick, which 0.12.1 ships off: here it
    waits for the keyboard and switches through Android's picker (the long press) instead. Returns the switches used."""
    for _ in range(10):
        if bl.space_label() != '?':
            break
        time.sleep(0.4)
    else:
        return 0  # no keyboard on screen: the caller's own checks say so
    for i in range(2):
        if bl.space_label() == target:
            return i
        pick_language('Español (Resyst)' if target == 'es' else 'English (Resyst)')
    return 2


bl.set_lang = set_lang


# ── A: G1 ────────────────────────────────────────────────────────────────
def section_a():
    r13d.fresh('text')
    time.sleep(0.8)
    st0 = stored_flick()
    r13c.type_seq('hola')
    swipe_dp(0, -70, 120)  # deliberate (s4's restores went 64-114 dp)
    f1, l1 = field(), bl.space_label()
    shot('r13h-A1-swipe-off.png')
    check('A', 'clean install: the switch is off (stored false or absent); a 70 dp swipe up on space types nothing and keeps Spanish (LF1/LF3)',
          st0 in (None, 'false') and f1 == 'Hola' and l1 == 'es', f'stored {st0!r}, field {f1!r}, layout {l1}')
    swipe_dp(2, -25, 80)  # s4's accidental shape
    f2, l2 = field(), bl.space_label()
    check('A', 'a 25 dp drift up is still a space (LF3)', f2 == 'Hola ' and l2 == 'es', f'{f2!r} {l2}')
    was, stored = set_flick(True)
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola')
    swipe_dp(0, -70, 120)
    f3, l3 = field(), bl.space_label()
    shot('r13h-A2-swipe-on.png')
    swipe_dp(0, -70, 120)
    l4 = bl.space_label()
    check('A', 'switched on in Settings › Teclas e idioma: the 70 dp swipe switches to English (no space typed) and back (LF1)',
          was is False and stored == 'true' and f3 == 'Hola' and l3 == 'en' and l4 == 'es', f'was {was}, stored {stored!r}, {f3!r} {l3} → {l4}')
    was2, stored2 = set_flick(False)
    check('A', 'switched off again (the default)', was2 is True and stored2 == 'false', f'{was2} {stored2!r}')
    # LF4: the manual way without the flick
    r13d.fresh('text')
    time.sleep(0.8)
    texts, ok = pick_language('English (Resyst)')
    ime, lang = sh('settings get secure default_input_method').strip(), bl.space_label()
    shot('r13h-A3-picked-english.png')
    check('A', 'long press on space → Android\'s picker lists Español / English (Resyst); English switches the layout, the input method stays this build (LF4)',
          ok and 'English (Resyst)' in texts and 'Español (Resyst)' in texts and ime == IME and lang == 'en', f'picker {texts}, ime {ime}, layout {lang}')
    texts2, ok2 = pick_language('Español (Resyst)')
    lang2 = bl.space_label()
    check('A', 'and back to Español the same way', ok2 and lang2 == 'es' and sh('settings get secure default_input_method').strip() == IME, f'layout {lang2}')


# ── B: G2 ────────────────────────────────────────────────────────────────
def motion_jump(a, b, hold_s=0.15):
    """DOWN at [a], [hold_s] still, ONE move onto [b], UP there: one pointer that teleports (no UP / DOWN between)."""
    sh(f'input motionevent DOWN {a[0]} {a[1]}; sleep {hold_s:.3f}; input motionevent MOVE {b[0]} {b[1]}; '
       f'input motionevent UP {b[0]} {b[1]}', check=False)
    time.sleep(0.9)


def fast_swipe(a, b, ms=30):
    sh(f'input swipe {a[0]} {a[1]} {b[0]} {b[1]} {ms}')
    time.sleep(0.9)


def section_b():
    # 1. space → a, a teleport after a held space
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola')
    motion_jump(space_xy(), key_xy('a'))
    f = field()
    r13c.type_seq('x')
    fx = field()
    shot('r13h-B1-teleport-space-a.png')
    check('B', 'space held, then the pointer teleports onto a (one MOVE, same pointer): "hola" + it → "Hola a", an x lands at the end (JG1)',
          f == 'Hola a' and fx == 'Hola ax', f'{f!r} → {fx!r}')
    # 2. space → q in 30 ms (a smoothed jump)
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola')
    fast_swipe(space_xy(), key_xy('q'))
    f = field()
    r13c.type_seq('x')
    fx = field()
    shot('r13h-B2-fast-space-q.png')
    check('B', 'the pointer travels from space onto q in 30 ms: "Hola q", an x lands at the end (JG1/JG2/JG3)',
          f == 'Hola q' and fx == 'Hola qx', f'{f!r} → {fx!r}')
    # 3. a → space in 30 ms
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hol')
    fast_swipe(key_xy('a'), space_xy())
    f = field()
    r13c.type_seq('x')
    fx = field()
    check('B', 'a letter, then the pointer travels onto space in 30 ms: "hol" + it → "Hola ", an x after it (JG1)',
          f == 'Hola ' and fx == 'Hola x', f'{f!r} → {fx!r}')
    # 4. ⌫ → n in 30 ms
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola')
    fast_swipe(key_xy('Borrar'), key_xy('n'))
    f = field()
    shot('r13h-B4-fast-backspace-n.png')
    check('B', '⌫, then the pointer travels onto n in 30 ms: one ⌫ and the n, no word delete ("hola" → "Holn") (JG1)', f == 'Holn', repr(f))
    # 5. hold, then drag 12 dp: one step (r13f SG2 unchanged)
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola que ')
    st = r13f.startup_ms()
    wait = max(0.0, (260 - st) / 1000.0)
    x, y = space_xy()
    x2 = int(x - 12 * r13f.density())
    sh(f'input motionevent DOWN {x} {y}; sleep {wait:.3f}; input motionevent MOVE {x2} {y}; input motionevent UP {x2} {y}', check=False)
    time.sleep(0.8)
    if r13f.picker_up():
        sh('input keyevent 4', check=False)
        time.sleep(0.8)
    r13c.type_seq('x')
    f = field()
    shot('r13h-B5-hold-drag.png')
    check('B', f'a deliberate hold-then-drag 12 dp still moves the caret one step (input start {st} ms)', f == 'Hola quex ', repr(f))
    # 6. a quick 30 dp drag in 100 ms: three steps
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola que ')
    swipe_dp(-30, 0, 100)
    r13c.type_seq('x')
    f = field()
    check('B', 'a quick 30 dp drag moves the caret three steps (SG2 unchanged)', f == 'Hola qxue ', repr(f))
    # 7. a slow long drag: the caret walks (never more than 3 steps a sample, every step arrives)
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola que tal como estas ')
    before = field()
    swipe_dp(-120, 0, 1200)
    r13c.type_seq('x')
    f = field()
    i = f.find('x')
    check('B', 'a slow 120 dp drag in 1.2 s moves the caret many steps (13 steps of 9 dp; the per-sample cap does not shorten it)',
          i >= 0 and len(before) - i >= 10 and f.replace('x', '', 1) == before, f'{before!r} → {f!r} (x at {i}, {len(before) - i} from the end)')


# ── C: G3 ────────────────────────────────────────────────────────────────
MANY = re.compile(r'^(?:Cambiar|Replace) (.+)$')


def chips(k):
    """(single chips, multi chip desc) from the bar's TalkBack texts."""
    single = r13f.offer_chips(k)
    multi = [d for d in k if MANY.match(d) and not r13f.CHIP.match(d)]
    return single, multi


def wait_offers(n, timeout=5.0):
    t0 = time.time()
    k = {}
    while time.time() - t0 < timeout:
        k = r13.km()
        s, m = chips(k)
        if (n == 1 and s) or (n > 1 and m):
            return s, m, k
        time.sleep(0.4)
    s, m = chips(k)
    return s, m, k


def section_c():
    r13d.fresh('text')
    time.sleep(3.0)
    r13c.type_seq('paso por tu caza y esta muy bien ')
    single, multi, k = wait_offers(2)
    shot('r13h-C1-chip-2-cambios.png')
    want = ('Cambiar caza por casa y esta por está', 'Replace caza with casa and esta with está')
    check('C', '"paso por tu caza y esta muy bien " → one chip for both offers, TalkBack "Cambiar caza por casa y esta por está" (MO1/MO2)',
          multi and multi[0] in want and not single, f'field {field()!r}, multi {multi}, single {single}')
    if multi:
        e2e.tap(k[multi[0]])
        time.sleep(1.0)
    after = field()
    k2 = r13.km()
    bar = r13.bar(k2)
    shot('r13h-C2-after-tap.png')
    undo = next((b for b in bar if b.startswith('↶ ')), None)
    check('C', 'one tap → "Paso por tu casa y está muy bien ", the chip gone, the bar offers "↶ caza, esta" (MO3/MO4)',
          after == 'Paso por tu casa y está muy bien ' and not any(chips(k2)) and undo == '↶ caza, esta', f'{after!r}, bar {bar}')
    if undo:
        e2e.tap(r13.find(k2, 'Sugerencia: ' + undo))
        time.sleep(1.0)
    restored = field()
    shot('r13h-C3-undo.png')
    check('C', 'one ↶ restores both words: "Paso por tu caza y esta muy bien " (MO4)', restored == 'Paso por tu caza y esta muy bien ', repr(restored))
    # the cursor stays at the end: an a typed after the tap lands there
    r13d.fresh('text')
    time.sleep(1.0)
    r13c.type_seq('paso por tu caza y esta muy bien ')
    single, multi, k = wait_offers(2)
    if multi:
        e2e.tap(k[multi[0]])
        time.sleep(1.0)
    r13c.type_seq('a')
    f = field()
    k3 = r13.km()
    check('C', 'after the tap the caret is still at the end (an a lands after "bien "), and typing ends the ↶ (MO3/MO4)',
          f == 'Paso por tu casa y está muy bien a' and not any(b.startswith('↶ caza') for b in r13.bar(k3)), f'{f!r}, bar {r13.bar(k3)}')
    # the owner's example: one offer (esta → está Δ 5.73 < τ 6), the 0.12.0 chip
    r13d.fresh('text')
    time.sleep(1.0)
    r13c.type_seq('paso por tu caza y esta bien ')
    single, multi, k = wait_offers(1)
    time.sleep(1.5)
    single, multi = chips(r13.km())
    shot('r13h-C4-owner-example.png')
    check('C', '"paso por tu caza y esta bien ": the single chip "casa" as in 0.12.0 (esta → está scores Δ 5.73 there, under the frozen τ 6)',
          single == [('caza', 'casa')] and not multi, f'single {single}, multi {multi}')


# ── F: the r13f rows ─────────────────────────────────────────────────────
def r13f_d_with_flick():
    """r13f D includes the 70 dp flick that switches the language: 0.12.1 needs the switch on for it."""
    set_flick(True)
    try:
        r13f.section_d()
    finally:
        set_flick(False)


def r13f_o_in_a_fresh_process(apk):
    """r13f O measures the model being mapped at the first offer asked for: sections A-C of this run already mapped
    it in this IME process, so the process is restarted first (as r13f's run had it: right after the install)."""
    sh(f'am force-stop {PKG}', check=False)
    time.sleep(2.0)
    e2e.ensure_ime()
    r13f.section_o(apk)


def section_f(apk):
    before = len(r13f.rows)
    for name, fn in (('A', r13f.section_a), ('B', r13f.section_b), ('C', r13f.section_c), ('D', r13f_d_with_flick), ('W', r13f.section_w),
                     ('E', r13f.section_e), ('O', lambda: r13f_o_in_a_fresh_process(apk))):
        try:
            sh('input keyevent KEYCODE_WAKEUP', check=False)
            fn()
        except Exception as ex:
            r13f.check(name, 'section ran', False, repr(ex))
    for r in r13f.rows[before:]:
        check('F/' + r['section'], r['check'], r['ok'], r['detail'])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--only', default='A,B,C,F')
    ap.add_argument('--out', help='output directory (default build/e2e-r13h); e.g. a before-run on the 0.12.0 debug build')
    ap.add_argument('--version', default='0.12.1', help='the versionName the debug build under test must report')
    a = ap.parse_args()
    global OUT
    if a.out:
        OUT = os.path.abspath(a.out)
    if a.serial in FORBIDDEN:
        sys.exit(f'refusing to run on {a.serial}')
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    r13.OUT = r13c.OUT = r13d.OUT = r13f.OUT = OUT
    only = set(a.only.split(','))
    previous = sh('settings get secure default_input_method').strip()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk}) previous IME={previous}')
    try:
        sh('input keyevent KEYCODE_WAKEUP', check=False)
        sh('wm dismiss-keyguard', check=False)
        time.sleep(1.5)
        r13c.install('-r', '-t', '-d', a.apk)
        sh(f'pm clear {PKG}', check=False)  # debug package only
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        r13d.fresh()
        time.sleep(3.0)
        version = sh(f'dumpsys package {PKG} | grep -m1 versionName').strip()
        check('setup', f'the debug build under test is {a.version}', a.version in version, version)
        for sec, fn in (('A', section_a), ('B', section_b), ('C', section_c), ('F', lambda: section_f(a.apk))):
            if sec in only:
                try:
                    sh('input keyevent KEYCODE_WAKEUP', check=False)
                    fn()
                except Exception as ex:  # one broken section must not hide the others
                    check(sec, 'section ran', False, repr(ex))
    finally:
        sh(f'ime set {previous}', check=False)
        restored = sh('settings get secure default_input_method').strip()
        check('teardown', 'default input method restored', restored == previous, restored)
        res = {'device': model, 'sdk': sdk, 'rows': rows,
               'passed': sum(r['ok'] for r in rows), 'failed': sum(not r['ok'] for r in rows)}
        json.dump(res, open(os.path.join(OUT, 'r13h-e2e.json'), 'w'), ensure_ascii=False, indent=1)
        print(f"✦ {res['passed']} passed, {res['failed']} failed → {os.path.join(OUT, 'r13h-e2e.json')}")
    sys.exit(1 if any(not r['ok'] for r in rows) else 0)


if __name__ == '__main__':
    main()
