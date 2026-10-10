#!/usr/bin/env python3
"""✦ Resyst VK — r11c E2E: the update check runs when the keyboard opens (throttled, persisted).

Every `update check: GET release.json` log line is one real HTTPS GET of kv.resyst.cl/release.json
(Updater logs it right before the fetch), so requests are counted from logcat and attributed to the
IME process id. The live manifest advertises 0.7.0 / code 7:
  --uptodate-apk  a 0.7.1 debug build (code 8)        → "up to date"
  --old-apk       the same code stamped 0.6.0 / code 6 → "0.7.0 available" (the chip)
Both are this branch's code; only the version stamp differs. Nothing in the release logic is
sped up: the stored last attempt is moved back inside the live process by the debug-only
E2EUpdateClock receiver (a run-as edit of the prefs file is overwritten by the process's own
in-memory SharedPreferences), then the keyboard is reopened.

Sections (each row can fail):
  A  20 opens on the up-to-date build → exactly 1 GET, one pid, no chip; settings reuses the state
  B  the old build installed (new process) → 0 GETs: the throttle survived the process (OC2)
  C  stored attempt moved back 13 h, same pid → exactly 1 GET and the chip appears (OC1, OC11)
  D  chip → typing → ⚙ dot → quick-panel line; a password field: no chip, no GET even when due (OC8)
  E  ✕ dismiss → hidden; a later check (rewound) does not bring the same version back (OC13)
  F  toggle off → no GET on open even with the stamp rewound; on again → the next open checks (OC6)
  G  offline → 1 failed GET logged; reopen within the hour → 0; back online + 61 min → 1 (OC4)
  H  clock moved back (stamp 10 days ahead) → 1 GET, then throttled again (OC3)
  I  process restart inside the interval → 0 GETs, the known update is restored offline (OC16)
  J  Libro de conexiones lists "al abrir el teclado" next to older "al iniciar el teclado" (OC15)

Writes build/e2e-r11c/r11c-e2e.json + PNGs.
Usage: scripts/e2e_r11c.py --serial SERIAL --old-apk PATH --uptodate-apk PATH
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
from e2e import adb, sh, dump, keys, bounds, center, PKG, SETTINGS  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r11c')
PREFS = f'shared_prefs/resyst_vk_update.xml'
PROFILES = 'shared_prefs/resyst_vk_profiles.xml'
CLOCK = PKG + '/com.resyst.vk.debug.E2EUpdateClock'
H = 3_600_000
rows = []
all_gets = []  # every GET line seen during the run (logcat is cleared per read)
log_seen = []
SCREEN_H = 2400


def check(section, name, ok, detail=''):
    rows.append({'section': section, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:400]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}] {name}' + ('  ' + str(detail)[:200] if detail else ''), flush=True)


def shot(name):
    open(os.path.join(OUT, name), 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))


def pid():
    return sh(f'pidof {PKG}', check=False).strip()


def run_as(cmd):
    return sh(f"run-as {PKG} sh -c '{cmd}'", check=False)


def drain():
    """New ResystVK/ResystE2E lines since the last drain; GET lines are kept in all_gets."""
    lines = [l for l in adb('logcat', '-d', '-v', 'time', '-s', 'ResystVK:I', 'ResystE2E:I').splitlines()
             if ('update' in l or 'connection log' in l)]
    sh('logcat -c')
    log_seen.extend(lines)
    g = [l for l in lines if 'update check: GET release.json' in l]
    all_gets.extend(g)
    return lines, g


class Window:
    """GETs and update lines between two points of the run."""
    def __init__(self):
        drain()
        self.lines, self.gets = [], []

    def read(self):
        l, g = drain()
        self.lines += l
        self.gets += g
        return self

    def has(self, s):
        return any(s in l for l in self.lines)


def wait_line(win, s, timeout=15.0):
    end = time.time() + timeout
    while time.time() < end:
        win.read()
        if win.has(s):
            return True
        time.sleep(0.7)
    return False


def strip(tree):
    k = keys(tree)
    chip = next((d for d in k if d.endswith('disponible. Toca para actualizar')), None)
    x = next((d for d in k if d.startswith('Descartar el aviso')), None)
    gear = next((d for d in k if d.startswith('Ajustes de Resyst VK')), None)
    return k, chip, x, gear


def open_kb(kind='text'):
    tree, km = e2e.open_host(kind)
    return tree


def rewind(ms):
    sh(f'am broadcast -n {CLOCK} --el by_ms {ms}')
    time.sleep(0.6)


def ahead(ms):
    sh(f'am broadcast -n {CLOCK} --el ahead_ms {ms}')
    time.sleep(0.6)


def prefs():
    return run_as(f'cat {PREFS}')


def find(tree, pred):
    for n in tree.iter('node'):
        if n.get('package') != PKG:
            continue
        if pred(n.get('content-desc') or '', n.get('text') or ''):
            return n
    return None


def on_screen(n):
    x1, y1, x2, y2 = bounds(n)
    return y2 > y1 and y1 >= 120 and y2 <= SCREEN_H - 200


def scroll_find(pred, swipes=16):
    for _ in range(swipes):
        t = dump()
        n = find(t, pred)
        if n is not None and on_screen(n):
            return n, t
        sh('input swipe 540 1600 540 900 300')
        time.sleep(0.5)
    return None, dump()


def top():
    for _ in range(8):
        sh('input swipe 540 700 540 1900 200')
    time.sleep(0.5)


def open_about():
    sh(f'am start -W -n {SETTINGS} --es com.resyst.vk.page acerca')
    time.sleep(1.8)
    top()


def texts(tree):
    return [((n.get('text') or '') or (n.get('content-desc') or '')).strip() for n in tree.iter('node') if n.get('package') == PKG]


def set_toggle(on):
    """Tap 'Buscar actualizaciones automáticamente' in settings until it reads [on]."""
    want = 'activado' if on else 'desactivado'
    n, t = scroll_find(lambda d, x: d.startswith('Buscar actualizaciones automáticamente, '))
    if n is None:
        return None, t
    if not n.get('content-desc').endswith(', ' + want):
        e2e.tap(n)
        time.sleep(0.9)
        n, t = scroll_find(lambda d, x: d.startswith('Buscar actualizaciones automáticamente, '))
    return n, t


def net(on):
    if on:
        sh('cmd connectivity airplane-mode disable', check=False)
        sh('svc wifi enable', check=False)
        sh('svc data enable', check=False)
    else:
        sh('cmd connectivity airplane-mode enable', check=False)
        sh('svc wifi disable', check=False)
        sh('svc data disable', check=False)
    for _ in range(30):
        time.sleep(1.5)
        up = sh('ping -c1 -W2 kv.resyst.cl >/dev/null 2>&1 && echo UP || echo DOWN', check=False).strip()
        if (up == 'UP') == on:
            return up
    return up


def reset_prefs_keep_log():
    """Process must be dead. Keeps the connection log (older entries), drops every other key."""
    xml = prefs()
    m = re.search(r'<string name="connections">.*?</string>', xml, re.S)
    body = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            + (f'    {m.group(0)}\n' if m else '') + '</map>\n')
    local = os.path.join(OUT, '.update-prefs.xml')
    open(local, 'w', encoding='utf-8').write(body)
    adb('push', local, '/data/local/tmp/r11c-update.xml')
    sh('chmod 644 /data/local/tmp/r11c-update.xml')
    run_as(f'cp /data/local/tmp/r11c-update.xml {PREFS}')
    return bool(m)


def set_auto_on_file():
    """Process must be dead: update.auto = true in the v2 store (fresh install writes it on first edit)."""
    xml = run_as(f'cat {PROFILES}')
    if '<map' not in xml:
        return False
    xml = re.sub(r'\s*<string name="update.auto">[^<]*</string>', '', xml)
    local = os.path.join(OUT, '.profiles.xml')
    open(local, 'w', encoding='utf-8').write(xml)
    adb('push', local, '/data/local/tmp/r11c-prof.xml')
    sh('chmod 644 /data/local/tmp/r11c-prof.xml')
    run_as(f'cp /data/local/tmp/r11c-prof.xml {PROFILES}')
    return True


def main():
    global SCREEN_H
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--old-apk', required=True, help='debug build stamped older than the live manifest')
    ap.add_argument('--uptodate-apk', required=True, help='debug build at/above the live manifest')
    ap.add_argument('--opens', type=int, default=20)
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    m = re.search(r'(\d+)x(\d+)', sh('wm size'))
    if m:
        SCREEN_H = int(m.group(2))
    avd = adb('emu', 'avd', 'name', check=False).splitlines()[0].strip() if a.serial.startswith('emulator-') else ''
    started = time.time()
    if net(True) != 'UP':
        check('setup', 'network up before the run', False, 'kv.resyst.cl unreachable')

    # ── A: up to date, 20 opens in one process → exactly one GET ────────────────
    adb('install', '-r', '-d', a.uptodate_apk)
    sh(f'am force-stop {PKG}')
    kept_log = reset_prefs_keep_log()
    set_auto_on_file()
    w = Window()
    pids = []
    t0 = time.time()
    for i in range(a.opens):
        open_kb('text' if i % 2 == 0 else 'multiline')
        pids.append(pid())
        if i == 0:
            wait_line(w, 'update open-check: UpToDate', 15)
    w.read()
    secs = round(time.time() - t0, 1)
    check('A', f'{a.opens} keyboard opens in {secs}s → exactly 1 GET', len(w.gets) == 1 and 'auto=true' in w.gets[0], w.gets)
    check('A', 'one IME process for all opens', len(set(pids)) == 1 and pids[0] != '', sorted(set(pids)))
    pid_a = pids[0]
    _, chip, _, gear = strip(dump())
    check('A', 'up to date: no chip, no dot', chip is None and gear == 'Ajustes de Resyst VK', f'{chip} {gear}')
    p = prefs()
    check('A', 'attempt persisted: attemptAt + attemptOk=true', 'name="attemptAt"' in p and '<boolean name="attemptOk" value="true"' in p, re.sub(r'\s+', ' ', p)[-220:])
    shot('r11c-A-uptodate-keyboard.png')
    open_about()
    nn, t2 = scroll_find(lambda d, x: x.startswith('Su única conexión'))
    prom = nn.get('text') if nn is not None else None
    check('A', 'promise line says when and how often', bool(prom) and 'al abrir el teclado' in prom and 'una vez cada 12 horas' in prom, prom)
    n, t = scroll_find(lambda d, x: x.startswith('✓ Ya tienes la última versión'))
    tx = texts(t)
    check('A', 'settings shows "Ya tienes la última versión" without a tap', n is not None, next((s for s in tx if 'última' in s), ''))
    check('A', 'settings card says "Comprobado al abrir el teclado"', any(s.startswith('Comprobado al abrir el teclado') for s in tx), next((s for s in tx if s.startswith('Comprobado')), ''))
    shot('r11c-A-settings-uptodate.png')
    n, t = scroll_find(lambda d, x: d.startswith('Buscar actualizaciones automáticamente, '))
    tx = texts(t)
    check('A', 'toggle label + subtitle', n is not None and n.get('content-desc').endswith('activado') and
          'Al abrir el teclado, como mucho una vez cada 12 horas. Nunca envía lo que escribes.' in tx, n.get('content-desc') if n is not None else None)
    shot('r11c-A-settings-toggle.png')
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.6)
    w.read()
    check('A', 'settings opened: no GET', len(w.gets) == 1, len(w.gets))

    # ── B: the old build (new process) → the throttle survived the process ──────
    adb('install', '-r', '-d', a.old_apk)
    ver = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}')).group(1)
    w = Window()
    for i in range(5):
        open_kb('text')
    pid_b = pid()
    time.sleep(1.5)
    w.read()
    _, chip, _, gear = strip(dump())
    check('B', f'reinstalled as {ver}: new process, 5 opens → 0 GETs (persisted 12 h throttle)', len(w.gets) == 0 and pid_b != pid_a, f'pid {pid_a}→{pid_b} gets={w.gets}')
    check('B', 'no chip yet (last answer was "up to date")', chip is None, chip)

    # ── C: last attempt moved back 13 h, same pid → one GET, the chip appears ───
    rewind(13 * H)
    w = Window()
    open_kb('text')
    wait_line(w, 'update open-check: Available', 15)
    tree = None
    for _ in range(10):
        tree = dump()
        if strip(tree)[1]:
            break
        time.sleep(0.8)
    k, chip, x, gear = strip(tree)
    w.read()
    check('C', 'rewound 13 h → exactly 1 new GET, same pid', len(w.gets) == 1 and pid() == pid_b, f'pid={pid()} gets={w.gets}')
    check('C', 'the later check announces: chip "Resyst VK 0.7.0 disponible"', chip == 'Resyst VK 0.7.0 disponible. Toca para actualizar', chip)
    shot('r11c-C-chip.png')
    for _ in range(4):
        open_kb('multiline')
    w.read()
    check('C', '4 more opens → still 1 GET', len(w.gets) == 1, len(w.gets))

    # ── D: chip / dot / quick panel; password field ─────────────────────────────
    tree = open_kb('text')
    k, chip, x, gear = strip(dump())
    check('D', 'fresh field: chip with a ≥48 dp ✕', chip is not None and x is not None, f'{chip} {x}')
    e2e.type_word('ho', k)
    time.sleep(0.6)
    k2, chip2, _, gear2 = strip(dump())
    check('D', 'after typing: chip steps down to the amber ⚙ dot', chip2 is None and gear2 == 'Ajustes de Resyst VK. Actualización 0.7.0 disponible', f'{chip2} | {gear2}')
    shot('r11c-D-dot.png')
    if gear2:
        pid_d = pid()
        e2e.tap(k2[gear2])  # this process never opened the emoji panel (OC17)
        time.sleep(0.9)
        t = dump()
        check('D', '⚙ opens the quick panel without killing the IME (OC17)', pid() == pid_d and find(t, lambda d, x: d == 'Volver al teclado') is not None, f'pid {pid_d}→{pid()}')
        line = find(t, lambda d, x: d == 'Resyst VK 0.7.0 disponible · ver')
        check('D', 'quick panel shows the update line', line is not None, [d for d in keys(t)][:8])
        shot('r11c-D-quick.png')
        back = find(t, lambda d, x: d == 'Volver al teclado')
        if back is not None:
            e2e.tap(back)
            time.sleep(0.5)
    rewind(13 * H)  # due again: the password field must still not start the request
    w = Window()
    open_kb('password')
    time.sleep(2.0)
    w.read()
    kp, chipp, _, gearp = strip(dump())
    check('D', 'password field (due): 0 GETs, "skip secret" logged', len(w.gets) == 0 and w.has('update open-check: skip secret'), w.lines[-3:])
    check('D', 'password field: no chip', chipp is None, f'{chipp} | {gearp}')
    shot('r11c-D-password.png')
    open_kb('text')
    wait_line(w, 'update open-check: Available', 15)
    check('D', 'next non-secret field → the due check runs (1 GET)', len(w.gets) == 1, w.gets)

    # ── E: ✕ dismiss holds across later checks ───────────────────────────────────
    k, chip, x, gear = strip(dump())
    if x:
        e2e.tap(k[x])
        time.sleep(0.8)
    k5, chip5, _, gear5 = strip(dump())
    check('E', '✕ hides the chip and the dot', chip5 is None and gear5 == 'Ajustes de Resyst VK', f'{chip5} | {gear5}')
    shot('r11c-E-dismissed.png')
    rewind(13 * H)
    w = Window()
    open_kb('text')
    wait_line(w, 'update open-check: Available', 15)
    time.sleep(0.8)
    k6, chip6, _, gear6 = strip(dump())
    check('E', 'a later check (1 GET) does not bring the dismissed 0.7.0 back', len(w.gets) == 1 and chip6 is None and gear6 == 'Ajustes de Resyst VK', f'gets={len(w.gets)} {chip6} | {gear6}')
    check('E', 'dismissed version stored', 'name="dismissed">0.7.0<' in prefs())

    # ── F: toggle off → no GET even when long due; on again → next open checks ──
    open_about()
    n, t = set_toggle(False)
    check('F', 'toggle turns off', n is not None and n.get('content-desc').endswith('desactivado'), n.get('content-desc') if n is not None else None)
    top()
    nn, t = scroll_find(lambda d, x: x.startswith('No se conecta a internet por sí solo'))
    check('F', 'promise line follows the toggle', nn is not None)
    shot('r11c-F-toggle-off.png')
    sh('input keyevent KEYCODE_BACK')
    rewind(30 * 24 * H)
    w = Window()
    for _ in range(3):
        open_kb('text')
    time.sleep(1.5)
    w.read()
    check('F', 'toggle off, stamp 30 days old: 3 opens → 0 GETs ("skip off")', len(w.gets) == 0 and w.has('update open-check: skip off'), w.lines[-2:])
    open_about()
    n, t = set_toggle(True)
    time.sleep(1.0)
    w.read()
    check('F', 'turning it on in settings does not fetch', n is not None and n.get('content-desc').endswith(', activado') and len(w.gets) == 0, len(w.gets))
    sh('input keyevent KEYCODE_BACK')
    open_kb('text')
    wait_line(w, 'update open-check: Available', 15)
    check('F', 'on again: the next open checks (1 GET)', len(w.gets) == 1, w.gets)

    # ── G: offline → one failed attempt, 1 h backoff ─────────────────────────────
    pid_g = pid()
    rewind(13 * H)
    down = net(False)
    check('G', 'offline precondition: kv.resyst.cl unreachable', down == 'DOWN', down)
    try:
        w = Window()
        open_kb('text')
        wait_line(w, 'update open-check: silent', 25)
        check('G', 'offline open → 1 GET attempted, failure silent', len(w.gets) == 1 and w.has('update open-check: silent'), w.lines[-3:])
        cl = [l for l in w.lines if 'connection log: check (open) →' in l]
        check('G', 'failure logged in the connection log (open, not an answer)', len(cl) == 1 and 'Hay versión' not in cl[0] and 'Ya al día' not in cl[0], cl)
        p = prefs()
        check('G', 'attemptOk=false stored', '<boolean name="attemptOk" value="false"' in p)
        k, chip, _, gear = strip(dump())
        e2e.type_word('hola', k)
        time.sleep(0.4)
        check('G', 'keyboard types normally offline', 'hola' in (e2e.field_text(dump()) or '').lower())
        for _ in range(3):
            open_kb('multiline')
        time.sleep(1.0)
        w.read()
        check('G', 'reopen ×3 within the hour (offline) → still 1 GET', len(w.gets) == 1, len(w.gets))
    finally:
        up = net(True)
    check('G', 'back online', up == 'UP', up)
    w = Window()
    open_kb('text')
    time.sleep(1.5)
    w.read()
    check('G', 'online again, < 1 h after the failure → 0 GETs (backoff)', len(w.gets) == 0, w.gets)
    rewind(30 * 60_000)
    open_kb('text')
    time.sleep(1.5)
    w.read()
    check('G', 'failure 30 min ago → 0 GETs', len(w.gets) == 0, w.gets)
    rewind(31 * 60_000)
    open_kb('text')
    wait_line(w, 'update open-check: Available', 15)
    check('G', 'failure 61 min ago → exactly 1 GET, same pid', len(w.gets) == 1 and pid() == pid_g, f'pid {pid_g}→{pid()} {w.gets}')

    # ── H: clock moved back (stored attempt in the future) ──────────────────────
    ahead(10 * 24 * H)
    w = Window()
    open_kb('text')
    wait_line(w, 'update open-check: Available', 15)
    for _ in range(3):
        open_kb('multiline')
    w.read()
    check('H', 'stamp 10 days in the future → 1 GET, then throttled (3 opens, no more)', len(w.gets) == 1, w.gets)

    # ── I: restart inside the interval → no GET, known update restored offline ──
    sh(f'am force-stop {PKG}')
    time.sleep(0.8)
    w = Window()
    open_kb('text')
    time.sleep(1.5)
    w.read()
    check('I', 'new process inside 12 h → 0 GETs', len(w.gets) == 0, w.gets)
    check('I', 'known update restored without a request', w.has('update notice restored: 0.7.0 (no request)'), w.lines[-3:])
    open_about()
    n, t = scroll_find(lambda d, x: x.startswith('Nueva versión disponible'))
    check('I', 'settings still offers 0.7.0 after the restart (no GET)', n is not None and len(w.read().gets) == 0, n.get('text') if n is not None else None)

    # ── J: Libro de conexiones ──────────────────────────────────────────────────
    n_new, t = scroll_find(lambda d, x: x == 'Consulta de versión · al abrir el teclado')
    shot('r11c-J-libro.png')
    n_old, t2 = scroll_find(lambda d, x: x == 'Consulta de versión · al iniciar el teclado', swipes=30)
    if n_old is not None:
        shot('r11c-J-libro-old.png')
    check('J', 'Libro lists "Consulta de versión · al abrir el teclado"', n_new is not None)
    check('J', 'older "al iniciar el teclado" entries still listed', n_old is not None or not kept_log, 'no older log on this device' if not kept_log else '')
    log = re.search(r'<string name="connections">(.*?)</string>', prefs(), re.S)
    raw = (log.group(1) if log else '').replace('&quot;', '"')
    check('J', 'stored log: "open" and "startup" reasons side by side', '"open"' in raw and ('"startup"' in raw or not kept_log), re.findall(r'"(open|startup|user)"', raw)[:12])
    sh('input keyevent KEYCODE_BACK')

    passed = sum(r['ok'] for r in rows)
    result = {
        'device': sh('getprop ro.product.model').strip(), 'avd': avd, 'serial': a.serial,
        'api': sh('getprop ro.build.version.sdk').strip(), 'manifest': 'https://kv.resyst.cl/release.json (live)',
        'old_apk': os.path.basename(a.old_apk), 'uptodate_apk': os.path.basename(a.uptodate_apk),
        'gets_total': len(all_gets), 'get_lines': all_gets, 'seconds': round(time.time() - started),
        'passed': passed, 'total': len(rows), 'rows': rows, 'update_log': log_seen,
    }
    json.dump(result, open(os.path.join(OUT, 'r11c-e2e.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(f'{passed}/{len(rows)}  GETs={len(all_gets)}')
    sys.exit(0 if passed == len(rows) else 1)


if __name__ == '__main__':
    main()
