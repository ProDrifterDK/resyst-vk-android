#!/usr/bin/env python3
"""✦ Resyst VK — r11b: the demo video KLIPY's Production upgrade form asks for (mp4, ≤ 100 MB).

Records the Pixel's screen (`screenrecord`, 180 s cap) while a scripted, unhurried run shows:
open the keyboard in a neutral message field → emoji panel → GIF tab with the visible
"Powered by KLIPY" mark → the enable flow with its disclosure → trending grid → search a neutral
word ("gato") in the GIF box → tap a GIF → the GIF lands in a field that accepts images.

Starts with the GIF search OFF (consent + id deleted) so the enable flow is real. Notifications:
the shade is never opened; Do Not Disturb is turned on during the recording and restored after.
API budget: 3 KLIPY calls (trending, search, share) + the chosen file.

Output: build/e2e-r11b/klipy-production-demo.mp4 (pulled, then deleted from the phone).
Usage: scripts/demo_r11b.py --serial SERIAL
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r11 as r11  # noqa: E402
import e2e_r11b as g  # noqa: E402
from e2e import adb, sh, PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r11b')
REMOTE = '/sdcard/r11b-demo.mp4'
LOCAL = os.path.join(OUT, 'klipy-production-demo.mp4')


def pause(s):
    time.sleep(s)


def slow_type(word):
    k = r11.km()
    for ch in word:
        n = k.get(ch) if k.get(ch) is not None else k.get(ch.upper())
        r11.tap_node(n, 0.32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--word', default='gato')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    r11.OUT = e2e.OUT = g.OUT = OUT
    os.makedirs(OUT, exist_ok=True)
    previous = sh('settings get secure default_input_method').strip()
    dnd = sh('settings get global zen_mode', check=False).strip()
    # clean start: GIF search OFF, no temporary file, the keyboard selected
    g.reset_state()
    sh('cmd notification set_dnd priority', check=False)
    sh(f'rm -f {REMOTE}', check=False)
    rec = subprocess.Popen(e2e.ADB + ['shell', 'screenrecord', '--bit-rate', '6000000', '--time-limit', '180', REMOTE])
    t0 = time.time()
    try:
        pause(1.5)
        r11.fresh('demo')  # neutral message field that accepts images
        pause(2.0)
        r11.press('Emojis')
        pause(2.0)
        r11.select_tab('GIF')  # the GIF tab: explainer + "Powered by KLIPY"
        pause(3.5)
        r11.press('Ver qué se envía')  # the disclosure, before anything is sent
        pause(7.0)
        r11.press('Activar la búsqueda')
        g.wait_for(lambda: g.gif_items(), 15)
        pause(5.0)  # the trending grid animating
        r11.scroll_grid(0.5, 900)
        pause(2.5)
        r11.press('Buscar en KLIPY')
        pause(1.5)
        slow_type(a.word)
        pause(1.2)
        r11.press('Buscar GIF')
        g.wait_for(lambda: g.gif_items(), 15)
        pause(4.5)
        cells = g.gif_cells()
        if cells:
            r11.tap_node(cells[0][1], 0.2)
        g.wait_for(lambda: 'shown AnimatedImageDrawable' in g.logcat(), 15)
        pause(2.0)
        r11.press('Volver al teclado')
        pause(5.0)  # the GIF playing in the field
    finally:
        elapsed = time.time() - t0
        sh('pkill -INT screenrecord', check=False)
        try:
            rec.wait(timeout=15)
        except subprocess.TimeoutExpired:
            rec.kill()
        pause(2.0)
        adb('pull', REMOTE, LOCAL)
        sh(f'rm -f {REMOTE}', check=False)
        left = sh(f'ls {REMOTE} 2>&1', check=False).strip()
        sh('cmd notification set_dnd ' + ('off' if dnd in ('0', '') else 'priority'), check=False)
        if previous and previous != 'null' and previous != IME:
            sh(f'ime set {previous}', check=False)
        size = os.path.getsize(LOCAL) if os.path.exists(LOCAL) else 0
        print(f'✦ demo: {LOCAL} · {size / 1e6:.1f} MB · ~{elapsed:.0f} s recorded · on phone after delete: {left!r}', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
