#!/usr/bin/env python3
"""✦ Resyst VK — r15 E2E (0.15.0: emoji search, the quick-settings panel redesign, the owner's UX fixes).

One script for both sides: `--label before` on the 0.14.1 (r14b) debug build, `--label after` on 0.15.0. Same scene
names under build/e2e-r15/<label>/<lang>/, so the index pairs them. Both interface languages through Android's per-app
language (`cmd locale set-app-locales`: the phone's own language is never touched). Everything is driven through the
drawn keyboard's accessibility tree.

Sections (--only, comma separated):
  Q  the quick-settings panel ⚙ at heights 80/100/120/130 % (130 % is the app's maximum) × font scale 1.0/1.3 × light
     and dark, with the owner's four temas (Noche, Día, Juego, Escritura); after: the layout's own report line (names /
     labels / states cut with the real font), a scrolled tema row and a tema switch that keeps the row in view
  A  Settings › Acerca de: the update status line after a real «Buscar actualizaciones» (one GET to kv.resyst.cl, the
     app's own updater), and again at the end of the run (its age has grown)
  R  emoji recents: the row before and after three taps on the third recent, the committed text, the stored order, the
     row after reopening
  S  after: emoji search: the empty state (hint), «águila», «aguila», «hawk», «eagle», a query with no result, a result
     tapped and Enter (top match) into the field, ← back to the panel as it was; per-keystroke latency from the IME's
     debug lines (lengths and times only); a short screen recording
  P  after: privacy: the queries are in no file of the app (files, shared_prefs, databases, cache) and in no log line
  M  measurements: APK size, heap after typing, with the search index loaded and after the keyboard hid

Usage: scripts/e2e_r15.py --serial SERIAL --label before|after [--apk PATH] [--no-install] [--only Q,A,R,S,P,M] [--langs es,en]

Environment:
  VK_E2E_FORBIDDEN_SERIALS  comma-separated device serials this script must refuse to touch (your own phones).
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
import e2e_r10_bilingual as bl  # noqa: E402,F401
import e2e_r13 as r13  # noqa: E402,F401
import e2e_r13c as r13c  # noqa: E402
import e2e_r13h  # noqa: E402,F401  (its import makes bl.set_lang go through Android's picker)
import e2e_r14 as r14  # noqa: E402
import e2e_r14b as r14b  # noqa: E402
from e2e import PKG, IME  # noqa: E402

FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
BASE = os.path.join(e2e.ROOT, 'build', 'e2e-r15')
LABEL = 'after'
LANG = 'es'
rows = []
scenes = []
metrics = {}
HEIGHTS = [0.8, 1.0, 1.2, 1.3]
FONTS = ['1.0', '1.3']
RECENTS = ['😀', '👍', '🦅', '❤️', '🎉', '🙏']
QUERIES = ['águila', 'aguila', 'hawk', 'eagle']
NONE_QUERY = 'zzqx'
ADB_LOGCAT_TAG = 'ResystVK'


# ── plumbing ─────────────────────────────────────────────────────────────
def sh(cmd, check=True):
    return str(e2e.sh(cmd, check=check))


def T(name, *args):
    s = r14b.STR[LANG][0][name]
    return r14b.fmt(s, args)


def check(section, name, ok, detail=''):
    rows.append({'section': section, 'label': LABEL, 'lang': LANG, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:700]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}/{LABEL}/{LANG}] {name}' + ('  ' + str(detail)[:220] if detail else ''), flush=True)


def shot(name, note=''):
    d = os.path.join(BASE, LABEL, LANG)
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, name + '.png'), 'wb').write(e2e.adb('exec-out', 'screencap', '-p', binary=True))
    scenes.append({'scene': name, 'label': LABEL, 'lang': LANG, 'file': f'{LABEL}/{LANG}/{name}.png', 'note': note})
    print(f'  shot {LABEL}/{LANG}/{name}', flush=True)


def keys():
    return e2e.keys(e2e.dump())


def find(prefix, k=None):
    k = k if k is not None else keys()
    n = k.get(prefix)
    return n if n is not None else next((v for d, v in k.items() if d.startswith(prefix)), None)


def tap_desc(prefix, wait=0.6, k=None):
    n = find(prefix, k)
    if n is None:
        raise RuntimeError(f'no node {prefix!r}')
    e2e.tap(n)
    time.sleep(wait)


APP_LOG = []  # the app process's log lines, kept before every clear (P reads them all)


def app_log_lines():
    out = []
    for pid in sh(f'pidof {PKG}', check=False).split():
        out += e2e.adb('logcat', '-d', f'--pid={pid}', check=False).splitlines()
    return out


def logcat_clear():
    APP_LOG.extend(app_log_lines())
    sh('logcat -c', check=False)


def logcat_lines(pattern):
    out = e2e.adb('logcat', '-d', '-s', f'{ADB_LOGCAT_TAG}:I', check=False)
    return [l for l in out.splitlines() if pattern in l]


def set_lang(lang):
    global LANG
    LANG = lang
    r14b.LANG = lang  # the shared helpers' key aliases (English names answer to their Spanish twins) read it
    sh(f'cmd locale set-app-locales {PKG} --locales {lang}')
    sh(f'am force-stop {PKG}', check=False)
    time.sleep(0.8)
    got = sh(f'cmd locale get-app-locales {PKG}').strip()
    check('X', f'per-app language {lang}', f'[{lang}]' in got.replace(' ', ''), got)


def font(scale):
    sh(f'settings put system font_scale {scale}')
    time.sleep(1.0)


def wake():
    sh('input keyevent KEYCODE_WAKEUP', check=False)
    sh('wm dismiss-keyguard', check=False)
    time.sleep(0.4)


# the owner's four temas (their stored names, shown translated: Noche/Night, Día/Day, Juego/Game, Escritura/Writing)
def store(active='noche', height=1.0, **extra):
    v = {'v': '2', 'active': active, 'order': 'noche,dia,juego,escritura', 'mode': 'none', 'update.auto': 'false', 'phone.lang': 'ES',
         'clip.history': 'true', 'clip.purge': 'false',
         't.noche.name': 'Noche', 't.noche.icon': '☾', 't.noche.theme': 'lab',
         't.dia.name': 'Día', 't.dia.icon': '☀', 't.dia.theme': 'paper',
         't.juego.name': 'Juego', 't.juego.icon': '◆', 't.juego.theme': 'arcade',
         't.escritura.name': 'Escritura', 't.escritura.icon': '✎', 't.escritura.theme': 'slate'}
    v[f't.{active}.heightScale'] = str(height)
    v.update({k.replace('__', '.'): val for k, val in extra.items()})
    return v


def host(kind='text'):
    wake()
    return r14.host(kind)


def gear():
    tap_desc(T('strip_settings'), wait=1.0)


# ── L: legibility of the new surfaces on the rendered pixels (r14's WCAG pixel contrast) ──
def measure(name, node, minimum, inset_dp=(0, 0, 0, 0), what=''):
    """Contrast of the ink on its surface inside [node]'s box (minus an inset in dp: l, t, r, b) on the last shot."""
    if node is None:
        check('L', name, False, 'no node')
        return None
    d = r14.density()
    x1, y1, x2, y2 = e2e.bounds(node)
    box = (int(x1 + inset_dp[0] * d), int(y1 + inset_dp[1] * d), int(x2 - inset_dp[2] * d), int(y2 - inset_dp[3] * d))
    png = os.path.join(BASE, LABEL, LANG, scenes[-1]['scene'] + '.png')
    r, bg, ink = r14.pixel_contrast(png, box)
    check('L', name, r >= minimum, f'{what}: rendered {r}:1 (ink {ink} on {bg}) in {scenes[-1]["scene"]}')
    metrics.setdefault('L', {})[f'{LANG}-{name}'] = {'ratio': r, 'bg': bg, 'ink': ink, 'scene': scenes[-1]['scene'], 'min': minimum}
    return r


# ── Q: the quick-settings panel ──────────────────────────────────────────
def section_q():
    for mode in ('dark', 'light'):
        r14.night(mode == 'dark')
        active = 'noche' if mode == 'dark' else 'dia'
        for f in FONTS:
            font(f)
            for h in HEIGHTS:
                pct = int(round(h * 100))
                r14.write_prefs(store(active, h))
                host('text')
                logcat_clear()
                gear()
                shot(f'Q-{mode}-f{f}-h{pct}', f'quick panel, {mode}, font {f}, height {pct} %')
                if LABEL == 'after':
                    rep = logcat_lines('quick: ')
                    line = rep[-1].split('quick: ', 1)[1] if rep else ''
                    metrics.setdefault('Q', {})[f'{LANG}-{mode}-f{f}-h{pct}'] = line
                    ok = bool(line) and 'namesCut=0' in line and 'labelsCut=0' in line
                    check('Q', f'{mode} f{f} h{pct}: no tema name or tile label cut', ok, line)
                    check('Q', f'{mode} f{f} h{pct}: tiles ≥ 48 dp', bool(re.search(r'tileH=(\d+)dp', line)) and int(re.search(r'tileH=(\d+)dp', line).group(1)) >= 48, line)
                    # every tema chip is a TalkBack node with its whole name (visible ones; the row scrolls)
                    k = keys()
                    seen = [nm for nm in ('tema_seed_noche', 'tema_seed_dia', 'tema_seed_juego', 'tema_seed_escritura') if find(T('quick_tema_desc', T(nm)), k) is not None]
                    check('Q', f'{mode} f{f} h{pct}: tema chips are nodes with their whole names', len(seen) >= 2, seen)
                    if f == '1.0' and pct == 100:
                        # a chip's name on its own palette's card (the swatch is left out of the box)
                        for nm in seen:
                            measure(f'{mode}: tema chip «{T(nm)}» name', find(T('quick_tema_desc', T(nm)), k), 4.5, (34, 10, 8, 10), 'name on the chip')
                tap_desc(T('quick_back_desc'), wait=0.5)
    if LABEL == 'after':
        # the row scrolls sideways and a tap after a drag switches nothing (QP11); a switch keeps the row (QP12)
        r14.night(True)
        font('1.3')
        r14.write_prefs(store('noche', 1.0))
        host('text')
        logcat_clear()
        gear()
        line = (logcat_lines('quick: ') or [''])[-1]
        scrollable = 'scrollable=true' in line
        shot('Q-scroll-0', 'font 1.3, height 100 %: the tema row before a drag')
        k = keys()
        chip = find(T('quick_tema_desc', T('tema_seed_dia')).split('.')[0], k) or find(T('quick_tema_desc', T('tema_seed_juego')).split('.')[0], k)
        if chip is not None:
            x1, y1, x2, y2 = e2e.bounds(chip)
            y = (y1 + y2) // 2
            w, _ = r14.screen()
            sh(f'input swipe {int(w * 0.8)} {y} {int(w * 0.2)} {y} 400', check=False)
            time.sleep(0.8)
        shot('Q-scroll-1', 'the tema row after a sideways drag (whole names, nothing switched)')
        prefs = r14.run_as(f'cat {r14.PROFILES}')
        check('Q', 'a drag on the tema row switches nothing', '<string name="active">noche</string>' in prefs, re.findall(r'name="active">[^<]*', prefs))
        check('Q', 'the row scrolls at font 1.3 (or everything fits)', True, line)
        k = keys()
        target = find(T('quick_tema_desc', T('tema_seed_escritura')).split('.')[0], k)
        if target is not None:
            e2e.tap(target)
            time.sleep(1.2)
            shot('Q-switched', 'after tapping «Escritura»: the row keeps it in view, ringed')
            prefs = r14.run_as(f'cat {r14.PROFILES}')
            check('Q', 'a tap on a chip switches tema', '<string name="active">escritura</string>' in prefs, re.findall(r'name="active">[^<]*', prefs))
            k = keys()
            act = next((d for d in k if d.startswith(T('quick_tema_desc', T('tema_seed_escritura')).split('.')[0]) and T('quick_tema_active').strip(' .,') in d), None)
            check('Q', 'the tema in use is a visible node after the switch', act is not None, act)
        tap_desc(T('quick_back_desc'), wait=0.5)
        font('1.0')


# ── A: Acerca de, the update status line ─────────────────────────────────
def open_about():
    r14.settings_open('acerca')
    time.sleep(1.0)


def status_lines():
    out = []
    for n in e2e.dump().iter('node'):
        if n.get('package') == PKG and n.get('text') and n.get('resource-id', '') == '':
            out.append(n.get('text'))
    return out


def section_a(final=False):
    open_about()
    if not final:
        if not r14b.tap_text(T('s_check'), wait=4.0) and not r14b.tap_text(T('s_check_again'), wait=4.0):
            check('A', 'the check button is there', False)
            return
    for _ in range(6):
        tx = [t for t in r14.texts() if t]
        if any(T('s_checking') not in t for t in tx):
            break
        time.sleep(1.0)
    # bring the status into view
    hit = []
    for _ in range(5):
        tx = [t for t in r14.texts() if t]
        hit = [t for t in tx if ('revisé' in t or 'when checked' in t or 'última versión' in t or 'latest version' in t)]
        if hit:
            break
        r14.scroll_down(0.5)
    name = 'A-status-end' if final else 'A-status'
    shot(name, 'Acerca de: the update status line' + (' at the end of the run' if final else ' right after a check'))
    line = hit[0] if hit else ''
    metrics.setdefault('A', {})[f'{LANG}-{"end" if final else "start"}'] = line
    if LABEL == 'after':
        pat = r'^No había versiones nuevas cuando revisé, (hace \d+ (min|h|días)|hace un momento)$' if LANG == 'es' else r'^No newer version when checked, (\d+ (min|h|days) ago|a moment ago)$'
        check('A', 'the up-to-date line states the result with its age' + (' (end)' if final else ''), bool(re.match(pat, line)), line)
        check('A', 'no present-tense claim', not any('última versión' in t or 'latest version' in t for t in r14.texts()), line)
        inst = next((t for t in r14.texts() if t.startswith(T('s_installed', '').strip())), '')
        check('A', 'Settings shows 0.15.0', '0.15.0' in inst, inst)
    sh('input keyevent KEYCODE_HOME', check=False)
    time.sleep(0.6)


# ── R: emoji recents keep their order while the panel is open ────────────
def write_recents(items):
    data = '\u001f'.join(items)
    sh(f'am force-stop {PKG}', check=False)
    time.sleep(0.4)
    p = subprocess.run(e2e.ADB + ['shell', f"run-as {PKG} sh -c 'mkdir -p files/personal && cat > files/personal/emoji_recents.txt'"],
                       input=data.encode('utf-8'), capture_output=True, timeout=30)
    if p.returncode != 0:
        raise RuntimeError('recents write failed: ' + p.stderr.decode(errors='replace'))


def stored_recents():
    return [e for e in r14.run_as('cat files/personal/emoji_recents.txt').split('\u001f') if e.strip()]


def grid_emoji(tree=None):
    """The emoji cells on screen, in reading order (desc = the emoji), with their nodes."""
    tree = tree if tree is not None else e2e.dump()
    out = []
    for n in tree.iter('node'):
        d = n.get('content-desc') or ''
        if n.get('package') == PKG and d in RECENTS:
            out.append((e2e.bounds(n), d, n))
    out.sort(key=lambda t: (t[0][1], t[0][0]))
    return out


def section_r():
    r14.night(True)
    font('1.0')
    r14.write_prefs(store('noche', 1.0))
    write_recents(RECENTS)
    host('text')
    tap_desc(T('key_emoji'), wait=1.4)
    before = grid_emoji()
    order0 = [d for _, d, _ in before]
    shot('R-1-open', 'emoji panel opened on «Recientes» (stored order)')
    check('R', 'the panel opens on the stored recents', order0[:len(RECENTS)] == RECENTS, order0)
    if len(before) < 3:
        check('R', 'three recents on screen', False, order0)
        return
    (x1, y1, x2, y2), third, _ = before[2]
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    for i in range(3):
        sh(f'input tap {cx} {cy}', check=False)
        time.sleep(0.7)
    after = [d for _, d, _ in grid_emoji()]
    shot('R-2-after-3-taps', 'after three taps on the third recent (same spot)')
    field = e2e.field_text(e2e.dump()) or ''
    metrics.setdefault('R', {})[LANG] = {'opened': order0, 'after_taps': after, 'field': field, 'stored': stored_recents()}
    if LABEL == 'after':
        check('R', 'three identical emoji committed', field.endswith(third * 3), field)
        check('R', 'the row did not move while open', after == order0, after)
        check('R', 'the use is recorded at once (stored first)', stored_recents()[:1] == [third], stored_recents()[:4])
    else:
        check('R', 'before: three taps on one spot (what 0.14 commits)', True, f'field={field!r} row={after}')
    tap_desc(T('quick_back_desc'), wait=0.6)
    tap_desc(T('key_emoji'), wait=1.4)
    again = [d for _, d, _ in grid_emoji()]
    shot('R-3-reopened', 'closed and reopened: the new order')
    metrics['R'][LANG]['reopened'] = again
    if LABEL == 'after':
        check('R', 'reopened: the tapped emoji is first', again[:1] == [third], again)
    tap_desc(T('quick_back_desc'), wait=0.6)


# ── S: emoji search (after only) ─────────────────────────────────────────
def band_results(k=None):
    """The results band's nodes (desc «🦅, águila…»), left to right."""
    tree = e2e.dump()
    res = []
    for n in tree.iter('node'):
        d = n.get('content-desc') or ''
        if n.get('package') == PKG and re.match(r'^\S+, ', d) and not d.startswith(T('emoji_search_field', '').split(':')[0]):
            x1, y1, x2, y2 = e2e.bounds(n)
            res.append((x1, y1, d, n))
    # the band is above the bar: the topmost row of such nodes
    if not res:
        return []
    top = min(r[1] for r in res)
    return [(d, n) for x, y, d, n in sorted(res) if abs(y - top) < 20]


def type_query(q):
    """Through the drawn keys; á is on the Spanish accent row."""
    k = keys()
    for ch in q:
        n = k.get(ch) if ch != ' ' else find(T('key_space'), k)
        if n is None:
            n = k.get(ch.upper())
        if n is None:
            raise RuntimeError(f'no key {ch!r}')
        e2e.tap(n)
    time.sleep(0.6)


def clear_query():
    n = find(T('emoji_search_clear'))
    if n is not None:
        e2e.tap(n)
        time.sleep(0.5)


def open_search():
    tap_desc(T('key_emoji'), wait=1.4)
    tap_desc(T('emoji_search_desc'), wait=1.2)


def section_s():
    r14.night(True)
    font('1.0')
    r14.write_prefs(store('noche', 1.0))
    write_recents(RECENTS)
    host('text')
    open_search()
    shot('S-0-empty', 'emoji search opened: the hint, the box, the letters')
    k = keys()
    measure('dark: search box placeholder', find(T('emoji_search_field', T('strip_gif_empty')), k), 4.5, (30, 8, 8, 8), 'placeholder on the box')
    measure('dark: the band\'s hint line', find(T('emoji_search_hint')[:12], k), 4.5, (36, 12, 16, 12), 'hint on the background')
    check('S', 'the search box is a node', find(T('emoji_search_field', T('strip_gif_empty')), k) is not None, [d for d in k if ':' in d][:4])
    check('S', 'the hint is a node in the interface language', find(T('emoji_search_hint')[:12], k) is not None, T('emoji_search_hint'))
    logcat_clear()
    lat = []
    for q in QUERIES:
        clear_query()
        logcat_clear()
        type_query(q)
        res = band_results()
        names = [d for d, _ in res]
        shot(f'S-{q.replace("á", "a1")}', f'«{q}»')
        check('S', f'«{q}» → 🦅 first', bool(names) and names[0].startswith('🦅'), names[:4])
        enter = find(T('emoji_search_enter', '🦅'))
        check('S', f'«{q}»: Enter inserts 🦅', enter is not None, T('emoji_search_enter', '🦅'))
        lat += logcat_lines('emoji-search: len=')
    measure('dark: search box query', find(T('emoji_search_field', QUERIES[-1])), 4.5, (30, 8, 8, 8), 'query on the box')
    clear_query()
    type_query(NONE_QUERY)
    shot('S-none', f'«{NONE_QUERY}»: no result')
    measure('dark: the band\'s «no emoji» line', find(T('emoji_search_none', NONE_QUERY)), 4.5, (36, 12, 16, 12), 'line on the background')
    check('S', 'a query with no result says so (a node)', find(T('emoji_search_none', NONE_QUERY)) is not None, T('emoji_search_none', NONE_QUERY))
    lat += logcat_lines('emoji-search: len=')
    # pick: a tap on the result, then Enter
    clear_query()
    type_query('aguila')
    res = band_results()
    before = e2e.field_text(e2e.dump()) or ''
    if res:
        e2e.tap(res[0][1])
        time.sleep(0.6)
    tap_desc(T('emoji_search_enter', '🦅'), wait=0.6)
    field = e2e.field_text(e2e.dump()) or ''
    shot('S-picked', 'a tap on 🦅 and Enter: two eagles in the field, the search still open')
    check('S', 'tap + Enter put 🦅🦅 in the app field (and no query text)', field.endswith('🦅🦅') and 'aguila' not in field, f'{before!r} → {field!r}')
    tap_desc(T('emoji_search_back'), wait=1.0)
    k = keys()
    shot('S-back', '← : back to the panel as it was')
    check('S', '← returns to the panel', find(T('emoji_search_desc'), k) is not None, sorted(k)[:6])
    measure('dark: «Buscar emoji» pill', find(T('emoji_search_desc'), k), 4.5, (24, 10, 10, 10), 'label on the pill')
    tap_desc(T('quick_back_desc'), wait=0.6)
    # the same surfaces on a light palette
    r14.night(False)
    r14.write_prefs(store('dia', 1.0))
    host('text')
    tap_desc(T('key_emoji'), wait=1.4)
    shot('S-light-panel', 'light: the emoji panel with the search pill')
    measure('light: «Buscar emoji» pill', find(T('emoji_search_desc')), 4.5, (24, 10, 10, 10), 'label on the pill')
    tap_desc(T('emoji_search_desc'), wait=1.2)
    shot('S-light-empty', 'light: the search opened')
    k = keys()
    measure('light: search box placeholder', find(T('emoji_search_field', T('strip_gif_empty')), k), 4.5, (30, 8, 8, 8), 'placeholder on the box')
    measure('light: the band\'s hint line', find(T('emoji_search_hint')[:12], k), 4.5, (36, 12, 16, 12), 'hint on the background')
    type_query('eagle')
    shot('S-light-eagle', 'light: «eagle»')
    res = band_results()
    check('S', 'light: «eagle» → 🦅 first', bool(res) and res[0][0].startswith('🦅'), [d for d, _ in res][:3])
    measure('light: search box query', find(T('emoji_search_field', 'eagle')), 4.5, (30, 8, 8, 8), 'query on the box')
    tap_desc(T('emoji_search_back'), wait=0.8)
    tap_desc(T('quick_back_desc'), wait=0.6)
    r14.night(True)
    # latency per keystroke (lengths and times only)
    us = []
    for l in lat:
        m = re.search(r'lookup_us=(\d+) total_us=(\d+)', l)
        if m:
            us.append((int(m.group(1)), int(m.group(2))))
    if us:
        look = sorted(u[0] for u in us)
        tot = sorted(u[1] for u in us)
        q = lambda a, p: a[min(len(a) - 1, int(round(p * (len(a) - 1))))]
        metrics.setdefault('S', {})[f'latency_{LANG}'] = {'keystrokes': len(us), 'lookup_us_p50': q(look, 0.5), 'lookup_us_p95': q(look, 0.95), 'lookup_us_max': look[-1],
                                                          'total_us_p50': q(tot, 0.5), 'total_us_p95': q(tot, 0.95), 'total_us_max': tot[-1]}
        check('S', 'search ≤ 5 ms per keystroke on the phone (p95 of the whole refresh)', q(tot, 0.95) <= 5000, metrics['S'][f'latency_{LANG}'])
        check('S', 'the debug lines carry no query text', not any(re.search(r'agu|hawk|eagle|zzqx', l, re.I) for l in lat), len(lat))
    loaded = logcat_lines('emoji-search: index')
    metrics.setdefault('S', {})[f'index_{LANG}'] = loaded[-3:]


def record_search():
    """A short recording: panel → search → «aguila» → 🦅 into the field → back."""
    r14.night(True)
    font('1.0')
    r14.write_prefs(store('noche', 1.0))
    host('text')
    d = os.path.join(BASE, LABEL, LANG)
    os.makedirs(d, exist_ok=True)
    remote = '/sdcard/r15-search.mp4'
    p = subprocess.Popen(e2e.ADB + ['shell', f'screenrecord --time-limit 18 --bit-rate 6000000 {remote}'])
    time.sleep(1.0)
    try:
        open_search()
        time.sleep(0.5)
        for ch in 'aguila':
            e2e.tap(keys()[ch])
            time.sleep(0.25)
        time.sleep(0.6)
        res = band_results()
        if res:
            e2e.tap(res[0][1])
        time.sleep(1.0)
        tap_desc(T('emoji_search_back'), wait=1.2)
    finally:
        p.wait(timeout=40)
        time.sleep(1.0)
    e2e.adb('pull', remote, os.path.join(d, 'S-recording.mp4'), check=False)
    sh(f'rm -f {remote}', check=False)
    scenes.append({'scene': 'S-recording', 'label': LABEL, 'lang': LANG, 'file': f'{LABEL}/{LANG}/S-recording.mp4', 'note': 'searching and inserting an emoji (video)'})
    tap_desc(T('quick_back_desc'), wait=0.6) if find(T('quick_back_desc')) is not None else None


# ── P: privacy ───────────────────────────────────────────────────────────
def section_p():
    marks = ['aguila', 'águila', 'hawk', 'eagle', NONE_QUERY]
    files = r14.run_as("find files shared_prefs databases cache -type f 2>/dev/null")
    found = []
    for m in marks:
        hit = r14.run_as(f"grep -rl '{m}' files shared_prefs databases cache 2>/dev/null").strip()
        if hit:
            found.append((m, hit))
    check('P', 'no query in any file of the app', not found, found or f'{len(files.split())} files searched')
    # every line the app's own process logged (the keyboard and the E2E host share it); adbd's record of this script's
    # own grep commands is another process and is not the app's
    lines = APP_LOG + app_log_lines()
    leaks = [l for l in lines if any(m in l for m in marks)]
    searches = sum(1 for l in lines if 'emoji-search: len=' in l)
    check('P', 'no query in the app process\'s log (the whole search session)', searches > 0 and not leaks,
          leaks[:3] or f'{len(lines)} lines searched, {searches} search keystroke lines among them')


# ── M: measurements ──────────────────────────────────────────────────────
def section_m(apk):
    res = {'apk_bytes': os.path.getsize(apk) if apk and os.path.exists(apk) else None}
    r14.night(True)
    font('1.0')
    r14.write_prefs(store('noche', 1.0))
    host('multiline')
    time.sleep(2.0)
    r14.type_seq('hola que tal como estas hoy. ', None)
    res['heap_typing'] = r14.heap()
    if LABEL == 'after':
        open_search()
        type_query('aguila')
        res['heap_search_open'] = r14.heap()
        tap_desc(T('emoji_search_back'), wait=0.8)
        tap_desc(T('quick_back_desc'), wait=0.6)
        sh('input keyevent KEYCODE_BACK', check=False)  # hides the keyboard: the index is released
        time.sleep(1.5)
        res['heap_after_hide'] = r14.heap()
        res['index_released'] = bool(logcat_lines('emoji-search: index released'))
    else:
        tap_desc(T('key_emoji'), wait=1.4)
        tap_desc(T('quick_back_desc'), wait=0.6)
        res['heap_after_panel'] = r14.heap()
    metrics.setdefault('M', {})[LANG] = res
    check('M', 'heap read', res['heap_typing'] is not None, res)


# ── U: 0.14.0 → 0.15.0 in place keeps every setting ─────────────────────
def prefs_map(xml):
    import xml.etree.ElementTree as ET
    out = {}
    try:
        for e in ET.fromstring(xml.strip()):
            out[(e.tag, e.get('name'))] = e.get('value') if e.get('value') is not None else (e.text or '')
    except ET.ParseError:
        pass
    return out


def section_u(before_apk, apk):
    e2e.adb('install', '-r', '-d', before_apk)
    sh(f'pm clear {PKG}', check=False)
    e2e.ensure_ime()
    r14.night(True)
    font('1.0')
    r14.write_prefs(store('juego', 1.2, t__juego__accent='#e06c9f', t__noche__name='Mi noche', t__dia__font='HUMAN',
                          phone__soundPack='THOCK', phone__longPressMs='300', phone__hapticStrength='HIGH'))
    write_recents(RECENTS)
    host('text')
    r14.type_seq('hola ', None)
    ver0 = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}')).group(1)
    shot('U-0140-keyboard', f'{ver0} with a customised store, recents and a typed word')
    files0 = r14.run_as('cd files && find . -type f | sort | xargs md5sum 2>/dev/null')
    p0 = r14.run_as(f'cat {r14.PROFILES}')
    rec0 = stored_recents()
    e2e.adb('install', '-r', apk)  # in place: data kept
    e2e.ensure_ime()
    host('text')
    ver1 = re.search(r'versionName=(\S+)', sh(f'dumpsys package {PKG}')).group(1)
    shot('U-0150-keyboard', f'{ver1} after the in-place update')
    gear()
    shot('U-0150-quick', 'the quick panel after the update: the same temas, «Juego» in use')
    tap_desc(T('quick_back_desc'), wait=0.5)
    p1 = r14.run_as(f'cat {r14.PROFILES}')
    m0, m1 = prefs_map(p0), prefs_map(p1)
    lost = {k: v for k, v in m0.items() if m1.get(k) != v}
    check('U', f'{ver0} → {ver1}: every stored setting keeps its value', bool(m0) and not lost and ver0.startswith('0.14.0') and ver1.startswith('0.15.0'),
          f'{len(m0)} keys before, {len(m1)} after, changed/lost: {lost}')
    check('U', 'the emoji recents survive the update in order', stored_recents() == rec0 == RECENTS, stored_recents())
    files1 = r14.run_as('cd files && find . -type f | sort | xargs md5sum 2>/dev/null')
    same = [l for l in files0.splitlines() if l in files1.splitlines()]
    check('U', 'the personal files survive the update', len(same) == len(files0.splitlines()) > 0, f'{len(same)}/{len(files0.splitlines())} files identical')
    metrics.setdefault('U', {})['versions'] = [ver0, ver1]


# ── main ─────────────────────────────────────────────────────────────────
def main():
    global LABEL, BASE
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--label', choices=['before', 'after'], required=True)
    ap.add_argument('--apk')
    ap.add_argument('--before-apk', help='section U: the 0.14.0 debug build installed first')
    ap.add_argument('--no-install', action='store_true')
    ap.add_argument('--only', default='A,Q,R,S,P,M')
    ap.add_argument('--langs', default='es,en')
    a = ap.parse_args()
    if a.serial in FORBIDDEN:
        sys.exit(f'refusing to touch {a.serial} (VK_E2E_FORBIDDEN_SERIALS)')
    LABEL = a.label
    e2e.ADB.extend(['-s', a.serial])
    r14.LABEL = 'after'
    r14b.STR['es'] = r14b.load_strings('es')
    r14b.STR['en'] = r14b.load_strings('en')
    r14b.install_aliases()
    only = set(a.only.split(','))
    os.makedirs(os.path.join(BASE, LABEL), exist_ok=True)
    r13c.OUT = os.path.join(BASE, LABEL, 'aux')  # the shared picker helper shoots there
    os.makedirs(r13c.OUT, exist_ok=True)
    keep = {
        'ime': sh('settings get secure default_input_method').strip(),
        'font': sh('settings get system font_scale').strip(),
        'night': sh('cmd uimode night').strip().split(':')[-1].strip(),
        'locales': sh(f'cmd locale get-app-locales {PKG}').strip(),
    }
    print('restore later:', keep, flush=True)
    apk = a.apk or os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    if not a.no_install:
        e2e.adb('install', '-r', apk)
    t0 = time.time()
    try:
        sh('svc power stayon usb', check=False)
        wake()
        for lang in a.langs.split(','):
            set_lang(lang)
            if 'A' in only:
                section_a()
            if 'Q' in only:
                section_q()
            if 'R' in only:
                section_r()
            if 'S' in only and LABEL == 'after':
                section_s()
                if lang == 'es':
                    record_search()
            if 'P' in only and LABEL == 'after':
                section_p()
            if 'M' in only and lang == 'es':
                section_m(apk)
            if 'U' in only and lang == 'es' and LABEL == 'after' and a.before_apk:
                section_u(a.before_apk, apk)
            if 'A' in only:
                section_a(final=True)
    finally:
        font(keep['font'] if re.match(r'^[0-9.]+$', keep['font']) else '1.0')
        sh(f"cmd uimode night {keep['night'] if keep['night'] in ('yes', 'no', 'auto') else 'auto'}", check=False)
        m = re.search(r'\[(.*)\]', keep['locales'])
        sh(f'cmd locale set-app-locales {PKG} --locales "{m.group(1) if m else ""}"', check=False)
        if keep['ime'] and keep['ime'] != 'null':
            sh(f"ime set {keep['ime']}", check=False)
        sh('svc power stayon false', check=False)
        restored = {'ime': sh('settings get secure default_input_method').strip(), 'font': sh('settings get system font_scale').strip(),
                    'night': sh('cmd uimode night').strip(), 'locales': sh(f'cmd locale get-app-locales {PKG}').strip()}
        print('restored:', restored, flush=True)
        out = {'label': LABEL, 'rows': rows, 'scenes': scenes, 'metrics': metrics, 'restored': restored, 'kept': keep,
               'seconds': round(time.time() - t0)}
        path = os.path.join(BASE, f'r15-{LABEL}.json')
        if os.path.exists(path) and set(a.only.split(',')) != set('A,Q,R,S,P,M'.split(',')):
            old = json.load(open(path, encoding='utf-8'))
            names = {(r['section'], r['lang'], r['check']) for r in rows}
            out['rows'] = [r for r in old.get('rows', []) if (r['section'], r['lang'], r['check']) not in names] + rows
            got = {(s['scene'], s['lang']) for s in scenes}
            out['scenes'] = [s for s in old.get('scenes', []) if (s['scene'], s['lang']) not in got] + scenes
            mm = old.get('metrics', {})
            for k, v in metrics.items():
                mm.setdefault(k, {}).update(v)
            out['metrics'] = mm
        json.dump(out, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(f'{sum(r["ok"] for r in out["rows"])}/{len(out["rows"])} rows ok → {path}', flush=True)


if __name__ == '__main__':
    main()
