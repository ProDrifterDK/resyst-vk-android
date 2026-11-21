#!/usr/bin/env python3
"""✦ Resyst VK — r14 E2E (0.14.0: the visual overhaul and deeper personalisation).

One script for both sides of the comparison: `--label before` runs on the 0.13.0 debug build and `--label after` on
0.14.0, and both write the same scene names under build/e2e-r14/<label>/, so scripts/e2e_r14_index.py (or --index)
can pair them. Every scene is driven through the DRAWN keys (accessibility tree), like the earlier E2E scripts.

Sections (--only, comma separated; default all but U):
  K  the keyboard in every palette, with the system in light and in dark mode (cmd uimode night no / yes)
  S  key states on the default tema: shift auto / off / once / locked, a held key with its preview, the long-press
     popup, the symbols layer, suggestions, a context offer ("casa"), "2 cambios", the ↶ after it
  P  every panel: quick, edit, emoji, GIF tab, clipboard (three clips), one-hand right and left
  F  large text: font_scale 1.3 (keyboard, quick panel, Settings)
  T  Settings: home, Apariencia, Teclas, Sonido y vibración, Lo que sé de ti (after some typing), the setup card
  R  short screen recordings of the motion (typing, long press, quick panel + tema switch, emoji panel)
  N  0.14.0 only: rows for what is new (follow the system's dark mode, the quick panel's temas, styles, the
     legibility of every palette x accent x tint, high-contrast text, reduced motion, TalkBack names)
  M  measurements: typing latency (the IME's own keytime lines), frame times (dumpsys gfxinfo) while typing, heap
     (dumpsys meminfo after a GC), APK size
  U  0.14.0 only: upgrade from 0.13.0 (--before-apk) keeps temas, accent and settings (needs --before-apk)

Writes build/e2e-r14/<label>/*.png|mp4 + r14-<label>.json (rows, metrics, scenes). Debug package only
(com.resyst.vk.debug); the system's night mode, font scale, animator scale and the default input method are restored
at the end.

Usage: scripts/e2e_r14.py --serial SERIAL --label before|after [--apk PATH] [--before-apk PATH] [--only K,S,...]

Environment:
  VK_E2E_FORBIDDEN_SERIALS  comma-separated device serials this script must refuse to touch (your own phones);
                            default empty. A --serial in the list exits before any adb call.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r10_bilingual as bl  # noqa: E402
import e2e_r13 as r13  # noqa: E402
import e2e_r13c as r13c  # noqa: E402
import e2e_r13f as r13f  # noqa: E402
from e2e import PKG, IME, SETTINGS  # noqa: E402

FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
PROFILES = 'shared_prefs/resyst_vk_profiles.xml'
BASE = os.path.join(e2e.ROOT, 'build', 'e2e-r14')
OUT = BASE
LABEL = 'after'
rows = []
metrics = {}
scenes = []

CLASSIC = ['lab', 'slate', 'arcade', 'paper', 'hc', 'sepia', 'amoled', 'eink']
# 0.14.0 palettes (core/Theme.kt Themes.MORE); "you" / "youlight" follow the wallpaper on Android 12+
MORE = ['forest', 'sage', 'ocean', 'mist', 'plum', 'lavender', 'graphite', 'cloud', 'you', 'youlight']


# ── plumbing ─────────────────────────────────────────────────────────────
def sh(cmd, check=True) -> str:
    return str(e2e.sh(cmd, check=check))


def check(section, name, ok, detail: object = ''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:240] if detail else ''), flush=True)


def shot(name, note=''):
    path = os.path.join(OUT, name + '.png')
    open(path, 'wb').write(e2e.adb('exec-out', 'screencap', '-p', binary=True))
    scenes.append({'scene': name, 'file': os.path.basename(path), 'note': note})
    print(f'  shot {name}', flush=True)


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


def km():
    for _ in range(4):
        k = r13.km()
        if k:
            return k
        time.sleep(0.5)
    return {}


def find(prefix, k=None):
    return r13.find(k if k is not None else km(), prefix)


def key(ch, k=None):
    """A letter key by its exact TalkBack name, lower or upper case (never a prefix: 'e' must not hit 'Espacio')."""
    for _ in range(3):
        n = bl.node(k if k is not None else km(), ch)
        if n is not None:
            return n
        k = None
        time.sleep(0.5)
    raise RuntimeError(f'no key {ch!r}')


def press(prefix, wait=0.5, k=None):
    n = find(prefix, k)
    if n is None:
        raise RuntimeError(f'no node {prefix!r}')
    e2e.tap(n)
    time.sleep(wait)


def xml_escape(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


def write_prefs(values: dict):
    """The profile store as SharedPreferences XML, written while our package is stopped (the IME process caches it)."""
    body = ''.join(f'    <string name="{xml_escape(k)}">{xml_escape(str(v))}</string>\n' for k, v in values.items())
    xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + body + '</map>\n'
    sh(f'am force-stop {PKG}', check=False)
    time.sleep(0.5)
    p = subprocess.run(e2e.ADB + ['shell', f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cat > {PROFILES}'"],
                       input=xml.encode('utf-8'), capture_output=True, timeout=30)
    if p.returncode != 0:
        raise RuntimeError('prefs write failed: ' + p.stderr.decode(errors='replace'))
    e2e.ensure_ime()


def store(theme='lab', **extra):
    """A v2 store: the seed temas with [theme] on the active one, no network (update.auto false), [extra] keys."""
    v = {'v': '2', 'active': 'resyst', 'order': 'resyst,arcade,pizarra', 'mode': 'none', 'update.auto': 'false',
         'clip.history': 'true', 'clip.purge': 'false', 't.resyst.theme': theme}
    v.update({k.replace('__', '.'): val for k, val in extra.items()})
    return v


def night(on: bool):
    sh(f"cmd uimode night {'yes' if on else 'no'}", check=False)
    time.sleep(1.2)


def host(kind='text'):
    """A fresh E2E host field with the keyboard up, Spanish layout."""
    e2e.open_host(kind)
    time.sleep(0.6)
    k = km()
    if k and bl.space_label() != 'es':
        bl.set_lang('es')
        k = km()
    return k


def type_seq(text, k=None):
    return r13c.type_seq(text, k)


def hold_and_shoot(node, name, hold_ms=1600, at=0.55, note=''):
    """Holds [node] with `input swipe x y x y` in the background and takes the screenshot [at] s into the hold."""
    x, y = e2e.center(node)
    p = subprocess.Popen(e2e.ADB + ['shell', f'input swipe {x} {y} {x} {y} {hold_ms}'])
    time.sleep(at)
    shot(name, note)
    p.wait(timeout=10)
    time.sleep(0.3)


def settings_open(page=None):
    extra = f' --es com.resyst.vk.page {page}' if page else ''
    sh(f'am start -W -f 0x10008000 -n {SETTINGS}{extra}', check=False)
    time.sleep(1.6)


def scroll_down(frac=0.55):
    w, h = screen()
    sh(f'input swipe {w // 2} {int(h * 0.78)} {w // 2} {int(h * (0.78 - frac))} 450', check=False)
    time.sleep(0.9)


_screen = None


def screen():
    global _screen
    if _screen is None:
        m = re.search(r'(\d+)x(\d+)', sh('wm size'))
        _screen = (int(m.group(1)), int(m.group(2)))
    return _screen


def texts():
    return [n.get('text', '') for n in e2e.dump().iter('node')]


def by_tag_or_text(needle):
    for n in e2e.dump().iter('node'):
        if n.get('package') == PKG and needle in (n.get('text', ''), n.get('content-desc', ''), n.get('resource-id', '')):
            return n
    return None


def record(name, seconds, actions, note=''):
    """screenrecord on the phone while [actions] runs; pulls build/e2e-r14/<label>/<name>.mp4."""
    remote = f'/sdcard/r14-{name}.mp4'
    p = subprocess.Popen(e2e.ADB + ['shell', f'screenrecord --time-limit {seconds} --bit-rate 6000000 {remote}'])
    time.sleep(1.0)
    try:
        actions()
    finally:
        p.wait(timeout=seconds + 20)
        time.sleep(1.0)
    e2e.adb('pull', remote, os.path.join(OUT, name + '.mp4'), check=False)
    sh(f'rm -f {remote}', check=False)
    scenes.append({'scene': name, 'file': name + '.mp4', 'note': note})
    print(f'  video {name}', flush=True)


# ── K: every palette, light and dark system mode ─────────────────────────
def palettes():
    return CLASSIC + (MORE if LABEL == 'after' else [])


def section_k():
    for mode in ('light', 'dark'):
        night(mode == 'dark')
        for t in palettes():
            write_prefs(store(t))
            k = host('text')
            type_seq('hol', k)
            time.sleep(0.6)
            shot(f'K-{mode}-{t}', f'palette {t}, system {mode}, "hol" typed (suggestions on the bar)')
    night(False)


# ── S: key states ────────────────────────────────────────────────────────
def section_s():
    for mode in ('light', 'dark'):
        night(mode == 'dark')
        theme = 'lab' if mode == 'dark' else 'paper'
        write_prefs(store(theme))
        k = host('text')
        shot(f'S-{mode}-shift-auto', 'empty field: shift on by itself (auto-capital)')
        type_seq('h', k)
        shot(f'S-{mode}-shift-off', 'after a letter: lower case')
        hold_and_shoot(key('g'), f'S-{mode}-pressed', hold_ms=1400, at=0.25, note='g held: pressed key + preview bubble')
        press('Borrar')
        type_seq('h')
        hold_and_shoot(key('e'), f'S-{mode}-longpress', hold_ms=2200, at=1.2, note='e held: the long-press variants')
        k = host('text')
        type_seq('h', k)
        press('Mayúsculas', wait=0.2)
        shot(f'S-{mode}-shift-once', 'shift tapped once')
        n = find('Mayúsculas')
        x, y = e2e.center(n)
        sh(f'input tap {x} {y}; input tap {x} {y}', check=False)
        time.sleep(0.6)
        shot(f'S-{mode}-caps-lock', 'shift tapped twice quickly: caps lock')
        press('Mayúsculas', wait=0.3)
        press('Símbolos', wait=0.6)
        shot(f'S-{mode}-symbols', 'the symbols layer')
        press('Letras', wait=0.6)
        k = host('text')
        type_seq('cancio', k)
        time.sleep(0.8)
        shot(f'S-{mode}-suggestions', '"cancio": completions on the bar')
        k = host('text')
        type_seq('paso por tu caza y esta bien ', k)
        r13f.wait_chip(timeout=5.0)
        time.sleep(0.8)
        shot(f'S-{mode}-offer', 'context offer chip "casa"')
        k = host('text')
        type_seq('paso por tu caza y esta muy bien ', k)
        t0 = time.time()
        multi = None
        while time.time() - t0 < 6:
            kk = km()
            multi = next((d for d in kk if re.match(r'^(Cambiar|Replace) .+ (y|and) ', d)), None)
            if multi:
                break
            time.sleep(0.4)
        time.sleep(0.6)
        shot(f'S-{mode}-two-changes', '"2 cambios": two context offers in one chip')
        if multi:
            e2e.tap(km()[multi])
            time.sleep(1.0)
            shot(f'S-{mode}-undo', 'after the tap: the ↶ chip')
        check('S', f'{mode}: "2 cambios" chip present', bool(multi), multi)
    night(False)


# ── P: panels ────────────────────────────────────────────────────────────
def gear():
    return find('Ajustes de Resyst VK')


def tile(prefix):
    return find(prefix)


def section_p():
    for mode in ('light', 'dark'):
        night(mode == 'dark')
        theme = 'lab' if mode == 'dark' else 'paper'
        write_prefs(store(theme))
        k = host('text')
        # three clips for the clipboard panel (the host puts them on the system clipboard while focused)
        for txt in ('nos vemos a las 8 en la plaza', 'https://kv.resyst.cl/novedades/', 'dirección: Av. Siempre Viva 742'):
            sh(f"am broadcast -a com.resyst.vk.debug.SET_CLIP --es text '{txt}'", check=False)
            time.sleep(0.8)
        type_seq('hola ', k)
        time.sleep(0.5)
        shot(f'P-{mode}-paste-chip', 'the bar after copying: «Pegar» chip')
        e2e.tap(gear())
        time.sleep(0.8)
        shot(f'P-{mode}-quick', 'quick panel (⚙)')
        n = tile('Edición')
        if n is not None:
            e2e.tap(n)
            time.sleep(0.7)
            shot(f'P-{mode}-edit', 'edit panel')
            press('Volver al teclado')
        e2e.tap(gear())
        time.sleep(0.7)
        n = tile('Portapapeles')
        if n is not None:
            e2e.tap(n)
            time.sleep(0.8)
            shot(f'P-{mode}-clipboard', 'clipboard history panel')
            n = find('Cerrar portapapeles')
            if n is not None:
                e2e.tap(n)
                time.sleep(0.5)
        k = host('text')
        press('Emojis', wait=1.2)
        shot(f'P-{mode}-emoji', 'emoji panel')
        g = find('GIF (KLIPY)')
        if g is not None:
            e2e.tap(g)
            time.sleep(0.9)
            shot(f'P-{mode}-gif', 'GIF tab (the opt-in disclosure: no request is made)')
        press('Volver al teclado', wait=0.6)
        # one-hand: right, then left, then off
        e2e.tap(gear())
        time.sleep(0.6)
        press('Una mano', wait=0.8)
        press('Volver al teclado', wait=0.6) if find('Volver al teclado') is not None else None
        shot(f'P-{mode}-onehand-right', 'one-hand mode, right')
        e2e.tap(gear())
        time.sleep(0.6)
        press('Una mano', wait=0.8)
        press('Volver al teclado', wait=0.6) if find('Volver al teclado') is not None else None
        shot(f'P-{mode}-onehand-left', 'one-hand mode, left')
        e2e.tap(gear())
        time.sleep(0.6)
        press('Una mano', wait=0.8)
        press('Volver al teclado', wait=0.6) if find('Volver al teclado') is not None else None
    night(False)


# ── F: large font scale ──────────────────────────────────────────────────
def section_f(font_before):
    try:
        sh('settings put system font_scale 1.3')
        time.sleep(1.0)
        for mode in ('light', 'dark'):
            night(mode == 'dark')
            theme = 'lab' if mode == 'dark' else 'paper'
            write_prefs(store(theme))
            k = host('text')
            type_seq('hol', k)
            time.sleep(0.6)
            shot(f'F-{mode}-keyboard', 'font_scale 1.3: keyboard')
            e2e.tap(gear())
            time.sleep(0.8)
            shot(f'F-{mode}-quick', 'font_scale 1.3: quick panel')
            settings_open()
            shot(f'F-{mode}-settings', 'font_scale 1.3: Settings home')
            settings_open('apariencia')
            shot(f'F-{mode}-apariencia', 'font_scale 1.3: Apariencia')
    finally:
        sh(f'settings put system font_scale {font_before}', check=False)
        night(False)


# ── T: Settings ──────────────────────────────────────────────────────────
def section_t():
    for mode in ('light', 'dark'):
        night(mode == 'dark')
        theme = 'lab' if mode == 'dark' else 'paper'
        write_prefs(store(theme))
        # something to know: a few sentences typed (learned words, a pair-free history)
        k = host('text')
        type_seq('hola que tal como estas hoy. ', k)
        k = host('multiline')
        type_seq('mañana vamos al cine con la familia. ', k)
        settings_open()
        shot(f'T-{mode}-home-1', 'Settings home, top')
        scroll_down()
        shot(f'T-{mode}-home-2', 'Settings home, second screen')
        scroll_down()
        shot(f'T-{mode}-home-3', 'Settings home, third screen')
        for page in ('apariencia', 'teclas', 'respuesta', 'datos', 'modos'):
            settings_open(page)
            shot(f'T-{mode}-{page}-1', f'page {page}, top')
            for i in range(2, 6 if page == 'apariencia' else 3):
                scroll_down()
                shot(f'T-{mode}-{page}-{i}', f'page {page}, screen {i}')
        # the setup card: our keyboard enabled but not the current one (Gboard selected for the shot)
        gboard = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'
        if gboard in sh('ime list -s', check=False):
            sh(f'ime set {gboard}', check=False)
            settings_open()
            shot(f'T-{mode}-setup', 'setup card (another keyboard is the current one)')
            e2e.ensure_ime()
    night(False)


# ── R: recordings ────────────────────────────────────────────────────────
def section_r():
    night(True)
    write_prefs(store('lab'))

    def typing():
        k = host('text')
        type_seq('hola, nos vemos ', k)

    k = host('text')
    record('R-typing', 9, typing, 'typing "hola, nos vemos " (press feedback, previews)')

    def longpress():
        n = key('e')
        x, y = e2e.center(n)
        sh(f'input swipe {x} {y} {x + 60} {y - 10} 1800', check=False)
        time.sleep(0.6)

    host('text')
    record('R-longpress', 5, longpress, 'long press e and slide across the variants')

    def quick():
        e2e.tap(gear())
        time.sleep(1.2)
        n = find('Tema')
        if n is not None:
            e2e.tap(n)
            time.sleep(1.2)
            e2e.tap(n)
            time.sleep(1.2)
        press('Volver al teclado', wait=0.8)

    host('text')
    record('R-quick', 9, quick, 'quick panel, Tema twice (tema switch), back')

    def emoji():
        press('Emojis', wait=1.5)
        press('Volver al teclado', wait=1.0)

    host('text')
    record('R-emoji', 6, emoji, 'emoji panel in and out')
    night(False)


# ── M: measurements ──────────────────────────────────────────────────────
WARMUP = 'ayer en la tarde estuvimos revisando el pixel con calma, '
TEXT = r13c.TEXT


def gfx_stats(text):
    out = {}
    for key, rx in (('frames', r'Total frames rendered: (\d+)'), ('janky', r'Janky frames: (\d+)'),
                    ('p50', r'50th percentile: (\d+)ms'), ('p90', r'90th percentile: (\d+)ms'),
                    ('p95', r'95th percentile: (\d+)ms'), ('p99', r'99th percentile: (\d+)ms')):
        m = re.search(rx, text)
        out[key] = int(m.group(1)) if m else None
    m = re.search(r'Janky frames: \d+ \(([\d.]+)%\)', text)
    out['janky_pct'] = float(m.group(1)) if m else None
    return out


def heap():
    pid = sh(f'pidof {PKG}', check=False).strip().split()
    if not pid:
        return None
    run_as(f'kill -10 {pid[0]}')
    time.sleep(2.0)
    txt = sh(f'dumpsys -t 60 meminfo {PKG}', check=False)
    out = {}
    for key, rx in (('java_heap_kb', r'Java Heap:\s+(\d+)'), ('native_heap_kb', r'Native Heap:\s+(\d+)'),
                    ('graphics_kb', r'Graphics:\s+(\d+)'), ('total_pss_kb', r'TOTAL PSS:\s+(\d+)'),
                    ('total_rss_kb', r'TOTAL RSS:\s+(\d+)')):
        m = re.search(rx, txt)
        out[key] = int(m.group(1)) if m else None
    return out


def section_m(apk):
    night(True)
    write_prefs(store('lab'))
    k = host('multiline')
    time.sleep(3.0)
    type_seq(WARMUP, k)
    res = {'apk_bytes': os.path.getsize(apk), 'runs': []}
    for i in range(3):
        k = host('multiline')
        sh('logcat -c', check=False)
        sh(f'dumpsys gfxinfo {PKG} reset', check=False)
        t0 = time.time()
        type_seq(TEXT, k)
        wall = time.time() - t0
        time.sleep(0.8)
        gfx = gfx_stats(sh(f'dumpsys gfxinfo {PKG}', check=False))
        kt = r13c.keytimes()
        res['runs'].append({'gfx': gfx, 'keytime': {kind: r13c.summary(v) for kind, v in kt.items()}, 'wall_s': round(wall, 1)})
        print(f'  run {i}: gfx {gfx} key {res["runs"][-1]["keytime"]["key"]}', flush=True)
    res['heap_typing'] = heap()
    # a panel cycle on top (quick, emoji, clipboard) then the heap again: panels must not keep growing it
    k = host('text')
    e2e.tap(gear()); time.sleep(0.8); press('Volver al teclado')
    press('Emojis', wait=1.5); press('Volver al teclado', wait=0.6)
    res['heap_after_panels'] = heap()
    metrics['M'] = res
    check('M', 'frame stats and keytime lines read on 3 runs', all(r['gfx']['frames'] and r['keytime']['key'] for r in res['runs']),
          {'janky_pct': [r['gfx']['janky_pct'] for r in res['runs']]})
    night(False)


# ── main ─────────────────────────────────────────────────────────────────
def main():
    global OUT, LABEL
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--label', required=True, choices=['before', 'after'])
    ap.add_argument('--apk')
    ap.add_argument('--before-apk')
    ap.add_argument('--only', default='K,S,P,F,T,R,N,M')
    ap.add_argument('--no-install', action='store_true')
    a = ap.parse_args()
    if a.serial in FORBIDDEN:
        print(f'refusing: {a.serial} is in VK_E2E_FORBIDDEN_SERIALS')
        return 2
    e2e.ADB.extend(['-s', a.serial])
    LABEL = a.label
    OUT = os.path.join(BASE, LABEL)
    for mod in (e2e, r13, r13c, r13f):
        mod.OUT = OUT
    os.makedirs(OUT, exist_ok=True)
    apk = a.apk or os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    only = set(a.only.split(','))
    pre = {
        'ime': sh('settings get secure default_input_method').strip(),
        'night': sh('cmd uimode night').strip(),
        'font_scale': sh('settings get system font_scale').strip(),
    }
    print('prestate', pre)
    started = time.time()
    try:
        if not a.no_install:
            r13c.install('-r', '-t', '-d', apk)
            sh(f'pm clear {PKG}', check=False)
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        sh('svc power stayon usb', check=False)
        if 'K' in only:
            section_k()
        if 'S' in only:
            section_s()
        if 'P' in only:
            section_p()
        if 'F' in only:
            section_f(pre['font_scale'] if pre['font_scale'] not in ('', 'null') else '1.0')
        if 'T' in only:
            section_t()
        if 'R' in only:
            section_r()
        if 'N' in only and LABEL == 'after':
            import e2e_r14_new as new  # noqa: E402  (0.14.0-only rows, kept apart so the before run never imports them)
            new.run(sys.modules[__name__])
        if 'U' in only and LABEL == 'after' and a.before_apk:
            import e2e_r14_new as new  # noqa: E402
            new.upgrade(sys.modules[__name__], a.before_apk, apk)
        if 'M' in only:
            section_m(apk)
    finally:
        night_word = pre['night'].split(':')[-1].strip()
        sh(f'cmd uimode night {night_word}', check=False)
        if pre['font_scale'] not in ('', 'null'):
            sh(f"settings put system font_scale {pre['font_scale']}", check=False)
        sh('svc power stayon false', check=False)
        if pre['ime'] and pre['ime'] != 'null' and pre['ime'] != IME:
            sh(f"ime set {pre['ime']}", check=False)
        post = {
            'ime': sh('settings get secure default_input_method', check=False).strip(),
            'night': sh('cmd uimode night', check=False).strip(),
            'font_scale': sh('settings get system font_scale', check=False).strip(),
        }
        print('restored', post)
        passed = sum(r['ok'] for r in rows)
        res = {'label': LABEL, 'apk': os.path.relpath(apk, e2e.ROOT), 'prestate': pre, 'poststate': post,
               'restored': post == pre, 'passed': passed, 'total': len(rows), 'seconds': round(time.time() - started, 1),
               'rows': rows, 'metrics': metrics, 'scenes': scenes}
        path = os.path.join(OUT, f'r14-{LABEL}.json')
        old = json.load(open(path)) if os.path.exists(path) else {}
        if old and not a.only == 'K,S,P,F,T,R,N,M':
            # partial reruns merge into the label's file (scenes and rows by name)
            seen = {s['scene'] for s in scenes}
            res['scenes'] = [s for s in old.get('scenes', []) if s['scene'] not in seen] + scenes
            names = {(r['section'], r['check']) for r in rows}
            res['rows'] = [r for r in old.get('rows', []) if (r['section'], r['check']) not in names] + rows
            res['metrics'] = {**old.get('metrics', {}), **metrics}
            res['passed'] = sum(r['ok'] for r in res['rows'])
            res['total'] = len(res['rows'])
        json.dump(res, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {res["passed"]}/{res["total"]} rows → {os.path.relpath(path, e2e.ROOT)}')
    return 0 if all(r['ok'] for r in rows) else 1


if __name__ == '__main__':
    sys.exit(main())
