#!/usr/bin/env python3
"""✦ Resyst VK — r13c E2E (0.10.0: correction at every word ending, two strikes, short words).

Types through the DRAWN keys (accessibility tree, like scripts/e2e.py / e2e_r13.py) into the debug
E2E host:
  A  "tan lentl" + coma → "tan lento," (EP1)
  B  "tenemos que pillae" + punto → "tenemos que pillar." (EP1)
  C  one Borrar right after A's comma → "tan lentl," exactly: the typed word back, the comma kept (EP3)
  D  two strikes (TS1/TS2): field 1 (text) rejects lentl → lento once by Borrar → files/personal/
     rejections.json holds a strike, words.json does not keep-mark lentl; field 2 (multi-line, another
     editor) still corrects lentl, rejected again → rejections.json `rejected` holds lentl → lento and
     words.json keep-marks lentl; field 3 (text again, another editor) keeps lentl
  E  "nl" + Espacio → "no"; "u" stays (SW1/SW2)
  L  latency: one text with letters, spaces and word endings typed on --before-apk (0.9.0 debug) and on
     --apk; the IME's own debuggable "keytime" lines (kind + time, never text) give median / p90 per kind
     (key, key space, key end = a closing mark / new line, bar)

Writes build/e2e-r13c/r13c-e2e.json + PNGs + keytime dumps. Debug package only (com.resyst.vk.debug);
the default input method is restored at the end.
Usage: scripts/e2e_r13c.py --serial SERIAL [--apk PATH] [--before-apk PATH] [--news-base-apk PATH] [--only A,B,C,D,E,N,L]

Environment:
  VK_E2E_FORBIDDEN_SERIALS  comma-separated device serials this script must refuse to touch (your
                            own phones); default empty. A --serial in the list exits before any adb call.
"""
import argparse
import json
import os
import re
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r10_bilingual as bl  # noqa: E402
import e2e_r13 as r13  # noqa: E402
from e2e import adb, sh, dump, keys, PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r13c')
FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
rows = []
MARKS = {',': 'coma', '.': 'punto', '\n': 'Nueva línea'}


def check(section, name, ok, detail: object = ''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:240] if detail else ''), flush=True)


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def field():
    return e2e.field_text(dump()) or ''


def fresh(kind='text'):
    """A new E2E host field of [kind] (text / multiline differ in inputType: another editor for the IME)."""
    e2e.open_host(kind)
    time.sleep(0.4)
    if bl.space_label() != 'es':
        bl.set_lang('es')
    return r13.km()


def type_seq(text, k=None):
    """Taps [text] on the drawn keys: letters (capitals via shift), Espacio, coma, punto, Nueva línea, ⌫ = Borrar."""
    k = k if k is not None else r13.km()
    for ch in text:
        if ch == '⌫':
            e2e.tap(r13.find(k, 'Borrar'))
        elif ch in MARKS:
            e2e.tap(r13.find(k, MARKS[ch]))
        else:
            r13.type_text(ch, k)
    time.sleep(0.4)
    return k


def personal(name):
    return r13.run_as(f'cat files/personal/{name}')


# ── A / B / C: endings and their undo ────────────────────────────────────
def section_abc():
    k = fresh()
    type_seq('al final andaba tan lentl,', k)
    f = field()
    shot('r13c-A-comma.png')
    check('A', 'tan lentl + coma → tan lento,', f.lower() == 'al final andaba tan lento,', repr(f))
    type_seq('⌫', k)
    f = field()
    shot('r13c-C-undo.png')
    check('C', 'one Borrar right after → tan lentl, (typed word back, comma kept)', f.lower() == 'al final andaba tan lentl,', repr(f))
    k = fresh()
    type_seq('tenemos que pillae.', k)
    f = field()
    shot('r13c-B-period.png')
    check('B', 'que pillae + punto → que pillar.', f.lower() == 'tenemos que pillar.', repr(f))


# ── D: two strikes across fields ─────────────────────────────────────────
def strikes_of(json_text):
    m = re.search(r'"strikes":\[(.*?)\]\]?}', json_text)
    return m.group(0) if m else ''


def section_d():
    sh(f'pm clear {PKG}', check=False)  # debug package only: a clean store for the loop
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    k = fresh('text')
    time.sleep(2.5)  # lexicons + valid sets load on the io thread
    type_seq('andaba tan lentl ', k)
    f = field()
    check('D', 'field 1: lentl is corrected to lento', f.lower() == 'andaba tan lento ', repr(f))
    type_seq('⌫ ', k)
    type_seq('y lentl ', k)
    f = field()
    shot('r13c-D1-field1.png')
    check('D', 'field 1: rejected once by Borrar, lentl stays there (TS1)', f.lower() == 'andaba tan lentl y lentl ', repr(f))
    time.sleep(2.5)  # PersonalStore saves 1.5 s after the last change
    k = fresh('multiline')  # field 2: another editor (multi-line inputType)
    time.sleep(1.0)
    rej1 = personal('rejections.json')
    words1 = personal('words.json')
    check('D', 'after one rejection rejections.json holds a strike, no rejection', '"strikes":[["lentl","lento"]]' in rej1 and '"rejected":[]' in rej1, rej1[:200])
    check('D', 'after one rejection words.json does not keep-mark lentl', re.search(r'\["lentl","lentl",\d+,\d+,1', words1) is None,
          (re.search(r'\["lentl"[^\]]*\]', words1) or [''])[0])
    type_seq('hoy lentl ', k)
    f = field()
    shot('r13c-D2-field2.png')
    check('D', 'field 2 (another editor) still corrects lentl (TS1)', f.lower() == 'hoy lento ', repr(f))
    type_seq('⌫ ', k)
    f = field()
    check('D', 'field 2: rejected again by Borrar', f.lower() == 'hoy lentl ', repr(f))
    time.sleep(2.5)
    k = fresh('text')  # field 3: another editor again (after the multi-line one)
    time.sleep(1.0)
    type_seq('mi lentl ', k)
    f = field()
    shot('r13c-D3-field3.png')
    check('D', 'field 3 keeps lentl (second strike: for good)', f.lower() == 'mi lentl ', repr(f))
    time.sleep(2.5)
    rej = personal('rejections.json')
    words = personal('words.json')
    check('D', 'rejections.json holds lentl → lento as a rejection (TS2)', '"rejected":[["lentl","lento"]]' in rej and 'strikes' not in rej, rej[:200])
    kept = re.search(r'\["lentl","lentl",\d+,\d+,1', words)
    check('D', 'words.json keep-marks lentl (K1 after two)', kept is not None, kept.group(0) if kept else words[:200])
    open(os.path.join(OUT, 'r13c-D-rejections.json'), 'w').write(rej)


# ── E: two-letter words ──────────────────────────────────────────────────
def section_e():
    k = fresh()
    type_seq('dijo que nl y u otro ', k)
    f = field()
    shot('r13c-E-short.png')
    check('E', 'nl + Espacio → no (SW1)', f.lower().startswith('dijo que no '), repr(f))
    check('E', 'u stays u (SW2)', f.lower() == 'dijo que no y u otro ', repr(f))


# ── L: latency before / after ────────────────────────────────────────────
TEXT = 'hoy quiero probar el teclado en serio, y ver si corrige calna. andaba tan lentl, que pillae. listl y busvamos, sin romper cachai ni fome.\n'
WARMUP = 'ayer en la tarde estuvimos revisando el pixel con calma, '


def keytimes():
    log = adb('logcat', '-d', '-v', 'brief', '-s', 'ResystVK:I')
    out = {'key': [], 'key space': [], 'key end': [], 'bar': []}
    for kind, us in re.findall(r'keytime: (key space|key end|key|bar) us=(\d+)', log):
        out[kind].append(int(us))
    return out


def summary(v):
    if not v:
        return None
    s = sorted(v)
    return {'n': len(s), 'median_us': int(statistics.median(s)), 'p90_us': s[max(0, int(len(s) * 0.9) - 1)], 'max_us': s[-1]}


def measure(apk, label, downgrade):
    adb('install', '-r', '-t', *(['-d'] if downgrade else []), apk)
    sh(f'am force-stop {PKG}')
    time.sleep(0.6)
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    k = fresh('multiline')
    time.sleep(3.0)
    type_seq(WARMUP, k)
    k = fresh('multiline')
    sh('logcat -c')
    type_seq(TEXT, k)
    time.sleep(1.0)
    shot(f'r13c-L-{label}.png')
    t = keytimes()
    res = {kind: summary(v) for kind, v in t.items()}
    res['field'] = field()
    open(os.path.join(OUT, f'keytime-{label}.json'), 'w').write(json.dumps(t))
    return res


def section_l(apk, before_apk):
    if not before_apk:
        check('L', 'before APK given (--before-apk)', False, 'skipped')
        return None
    before = measure(before_apk, 'before', downgrade=True)
    after = measure(apk, 'after', downgrade=False)
    check('L', 'IME timing present on both builds', before['key'] and after['key'] and before['bar'] and after['bar'], {'before': before, 'after': after})
    # 0.9.0 logs a mark / new line as a plain "key"; 0.10.0 as "key end" (it may correct now)
    b_end = summary([x for x in json.load(open(os.path.join(OUT, 'keytime-before.json')))['key']])
    for kind in ('key', 'key space', 'bar'):
        b, a = before.get(kind), after.get(kind)
        if b and a:
            check('L', f'{kind}: median before {b["median_us"]} us → after {a["median_us"]} us (p90 {b["p90_us"]} → {a["p90_us"]})',
                  a['median_us'] <= max(b['median_us'] * 1.5, b['median_us'] + 2000), {'before': b, 'after': a})
    a_end, b_space = after.get('key end'), before.get('key space')
    if a_end and b_space:
        check('L', f'key end (after) median {a_end["median_us"]} us, p90 {a_end["p90_us"]} us vs a correcting space before: median {b_space["median_us"]} us, p90 {b_space["p90_us"]} us',
              a_end['p90_us'] <= max(b_space['p90_us'] * 2, b_space['p90_us'] + 8000), {'after_end': a_end, 'before_space': b_space, 'before_key_all': b_end})
    f = after.get('field', '')
    check('L', 'the latency text was corrected at its endings on the after build', 'lento,' in f and 'pillar.' in f and 'calma.' in f, repr(f))
    return {'before': before, 'after': after, 'text': TEXT}


# ── N: "Novedades de la X" after an update (r13c phase B, WN1-WN6) ─────────
NEWS_URL = 'https://kv.resyst.cl/novedades/'
NEWS_PREFS = 'shared_prefs/resyst_vk_news.xml'


def news_chip(tree=None):
    """(chip, close) nodes of the Novedades chip in the IME's a11y tree, or (None, None)."""
    tree = tree if tree is not None else dump()
    chip = close = None
    for n in tree.iter('node'):
        d = n.get('content-desc', '')
        if n.get('package') == PKG and d.startswith('Novedades de la versión'):
            chip = n
        elif n.get('package') == PKG and d.startswith('Descartar el aviso de novedades'):
            close = n
    return chip, close


def news_seen():
    out = sh(f'run-as {PKG} cat {NEWS_PREFS}', check=False)
    m = re.search(r'name="seen">([^<]+)<', out)
    return m.group(1) if m else None


def news_log():
    return adb('logcat', '-d', '-v', 'brief', '-s', 'ResystVK:I')


def install_clean(apk):
    """A clean install of the DEBUG package only: uninstall first, so first-install == last-update time."""
    adb('uninstall', PKG, check=False)
    adb('install', '-t', apk)
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')


def install_update(base_apk, apk):
    """The base build in use (keyboard opened once), then an in-place update to [apk]."""
    install_clean(base_apk)
    fresh()
    time.sleep(2.5)  # past the installer's clock slack: lastUpdateTime moves clearly on
    adb('install', '-r', '-t', apk)
    time.sleep(1.5)
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')


def open_kind(kind):
    e2e.open_host(kind)
    time.sleep(1.2)


def section_n(base_apk, apk):
    if not base_apk:
        check('N', 'base APK given (--news-base-apk)', False, 'skipped')
        return
    version = '0.10.0-debug'
    # N1: update in place 0.9.0 -> 0.10.0; secret / incognito first, then a plain field
    install_update(base_apk, apk)
    check('N1', 'installed version after the update is 0.10.0', '0.10.0' in sh(f'dumpsys package {PKG} | grep -m1 versionName'))
    sh('logcat -c')
    for kind in ('password', 'incognito'):
        open_kind(kind)
        chip, _ = news_chip()
        check('N1', f'no notice in a {kind} field', chip is None)
    check('N1', 'still pending after the fields that could not show it (nothing stored yet)', news_seen() is None, news_seen())
    sh('logcat -c')
    open_kind('text')
    chip, close = news_chip()
    check('N1', 'the notice shows on the first plain field after the update', chip is not None and close is not None,
          chip.get('content-desc') if chip is not None else None)
    shot('r13c-N1-chip.png')
    if chip is not None:
        check('N1', 'the chip says what a tap does (browser)', 'navegador' in chip.get('content-desc', ''), chip.get('content-desc'))
        sh('logcat -c')
        e2e.tap(chip)
        time.sleep(2.5)
        log = adb('logcat', '-d', '-b', 'all', '-v', 'brief')
        fired = [l for l in log.splitlines() if 'android.intent.action.VIEW' in l and 'kv.resyst.cl/novedades' in l]
        top = sh('dumpsys activity activities | grep -E "ResumedActivity|topResumedActivity" | head -3', check=False)
        shot('r13c-N1-after-tap.png')
        check('N1', 'a VIEW intent for https://kv.resyst.cl/novedades/ fired', bool(fired), (fired[:1] or [top.strip()])[0][:300])
        check('N1', 'the foreground app left VK for the browser', PKG not in top.split('\n')[0] if top.strip() else False, top.strip()[:200])
        check('N1', 'the keyboard logged the tap (no text)', 'whats new: opened 0.10.0' in news_log(), '')
        check('N1', 'only the last version seen is stored', news_seen() == version, news_seen())
        # the strip of the browser could hold a different keyboard session: back to the host
        sh('input keyevent KEYCODE_HOME')
        time.sleep(0.8)
        open_kind('text')
        chip, _ = news_chip()
        check('N1', 'not again on the next field', chip is None)
        sh(f'am force-stop {PKG}')
        open_kind('text')
        chip, _ = news_chip()
        check('N1', 'not again after the keyboard process restarts', chip is None)
        shot('r13c-N1-gone.png')
    # N2: a clean install never shows it
    install_clean(apk)
    fresh()
    time.sleep(1.5)
    chip, _ = news_chip()
    shot('r13c-N2-clean.png')
    check('N2', 'no notice on a clean install', chip is None)
    check('N2', 'the baseline is the installed version', news_seen() == version, news_seen())
    open_kind('text')
    chip, _ = news_chip()
    check('N2', 'nor on the next field', chip is None)
    # N3: dismiss with the x: gone for good, no browser intent
    install_update(base_apk, apk)
    sh('logcat -c')
    open_kind('text')
    chip, close = news_chip()
    check('N3', 'the notice shows after a second update in place', chip is not None)
    if close is not None:
        e2e.tap(close)
        time.sleep(0.8)
        chip, _ = news_chip()
        log = adb('logcat', '-d', '-b', 'all', '-v', 'brief')
        check('N3', 'the x dismisses it', chip is None)
        check('N3', 'a dismiss opens nothing', 'kv.resyst.cl/novedades' not in log)
        check('N3', 'the dismissed version is stored', news_seen() == version, news_seen())
        open_kind('text')
        chip, _ = news_chip()
        check('N3', 'not again after a dismiss', chip is None)
    # N4: typing past it counts as seen
    install_update(base_apk, apk)
    k = fresh()
    chip, _ = news_chip()
    check('N4', 'the notice shows again after a third update', chip is not None)
    type_seq('a', k)
    time.sleep(0.6)
    chip, _ = news_chip()
    check('N4', 'typing in the field takes it off the strip', chip is None)
    open_kind('text')
    chip, _ = news_chip()
    check('N4', 'and it stays gone on the next field', chip is None)
    check('N4', 'the version is stored', news_seen() == version, news_seen())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--before-apk')
    ap.add_argument('--news-base-apk', help='the 0.9.0 debug build the Novedades section updates from (section N)')
    ap.add_argument('--only', default='A,B,C,D,E,N,L')
    a = ap.parse_args()
    if a.serial in FORBIDDEN:
        sys.exit(f'refusing to run on {a.serial}')
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    r13.OUT = OUT
    only = set(a.only.split(','))
    previous = sh('settings get secure default_input_method').strip()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk}) previous IME={previous}')
    latency = None
    try:
        adb('install', '-r', '-t', '-d', a.apk)
        sh(f'pm clear {PKG}', check=False)  # debug package only
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        fresh()
        time.sleep(3.0)
        version = sh(f'dumpsys package {PKG} | grep -m1 versionName').strip()
        check('setup', 'the debug build under test is 0.10.0', '0.10.0' in version, version)
        for sec, fn in (('A', section_abc), ('D', section_d), ('E', section_e)):
            if sec in only or (sec == 'A' and only & {'B', 'C'}):
                try:
                    fn()
                except Exception as ex:  # one broken section must not hide the others
                    check(sec, 'section ran', False, repr(ex))
        if 'N' in only:
            try:
                section_n(a.news_base_apk, a.apk)
            except Exception as ex:
                check('N', 'section ran', False, repr(ex))
        if 'L' in only:
            latency = section_l(a.apk, a.before_apk)
    finally:
        sh(f'ime set {previous}', check=False)
        restored = sh('settings get secure default_input_method').strip()
        check('teardown', 'default input method restored', restored == previous, restored)
        res = {'device': model, 'sdk': sdk, 'rows': rows, 'latency': latency,
               'passed': sum(r['ok'] for r in rows), 'failed': sum(not r['ok'] for r in rows)}
        json.dump(res, open(os.path.join(OUT, 'r13c-e2e.json'), 'w'), ensure_ascii=False, indent=1)
        print(f"✦ {res['passed']} passed, {res['failed']} failed → build/e2e-r13c/r13c-e2e.json")
    sys.exit(1 if any(not r['ok'] for r in rows) else 0)


if __name__ == '__main__':
    main()
