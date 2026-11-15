#!/usr/bin/env python3
"""✦ Resyst VK — r13f E2E (0.12.0: the chat-typing fixes from live session s4).

Types through the DRAWN keys (accessibility tree, like scripts/e2e_r13d.py) into the debug E2E host:
  A  F1 (CL1/CL2): "hola" + Símbolos + "?" + Espacio (still on the symbols) + Letras + "salgo" → "Hola? Salgo";
     and an emptied field: "hola" + Símbolos + "?", the field emptied from outside (the page clearing its composer
     after a send: key events, not the keyboard), Letras + "salgo" → "Salgo"
  B  F2 (OP1/OP2): an empty field, Símbolos + Más símbolos + "¿" + Letras + "que tal" → "¿Que tal"; "oye, " +
     "¿" + "vienes" → "Oye, ¿vienes"
  C  F3 (SE1/SE2): the single-line host field (IME_ACTION_DONE): "va muy lenyo" + the Enter key ("Listo") → the
     editor action receives "Va muy lento" (the host logs the field's text inside its action listener)
  D  F4 (SG1/SG2/SG4) on the space bar: a 25 dp flick up (s4's accidental shape) → a space, the language stays; a
     15 dp slip sideways in 70 ms → a space, the language stays; a 70 dp swipe up → the language switches (the
     English layout shows), no space; back; a hold-then-drag (down, ~250 ms still, then 12 dp sideways) moves the
     cursor one step; a long quick drag (30 dp) moves it three
  W  Firefox (GeckoView) on a local chat page served from this computer through `adb reverse` (a textarea whose
     Enter sends and empties it, like s4's composer): "va muy lenyo" + VK's Enter → the page sends "Va muy lento";
     "hola?" sent on the symbols layer, then Letras + "salgo" → "Salgo" in the emptied composer
  E  the r13d rows (scripts/e2e_r13d.py sections F, T, W, O, R)

Writes build/e2e-r13f/r13f-e2e.json + PNGs. Debug package only (com.resyst.vk.debug); the default input method is
restored at the end.
Usage: scripts/e2e_r13f.py --serial SERIAL [--apk PATH] [--only A,B,C,D,W,E] [--out DIR]

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
from e2e import PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r13f')
FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
rows = []
VERSION = '0.12.0'


def sh(cmd, check=True) -> str:
    return str(e2e.sh(cmd, check=check))


def check(section, name, ok, detail: object = ''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:240] if detail else ''), flush=True)


def shot(name):
    r13c.shot(name)


def field():
    return r13c.field()


def press(desc, wait=0.45):
    r13.press(desc, wait=wait)


def tap_char(ch, wait=0.35):
    k = r13.km()
    n = k.get(ch)
    if n is None:
        raise RuntimeError(f'no key {ch!r}')
    e2e.tap(n)
    time.sleep(wait)


def density():
    m = re.search(r'Override density: (\d+)', sh('wm density')) or re.search(r'Physical density: (\d+)', sh('wm density'))
    return int(m.group(1)) / 160.0


def empty_from_outside():
    """The page clears its composer after a send: here the host's EditText gets key events (not from the IME)."""
    n = len(field())
    sh('input keyevent 123')  # MOVE_END
    if n:
        sh('input keyevent ' + ' '.join(['67'] * (n + 2)))
    time.sleep(0.8)


# ── A: F1 ────────────────────────────────────────────────────────────────
def section_a():
    r13d.fresh('text')
    r13c.type_seq('hola')
    press('Símbolos')
    tap_char('?')
    press('Espacio')
    press('Letras', wait=0.7)
    r13c.type_seq('salgo')
    f = field()
    shot('r13f-A1-after-question.png')
    check('A', '"hola" + ? + space on the symbols + Letras + "salgo" → "Hola? Salgo" (CL1)', f == 'Hola? Salgo', repr(f))
    r13d.fresh('text')
    r13c.type_seq('hola')
    press('Símbolos')
    tap_char('?')
    before = field()
    empty_from_outside()
    emptied = field()
    press('Letras', wait=0.7)
    r13c.type_seq('salgo')
    f = field()
    shot('r13f-A2-emptied-field.png')
    check('A', 'field emptied from outside on the symbols layer, Letras + "salgo" → "Salgo" (CL1/CL2)',
          before == 'Hola?' and emptied == '' and f == 'Salgo', f'{before!r} → {emptied!r} → {f!r}')


# ── B: F2 ────────────────────────────────────────────────────────────────
def opener(ch='¿'):
    press('Símbolos')
    press('Más símbolos')
    tap_char(ch)
    press('Letras', wait=0.7)


def section_b():
    r13d.fresh('text')
    opener()
    r13c.type_seq('que tal')
    f = field()
    shot('r13f-B1-opener-start.png')
    check('B', 'empty field: ¿ + "que tal" → "¿Que tal" (OP1)', f == '¿Que tal', repr(f))
    r13d.fresh('text')
    r13c.type_seq('oye, ')
    opener()
    r13c.type_seq('vienes')
    f = field()
    shot('r13f-B2-opener-mid.png')
    check('B', '"oye, " + ¿ + "vienes" → "Oye, ¿vienes" (OP2: a mid-sentence opener stays lowercase)', f == 'Oye, ¿vienes', repr(f))
    r13d.fresh('text')
    r13c.type_seq('listo. ')
    opener('¡')
    r13c.type_seq('genial')
    f = field()
    check('B', '"listo. " + ¡ + "genial" → "Listo. ¡Genial" (OP1 after a period)', f == 'Listo. ¡Genial', repr(f))


# ── C: F3 ────────────────────────────────────────────────────────────────
def section_c():
    r13d.fresh('text')  # TEXT | CAP_SENTENCES | AUTO_CORRECT, IME_ACTION_DONE: single-line, Enter = "Listo"
    time.sleep(1.0)
    r13c.type_seq('va muy lenyo')
    typed = field()
    sh('logcat -c', check=False)
    press('Listo', wait=1.0)
    log = sh('logcat -d -s ResystE2E:I', check=False)
    acts = [(a_, t_.strip()) for a_, t_ in re.findall(r'editorAction=(\d+) text=(.*)', log)]
    f = field()
    shot('r13f-C-enter.png')
    check('C', '"va muy lenyo" + Listo → the editor action reads "Va muy lento" (SE1/SE2)',
          typed == 'Va muy lenyo' and acts and acts[-1] == ('6', 'Va muy lento') and f == 'Va muy lento',
          f'typed {typed!r}, actions {acts}, field {f!r}')
    sh('logcat -c', check=False)
    r13d.fresh('text')
    r13c.type_seq('va muy lento')
    press('Listo', wait=1.0)
    acts = [(a_, t_.strip()) for a_, t_ in re.findall(r'editorAction=(\d+) text=(.*)', sh('logcat -d -s ResystE2E:I', check=False))]
    check('C', 'a right word goes out unchanged', acts and acts[-1] == ('6', 'Va muy lento'), acts)


# ── D: F4 ────────────────────────────────────────────────────────────────
def space_xy():
    n = bl.space(r13.km())
    return e2e.center(n)


def swipe(dx_dp, dy_dp, ms):
    x, y = space_xy()
    d = density()
    sh(f'input swipe {x} {y} {int(x + dx_dp * d)} {int(y + dy_dp * d)} {ms}')
    time.sleep(0.9)


def picker_up():
    w = sh('dumpsys window | grep -m1 mCurrentFocus', check=False)
    return 'InputMethodPicker' in w or 'Chooser' in w


def stamps(out):
    """Nanosecond stamps echoed by the device shell (the arithmetic is done here: mksh integers are 32-bit)."""
    return [int(v) for v in re.findall(r'\b(\d{15,})\b', out)]


def startup_ms():
    """How long one `input` command takes on this device (the hold-then-drag timing depends on it)."""
    t = stamps(sh('a=$(date +%s%N); input keyevent 0; b=$(date +%s%N); echo $a $b', check=False))
    return (t[1] - t[0]) // 1_000_000 if len(t) == 2 else 250


def section_d():
    r13d.fresh('text')
    time.sleep(0.8)
    r13c.type_seq('hola')
    swipe(2, -25, 80)  # s4's accidental shape: 21-26 dp up in 66-95 ms
    f, lang = field(), bl.space_label()
    shot('r13f-D1-short-flick.png')
    check('D', 'a 25 dp flick up types a space and keeps the language (SG1)', f == 'Hola ' and lang == 'es', f'{f!r} {lang}')
    r13c.type_seq('que')
    swipe(15, -1, 70)  # s4's accidental drag: 15 dp sideways in 75 ms
    f, lang = field(), bl.space_label()
    check('D', 'a 15 dp slip sideways types a space, no cursor move (SG2)', f == 'Hola que ' and lang == 'es', f'{f!r} {lang}')
    swipe(0, -70, 120)  # deliberate: s4's restores went 64-114 dp
    f, lang = field(), bl.space_label()
    shot('r13f-D2-deliberate-swipe.png')
    check('D', 'a 70 dp swipe up switches the language (the English layout shows) and types nothing (SG1/SG4)', f == 'Hola que ' and lang == 'en', f'{f!r} {lang}')
    swipe(0, -70, 120)
    check('D', 'the same swipe switches back', bl.space_label() == 'es', bl.space_label())
    # hold, then drag 12 dp left: one cursor step only through the hold rule (< 24 dp)
    st = startup_ms()
    wait = max(0.0, (260 - st) / 1000.0)
    x, y = space_xy()
    d = density()
    x2 = int(x - 12 * d)
    t = stamps(sh(f'input motionevent DOWN {x} {y}; b=$(date +%s%N); sleep {wait:.3f}; '
                  f'input motionevent MOVE {x2} {y}; c=$(date +%s%N); input motionevent UP {x2} {y}; echo $b $c', check=False))
    out = f'down→move ≈ {(t[1] - t[0]) // 1_000_000} ms' if len(t) == 2 else 'timing n/a'
    time.sleep(0.8)
    picker = picker_up()
    if picker:
        sh('input keyevent 4', check=False)
        time.sleep(0.8)
    r13c.type_seq('x')
    f = field()
    shot('r13f-D3-hold-drag.png')
    check('D', f'hold-then-drag 12 dp moves the cursor one step (SG2; input start {st} ms, {out})',
          f == 'Hola quex ' and not picker, f'{f!r} picker={picker}')
    r13d.fresh('text')
    r13c.type_seq('hola que ')
    swipe(-30, 0, 100)
    r13c.type_seq('x')
    f = field()
    check('D', 'a quick 30 dp drag moves the cursor three steps (SG2)', f == 'Hola qxue ', repr(f))


# ── W: a web chat composer in Firefox (GeckoView), like s4's ────────────
PAGE = b'''<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>vk-e2e-chat</title><body style="background:#08080f;color:#e9e4d6;font:20px sans-serif">
<div id=log aria-label=log>sent:</div>
<textarea id=t autofocus rows=3 style="width:92%;font-size:22px"></textarea>
<script>
const t = document.getElementById('t'), log = document.getElementById('log');
t.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); log.textContent += ' [' + t.value + ']'; t.value = ''; }
});
</script>'''


def enter_key():
    k = r13.km()
    for d in ('Intro', 'Enviar', 'Listo', 'Ir', 'Buscar', 'Siguiente', 'Nueva línea'):
        if d in k:
            e2e.tap(k[d])
            time.sleep(1.2)
            return d
    raise RuntimeError('no Enter key')


def web_nodes():
    tree = e2e.dump()
    box = sent = None
    for n in tree.iter('node'):
        if n.get('package') != 'org.mozilla.firefox':
            continue
        if n.get('class') == 'android.widget.EditText' and box is None and 'vk-e2e' not in n.get('text', '') and 'localhost' not in n.get('text', ''):
            box = n
        t = n.get('text', '') or n.get('content-desc', '')
        if t.startswith('sent:'):
            sent = t
    return box, sent


def section_w():
    """Firefox on a local page (served from this computer through `adb reverse`): a textarea whose Enter sends the
    text and empties it (the page's keydown handler), the way s4's chat composer behaved."""
    import http.server
    import threading

    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(PAGE)

        def log_message(self, format, *args):  # noqa: A002 (the base signature)
            pass

    srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), H)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        e2e.adb('reverse', f'tcp:{port}', f'tcp:{port}')
        e2e.ensure_ime()
        sh('logcat -c', check=False)
        sh(f'am start -W -a android.intent.action.VIEW -d http://localhost:{port}/ org.mozilla.firefox')
        time.sleep(4.0)
        box, _ = web_nodes()
        if box is not None:
            e2e.tap(box)
        time.sleep(1.5)
        if bl.space_label() != 'es':
            bl.set_lang('es')
        fld = re.findall(r'field: pkg=org\.mozilla\.firefox ([^\n]*)', sh('logcat -d -s ResystVK:I', check=False))
        check('W', 'Firefox composer field (what VK sees)', bool(fld), fld[-1] if fld else 'no field line')
        r13c.type_seq('va muy lenyo')
        box, _ = web_nodes()
        typed = box.get('text', '') if box is not None else None
        enter_key()
        box, sent = web_nodes()
        shot('r13f-W1-firefox-send.png')
        check('W', 'Firefox: "va muy lenyo" + VK\'s Enter → the page\'s keydown handler sends "Va muy lento" (SE1/SE2)',
              sent is not None and sent.endswith('[Va muy lento]'), f'typed {typed!r}, page {sent!r}')
        r13c.type_seq('hola')
        press('Símbolos')
        tap_char('?')
        enter_key()  # sent on the symbols layer; the page empties the field
        press('Letras', wait=0.7)
        r13c.type_seq('salgo')
        box, sent = web_nodes()
        now = box.get('text', '') if box is not None else None
        shot('r13f-W2-firefox-salgo.png')
        check('W', 'Firefox: "hola?" sent on the symbols layer, Letras + "salgo" → "Salgo" in the emptied composer (CL1/CL2)',
              sent is not None and sent.endswith('[Hola?]') and now == 'Salgo', f'page {sent!r}, composer {now!r}')
    finally:
        e2e.adb('reverse', '--remove', f'tcp:{port}', check=False)
        srv.shutdown()
        sh('input keyevent 3', check=False)  # home


# ── E: the r13d rows ─────────────────────────────────────────────────────
def section_e():
    before = len(r13d.rows)
    for fn in (r13d.section_f, r13d.section_t, r13d.section_w, r13d.section_o, r13d.section_r):
        try:
            fn()
        except Exception as ex:
            r13d.check('r13d', f'{fn.__name__} ran', False, repr(ex))
    for r in r13d.rows[before:]:
        check('E/' + r['section'], r['check'], r['ok'], r['detail'])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--only', default='A,B,C,D,W,E')
    ap.add_argument('--out', help='output directory (default build/e2e-r13f); e.g. a before-run on the 0.11.0 debug build')
    a = ap.parse_args()
    global OUT
    if a.out:
        OUT = os.path.abspath(a.out)
    if a.serial in FORBIDDEN:
        sys.exit(f'refusing to run on {a.serial}')
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    r13.OUT = OUT
    r13c.OUT = OUT
    r13d.OUT = OUT
    only = set(a.only.split(','))
    previous = sh('settings get secure default_input_method').strip()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk}) previous IME={previous}')
    try:
        sh('input keyevent KEYCODE_WAKEUP', check=False)  # a dozing screen has no focused field
        sh('wm dismiss-keyguard', check=False)  # no credential on the test phone; a locked one stays locked
        time.sleep(1.5)
        r13c.install('-r', '-t', '-d', a.apk)
        sh(f'pm clear {PKG}', check=False)  # debug package only
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        r13d.fresh()
        time.sleep(3.0)
        version = sh(f'dumpsys package {PKG} | grep -m1 versionName').strip()
        check('setup', f'the debug build under test is {VERSION}', VERSION in version, version)
        for sec, fn in (('A', section_a), ('B', section_b), ('C', section_c), ('D', section_d), ('W', section_w), ('E', section_e)):
            if sec in only:
                try:
                    sh('input keyevent KEYCODE_WAKEUP', check=False)  # no-op when awake
                    fn()
                except Exception as ex:  # one broken section must not hide the others
                    check(sec, 'section ran', False, repr(ex))
    finally:
        sh(f'ime set {previous}', check=False)
        restored = sh('settings get secure default_input_method').strip()
        check('teardown', 'default input method restored', restored == previous, restored)
        res = {'device': model, 'sdk': sdk, 'rows': rows,
               'passed': sum(r['ok'] for r in rows), 'failed': sum(not r['ok'] for r in rows)}
        json.dump(res, open(os.path.join(OUT, 'r13f-e2e.json'), 'w'), ensure_ascii=False, indent=1)
        print(f"✦ {res['passed']} passed, {res['failed']} failed → {os.path.join(OUT, 'r13f-e2e.json')}")
    sys.exit(1 if any(not r['ok'] for r in rows) else 0)


if __name__ == '__main__':
    main()
