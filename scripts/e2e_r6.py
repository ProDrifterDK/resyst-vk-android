#!/usr/bin/env python3
"""✦ Resyst VK — round 6 on a device / emulator: clipboard paste chip + clipboard history.

Copies are made by the debug E2E host itself (broadcast → ClipboardManager.setPrimaryClip while
the host has focus), i.e. exactly what an app's "Copiar" does. Everything is asserted through
the keyboard's accessibility tree (same helpers as e2e.py) plus the private history file.

  1. copy → "Pegar" chip shows the clip; a tap commits it; the chip steps aside afterwards
  2. history: copies accumulate newest-first, a repeated copy collapses (×2)
  3. a history row pastes; typing hides the chip
  4. long-press preview: pin (goes first) and delete
  5. persistence: history survives killing the keyboard process
  6. privacy: password field → no chip, no history button, a copy made there is never stored
     and its chip is masked elsewhere; a "sensitive" clip is masked and never stored
  7. images: an image clip → "Pegar imagen" in a field that takes images (commitContent
     delivers the bytes); no chip in a plain text field
  8. "Borrar todo" in the panel; settings: history off stops capture, "Borrar historial" wipes

Test data is synthetic. --clean wipes the debug app's data first (emulator only).
Artifacts: build/e2e-clipboard/results.json, build/e2e-clipboard/*.png

Usage: scripts/e2e_r6.py --serial SERIAL [--clean]
"""
import argparse
import json
import os
import shlex
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, field_text, tap, type_word, PKG, IME, SETTINGS  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-clipboard')
e2e.OUT = OUT
results = e2e.results
record = e2e.record
ACTION = 'com.resyst.vk.debug.SET_CLIP'
CHIP = 'Pegar del portapapeles: '
CHIP_IMG = 'Pegar imagen del portapapeles'
HIST = 'Historial del portapapeles'
MASK = '••••••'


def set_clip(text=None, sensitive=False, image=False):
    args = f'am broadcast -a {ACTION} -p {PKG}'
    if text is not None:
        args += ' --es text ' + shlex.quote(text)
    if sensitive:
        args += ' --ez sensitive true'
    if image:
        args += ' --ez image true'
    sh(args)
    time.sleep(1.2)


def km():
    return keys(dump())


def chip(k=None):
    k = k or km()
    for d in k:
        if d.startswith(CHIP) or d == CHIP_IMG:
            return d
    return None


def rows(k=None):
    k = k or km()
    return [d for d in k if d.startswith('Pegar: ') or d.startswith('Fijado. Pegar: ')]


def row(prefix_text, k=None):
    k = k or km()
    for d, n in k.items():
        if d.startswith('Pegar: ' + prefix_text) or d.startswith('Fijado. Pegar: ' + prefix_text):
            return n
    return None


def open_panel():
    tap(km()[HIST])
    time.sleep(0.5)
    return km()


def history_file():
    return sh(f'run-as {PKG} cat files/clipboard/history.json 2>/dev/null', check=False)


def restart_keyboard():
    sh(f'am force-stop {PKG}')
    time.sleep(0.8)
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')


def shot(name):
    e2e.shot(name)


def find_setting(prefix, max_swipes=18):
    """Scrolls the settings screen until a node whose content-desc / text starts with prefix shows."""
    for _ in range(max_swipes):
        st = dump()
        for n in st.iter('node'):
            if (n.get('content-desc') or '').startswith(prefix) or (n.get('text') or '') == prefix:
                return n
        sh('input swipe 540 1700 540 1000 300')
        time.sleep(0.6)
    return None


def open_settings():
    sh(f'am start -W -f 0x10008000 -n {SETTINGS}')
    time.sleep(1.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--clean', action='store_true', help='pm clear the debug app first (emulator)')
    ap.add_argument('--apk', default=os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'))
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    started = time.time()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk})')
    try:
        adb('install', '-r', '-t', a.apk)
        if a.clean:
            sh(f'pm clear {PKG}', check=False)
            # the emulator's "copied" overlay (API 33+) would sit over the keyboard
            sh('device_config put systemui clipboard_overlay_enabled false', check=False)
        else:
            sh(f'run-as {PKG} rm -rf files/clipboard', check=False)
        restart_keyboard()

        # 1 — copy → chip → paste
        tree, k = e2e.open_host('text')
        record('history button in the strip', HIST in k)
        # The system clipboard outlives `pm clear`: whatever it holds was just captured at field
        # focus (by design). Start from an empty history through the panel's own "Borrar todo"
        # (the cleared clip is never re-captured: its stamp is remembered).
        k = open_panel()
        if 'Borrar todo el historial' in k:
            tap(k['Borrar todo el historial'])
            time.sleep(0.3)
            tap(km()['Sí, borrar todo el historial'])
            time.sleep(0.4)
        record('start: empty history', rows() == [], repr(rows()))
        tap(km()['Cerrar portapapeles, volver al teclado'])
        tree, k = e2e.open_host('text')
        open_panel()
        r = rows()
        record('a cleared clip is not re-captured at the next field focus', r == [], repr(r))
        tap(km()['Cerrar portapapeles, volver al teclado'])
        set_clip('segundo plano')  # make the clipboard fresh and known
        tree, k = e2e.open_host('text')  # new field: the chip offers it
        record('fresh clip offered in a new field', chip() == CHIP + 'segundo plano', repr(chip()))
        set_clip('Hola desde el portapapeles ñ')
        c = chip()
        shot('01-paste-chip')
        record('copy → "Pegar" chip shows the clip (ellipsized)', c is not None and c.startswith(CHIP + 'Hola desde el porta'), repr(c))
        tap(km()[c])
        time.sleep(0.4)
        txt = field_text(dump())
        record('chip pastes the whole clip via commitText', txt == 'Hola desde el portapapeles ñ', repr(txt))
        record('the pasted clip is not offered again', chip() is None, repr(chip()))

        # 2 — history accumulates, dedup
        set_clip('tercero')
        set_clip('Hola desde el portapapeles ñ')  # again: collapses, moves to top
        k = open_panel()
        r = rows(k)
        shot('02-history-panel')
        record('history newest first, repeated copy collapsed',
               len(r) == 3 and r[0].startswith('Pegar: Hola desde el porta') and 'copiado 2 veces' in r[0]
               and r[1].startswith('Pegar: tercero') and r[2].startswith('Pegar: segundo plano'), repr(r))

        # 3 — a row pastes; typing hides the chip
        tap(row('segundo plano', k))
        time.sleep(0.4)
        txt = field_text(dump())
        record('tapping a history row pastes it and closes the panel', txt.endswith('ñsegundo plano') and HIST in km(), repr(txt))
        set_clip('chip efímero')
        record('new copy → chip', chip() == CHIP + 'chip efímero', repr(chip()))
        type_word('a')
        record('typing hides the chip', chip() is None, repr(chip()))

        # 4 — preview: pin + delete
        k = open_panel()
        tap(row('tercero', k), hold_ms=900)
        time.sleep(0.4)
        k = km()
        shot('03-preview')
        record('long-press opens the preview (Pegar / Fijar / Borrar)', all(x in k for x in ('Pegar', 'Fijar', 'Borrar', 'Volver a la lista')), repr(sorted(k)))
        tap(k['Fijar'])
        time.sleep(0.3)
        tap(km()['Volver a la lista'])
        time.sleep(0.3)
        r = rows()
        record('pinned entry goes first', r and r[0].startswith('Fijado. Pegar: tercero'), repr(r))
        tap(row('chip efímero'), hold_ms=900)
        time.sleep(0.3)
        tap(km()['Borrar'])
        time.sleep(0.4)
        r = rows()
        shot('04-pinned')
        record('delete removes the entry', not any('chip efímero' in d for d in r) and len(r) == 3, repr(r))
        tap(km()['Cerrar portapapeles, volver al teclado'])

        # 5 — persistence across process death
        e2e.open_host('text')  # field switch flushes the pending save
        time.sleep(1.5)
        stored = history_file()
        record('history written to private storage', '"tercero"' in stored and 'chip efímero' not in stored, f'{len(stored)} bytes')
        restart_keyboard()
        tree, k = e2e.open_host('text')
        time.sleep(1.0)
        k = open_panel()
        r = rows(k)
        record('after killing the keyboard process the history is back (pin kept)',
               len(r) == 3 and r[0].startswith('Fijado. Pegar: tercero'), repr(r))
        shot('05-after-restart')
        tap(k['Cerrar portapapeles, volver al teclado'])

        # 6 — privacy
        tree, k = e2e.open_host('password')
        record('password field: no paste chip for a fresh clip', chip(k) is None, repr(chip(k)))
        record('password field: no history button', HIST not in k)
        set_clip('S3cr3t-clave-XYZ')
        k = km()
        shot('06-password')
        record('password field: still no chip after a copy there', chip(k) is None, repr(chip(k)))
        tree, k = e2e.open_host('text')
        time.sleep(0.8)
        record('a copy made in a password field is masked elsewhere', chip() == CHIP + MASK, repr(chip()))
        k = open_panel()
        r = rows(k)
        record('…and never enters the history (panel)', not any('S3cr3t' in d for d in r), repr(r))
        tap(k['Cerrar portapapeles, volver al teclado'])
        set_clip('token-sensible-123', sensitive=True)
        record('sensitive clip: chip masked', chip() == CHIP + MASK, repr(chip()))
        e2e.open_host('text')
        time.sleep(1.5)
        stored = history_file()
        record('password-field copy and sensitive clip never reach the file',
               'S3cr3t' not in stored and 'token-sensible' not in stored, f'{len(stored)} bytes')

        # 7 — images
        sh('logcat -c')
        tree, k = e2e.open_host('rich')
        set_clip(image=True)
        c = chip()
        shot('07-image-chip')
        record('image clip in an image-accepting field → "Pegar imagen"', c == CHIP_IMG, repr(c))
        if c:
            tap(km()[c])
            time.sleep(1.5)
            log = adb('logcat', '-d', '-s', 'ResystE2E:I', 'ResystVK:I')
            got = [l for l in log.splitlines() if 'commitContent mime=' in l]
            record('commitContent delivers the image bytes to the app', any('bytes=' in l and 'bytes=-1' not in l for l in got), got[-1] if got else log[-300:])
            txt = field_text(dump())
            record('the field received the image', '[imagen' in (txt or ''), repr(txt))
        tree, k = e2e.open_host('text')
        record('image-only clip: no chip in a plain text field', chip(k) is None, repr(chip(k)))

        # 8a — "Borrar todo"
        k = open_panel()
        tap(k['Borrar todo el historial'])
        time.sleep(0.3)
        k = km()
        shot('08-confirm-clear')
        record('"Borrar todo" asks first', 'Sí, borrar todo el historial' in k and 'No borrar' in k)
        tap(k['Sí, borrar todo el historial'])
        time.sleep(0.6)
        r = rows()
        shot('09-empty')
        record('"Borrar todo" empties the panel (pinned too)', r == [], repr(r))
        tap(km()['Cerrar portapapeles, volver al teclado'])
        time.sleep(1.0)
        stored = history_file()
        record('…and the file', '"items":[]' in stored, stored[:120])

        # 8b — settings
        open_settings()
        n = find_setting(HIST + ',')
        record('settings: "Historial del portapapeles" toggle (on by default)', n is not None and n.get('content-desc', '').endswith('activado') and 'desactivado' not in n.get('content-desc', ''), n.get('content-desc') if n is not None else None)
        p = find_setting('Purgar tras 1 hora,')
        record('settings: "Purgar tras 1 hora" toggle (off by default)', p is not None and p.get('content-desc', '').endswith('desactivado'), p.get('content-desc') if p is not None else None)
        b = find_setting('Borrar historial del portapapeles')
        record('settings: "Borrar historial del portapapeles" row', b is not None)
        shot('10-settings')
        if n is not None:
            tap(find_setting(HIST + ','))
            time.sleep(0.6)
            tree, k = e2e.open_host('text')
            record('history off: no history button', HIST not in k)
            set_clip('no-guardar-esto')
            record('history off: the paste chip still works', chip() == CHIP + 'no-guardar-esto', repr(chip()))
            e2e.open_host('text')
            time.sleep(1.5)
            record('history off: nothing captured', 'no-guardar' not in history_file())
            open_settings()
            tap(find_setting(HIST + ','))
            time.sleep(0.6)
            # history back on: a copy is captured, then the settings wipe removes it
            tree, k = e2e.open_host('text')
            set_clip('para borrar desde ajustes')
            open_settings()
            b = find_setting('Borrar historial del portapapeles')
            summary = b.get('content-desc', '') if b is not None else ''
            record('settings shows what is stored', '1 elementos' in summary, summary)
            tap(b)
            time.sleep(0.8)
            dlg = dump()
            btn = next((x for x in dlg.iter('node') if (x.get('text') or '').lower() == 'borrar'), None)
            shot('11-settings-confirm')
            record('settings wipe asks first', btn is not None)
            if btn is not None:
                tap(btn)
                time.sleep(1.0)
            b = find_setting('Borrar historial del portapapeles')
            record('settings wipe → "Vacío"', b is not None and 'Vacío' in b.get('content-desc', ''), b.get('content-desc') if b is not None else None)
            record('settings wipe → file empty', 'para borrar' not in history_file())
    finally:
        passed = sum(r['ok'] for r in results)
        summary = {
            'device': model, 'api': sdk, 'passed': passed, 'total': len(results),
            'seconds': round(time.time() - started, 1), 'checks': results,
        }
        json.dump(summary, open(os.path.join(OUT, 'results.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {passed}/{len(results)} checks passed → build/e2e-clipboard/results.json')
    return 0 if results and all(r['ok'] for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
