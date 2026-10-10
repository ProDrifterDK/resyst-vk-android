#!/usr/bin/env python3
"""✦ Resyst VK — r11b on top of r11c: the GIF search and the keyboard-open update check together.

One run on one device, debug package only, the build under test already installed:
  X1 update check ON, throttle stamps cleared, GIF search turned ON from the keyboard (one
     trending request): N keyboard opens in one IME process → exactly 1 update GET, and the
     opens themselves start no KLIPY request (the GIF gate is the user's tab, never a show)
  X2 the stored attempt moved back 13 h (debug E2EUpdateClock) → the next open makes exactly
     1 more GET (one GET per throttle window, GIF still ON), still no KLIPY request
  X3 one Libro de conexiones: the stored log and the settings page list the updater's
     "al abrir el teclado" entries next to the KLIPY entries, each with its host
  X4 GIF turned off again (prefs cleared, as «Apagar» does): opening the GIF tab with the update
     check ON makes no KLIPY request and no update GET (inside the window)
Requests are counted from logcat ("update check: GET release.json", "klipy: GET/POST https").
KLIPY API budget: 1 call. Writes build/e2e-r11bc/r11bc-e2e.json + PNGs.
Usage: scripts/e2e_r11bc.py --serial SERIAL [--opens 20]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r11 as r11  # noqa: E402
import e2e_r11b as gif  # noqa: E402
import e2e_r11c as upd  # noqa: E402
from e2e import adb, sh, dump, PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r11bc')
r11.OUT = e2e.OUT = gif.OUT = upd.OUT = OUT
H = 3_600_000
rows = []
seen = []


def check(sec, name, ok, detail=''):
    rows.append({'section': sec, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:500]})
    print(('PASS ' if ok else 'FAIL ') + f'[{sec}] {name}' + ('  ' + str(detail)[:220] if detail else ''), flush=True)


def drain():
    """Update GET lines and KLIPY API lines since the last drain (logcat cleared after each read)."""
    log = adb('logcat', '-d', '-v', 'threadtime', check=False)
    sh('logcat -c')
    seen.append(log)
    gets = [l for l in log.splitlines() if 'update check: GET release.json' in l]
    kl = [l for l in log.splitlines() if 'klipy: GET https' in l or 'klipy: POST https' in l]
    return gets, kl, log


def wait_log(s, timeout=15.0):
    end = time.time() + timeout
    acc = ([], [], '')
    while time.time() < end:
        g, k, log = drain()
        acc = (acc[0] + g, acc[1] + k, acc[2] + log)
        if s in acc[2]:
            break
        time.sleep(0.7)
    return acc


def libro_rows():
    t = r11.settings_page('acerca')
    titles = []
    for _ in range(12):
        titles += [(n.get('text') or '') for n in t.iter('node') if n.get('package') == PKG and ' · ' in (n.get('text') or '')]
        sh('input swipe 540 1800 540 700 300')
        time.sleep(0.5)
        t = dump()
    return list(dict.fromkeys(titles))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--opens', type=int, default=20)
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    started = time.time()
    model = sh('getprop ro.product.model').strip()
    dp = sh(f'dumpsys package {PKG}')
    ver = re.search(r'versionName=(\S+)', dp)
    code = re.search(r'versionCode=(\d+)', dp)
    upd_at = re.search(r'lastUpdateTime=([^\n]+)', dp)
    print(f'✦ {model} {PKG} {ver.group(1) if ver else "?"} ({code.group(1) if code else "?"}) updated {upd_at.group(1).strip() if upd_at else "?"}', flush=True)
    previous = sh('settings get secure default_input_method').strip()
    sh('logcat -G 4M', check=False)
    try:
        # ── setup: update check ON, no stored attempt, GIF OFF, log kept ─────────
        r11.set_phone('update.auto', 'true')  # force-stops the package
        upd.reset_prefs_keep_log()
        r11.run_as(f'rm -f {gif.GIF_PREFS}')
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        time.sleep(0.8)
        b0 = gif.book()['total']
        drain()

        # ── X1: first open (the window's GET), GIF ON, then N-1 more opens ─────────
        gets, kl, _ = [], [], ''
        r11.fresh('text')
        g, k, _ = wait_log('update open-check:', 15)
        gets += g; kl += k
        pid0 = upd.pid()
        gif.open_gif_tab('rich')
        r11.press('Ver qué se envía')
        time.sleep(0.7)
        r11.press('Activar la búsqueda')
        items = gif.wait_for(lambda: gif.gif_items(), 15)
        time.sleep(2.0)
        g, k, _ = drain()
        gets += g; kl += k
        check('X1', 'GIF search turned ON from the keyboard: the trending grid shows', len(items) >= 6 and gif.gif_prefs().get('gif.on') == 'true', len(items))
        check('X1', 'turning GIF ON = exactly 1 KLIPY API request (trending)', len(kl) == 1 and '/gifs/trending?' in kl[0], [l.split('klipy: ', 1)[1][:90] for l in kl])
        gif.shot('r11bc-X1-gif-on.png')
        r11.press('Volver al teclado')
        pids = [pid0]
        kl_opens = []
        for i in range(a.opens - 1):
            r11.fresh('text' if i % 2 == 0 else 'multiline')
            pids.append(upd.pid())
        time.sleep(1.5)
        g, k, _ = drain()
        gets += g; kl_opens += k
        check('X1', f'GIF ON: {a.opens} keyboard opens in one process → exactly 1 update GET',
              len(gets) == 1 and 'auto=true' in gets[0] and len(set(pids)) == 1 and pids[0], {'gets': [l[-60:] for l in gets], 'pids': sorted(set(pids))})
        check('X1', 'the keyboard opens start no KLIPY request', kl_opens == [], [l.split('klipy: ', 1)[1][:90] for l in kl_opens])
        p = upd.prefs()
        check('X1', 'update attempt persisted (attemptAt, attemptOk=true)', 'name="attemptAt"' in p and '<boolean name="attemptOk" value="true"' in p, re.sub(r'\s+', ' ', p)[-160:])

        # ── X2: next throttle window → exactly one more GET ────────────────────────
        upd.rewind(13 * H)
        r11.fresh('text')
        g, k, _ = wait_log('update open-check:', 15)
        for _ in range(4):
            r11.fresh('multiline')
        time.sleep(1.5)
        g2, k2, _ = drain()
        g += g2; k += k2
        check('X2', 'stamp moved back 13 h, GIF still ON: 5 opens → exactly 1 more update GET, same pid',
              len(g) == 1 and upd.pid() == pid0, {'gets': [l[-60:] for l in g], 'pid': [pid0, upd.pid()]})
        check('X2', 'still no KLIPY request from keyboard opens', k == [], [l.split('klipy: ', 1)[1][:90] for l in k])

        # ── X3: one book, both features ────────────────────────────────────────────
        b = gif.book()
        items_b = b.get('items', [])
        kinds = [(it[1], it[2]) for it in items_b]
        check('X3', 'stored log: total grew by exactly 3 (2 update GETs + 1 KLIPY page)', b['total'] == b0 + 3, {'before': b0, 'after': b['total']})
        check('X3', 'stored log (v2): update "check/open" entries next to "gif_trending/user"',
              ('check', 'open') in kinds and ('gif_trending', 'user') in kinds and b.get('v') == 2, kinds[:6])
        titles = libro_rows()
        gif.shot('r11bc-X3-libro.png')
        t_upd = [t for t in titles if t == 'Consulta de versión · kv.resyst.cl · al abrir el teclado']
        t_gif = [t for t in titles if t == 'GIF en tendencia · KLIPY · tú lo pediste']
        check('X3', 'Libro page lists "Consulta de versión · kv.resyst.cl · al abrir el teclado"', bool(t_upd), titles[:8])
        check('X3', 'Libro page lists "GIF en tendencia · KLIPY · tú lo pediste"', bool(t_gif), titles[:8])
        sh('input keyevent KEYCODE_BACK')
        time.sleep(0.4)

        # ── X4: GIF OFF again, update ON: the GIF tab makes no request ──────────────
        sh(f'am force-stop {PKG}')
        r11.run_as(f'rm -f {gif.GIF_PREFS}')
        sh(f'ime set {IME}')
        drain()
        n0 = gif.uid_bytes()
        bt0 = gif.book()['total']
        gif.open_gif_tab('rich')
        kk = r11.km()
        time.sleep(3.0)
        g, k, _ = drain()
        check('X4', 'GIF OFF (update ON): the GIF tab shows the explainer, 0 KLIPY requests', 'Ver qué se envía y activar' in kk and k == [], [l[-80:] for l in k])
        check('X4', 'new process inside the window: 0 update GETs, book unchanged', g == [] and gif.book()['total'] == bt0, {'gets': len(g), 'book': [bt0, gif.book()['total']], 'bytes': [n0, gif.uid_bytes()]})
        r11.press('Volver al teclado')
    except Exception as ex:  # a crash is a failed row
        check('run', 'script ran to the end', False, repr(ex))
    finally:
        # leave the debug package as the r10/r11b runs do: update check off, GIF off
        r11.set_phone('update.auto', 'false')
        r11.run_as(f'rm -f {gif.GIF_PREFS}')
        if previous and previous != 'null':
            sh(f'ime set {previous}', check=False)
        alllog = '\n'.join(seen)
        api = sorted({l for l in alllog.splitlines() if 'klipy: GET https' in l or 'klipy: POST https' in l})
        ugets = sorted({l for l in alllog.splitlines() if 'update check: GET release.json' in l})
        key = open(gif.KEY_FILE).read().strip() if os.path.exists(gif.KEY_FILE) else ''
        if key:
            check('run', 'the KLIPY key never appears in the captured logcat', key not in alllog, f'{len(alllog.splitlines())} lines')
        passed = sum(r['ok'] for r in rows)
        out = {'device': model, 'serial': a.serial, 'package': PKG, 'version': ver.group(1) if ver else None,
               'versionCode': code.group(1) if code else None, 'lastUpdateTime': upd_at.group(1).strip() if upd_at else None,
               'passed': passed, 'total': len(rows), 'seconds': round(time.time() - started, 1),
               'klipy_api_calls': len(api), 'klipy_api_lines': [l.split('klipy: ', 1)[1] for l in api],
               'update_gets': len(ugets), 'update_get_lines': [l[-80:] for l in ugets], 'rows': rows}
        json.dump(out, open(os.path.join(OUT, 'r11bc-e2e.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {passed}/{len(rows)} · KLIPY API calls: {len(api)} · update GETs: {len(ugets)} → build/e2e-r11bc/r11bc-e2e.json', flush=True)
    return 0 if rows and passed == len(rows) else 1


if __name__ == '__main__':
    sys.exit(main())
