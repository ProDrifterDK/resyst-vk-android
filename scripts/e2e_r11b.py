#!/usr/bin/env python3
"""✦ Resyst VK — r11b E2E: the opt-in GIF search (KLIPY) on a real device.

Drives the debug E2E host through the keyboard's accessibility tree (same helpers as e2e.py).
Three independent oracles for "what went out": the app's own Libro de conexiones (read from its
prefs), the debug client's logcat lines ("klipy: GET/POST …", key and id redacted), and the
kernel's per-UID traffic counters (dumpsys netstats) for the debug package.

  G1 OFF by default: the setting reads "desactivado", the GIF tab shows the explainer, and opening
     it makes zero requests (book total, logcat and the UID's bytes unchanged)
  G2 settings: the toggle opens the disclosure; «Cancelar» leaves it OFF
  G3 keyboard: «Ver qué se envía y activar» shows the disclosure, still no request; «Activar» →
     ONE trending request; the grid renders animated previews (decoded AnimatedImageDrawable,
     pixels change between two frames); hiding the panel stops the animations
  G4 pagination: scrolling to the end asks for exactly one more page
  G5 search: a sentinel typed in the app field stays there; the GIF box query goes out exactly
     (q=…) and nothing from the field; the query is not learned
  G6 a GIF tapped in a field that takes images is committed (image/gif bytes, GIF89a) and KLIPY's
     share trigger is sent; one temporary file at most
  G7 a field that rejects images: the message, no download, no URL pasted
  G8 password and incognito fields: no GIF tab
  G9 offline: one calm line + «Reintentar», logged; no automatic retry
  G10 the Libro de conexiones lists every request (KLIPY next to the updater's old entries)
  G11 «Nuevo ID anónimo» changes the id; G12 «Apagar» deletes it and stops all requests
  G13 the key never appears in the captured logcat

Artifacts: build/e2e-r11b/r11b-e2e.json, logcat.txt (key-scrubbed check), PNGs.
API budget: ~6 KLIPY API calls per full run (the testing key allows 100/hour for everyone).
Usage: scripts/e2e_r11b.py --serial SERIAL [--keep-on]
"""
import argparse
import html
import json
import os
import re
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r11 as r11  # noqa: E402
from e2e import adb, sh, dump, keys, center, bounds, PKG, IME  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r11b')
r11.OUT = e2e.OUT = OUT
KEY_FILE = os.path.join(e2e.ROOT, 'keystore', 'klipy.key')
GIF_PREFS = 'shared_prefs/resyst_vk_gif.xml'
UPDATE_PREFS = 'shared_prefs/resyst_vk_update.xml'
rows = []
facts = {}


def check(name, ok, detail=''):
    rows.append({'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail)[:240] if detail else ''), flush=True)


def shot(name):
    r11.shot(name)


km = r11.km
press = r11.press
run_as = r11.run_as


def has(prefix, k=None):
    k = k if k is not None else km()
    return next((d for d in k if d.startswith(prefix)), None)


def wait_for(pred, timeout=12.0, step=0.7):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(step)
    return pred()


# ── oracles ──────────────────────────────────────────────────────────────
def book():
    raw = run_as(f'cat {UPDATE_PREFS} 2>/dev/null')
    m = re.search(r'<string name="connections">(.*?)</string>', raw, re.S)
    if not m:
        return {'total': 0, 'items': []}
    return json.loads(html.unescape(m.group(1)))


def gif_prefs():
    raw = run_as(f'cat {GIF_PREFS} 2>/dev/null')
    return dict(re.findall(r'<string name="([^"]+)">([^<]*)</string>', raw))


def uid():
    out = sh(f'pm list packages -U {PKG}')
    m = re.search(rf'package:{re.escape(PKG)} uid:(\d+)', out)
    return m.group(1) if m else None


def uid_bytes():
    """rx+tx bytes the kernel counted for the debug package's UID (all buckets, untagged)."""
    sh('dumpsys netstats --poll >/dev/null 2>&1', check=False)
    out = sh('dumpsys netstats detail', check=False)
    u = facts.setdefault('uid', uid())
    total = 0
    sect = out.split('UID stats:')[1].split('UID tag stats:')[0] if 'UID stats:' in out else ''
    for block in re.split(r'\n  ident=', sect):
        if f' uid={u} ' not in block:
            continue
        for rb, tb in re.findall(r'rb=(\d+) rp=\d+ tb=(\d+)', block):
            total += int(rb) + int(tb)
    return total


def logcat():
    return adb('logcat', '-d', '-v', 'threadtime', check=False)


def klipy_lines(log=None):
    log = log if log is not None else logcat()
    return [ln.split('klipy: ', 1)[1].strip() for ln in log.splitlines() if 'klipy: ' in ln]


def gif_entries(b):
    return [it for it in b.get('items', []) if str(it[1]).startswith('gif_')]


# ── raw frames for the animation check ─────────────────────────────────
def frame():
    raw = adb('exec-out', 'screencap', binary=True)
    w, h, _fmt = struct.unpack('<III', raw[:12])
    hdr = 16 if len(raw) - 16 == w * h * 4 else 12
    return w, h, raw[hdr:]


def region_diff(a, b, box):
    w, _h, pa = a
    _, _, pb = b
    x1, y1, x2, y2 = box
    changed = 0
    for y in range(y1, y2, 4):
        s = (y * w + x1) * 4
        e = (y * w + x2) * 4
        ra, rb = pa[s:e], pb[s:e]
        if ra != rb:
            changed += sum(1 for i in range(0, len(ra), 16) if ra[i:i + 3] != rb[i:i + 3])
    return changed


# ── flows ────────────────────────────────────────────────────────────────
def open_gif_tab(kind='rich'):
    r11.fresh(kind)
    press('Emojis')
    time.sleep(0.8)
    ok = r11.select_tab('GIF')
    time.sleep(0.6)
    return ok


def gif_items(k=None):
    k = k if k is not None else km()
    return [(d, n) for d, n in k.items() if d.startswith('GIF: ')]


def gif_cells():
    return [(d, n) for n in dump().iter('node') if (d := n.get('content-desc') or '').startswith('GIF: ') and n.get('package') == PKG]


def settings_toggle():
    t = r11.settings_page('privacidad')
    return t, r11.node_where(t, lambda d, _: d.startswith('Búsqueda de GIF (KLIPY)'))


def reset_state():
    sh(f'am force-stop {PKG}')
    r11.set_phone('update.auto', 'false')  # the updater's startup check stays out of the counts
    run_as(f'rm -f {GIF_PREFS}; rm -f cache/clip/gif.*')
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    time.sleep(0.8)


def off_by_default():
    reset_state()
    sh('logcat -c')
    b0, n0 = book()['total'], uid_bytes()
    t, row = settings_toggle()
    shot('r11b-G1-setting-off.png')
    desc = row.get('content-desc') if row is not None else None
    check('G1 the setting exists and is OFF by default', desc is not None and desc.endswith('desactivado'), desc)
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.5)
    check('G1 the emoji panel has a GIF tab in a normal field', open_gif_tab('rich'))
    k = km()
    shot('r11b-G1-tab-off.png')
    check('G1 OFF: the GIF tab shows the explainer + its button, no search box, no grid',
          'Ver qué se envía y activar' in k and not has('Buscar en KLIPY', k) and not gif_items(k), sorted(k)[-6:])
    check('G1 the KLIPY attribution is visible in the GIF tab', 'Powered by KLIPY' in k)
    time.sleep(3.0)
    b1, n1 = book()['total'], uid_bytes()
    lines = klipy_lines()
    check('G1 OFF makes zero requests: book total unchanged', b1 == b0, {'before': b0, 'after': b1})
    check('G1 OFF makes zero requests: no klipy line in logcat', lines == [], lines[:3])
    check('G1 OFF makes zero requests: the UID sent/received 0 bytes (netstats)', n1 == n0, {'before': n0, 'after': n1, 'uid': facts.get('uid')})
    facts['G1'] = {'book': b0, 'bytes': n0}


def settings_cancel():
    t, row = settings_toggle()
    if row is None:
        check('G2 settings toggle present', False)
        return
    r11.tap_node(row, 1.0)
    t = dump()
    texts = ' '.join((n.get('text') or '') for n in t.iter('node'))
    shot('r11b-G2-settings-disclosure.png')
    check('G2 settings: the toggle shows the disclosure first (what, to whom, the book, how to stop)',
          all(s in texts for s in ('buscador de GIF', 'KLIPY', 'nunca se envía', 'Libro de conexiones', 'Apágalo')), texts[:300])
    btn = r11.node_where(t, lambda _, tt: tt.lower() == 'cancelar')
    if btn is not None:
        r11.tap_node(btn, 0.8)
    _, row = settings_toggle()
    p = gif_prefs()
    check('G2 «Cancelar» leaves it OFF (row + stored state, no id)', row is not None and row.get('content-desc', '').endswith('desactivado') and 'gif.id' not in p, p)
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.5)


def enable_from_keyboard():
    sh('logcat -c')
    b0 = book()['total']
    open_gif_tab('rich')
    press('Ver qué se envía')
    time.sleep(0.7)
    k = km()
    disc = has('Búsqueda de GIF con KLIPY.', k)
    shot('r11b-G3-disclosure.png')
    check('G3 the disclosure is shown before anything is sent', disc is not None and 'Activar la búsqueda de GIF con KLIPY' in k, (disc or '')[:200])
    check('G3 the disclosure says what is sent, to whom, never the app text, the book, how to stop',
          disc is not None and all(s in disc for s in ('buscador de GIF', 'ID anónimo', 'país', 'otra empresa', 'nunca se envía', 'Libro de conexiones', 'Apágalo')))
    time.sleep(1.0)
    check('G3 no request while the disclosure is open', klipy_lines() == [] and book()['total'] == b0)
    press('Activar la búsqueda')
    items = wait_for(lambda: gif_items(), 15)
    p = gif_prefs()
    facts['id1'] = p.get('gif.id')
    check('G3 «Activar» stores consent + a random v4 UUID', p.get('gif.on') == 'true' and re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', p.get('gif.id', '')) is not None,
          {k: (v[:8] + '…' if k == 'gif.id' else v) for k, v in p.items()})
    check('G3 ON: the trending grid shows KLIPY results', len(items) >= 6, len(items))
    time.sleep(4.0)
    log = logcat()
    gets = [ln for ln in klipy_lines(log) if ln.startswith('GET https')]
    check('G3 exactly one trending request', len(gets) == 1 and '/gifs/trending?' in gets[0], gets)
    check('G3 the request carries the anonymous id, region and safety level (redacted in the log)',
          gets and '{key}' in gets[0] and 'customer_id={id}' in gets[0] and 'content_filter=high' in gets[0] and 'locale=' in gets[0], gets[:1])
    animated = len(re.findall(r'gif: thumb decoded animated', log))
    check('G3 previews are decoded as animated images (ImageDecoder → AnimatedImageDrawable)', animated >= 4, animated)
    running = [int(x) for x in re.findall(r'gif: animating (\d+)', log)]
    check('G3 the visible previews animate', running and max(running) >= 3, running[-5:])
    k = km()
    top = min(bounds(n)[1] for d, n in gif_items(k)) if gif_items(k) else 1500
    abc = k.get('Volver al teclado')
    bottom = bounds(abc)[1] - 10 if abc is not None else top + 400
    f1 = frame()
    time.sleep(0.45)
    f2 = frame()
    diff = region_diff(f1, f2, (60, top + 10, f1[0] - 60, bottom))
    shot('r11b-G3-trending.png')
    check('G3 the grid pixels change between two frames 450 ms apart (animated, not static)', diff > 200, diff)
    b = book()
    tr = [it for it in gif_entries(b) if it[1] == 'gif_trending']
    check('G3 the trending request is one book entry', b['total'] == b0 + 1 and len(tr) >= 1, {'before': b0, 'after': b['total']})
    time.sleep(1.5)
    tr = [it for it in gif_entries(book()) if it[1] == 'gif_trending']
    check('G3 its thumbnails are counted on that same entry (no entry per thumbnail)', tr and tr[0][4] >= 4 and book()['total'] == b0 + 1, tr[:1])
    # pause: hiding the panel stops every animation
    press('Volver al teclado')
    time.sleep(0.8)
    running = [int(x) for x in re.findall(r'gif: animating (\d+)', logcat())]
    check('G3 hiding the panel stops the animations (animating 0)', running and running[-1] == 0, running[-3:])


def paginate():
    sh('logcat -c')
    press('Emojis')
    time.sleep(0.6)
    r11.select_tab('GIF')
    time.sleep(1.0)
    check('G4 back on the GIF tab, the loaded grid is reused (no new request)', [ln for ln in klipy_lines() if ln.startswith('GET')] == [])
    for _ in range(10):
        if any('page=2' in ln for ln in klipy_lines()):
            break
        r11.scroll_grid(0.7, 350)
    time.sleep(3.0)
    gets = [ln for ln in klipy_lines() if ln.startswith('GET')]
    check('G4 scrolling to the end asks for exactly one next page', len(gets) == 1 and 'page=2' in gets[0], gets)


SENTINEL = 'confidencial'
QUERY = 'capibara'


def search():
    r11.fresh('rich')
    r11.words_typed(SENTINEL, end_space=False)
    before = r11.field()
    check('G5 precondition: the app field holds the sentinel', SENTINEL in before.lower(), repr(before))
    sh('logcat -c')
    press('Emojis')
    time.sleep(0.6)
    r11.select_tab('GIF')
    wait_for(lambda: gif_items(), 12)  # a new field: trending again (one request)
    box = has('Buscar en KLIPY')
    check('G5 the search box says «Buscar en KLIPY» (KLIPY placeholder)', box == 'Buscar en KLIPY', box)
    press('Buscar en KLIPY')
    time.sleep(0.8)
    k = km()
    check('G5 the GIF search box opens on the keys (not the app field)', 'Búsqueda de GIF: vacía' in k and 'Buscar GIF' in k, [d for d in k if 'GIF' in d])
    e2e.type_word(QUERY)
    time.sleep(0.3)
    k = km()
    shot('r11b-G5-typing.png')
    check('G5 the typed query is in the GIF box', f'Búsqueda de GIF: {QUERY}' in k, [d for d in k if d.startswith('Búsqueda de GIF')])
    check('G5 nothing typed in the GIF box reached the app field', r11.field() == before, repr(r11.field()))
    check('G5 no request while typing (search runs on the search key only)', not any('/gifs/search' in ln for ln in klipy_lines()))
    press('Buscar GIF')
    items = wait_for(lambda: gif_items(), 12)
    time.sleep(1.5)
    gets = [ln for ln in klipy_lines() if '/gifs/search' in ln]
    shot('r11b-G5-results.png')
    check('G5 one search request with exactly the GIF box query', len(gets) == 1 and f'&q={QUERY}&' in gets[0], gets)
    check('G5 nothing from the app field is sent', gets and not any(SENTINEL in ln.lower() for ln in klipy_lines()), gets)
    check('G5 the results grid is shown with the query in the box', len(items) >= 3 and has(f'Buscar en KLIPY: {QUERY}') is not None, len(items))
    check('G5 the app field is still exactly as typed', r11.field() == before, repr(r11.field()))
    facts['search_line'] = gets[0] if gets else None


def pick_rich():
    sh('logcat -c')
    run_as('ls cache/clip')
    cells = gif_cells()
    if not cells:
        check('G6 a GIF to tap', False)
        return
    d, n = cells[0]
    r11.tap_node(n, 0.5)
    ok = wait_for(lambda: re.search(r'commitContent mime=(\S+) bytes=(\d+) head=(\S*)', logcat()), 15)
    time.sleep(2.5)
    log = logcat()
    shot('r11b-G6-committed.png')
    m = re.search(r'commitContent mime=(\S+) bytes=(\d+) head=(\S*)', log)
    check('G6 tapping a GIF commits image content into the field', m is not None and m.group(1) in ('image/gif', 'image/webp') and int(m.group(2)) > 1000,
          m.group(0) if m else 'no commitContent')
    check('G6 the bytes are a real GIF/WebP (magic header)', m is not None and (m.group(3).startswith('GIF8') or m.group(3).startswith('RIFF')), m.group(3) if m else None)
    check('G6 the field shows the animated GIF it received', 'shown AnimatedImageDrawable' in log, re.findall(r'shown \S+', log)[-1:])
    check('G6 the service saw commitContent succeed', 'gif: commitContent' in log and '→ true' in log, re.findall(r'gif: commitContent.*', log)[-1:])
    posts = [ln for ln in klipy_lines(log) if ln.startswith('POST')]
    check('G6 KLIPY\'s share trigger was sent for the committed GIF', len(posts) == 1 and '/gifs/share/' in posts[0], posts)
    b = book()
    kinds = [it[1] for it in b['items'][:3]]
    check('G6 the file download and the share trigger are in the book', 'gif_file' in kinds and 'gif_share' in kinds, b['items'][:3])
    files = run_as('ls cache/clip 2>/dev/null').split()
    check('G6 at most one temporary GIF file is kept', len([f for f in files if f.startswith('gif.')]) <= 1, files)


def pick_rejected():
    sh('logcat -c')
    b0 = book()['total']
    open_gif_tab('text')
    items = wait_for(lambda: gif_cells(), 12)
    before = r11.field()
    if not items:
        check('G7 a GIF to tap in the plain field', False)
        return
    r11.tap_node(items[0][1], 1.2)
    log = logcat()
    shot('r11b-G7-rejected.png')
    check('G7 a field that rejects images shows the message', 'gif: notice "Este campo no acepta GIF"' in log, re.findall(r'gif: notice.*', log))
    check('G7 nothing is pasted (no URL, no text)', r11.field() == before and 'klipy' not in r11.field().lower(), repr(r11.field()))
    check('G7 no download and no share for a GIF the field cannot take', not any(ln.startswith('GET media') or ln.startswith('POST') for ln in klipy_lines(log)), klipy_lines(log))
    check('G7 only the trending page was requested (one entry)', book()['total'] == b0 + 1, {'before': b0, 'after': book()['total']})


def no_tab_in_secret():
    r11.fresh('password')
    k = km()
    check('G8 password field: no emoji key, so no GIF tab', 'Emojis' not in k and not has('GIF', k), sorted(k)[:8])
    r11.fresh('incognito')
    press('Emojis')
    time.sleep(0.6)
    tabs = [d for d, _ in r11.tabs()]
    shot('r11b-G8-incognito-tabs.png')
    check('G8 incognito field: the emoji panel has no GIF tab', not any(t.startswith('GIF') for t in tabs), tabs)


def offline():
    sh('logcat -c')
    sh('cmd connectivity airplane-mode enable', check=False)
    time.sleep(3.0)
    try:
        open_gif_tab('rich')
        retry = wait_for(lambda: 'Reintentar' in km(), 15)
        log = logcat()
        shot('r11b-G9-offline.png')
        check('G9 offline: one calm line with «Reintentar»', retry, re.findall(r'gif: page 1 failed.*', log))
        check('G9 offline is reported as "Sin conexión"', 'gif: page 1 failed → OFFLINE' in log, re.findall(r'gif: page.*', log))
        b = book()
        check('G9 the failed request is in the book with its outcome', b['items'] and b['items'][0][1] == 'gif_trending' and 'Sin conexión' in b['items'][0][3], b['items'][:1])
    finally:
        sh('cmd connectivity airplane-mode disable', check=False)
    # back online (no KLIPY call: an ICMP ping to a public resolver), then a few quiet seconds
    online = wait_for(lambda: ' 0% packet loss' in sh('ping -c 1 -W 2 1.1.1.1', check=False), 40, 2.0)
    facts['back_online'] = bool(online)
    time.sleep(5.0)
    n = len([ln for ln in klipy_lines() if ln.startswith('GET')])
    check('G9 no automatic retry after the network is back', n == 1, n)
    if 'Reintentar' in km():
        press('Reintentar')
        items = wait_for(lambda: gif_items(), 15)
        check('G9 «Reintentar» (a tap) loads the page', len(items) >= 3, len(items))


def connections_page():
    t = r11.settings_page('acerca')
    descs = []
    for _ in range(8):
        descs += [n.get('content-desc') for n in t.iter('node') if (n.get('content-desc') or '') and (n.get('content-desc') or '').split(',')[0] in
                  ('GIF en tendencia', 'Búsqueda de GIF', 'Descarga del GIF elegido', 'Aviso de GIF enviado', 'Consulta de versión', 'Descarga de actualización')]
        sh('input swipe 540 1800 540 700 300')
        time.sleep(0.5)
        t = dump()
    descs = list(dict.fromkeys(descs))
    shot('r11b-G10-book.png')
    total = r11.node_where(r11.settings_page('acerca'), lambda _, tt: 'conexiones a internet desde la instalación' in tt)
    shot('r11b-G10-book-top.png')
    b = book()
    facts['book_total'] = b['total']
    check('G10 the book header counts every request', total is not None and str(b['total']) in (total.get('text') or ''), total.get('text') if total is not None else None)
    kinds = {d.split(',')[0] for d in descs}
    check('G10 KLIPY requests are listed, each with its host', {'GIF en tendencia', 'Búsqueda de GIF', 'Descarga del GIF elegido', 'Aviso de GIF enviado'} <= kinds and
          all(', a KLIPY,' in d for d in descs if d.startswith(('GIF', 'Búsqueda', 'Descarga del GIF', 'Aviso'))), sorted(kinds))
    check('G10 the search entry shows the query the user typed', any(d.startswith('Búsqueda de GIF') and f'«{QUERY}»' in d for d in descs), [d for d in descs if d.startswith('Búsqueda')][:2])
    check('G10 the updater\'s older entries still read next to them (v1 → v2 log)', any(d.startswith(('Consulta de versión', 'Descarga de actualización')) and 'kv.resyst.cl' in d for d in descs),
          [d for d in descs if 'kv.resyst.cl' in d][:2])
    check('G10 a page entry carries its thumbnail count', any(d.startswith('GIF en tendencia') and 'archivos' in d for d in descs), [d for d in descs if 'archivos' in d][:2])
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.5)


def new_id_and_off():
    id1 = gif_prefs().get('gif.id')
    t, row = settings_toggle()
    link = r11.node_where(t, lambda _, tt: tt.startswith('Nuevo ID anónimo'))
    shown1 = r11.node_where(t, lambda _, tt: tt.startswith('ID actual:'))
    check('G11 «Nuevo ID anónimo» is offered while ON', link is not None and row is not None and row.get('content-desc', '').endswith('activado'))
    if link is not None:
        r11.tap_node(link, 1.0)
    id2 = gif_prefs().get('gif.id')
    t = dump()
    shown2 = r11.node_where(t, lambda _, tt: tt.startswith('ID actual:'))
    shot('r11b-G11-new-id.png')
    check('G11 «Nuevo ID anónimo» changes the id (stored and shown)', id1 and id2 and id1 != id2 and shown1 is not None and shown2 is not None and shown1.get('text') != shown2.get('text'),
          {'before': (id1 or '')[:8], 'after': (id2 or '')[:8]})
    facts['ids_changed'] = bool(id1 and id2 and id1 != id2)
    _, row = settings_toggle()
    if row is not None:
        r11.tap_node(row, 1.0)
    p = gif_prefs()
    _, row = settings_toggle()
    shot('r11b-G12-off.png')
    check('G12 «Apagar» deletes the id and the consent', 'gif.id' not in p and 'gif.on' not in p, p)
    check('G12 the setting reads OFF again', row is not None and row.get('content-desc', '').endswith('desactivado'))
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.4)
    sh('logcat -c')
    b0, n0 = book()['total'], uid_bytes()
    open_gif_tab('rich')
    k = km()
    time.sleep(3.0)
    shot('r11b-G12-tab-off-again.png')
    check('G12 OFF again: the tab shows the explainer, no grid', 'Ver qué se envía y activar' in k and not gif_items(k))
    b1, n1 = book()['total'], uid_bytes()
    check('G12 OFF again: zero requests (book, logcat, netstats)', b1 == b0 and klipy_lines() == [] and n1 == n0, {'book': [b0, b1], 'bytes': [n0, n1]})


def key_scan(log_all):
    key = open(KEY_FILE).read().strip() if os.path.exists(KEY_FILE) else ''
    ids = [i for i in (facts.get('id1'), gif_prefs().get('gif.id')) if i]
    path = os.path.join(OUT, 'logcat.txt')
    open(path, 'w', encoding='utf-8').write(log_all.replace(key, '<<KEY-FOUND>>') if key else log_all)
    check('G13 the KLIPY key never appears in the captured logcat', bool(key) and key not in log_all, f'{len(log_all.splitlines())} lines scanned')
    check('G13 the anonymous id never appears in logcat', not any(i in log_all for i in ids), len(ids))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--keep-on', action='store_true', help='leave the GIF search on (demo recording)')
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    previous = sh('settings get secure default_input_method').strip()
    started = time.time()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    version = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}', check=False))
    print(f'✦ device: {model} (API {sdk}) {PKG} {version.group(1) if version else "?"}', flush=True)
    sh('logcat -G 4M', check=False)
    captured = []
    t0 = book()['total'] if run_as(f'ls {UPDATE_PREFS} 2>/dev/null').strip() else 0

    def keep_log():
        captured.append(logcat())

    try:
        for step in (off_by_default, settings_cancel, enable_from_keyboard, paginate, search, pick_rich,
                     pick_rejected, no_tab_in_secret, offline, connections_page, new_id_and_off):
            step()
            keep_log()
    except Exception as ex:  # a crash is a failed row, never a silent pass
        check('script ran to the end', False, repr(ex))
        keep_log()
    finally:
        sh('cmd connectivity airplane-mode disable', check=False)
        try:
            key_scan('\n'.join(captured))
        except Exception as ex:
            check('G13 logcat scan ran', False, repr(ex))
        # the captures overlap (logcat is not cleared before every step): count each timestamped line once
        api = sorted({ln for ln in '\n'.join(captured).splitlines() if 'klipy: GET https' in ln or 'klipy: POST https' in ln})
        b = book()
        if previous and previous != 'null' and previous != IME:
            sh(f'ime set {previous}', check=False)
        passed = sum(r['ok'] for r in rows)
        out = {'device': model, 'api': sdk, 'package': PKG, 'version': version.group(1) if version else None,
               'passed': passed, 'total': len(rows), 'seconds': round(time.time() - started, 1),
               'klipy_api_calls': len(api), 'klipy_api_lines': [ln.split('klipy: ', 1)[1] for ln in api], 'book_total_before': t0, 'book_total_after': b['total'],
               'search_request': facts.get('search_line'), 'rows': rows}
        json.dump(out, open(os.path.join(OUT, 'r11b-e2e.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {passed}/{len(rows)} · KLIPY API calls this run: {len(api)} → build/e2e-r11b/r11b-e2e.json', flush=True)
    return 0 if rows and passed == len(rows) else 1


if __name__ == '__main__':
    sys.exit(main())
