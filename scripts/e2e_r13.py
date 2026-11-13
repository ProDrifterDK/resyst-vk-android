#!/usr/bin/env python3
"""✦ Resyst VK — r13a E2E (0.9.0, the deterministic correction core) on a real device / emulator.

Types through the DRAWN keys (accessibility tree, like scripts/e2e.py) into the debug E2E host:
  A  bastion + Espacio stays "bastion" (VK 0.8.1 wrote bastón) and the bar offers "bastión" first
  B  caido + Espacio → "caído" (a single valid accent variant, R2)
  C  late rejection (R4): "calna" is corrected to "calma"; later the user deletes the corrected word
     and types "calna" again → kept; files/personal/rejections.json holds calna → calma and
     words.json keep-marks calna; a NEW field keeps "calna" too
  D  learned capitalization (R5): "GrapheneOS" written twice mid-sentence, then "grapheneos" + Espacio
     → "GrapheneOS"
  E  latency: the same sentence typed on --before-apk (r13a phase A + the same timing log) and on
     --apk; the IME's own "keytime" lines (debuggable builds: per key, handler time, never text)
     give median / p90 per kind (key, key space, bar)

Writes build/e2e-r13/r13-e2e.json + PNGs + logcat excerpts. Debug package only (com.resyst.vk.debug);
the default input method is restored at the end.
Usage: scripts/e2e_r13.py --serial SERIAL [--apk PATH] [--before-apk PATH] [--only A,B,C,D,E]
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
from e2e import adb, sh, dump, keys, center, PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r13')
FORBIDDEN = {'PHONE_A', 'PHONE_B'}  # Alan's own phones: never touched
rows = []


def check(section, name, ok, detail: object = ''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:240] if detail else ''), flush=True)


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def km():
    return keys(dump())


def field():
    return e2e.field_text(dump()) or ''


def bar(k=None):
    k = k if k is not None else km()
    return [d[len('Sugerencia: '):] for d in k if d.startswith('Sugerencia: ')]


def find(k, prefix):
    n = k.get(prefix)
    return n if n is not None else next((v for d, v in k.items() if d.startswith(prefix)), None)


def press(prefix, wait=0.3, k=None):
    n = find(k if k is not None else km(), prefix)
    if n is None:
        raise RuntimeError(f'no key {prefix!r}')
    e2e.tap(n)
    time.sleep(wait)


def type_text(text, k=None):
    """Taps every character on the drawn keys; capitals through the shift key, spaces on Espacio."""
    k = k if k is not None else km()
    shift = find(k, 'Mayúsculas')
    space = find(k, 'Espacio')
    for ch in text:
        if ch == ' ':
            e2e.tap(space)
            continue
        n = k.get(ch.lower())
        if n is None:
            n = k.get(ch.upper())
        if n is None:
            raise RuntimeError(f'key {ch!r} not found')
        if ch.isupper():
            e2e.tap(shift)
        e2e.tap(n)
    time.sleep(0.4)
    return k


def backspace(n, k=None):
    k = k if k is not None else km()
    b = find(k, 'Borrar')
    for _ in range(n):
        e2e.tap(b)
    time.sleep(0.4)


def fresh_field():
    e2e.open_host('text')
    time.sleep(0.4)
    if bl.space_label() != 'es':
        bl.set_lang('es')
    return km()


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


# ── A / B ────────────────────────────────────────────────────────────────
def section_a():
    k = fresh_field()
    type_text('el servidor del bastion', k)
    b = bar()
    shot('r13-A1-bastion-bar.png')
    check('A', 'the bar offers bastión first while typing bastion', b[:1] == ['bastión'], b)
    press('Espacio', 0.6, k)
    f = field()
    check('A', 'bastion + Espacio stays bastion (not bastón)', f.lower().endswith('bastion ') and 'bastón' not in f, repr(f))
    shot('r13-A2-bastion-space.png')


def section_b():
    k = fresh_field()
    type_text('se ha caido ', k)
    time.sleep(0.4)
    f = field()
    check('B', 'caido + Espacio → caído', f.lower().endswith('caído '), repr(f))
    shot('r13-B-caido.png')


# ── C: late rejection ───────────────────────────────────────────────────
def section_c():
    k = fresh_field()
    type_text('el calna ', k)
    f = field()
    check('C', 'calna is autocorrected to calma first', f.lower() == 'el calma ', repr(f))
    type_text('hoy ', k)
    f = field()
    check('C', 'the user goes on writing', f.lower() == 'el calma hoy ', repr(f))
    backspace(len('calma hoy '), k)
    f = field()
    check('C', 'later, the corrected word is deleted', f.lower() == 'el ', repr(f))
    type_text('calna ', k)
    f = field()
    shot('r13-C1-late-rejection.png')
    check('C', 'retyped calna + Espacio stays calna (LR1)', f.lower() == 'el calna ', repr(f))
    time.sleep(2.5)  # PersonalStore saves 1.5 s after the last change
    k2 = fresh_field()  # a NEW field (the previous one is flushed on exit)
    time.sleep(1.0)
    type_text('mi calna ', k2)
    f = field()
    shot('r13-C2-new-field.png')
    check('C', 'a NEW field keeps calna too (LR2)', f.lower() == 'mi calna ', repr(f))
    time.sleep(2.5)
    rej = run_as('cat files/personal/rejections.json')
    words = run_as('cat files/personal/words.json')
    check('C', 'rejections.json stores calna → calma (LR5)', '["calna","calma"]' in rej, rej[:200])
    kept = re.search(r'\["calna","calna",\d+,\d+,1', words)
    check('C', 'words.json keep-marks calna (K1)', kept is not None, kept.group(0) if kept else words[:200])


# ── D: learned capitalization ────────────────────────────────────────────
def section_d():
    k = fresh_field()
    type_text('uso GrapheneOS y tambien GrapheneOS con grapheneos ', k)
    time.sleep(0.4)
    f = field()
    shot('r13-D-casing.png')
    check('D', 'grapheneos commits the learned GrapheneOS (R5)', f.endswith('con GrapheneOS '), repr(f))
    check('D', 'the two written uses stay as written', f.count('GrapheneOS') == 3, repr(f))


# ── E: latency before / after ────────────────────────────────────────────
SENTENCE = 'hoy quiero probar el teclado en serio y ver si corrige calna listl y busvamos sin romper cachai ni fome '
WARMUP = 'ayer en la tarde estuvimos revisando el pixel con calma '


def keytimes():
    log = adb('logcat', '-d', '-v', 'brief', '-s', 'ResystVK:I')
    out = {'key': [], 'key space': [], 'bar': []}
    for kind, us in re.findall(r'keytime: (key space|key|bar) us=(\d+)', log):
        out[kind].append(int(us))
    return out


def summary(v):
    if not v:
        return None
    s = sorted(v)
    return {'n': len(s), 'median_us': int(statistics.median(s)), 'p90_us': s[int(len(s) * 0.9) - 1 if len(s) >= 10 else -1], 'max_us': s[-1]}


def measure(apk, label, downgrade):
    adb('install', '-r', '-t', *(['-d'] if downgrade else []), apk)
    sh(f'am force-stop {PKG}')
    time.sleep(0.6)
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    k = fresh_field()
    time.sleep(3.0)  # lexicons + valid sets load on the io thread
    type_text(WARMUP, k)
    k = fresh_field()
    sh('logcat -c')
    type_text(SENTENCE, k)
    time.sleep(1.0)
    shot(f'r13-E-{label}.png')
    t = keytimes()
    res = {kind: summary(v) for kind, v in t.items()}
    res['field'] = field()
    open(os.path.join(OUT, f'keytime-{label}.txt'), 'w').write(json.dumps(t))
    return res


def section_e(apk, before_apk):
    if not before_apk:
        check('E', 'before APK given (--before-apk)', False, 'skipped')
        return None
    before = measure(before_apk, 'before', downgrade=True)
    after = measure(apk, 'after', downgrade=False)
    check('E', 'IME timing present on both builds', before['key'] and after['key'] and before['bar'] and after['bar'], {'before': before, 'after': after})
    for kind in ('key', 'key space', 'bar'):
        b, a = before.get(kind), after.get(kind)
        if b and a:
            check('E', f'{kind}: median before {b["median_us"]} us → after {a["median_us"]} us (p90 {b["p90_us"]} → {a["p90_us"]})',
                  a['median_us'] <= max(b['median_us'] * 1.5, b['median_us'] + 2000), {'before': b, 'after': a})
    return {'before': before, 'after': after, 'sentence': SENTENCE}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--before-apk')
    ap.add_argument('--only', default='A,B,C,D,E')
    a = ap.parse_args()
    if a.serial in FORBIDDEN:
        sys.exit(f'refusing to run on {a.serial}')
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    only = set(a.only.split(','))
    previous = sh('settings get secure default_input_method').strip()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk}) serial={a.serial} previous IME={previous}')
    latency = None
    try:
        adb('install', '-r', '-t', '-d', a.apk)
        sh(f'pm clear {PKG}', check=False)  # debug package only: a clean model for C / D
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        fresh_field()
        time.sleep(3.0)
        log = adb('logcat', '-d', '-v', 'brief', '-s', 'ResystVK:I')
        loads = re.findall(r'lexicon: valid-(\w+) words=(\d+) mapped=(\w+) ms=(\d+)', log)
        check('setup', 'valid-word sets mapped off the main thread', len(loads) >= 1, loads)
        for sec, fn in (('A', section_a), ('B', section_b), ('C', section_c), ('D', section_d)):
            if sec in only:
                try:
                    fn()
                except Exception as ex:  # one broken section must not hide the others
                    check(sec, 'section ran', False, repr(ex))
        if 'E' in only:
            latency = section_e(a.apk, a.before_apk)
    finally:
        sh(f'ime set {previous}', check=False)
        restored = sh('settings get secure default_input_method').strip()
        check('teardown', 'default input method restored', restored == previous, f'{restored}')
        res = {'device': model, 'sdk': sdk, 'serial': a.serial, 'rows': rows, 'latency': latency,
               'passed': sum(r['ok'] for r in rows), 'failed': sum(not r['ok'] for r in rows)}
        json.dump(res, open(os.path.join(OUT, 'r13-e2e.json'), 'w'), ensure_ascii=False, indent=1)
        print(f"✦ {res['passed']} passed, {res['failed']} failed → {OUT}/r13-e2e.json")
    sys.exit(1 if any(not r['ok'] for r in rows) else 0)


if __name__ == '__main__':
    main()
