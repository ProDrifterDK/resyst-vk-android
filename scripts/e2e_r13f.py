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
  O  r13f offers (context offers in the bar): "paso por tu caza como " → the chip "casa" ("Cambiar caza por casa") → a
     tap → "Paso por tu casa como ", the cursor still at the end (an "a" typed after it lands there); "no se di vamos "
     → the chip "si"; a right sentence → no chip and no offer decided; a password field and an incognito field → no
     chip, no decision; an opted-out ("Sin memoria") composer → the chip works and words.json / rejections.json stay
     byte-identical; the switch "Sugerencias por contexto" turned off in Settings → no chip; Java heap before / after the
     model loads (dumpsys meminfo after a forced GC, and the debug build's own GC'd heap line), decision times (the
     offertime lines), the APK size. The debug build is debuggable (ART runs it interpreted: ~8 ms a decision), so the
     ≤ 2 ms budget is checked on --timing-apk: a NON-debuggable build of the same commit for the same debug package
     (build it with `isDebuggable = false` in the debug build type and `timing = true` in ResystImeService, both
     reverted afterwards), as installed and then AOT-compiled (`cmd package compile -m speed`, what an installed
     release reaches); the debug APK is reinstalled after it

Writes build/e2e-r13f/r13f-e2e.json + PNGs. Debug package only (com.resyst.vk.debug); the default input method is
restored at the end.
Usage: scripts/e2e_r13f.py --serial SERIAL [--apk PATH] [--only A,B,C,D,W,E,O] [--out DIR] [--timing-apk PATH]

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


# ── O: context offers (r13f offers) ──────────────────────────────────────
PROFILES = 'shared_prefs/resyst_vk_profiles.xml'
TIMING_TEXTS = ('mañana paso por tu caza como a las siete si no alcanzo te aviso ',
                'no se di vamos a llegar a tiempo porque el trafico esta pesado ',
                'esa pelicula es yan buena como dicen y la vimos el fin de semana ')
offer_metrics = {}


CHIP = re.compile(r'^(?:Cambiar (\S+) por (\S+)|Replace (\S+) with (\S+))$')  # the phone's language: es / en strings


def offer_chips(k=None):
    """The context offer chips on the bar as (typed, offered), from their TalkBack text ("Cambiar caza por casa" /
    "Replace caza with casa": the string resources follow the phone's language)."""
    k = k if k is not None else r13.km()
    out = []
    for d in k:
        m = CHIP.match(d)
        if m:
            g = [x for x in m.groups() if x is not None]
            out.append((g[0], g[1]))
    return out


def chip_node(k, pair):
    return next(v for d, v in k.items() if CHIP.match(d) and [x for x in CHIP.match(d).groups() if x] == list(pair))


def wait_chip(timeout=4.0):
    t0 = time.time()
    k = {}
    while time.time() - t0 < timeout:
        k = r13.km()
        c = offer_chips(k)
        if c:
            return c, k
        time.sleep(0.4)
    return [], k


def offertimes(log):
    return [(int(us), ok == 'true') for us, ok in re.findall(r'offertime: decide us=(\d+) offer=(true|false)', log)]


def logcat():
    return sh('logcat -d -s ResystVK:I', check=False)


def gc_and_meminfo(tag):
    """dumpsys meminfo of the debug package after a forced GC (SIGUSR1 through run-as: debuggable builds only)."""
    pid = sh(f'pidof {PKG}', check=False).strip().split()
    if pid:
        sh(f'run-as {PKG} kill -10 {pid[0]}', check=False)
        time.sleep(2.5)
    out = sh(f'dumpsys meminfo {PKG}', check=False)
    open(os.path.join(OUT, f'r13f-O-meminfo-{tag}.txt'), 'w').write(out)

    def kb(label):
        m = re.search(re.escape(label) + r'\s+(\d+)', out)
        return int(m.group(1)) if m else None
    return {'pid': pid[0] if pid else None, 'java_heap_kb': kb('Java Heap:'), 'native_heap_kb': kb('Native Heap:'),
            'total_pss_kb': kb('TOTAL PSS:') or kb('TOTAL:'), 'apk_mmap_pss_kb': kb('.apk mmap')}


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


def set_offers(on):
    """Settings › Escritura › "Sugerencias por contexto" through the screen (a tap on the switch)."""
    sh(f'am start -W -n {e2e.SETTINGS} --es com.resyst.vk.page escritura')
    time.sleep(1.8)
    node = None
    for _ in range(8):
        for n in e2e.dump().iter('node'):
            d = n.get('content-desc') or ''
            if n.get('package') == PKG and (d.startswith('Sugerencias por contexto, ') or d.startswith('Context suggestions, ')):
                node = n
        if node is not None:
            break
        sh('input swipe 540 1500 540 900 300')
        time.sleep(0.6)
    if node is None:
        raise RuntimeError('no "Sugerencias por contexto" switch on the Escritura page')
    was = node.get('content-desc').endswith('activado') and not node.get('content-desc').endswith('desactivado')
    shot('r13f-O7-settings-switch.png' if on else 'r13f-O6-settings-switch-off-before.png')
    if was != on:
        e2e.tap(node)
        time.sleep(1.2)
    stored = re.search(r'<string name="phone\.contextOffers">([^<]*)</string>', run_as(f'cat {PROFILES}'))
    sh('input keyevent 4', check=False)  # back
    time.sleep(0.8)
    return was, stored.group(1) if stored else None


def section_o(apk, timing_apk=None):
    offer_metrics['apk_bytes'] = os.path.getsize(apk)
    sh('logcat -c', check=False)
    r13d.fresh('text')
    time.sleep(3.0)  # the lexicons load; no offer was asked for yet, so the model is not mapped
    before = gc_and_meminfo('before')
    mapped_before = 'context: model mapped' in logcat()
    # 1. paso por tu caza como → casa → tap → the word changes, the cursor stays at the end
    r13c.type_seq('paso por tu caza como ')
    chips, k = wait_chip()
    shot('r13f-O1-chip-casa.png')
    typed = field()
    descs = [d for d in k if CHIP.match(d)]
    check('O', '"paso por tu caza como " → the chip "casa" (TalkBack: "Cambiar caza por casa" / "Replace caza with casa")',
          chips == [('caza', 'casa')], f'field {typed!r}, chips {descs}')
    if chips:
        e2e.tap(chip_node(k, chips[0]))
        time.sleep(0.9)
    after_tap = field()
    left = offer_chips()
    r13c.type_seq('a')
    with_a = field()
    shot('r13f-O2-after-tap.png')
    check('O', 'a tap on the chip → "Paso por tu casa como ", the chip gone, the cursor still at the end (an "a" lands after "como ")',
          after_tap == 'Paso por tu casa como ' and not left and with_a == 'Paso por tu casa como a', f'{after_tap!r} → {with_a!r}, chips {left}')
    log = logcat()
    m = re.search(r'context: model mapped vocab=(\d+) ms=(\d+)(?: heapKb=(-?\d+))?', log)
    after = gc_and_meminfo('after')
    offer_metrics['meminfo_before'] = before
    offer_metrics['meminfo_after'] = after
    offer_metrics['model_line'] = m.group(0) if m else None
    jh = (after['java_heap_kb'] or 0) - (before['java_heap_kb'] or 0)
    offer_metrics['java_heap_delta_kb'] = jh
    check('O', 'Java heap after the model is mapped: dumpsys meminfo delta ≤ 5 MB (forced GC before each reading); not mapped before the first offer was asked for',
          m is not None and not mapped_before and before['java_heap_kb'] is not None and jh <= 5 * 1024,
          f'Java Heap {before["java_heap_kb"]} → {after["java_heap_kb"]} kB (Δ {jh} kB); in-process: {m.group(0) if m else None}; .apk mmap PSS {before["apk_mmap_pss_kb"]} → {after["apk_mmap_pss_kb"]} kB')
    # 2. no se di vamos → si
    r13d.fresh('text')
    r13c.type_seq('no se di vamos ')
    chips, _ = wait_chip()
    shot('r13f-O3-chip-si.png')
    check('O', '"no se di vamos " → the chip "si" ("Cambiar di por si")', chips == [('di', 'si')], f'field {field()!r}, chips {chips}')
    # 3. a right sentence: no chip, no offer decided
    r13d.fresh('text')
    sh('logcat -c', check=False)
    r13c.type_seq('mañana paso por tu casa como a las siete. ')
    time.sleep(1.5)
    chips = offer_chips()
    times = offertimes(logcat())
    shot('r13f-O4-right-sentence.png')
    check('O', 'a right sentence ("Mañana paso por tu casa como a las siete. "): no chip, every decision says no',
          not chips and times and not any(ok for _, ok in times), f'field {field()!r}, chips {chips}, decisions {len(times)} offers {sum(ok for _, ok in times)}')
    # 4. secret and incognito fields: no chip, no decision
    for kind, name in (('password', 'r13f-O5-password.png'), ('incognito', 'r13f-O5-incognito.png')):
        r13d.fresh(kind)
        sh('logcat -c', check=False)
        r13c.type_seq('paso por tu caza como ')
        time.sleep(1.5)
        chips = offer_chips()
        times = offertimes(logcat())
        shot(name)
        check('O', f'a {kind} field: no chip, no decision (PV1)', not chips and not times, f'field {field()!r}, chips {chips}, decisions {len(times)}')
    # 5. an opted-out ("Sin memoria") composer: the chip works, nothing is stored
    r13d.fresh('text')  # leaving a field flushes what was learned
    words0, rej0 = run_as('cat files/personal/words.json'), run_as('cat files/personal/rejections.json')
    r13d.fresh('social')
    r13c.type_seq('paso por tu caza como ')
    chips, k = wait_chip()
    if chips:
        e2e.tap(chip_node(k, chips[0]))
        time.sleep(0.9)
    social = field()
    shot('r13f-O8-social.png')
    r13d.fresh('text')  # flush again
    time.sleep(1.0)
    words1, rej1 = run_as('cat files/personal/words.json'), run_as('cat files/personal/rejections.json')
    check('O', 'an opted-out ("Sin memoria") composer: the chip works ("Cambiar caza por casa" → "Paso por tu casa como ") and words.json / rejections.json stay byte-identical (PV2)',
          chips == [('caza', 'casa')] and social == 'Paso por tu casa como ' and words0 == words1 and rej0 == rej1,
          f'chips {chips}, field {social!r}, words.json {len(words0)}→{len(words1)} B same={words0 == words1}, rejections same={rej0 == rej1}')
    # 6. the switch off: no chip, no decision; then on again
    was, stored = set_offers(False)
    r13d.fresh('text')
    sh('logcat -c', check=False)
    r13c.type_seq('paso por tu caza como ')
    time.sleep(1.5)
    chips = offer_chips()
    times = offertimes(logcat())
    shot('r13f-O6-switch-off.png')
    check('O', 'Settings › "Sugerencias por contexto" off: no chip, no decision (PV4)',
          was is True and stored == 'false' and not chips and not times, f'switch was on={was}, stored {stored!r}, chips {chips}, decisions {len(times)}')
    was2, stored2 = set_offers(True)
    check('O', 'the switch back on (default)', was2 is False and stored2 == 'true', f'{was2} {stored2!r}')
    # decision times (the offertime lines: the decision only, the model's mapping excluded)
    def timed(tag):
        """Decision times (what the chip waits for) and, apart, the confusion set each word end computes ahead."""
        out, pre = [], []
        for text in TIMING_TEXTS:
            r13d.fresh('text')
            sh('logcat -c', check=False)
            r13c.type_seq(text)
            time.sleep(1.5)
            log = logcat()
            out += [t for t, _ in offertimes(log)]
            pre += [int(x) for x in re.findall(r'offertime: prefetch us=(\d+)', log)]
        us = sorted(out)
        res = {'n': len(us), 'mean_ms': round(sum(us) / len(us) / 1000, 3), 'p50_ms': us[len(us) // 2] / 1000,
               'p90_ms': us[len(us) * 9 // 10] / 1000, 'max_ms': us[-1] / 1000, 'all_us': out} if us else {'n': 0}
        if pre:
            p = sorted(pre)
            res['prefetch'] = {'n': len(p), 'mean_ms': round(sum(p) / len(p) / 1000, 3), 'p90_ms': p[len(p) * 9 // 10] / 1000, 'max_ms': p[-1] / 1000}
            res['cpu_per_word_end_mean_ms'] = round((sum(us) + sum(p)) / max(1, len(p)) / 1000, 3)
        offer_metrics['decision_' + tag] = res
        return {k: v for k, v in res.items() if k != 'all_us'}
    debug = timed('debug_build_as_installed')  # debuggable: interpreted, recorded only
    if timing_apk:
        r13c.install('-r', '-t', '-d', timing_apk)
        e2e.ensure_ime()
        dbg = sh(f'dumpsys package {PKG} | grep -m1 pkgFlags', check=False)
        cold = timed('measure_as_installed')
        comp = sh(f'cmd package compile -m speed -f {PKG}', check=False).strip()
        sh(f'am force-stop {PKG}', check=False)  # the IME process restarts on the compiled code
        time.sleep(2.0)
        e2e.ensure_ime()
        warm = timed('measure_compiled')
        offer_metrics['measure_build'] = {'apk': os.path.basename(timing_apk), 'pkgFlags': dbg.strip(), 'compile': comp}
        r13c.install('-r', '-t', '-d', apk)  # the debug build under test again
        e2e.ensure_ime()
        check('O', 'decision time ≤ 2 ms on this phone (non-debuggable build of this commit, AOT-compiled as an installed release; every word end of three chat sentences)',
              'DEBUGGABLE' not in dbg and warm.get('n', 0) > 10 and warm['mean_ms'] <= 2.0 and warm['p90_ms'] <= 2.0,
              f'compiled {warm}; same build as installed (JIT warming) {cold}; debug build (debuggable, interpreted) {debug}')
    else:
        print(f'NOTE [O] decision time budget not checked: no --timing-apk (debug build, interpreted: {debug})')
    check('O', f'APK ≤ 18 MB', offer_metrics['apk_bytes'] <= 18 * 1024 * 1024, f'{offer_metrics["apk_bytes"]} bytes')
    json.dump(offer_metrics, open(os.path.join(OUT, 'r13f-e2e-offers.json'), 'w'), ensure_ascii=False, indent=1)


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
    ap.add_argument('--only', default='A,B,C,D,W,E,O')
    ap.add_argument('--out', help='output directory (default build/e2e-r13f); e.g. a before-run on the 0.11.0 debug build')
    ap.add_argument('--timing-apk', help='section O: a non-debuggable build of this commit for the debug package (decision-time budget)')
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
        for sec, fn in (('A', section_a), ('B', section_b), ('C', section_c), ('D', section_d), ('W', section_w), ('E', section_e),
                        ('O', lambda: section_o(a.apk, a.timing_apk))):
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
