#!/usr/bin/env python3
"""✦ Resyst VK — r13i E2E (0.13.0: the personal word-pair memory inside the context offers).

Types through the DRAWN keys (accessibility tree, like scripts/e2e_r13f.py) into the debug E2E host:
  S  the seed: the memory is active only after 2,000 learned tokens (cold start, PM6), more than a device run can type,
     so build/r13i/seed-pairs.bin (the r13f stand-in history, PUBLIC text, learned through PairLearner on the JVM with
     today's clock: `VK_R13I_SEED=1 gradlew testDebugUnitTest --tests '*R13iPrivateEvalTest.seedForDevice'`) is put in
     files/personal/pairs.bin of the debug package and the IME process restarted: the log says it loaded and is active
  C  control: "vamos a la peca temprano " gets no chip (the generic model does not prefer "pega" there: Δ < 0, and the
     seeded memory never saw the pair)
  I  the same teaching in an incognito field ("vamos a la pega temprano" + Listo) and in an opted-out ("Sin memoria")
     composer writes nothing: pairs.bin byte-identical; still no chip
  T  teaching in a normal field: "vamos a la pega temprano" + Listo (VK's Enter sends) → pairs.bin rewritten; then
     "vamos a la peca temprano " → the chip "pega" (TalkBack "Cambiar peca por pega"); the opted-out composer READS it
     (its chip too); a tap applies it
  K  Settings › Lo que sé de ti: the section "Pares de palabras que usas a menudo" (size, "en uso"), the switch, and
     "Borrar todos los pares" → confirm → pairs.bin gone, and "vamos a la peca temprano " has no chip again
  H  heap: the debug build's own GC'd heap log around the load ("pairs: loaded … heapKb=") and dumpsys meminfo of a
     process with the seeded memory vs one with none; decision times with the memory and without (debug build, and on
     --timing-apk: a NON-debuggable build of this commit for the debug package, AOT-compiled, as r13f's section O)

Writes build/e2e-r13i/r13i-e2e.json + PNGs. Debug package only (com.resyst.vk.debug); the default input method is
restored at the end. The r13h rows are scripts/e2e_r13h.py (run it after this one, --out build/e2e-r13i/r13h).
Usage: scripts/e2e_r13i.py --serial SERIAL [--apk PATH] [--seed PATH] [--timing-apk PATH] [--out DIR] [--only S,C,I,T,K,H]

Environment:
  VK_E2E_FORBIDDEN_SERIALS  comma-separated device serials this script must refuse to touch (your own phones);
                            default empty. A --serial in the list exits before any adb call.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r13 as r13  # noqa: E402
import e2e_r13c as r13c  # noqa: E402
import e2e_r13d as r13d  # noqa: E402
import e2e_r13f as r13f  # noqa: E402
from e2e import PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r13i')
FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
PAIRS = 'files/personal/pairs.bin'
TEACH = 'vamos a la pega temprano'
PROBE = 'vamos a la peca temprano '
rows = []
metrics = {}


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


def pairs_sha():
    """sha256 of pairs.bin read through run-as (None = no file)."""
    p = subprocess.run(e2e.ADB + ['exec-out', 'run-as', PKG, 'cat', PAIRS], capture_output=True)
    if p.returncode != 0 or b'No such file' in p.stdout[:200] or not p.stdout:
        return None
    return hashlib.sha256(p.stdout).hexdigest()


def pairs_exists():
    return 'pairs.bin' in run_as('ls files/personal/')


def logcat():
    return sh('logcat -d -s ResystVK:I', check=False)


def restart_ime():
    """A new IME process (PersonalStore reads files/personal/ again)."""
    sh(f'am force-stop {PKG}', check=False)
    time.sleep(1.0)
    e2e.ensure_ime()
    r13d.fresh('text')
    time.sleep(3.0)


def probe(name, shot_name):
    """'vamos a la peca temprano ' in a fresh normal field: the chips on the bar."""
    r13d.fresh('text')
    r13c.type_seq(PROBE)
    chips, k = r13f.wait_chip(timeout=4.0)
    shot(shot_name)
    return chips, k


def send(kind, text):
    """[text] typed in a fresh [kind] field and sent with VK's Enter (the host's action: Listo)."""
    r13d.fresh(kind)
    k = r13c.type_seq(text)
    enter = r13.find(r13.km(), 'Listo') or r13.find(r13.km(), 'Done')
    if enter is not None:
        e2e.tap(enter)
    time.sleep(0.8)
    return field()


def find_node(pred, swipes=10):
    for i in range(swipes):
        for n in e2e.dump().iter('node'):
            if n.get('package') == PKG and pred(n):
                return n
        sh('input swipe 540 1700 540 900 350')
        time.sleep(0.6)
    return None


def text_of(n):
    return (n.get('text') or '') + '|' + (n.get('content-desc') or '')


def offertimes_memory(log):
    return [(int(us), ok == 'true', mem == 'true') for us, ok, mem in
            re.findall(r'offertime: decide us=(\d+) offer=(true|false) .*?memory=(true|false)', log)]


def timed(tag, want_memory):
    us_all, mem_flags = [], []
    for text in r13f.TIMING_TEXTS:
        r13d.fresh('text')
        sh('logcat -c', check=False)
        r13c.type_seq(text)
        time.sleep(1.5)
        for us, _, mem in offertimes_memory(logcat()):
            us_all.append(us)
            mem_flags.append(mem)
    us = sorted(us_all)
    res = {'n': len(us), 'memory_flags': sorted(set(mem_flags)), 'all_us': us_all}
    if us:
        res.update({'mean_ms': round(sum(us) / len(us) / 1000, 3), 'p50_ms': us[len(us) // 2] / 1000,
                    'p90_ms': us[len(us) * 9 // 10] / 1000, 'max_ms': us[-1] / 1000})
    res['memory_as_expected'] = bool(mem_flags) and all(m == want_memory for m in mem_flags)
    metrics['decision_' + tag] = res
    return {k: v for k, v in res.items() if k != 'all_us'}


def gc_meminfo(tag):
    out = r13f.gc_and_meminfo(tag)
    return out


# ── sections ─────────────────────────────────────────────────────────────
def section_s(seed):
    check('S', 'the seed exists (build/r13i/seed-pairs.bin, stand-in public text learned through PairLearner)', os.path.exists(seed), seed)
    meta = os.path.join(os.path.dirname(seed), 'seed-pairs.json')
    if os.path.exists(meta):
        metrics['seed'] = json.load(open(meta))
    before = gc_meminfo('no-memory')
    metrics['meminfo_no_memory'] = before
    run_as('mkdir -p files/personal')
    with open(seed, 'rb') as f:
        p = subprocess.run(e2e.ADB + ['exec-in', 'run-as', PKG, 'sh', '-c', f'cat > {PAIRS}'], stdin=f, capture_output=True)
    sha = pairs_sha()
    want = hashlib.sha256(open(seed, 'rb').read()).hexdigest()
    check('S', 'the seed is files/personal/pairs.bin of the debug package', p.returncode == 0 and sha == want, f'rc {p.returncode} sha {sha} want {want}')
    sh('logcat -c', check=False)
    restart_ime()
    m = re.search(r'pairs: loaded entries=(\d+) words=(\d+) tokens=(\d+) active=(true|false) ms=(\d+)(?: heapKb=(-?\d+))?', logcat())
    metrics['load_line'] = m.group(0) if m else None
    check('S', 'a new IME process loads it: active (≥ 2,000 learned tokens), heap and load time logged',
          m is not None and m.group(4) == 'true', m.group(0) if m else 'no load line')
    after = gc_meminfo('seeded')
    metrics['meminfo_seeded'] = after
    if m and m.group(6) is not None:
        metrics['heap_delta_kb_in_process'] = int(m.group(6))


def section_c():
    chips, _ = probe('control', 'r13i-C1-control-no-chip.png')
    check('C', f'control: "{PROBE}" → no chip (the generic model does not prefer pega; the seeded memory never saw the pair)',
          chips == [], f'field {field()!r}, chips {chips}')


def section_i():
    sha0 = pairs_sha()
    f1 = send('incognito', TEACH)
    shot('r13i-I1-incognito-taught.png')
    r13d.fresh('social')  # an opted-out ("Sin memoria") composer: multi-line, the sentence moves on after its mark
    r13c.type_seq(TEACH + '. listo ')
    f2 = field()
    shot('r13i-I2-optedout-taught.png')
    r13d.fresh('text')  # leaving a field flushes what was learned
    time.sleep(2.5)
    sha1 = pairs_sha()
    check('I', 'the same teaching in an incognito field and in an opted-out composer writes nothing: pairs.bin byte-identical',
          sha0 is not None and sha0 == sha1, f'incognito field {f1!r}, opted-out field {f2!r}, sha {sha0} → {sha1}')
    chips, _ = probe('after-incognito', 'r13i-I3-still-no-chip.png')
    check('I', f'after it, "{PROBE}" still gets no chip', chips == [], f'chips {chips}')


def section_t():
    sha0 = pairs_sha()
    sent = send('text', TEACH)
    shot('r13i-T1-taught.png')
    r13d.fresh('text')
    time.sleep(2.5)  # PersonalStore saves 1.5 s after a learn; leaving the field flushes it
    sha1 = pairs_sha()
    check('T', f'teaching in a normal field: "{TEACH}" + Listo (VK\'s Enter sends) → pairs.bin rewritten',
          sha1 is not None and sha1 != sha0, f'field {sent!r}, sha {sha0} → {sha1}')
    chips, k = probe('taught', 'r13i-T2-chip-pega.png')
    check('T', f'"{PROBE}" → the chip "pega" (TalkBack "Cambiar peca por pega"): an offer only the memory makes',
          chips == [('peca', 'pega')], f'field {field()!r}, chips {chips}')
    if chips == [('peca', 'pega')]:
        e2e.tap(r13f.chip_node(k, chips[0]))
        time.sleep(0.9)
    tapped = field()
    shot('r13i-T3-tapped.png')
    check('T', 'a tap applies it: "Vamos a la pega temprano "', tapped == 'Vamos a la pega temprano ', repr(tapped))
    # the opted-out composer reads the memory (read yes, write no)
    r13d.fresh('social')
    r13c.type_seq(PROBE)
    chips2, _ = r13f.wait_chip(timeout=4.0)
    shot('r13i-T4-optedout-reads.png')
    check('T', 'an opted-out ("Sin memoria") composer reads it: the same chip', chips2 == [('peca', 'pega')], f'chips {chips2}')
    r13d.fresh('text')
    time.sleep(2.5)
    metrics['sha_after_teaching'] = pairs_sha()


def section_k():
    sh(f'am start -W -n {e2e.SETTINGS} --es com.resyst.vk.page datos')
    time.sleep(1.8)
    head = find_node(lambda n: (n.get('text') or '') in ('Pares de palabras que usas a menudo', 'Word pairs you use often'))
    summary = None
    if head is not None:
        for n in e2e.dump().iter('node'):
            t = n.get('text') or ''
            if re.search(r'\d+ (pares|pairs) · \d+ (palabras|words) · \d+ KB', t):
                summary = t
    shot('r13i-K1-section.png')
    check('K', 'Lo que sé de ti › "Pares de palabras que usas a menudo": its size and "en uso" (active)',
          head is not None and summary is not None and ('en uso' in summary or 'in use' in summary), f'summary {summary!r}')
    switch = find_node(lambda n: (n.get('content-desc') or '').startswith(('Aprender pares de palabras', 'Learn word pairs')), swipes=4)
    check('K', 'the switch "Aprender pares de palabras" is there (default on)', switch is not None,
          switch.get('content-desc') if switch is not None else None)
    clear = find_node(lambda n: (n.get('content-desc') or '').startswith(('Borrar todos los pares', 'Delete all pairs')), swipes=6)
    if clear is not None:
        e2e.tap(clear)
        time.sleep(1.0)
        shot('r13i-K2-confirm.png')
        ok = find_node(lambda n: (n.get('text') or '').lower() in ('borrar', 'delete') and n.get('class') == 'android.widget.Button', swipes=1)
        if ok is not None:
            e2e.tap(ok)
            time.sleep(1.5)
    shot('r13i-K3-cleared.png')
    gone = not pairs_exists()
    check('K', '"Borrar todos los pares" → confirm → pairs.bin is gone', clear is not None and gone, run_as('ls -la files/personal/'))
    sh('input keyevent 4', check=False)
    time.sleep(0.6)
    chips, _ = probe('cleared', 'r13i-K4-no-chip-after-clear.png')
    check('K', f'after clear all, "{PROBE}" has no chip again (memory empty, not active)', chips == [], f'chips {chips}')
    r13d.fresh('text')
    time.sleep(2.5)
    check('K', 'nothing is written back after the clear (no learn since)', not pairs_exists(), run_as('ls files/personal/'))


def section_h(apk, seed, timing_apk):
    # decision times on the debug build (interpreted, recorded only): memory empty now (after K), then seeded
    empty_dbg = timed('debug_no_memory', want_memory=False)
    with open(seed, 'rb') as f:
        subprocess.run(e2e.ADB + ['exec-in', 'run-as', PKG, 'sh', '-c', f'cat > {PAIRS}'], stdin=f, capture_output=True)
    restart_ime()
    seeded_dbg = timed('debug_memory', want_memory=True)
    print(f'NOTE [H] debug build (debuggable, interpreted): no memory {empty_dbg} / memory {seeded_dbg}')
    if not timing_apk:
        print('NOTE [H] decision time budget not checked: no --timing-apk')
        return
    # the non-debuggable build over the same data (seeded): memory active; then pm clear: no memory
    r13c.install('-r', '-t', '-d', timing_apk)
    e2e.ensure_ime()
    flags = sh(f'dumpsys package {PKG} | grep -m1 pkgFlags', check=False).strip()
    comp = sh(f'cmd package compile -m speed -f {PKG}', check=False).strip()
    restart_ime()
    timed('warmup_memory', want_memory=True)
    mem = timed('compiled_memory', want_memory=True)
    sh(f'pm clear {PKG}', check=False)
    e2e.ensure_ime()
    restart_ime()
    timed('warmup_no_memory', want_memory=False)
    none = timed('compiled_no_memory', want_memory=False)
    metrics['timing_build'] = {'apk': os.path.basename(timing_apk), 'pkgFlags': flags, 'compile': comp}
    r13c.install('-r', '-t', '-d', apk)  # the debug build under test again
    e2e.ensure_ime()
    ok = ('DEBUGGABLE' not in flags and mem.get('n', 0) > 10 and none.get('n', 0) > 10 and mem['memory_as_expected'] and none['memory_as_expected']
          and mem['mean_ms'] <= 2.0 and mem['p90_ms'] <= 2.0)
    check('H', 'decision time with the memory vs without (non-debuggable build of this commit, AOT-compiled): within the 2 ms budget; the difference is reported',
          ok, f'memory {mem}; no memory {none}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    ap.add_argument('--seed', default=os.path.join(e2e.ROOT, 'app', 'build', 'r13i', 'seed-pairs.bin'))
    ap.add_argument('--timing-apk', help='section H: a non-debuggable build of this commit for the debug package')
    ap.add_argument('--only', default='S,C,I,T,K,H')
    ap.add_argument('--out', help='output directory (default build/e2e-r13i)')
    ap.add_argument('--version', default='0.13.0', help='the versionName the debug build under test must report')
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
    m = re.search(r'main: ring buffer is (\d+) ([KM])iB', sh('logcat -g', check=False))
    log_size = f'{m.group(1)}{m.group(2)}' if m else None
    sh('logcat -G 2M', check=False)
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
        check('setup', 'a clean install has no pairs.bin', not pairs_exists(), run_as('ls files/personal/'))
        for sec, fn in (('S', lambda: section_s(a.seed)), ('C', section_c), ('I', section_i), ('T', section_t), ('K', section_k),
                        ('H', lambda: section_h(a.apk, a.seed, a.timing_apk))):
            if sec in only:
                try:
                    sh('input keyevent KEYCODE_WAKEUP', check=False)
                    fn()
                except Exception as ex:  # one broken section must not hide the others
                    check(sec, 'section ran', False, repr(ex))
    finally:
        sh(f'ime set {previous}', check=False)
        if log_size:
            sh(f'logcat -G {log_size}', check=False)
        restored = sh('settings get secure default_input_method').strip()
        check('teardown', 'default input method restored', restored == previous, restored)
        res = {'device': model, 'sdk': sdk, 'rows': rows, 'metrics': metrics,
               'passed': sum(r['ok'] for r in rows), 'failed': sum(not r['ok'] for r in rows)}
        json.dump(res, open(os.path.join(OUT, 'r13i-e2e.json'), 'w'), ensure_ascii=False, indent=1)
        print(f"✦ {res['passed']} passed, {res['failed']} failed → {os.path.join(OUT, 'r13i-e2e.json')}")
    sys.exit(1 if any(not r['ok'] for r in rows) else 0)


if __name__ == '__main__':
    main()
