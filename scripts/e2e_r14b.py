#!/usr/bin/env python3
"""✦ Resyst VK — r14b E2E (0.14.1: the whole interface in the phone's language).

Drives the debug build on a real phone in Spanish and in English (Android's per-app language, `cmd locale
set-app-locales`: the device's own language is never touched) and writes the same scene names under
build/e2e-r14b/<lang>/, then a paired index (index.md + index.html + r14b.json). It proves, on the rendered screens:

  S  every Settings screen (home and the eight pages, scrolled to the bottom) with the dialogs (new theme, rename,
     delete, forget what was learned, clear the clipboard, reset, GIF disclosure, download confirmation)
  K  the keyboard and its panels: letters, long-press popup, symbols, suggestions, context offer, «2 cambios», paste
     chip, update chip, «Novedades» chip, the «sin memoria» dot and its toast, quick panel, edit panel, clipboard
     (list, detail, clear confirmation), emoji (recents, a category, the skin-tone popup), GIF tab (off, disclosure),
     one-hand rail
  L  the same at the largest font scale (1.3 and 2.0): Settings home, a long page, the keyboard, the quick panel
  A  every TalkBack name of the keyboard in both languages (a11y-<lang>.json)
  X  checks: no screen mixes the two languages (a word list for each side over every text node dumped), the typing
     language label on the space bar is the typing language in both, a stored theme / style / accent survives a
     language change byte for byte, the connection log shows old (Spanish) and new (token) entries

Debug package only (com.resyst.vk.debug). Restores: the per-app language, the font scale, the night mode, the default
input method, stay-awake.

Usage: scripts/e2e_r14b.py --serial SERIAL [--apk PATH] [--no-install] [--only S,K,L,A,X] [--langs es,en]

Environment:
  VK_E2E_FORBIDDEN_SERIALS  comma-separated device serials this script must refuse to touch (your own phones).
"""
import argparse
import glob
import hashlib
import html
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r10_bilingual as bl  # noqa: E402
import e2e_r13 as r13  # noqa: E402
import e2e_r13c as r13c  # noqa: E402
import e2e_r13f as r13f  # noqa: E402
import e2e_r13h  # noqa: E402,F401  (its import makes bl.set_lang go through Android's picker)
import e2e_r14 as r14  # noqa: E402
from e2e import PKG, IME  # noqa: E402

FORBIDDEN = {s.strip() for s in os.environ.get('VK_E2E_FORBIDDEN_SERIALS', '').split(',') if s.strip()}
BASE = os.path.join(e2e.ROOT, 'build', 'e2e-r14b')
RES = os.path.join(e2e.ROOT, 'app', 'src', 'main', 'res')
OUT = BASE
LANG = 'es'
rows = []
scenes = []
a11y = {}
PAGES = ['modos', 'apariencia', 'teclas', 'escritura', 'respuesta', 'privacidad', 'datos', 'acerca']


# ── the app's own strings, read from the resources the phone uses ─────────────────────────────
def _unescape(t):
    t = t.strip()
    if len(t) >= 2 and t[0] == '"' and t[-1] == '"':
        t = t[1:-1]
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == 'u' and len(m.group(1)) == 5
                  else ('\n' if m.group(1) == 'n' else m.group(1)), t)


def load_strings(lang):
    s, p = {}, {}
    for d in (['values'] + (['values-en'] if lang == 'en' else [])):
        for f in sorted(glob.glob(os.path.join(RES, d, '*.xml'))):
            for e in ET.parse(f).getroot():
                if e.tag == 'string':
                    s[e.get('name')] = _unescape(''.join(e.itertext()))
                elif e.tag == 'plurals':
                    p[e.get('name')] = {i.get('quantity'): _unescape(''.join(i.itertext())) for i in e}
    return s, p


STR = {}


def T(name, *args):
    """The string [name] of the language being driven, formatted like Resources.getString."""
    s = STR[LANG][0][name]
    return fmt(s, args)


def fmt(s, args):
    s = s.replace('%%', '\0')
    s = re.sub(r'%(\d+)\$[sd]', lambda m: str(args[int(m.group(1)) - 1]), s)
    return s.replace('\0', '%')


# ── the earlier scripts look keys up by their Spanish TalkBack names ('Espacio', 'Sugerencia: …'): in English the
# drawn keys are named in English, so every name also answers to its Spanish twin (built from the same resources)
_ALIAS = []


def build_aliases():
    es, _ = STR['es']
    en, _ = STR['en']
    for k, e in en.items():
        if k not in es or es[k] == e or not (k.startswith(('key_', 'rail_', 'strip_', 'enter_', 'quick_', 'emoji_', 'clip_', 'gif_', 'qa_', 'editd_',
                                                           'edit_', 'nomem_desc', 'context_offer', 'say_', 'qs_')) or k == 'nomem_desc'):
            continue
        rx = re.escape(e)
        rx = re.sub(r'%(\d)\\\$[sd]', r'(.+?)', rx) if False else re.sub(r'%\d\\\$[sd]', '(.+?)', rx)
        try:
            _ALIAS.append((re.compile('^' + rx + '$', re.S), es[k]))
        except re.error:
            pass


def localized_keys(tree):
    base = e2e._keys_orig(tree)
    if LANG != 'en':
        return base
    out = dict(base)
    for desc, node in base.items():
        for rx, es_tpl in _ALIAS:
            m = rx.match(desc)
            if m:
                g = m.groups()
                out.setdefault(re.sub(r'%(\d)\$[sd]', lambda mm: g[int(mm.group(1)) - 1] if int(mm.group(1)) - 1 < len(g) else '', es_tpl), node)
                break
    return out


def install_aliases():
    build_aliases()
    e2e._keys_orig = e2e.keys
    for mod in (e2e, r13, bl, r13c, r13f, e2e_r13h, r14):
        if hasattr(mod, 'keys'):
            mod.keys = localized_keys


def check(section, name, ok, detail=''):
    rows.append({'section': section, 'lang': LANG, 'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + f'[{section}/{LANG}] {name}' + ('  ' + str(detail)[:200] if detail else ''), flush=True)


def sh(cmd, check=True):
    return str(e2e.sh(cmd, check=check))


def shot(name, note=''):
    d = os.path.join(OUT, LANG)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name + '.png')
    open(path, 'wb').write(e2e.adb('exec-out', 'screencap', '-p', binary=True))
    scenes.append({'scene': name, 'lang': LANG, 'file': f'{LANG}/{name}.png', 'note': note})
    print(f'  shot {LANG}/{name}', flush=True)


def set_lang(lang):
    """Per-app language of the debug package only: the phone's own language is never touched."""
    global LANG
    LANG = lang
    sh(f'cmd locale set-app-locales {PKG} --locales {lang}')
    sh(f'am force-stop {PKG}', check=False)
    time.sleep(0.8)
    got = sh(f'cmd locale get-app-locales {PKG}').strip()
    check('X', f'per-app language set to {lang}', f'[{lang}]' in got.replace(' ', ''), got)


def prefs_xml(name, values):
    body = ''.join(f'    <string name="{r14.xml_escape(k)}">{r14.xml_escape(str(v))}</string>\n' for k, v in values.items() if not isinstance(v, (bool, int)))
    body += ''.join(f'    <boolean name="{k}" value="{str(v).lower()}" />\n' for k, v in values.items() if isinstance(v, bool))
    body += ''.join(f'    <long name="{k}" value="{v}" />\n' for k, v in values.items() if isinstance(v, int) and not isinstance(v, bool))
    return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + body + '</map>\n'


def write_prefs_file(name, values):
    sh(f'am force-stop {PKG}', check=False)
    time.sleep(0.4)
    p = subprocess.run(e2e.ADB + ['shell', f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/{name}.xml'"],
                       input=prefs_xml(name, values).encode('utf-8'), capture_output=True, timeout=30)
    if p.returncode != 0:
        raise RuntimeError(f'prefs write failed: {p.stderr.decode(errors="replace")}')


def remove_prefs_file(name):
    sh(f'am force-stop {PKG}', check=False)
    sh(f"run-as {PKG} sh -c 'rm -f shared_prefs/{name}.xml'", check=False)


# ── the two sides of «no mixed language» ──────────────────────────────────────────────────────
ES_WORDS = r'\b(el|la|los|las|de|del|para|con|sin|tu|tus|una|por|al|más|ahora|todo|todos|que|esto|este|esta|solo|nunca)\b'
EN_WORDS = r'\b(the|and|for|with|your|you|this|that|only|never|not|from|when|what|are|is|of|to|in|on)\b'
# text that quotes the other language on purpose: sample words to type, credits that name Wikipedia's Spanish title
QUOTED = ('ñandú', 'enciclopedia libre', 'Wikipedia contributors', 'Tatoeba', 'FrequencyWords', 'Midnight', 'KLIPY', 'GPL')
# what the script itself typed or copied: it is the user's text, in Spanish, whatever the interface speaks
TYPED = ('Ya al día (0.14.0)', 'nos vemos a las 8 en la plaza', 'https://kv.resyst.cl/novedades/', 'dirección: Av. Siempre Viva 742', 'paso por tu caza y esta bien',
         'paso por tu caza y esta muy bien', 'hola que tal como estas hoy.', 'mañana vamos al cine con la familia.', 'caza', 'casa', 'hola', 'cancio')


def texts_of(tree):
    out = []
    for n in tree.iter('node'):
        for k in ('text', 'content-desc'):
            v = (n.get(k) or '').strip()
            if v and n.get('package') in (PKG, 'com.resyst.vk.debug') and n.get('class') != 'android.widget.EditText':
                out.append(v)
    return out


# the phone's own content shows next to the interface: words the keyboard learned, pairs, suggestions and clips
USER_CONTENT = re.compile(r'^(Delete the word|Forget the pair|Remove the emoji|Forget the chosen tone for|Suggestion|Paste from the clipboard|from the clipboard|'
                          r'Borrar la palabra|Olvidar el par|Borrar el emoji|Olvidar el tono elegido para|Sugerencia|Pegar del portapapeles|del portapapeles|'
                          r'Borrar la corrección de|Delete the correction from|Delete the email|Borrar el correo)\b.*')
CLIP_ROW = re.compile(r'^(Paste|Pegar): .*?\. ')


def foreign(texts):
    """Words of the other language in [texts] (excluding text that quotes it on purpose and the typed field)."""
    pat = re.compile(EN_WORDS if LANG == 'es' else ES_WORDS, re.I)
    bad = []
    for t in texts:
        if len(t.split()) <= 3 and t == t.lower() and re.fullmatch(r'[\wáéíóúñü ]+', t):
            continue  # a word or a pair the keyboard learned, listed as the user's content
        t = USER_CONTENT.sub('', t)
        t = CLIP_ROW.sub('', t)
        for typed in TYPED:
            t = t.replace(typed, '')
        if any(q in t for q in QUOTED) or t.startswith('✦ E2E host') or t in ('e2e', 'Mensaje', 'Escribe un mensaje'):
            continue
        m = pat.findall(t)
        if m:
            bad.append((t[:70], sorted(set(w.lower() for w in m))))
    return bad


SEEN_TEXTS = {}


def audit(scene, tree=None):
    """Dumps the screen and records the language check of every text on it."""
    tree = tree if tree is not None else e2e.dump()
    ts = texts_of(tree)
    SEEN_TEXTS.setdefault(LANG, {})[scene] = ts
    bad = foreign(ts)
    check('X', f'{scene}: only {"Spanish" if LANG == "es" else "English"} text', not bad, bad[:4])
    return tree


# ── Settings ─────────────────────────────────────────────────────────────────────────────────
def open_settings(page=None):
    r14.settings_open(page)


def scroll_pages(prefix, limit=7, note=''):
    """Shots of the screen, then of each scroll, until the content stops moving. Returns the number of shots."""
    last = None
    n = 0
    for i in range(1, limit + 1):
        tree = e2e.dump()
        sig = hashlib.md5('|'.join(texts_of(tree)).encode()).hexdigest()
        if sig == last:
            break
        last = sig
        n += 1
        shot(f'{prefix}-{i}', note or prefix)
        audit(f'{prefix}-{i}', tree)
        r14.scroll_down(0.62)
    return n


def find_text(*needles, exact=True):
    tree = e2e.dump()
    for n in tree.iter('node'):
        if n.get('package') != PKG:
            continue
        for k in ('text', 'content-desc'):
            v = (n.get(k) or '').strip()
            for needle in needles:
                if v == needle or (not exact and v.startswith(needle)):
                    return n
    return None


def tap_text(*needles, exact=True, wait=0.9, scroll=True):
    for i in range(7):
        n = find_text(*needles, exact=exact)
        if n is not None:
            e2e.tap(n)
            time.sleep(wait)
            return True
        if not scroll:
            break
        r14.scroll_down(0.5)
    return False


def dialog_dismiss(*labels):
    for l in labels:
        n = find_text(l)
        if n is not None:
            e2e.tap(n)
            time.sleep(0.6)
            return True
    sh('input keyevent KEYCODE_BACK', check=False)
    time.sleep(0.6)
    return False


def section_s():
    r14.write_prefs(r14.store('lab'))
    k = r14.host('text')
    r14.type_seq('hola que tal como estas hoy. ', k)
    k = r14.host('multiline')
    r14.type_seq('mañana vamos al cine con la familia. ', k)
    # something in the connection log: an entry as 0.14.0 stored it (Spanish text) beside new ones (tokens)
    now = int(time.time() * 1000)
    log = {'v': 2, 'total': 3, 'first': now - 7200000, 'items': [
        [now - 600000, 'check', 'open', '~new|9.9.9', 0, 3],
        [now - 3600000, 'gif_search', 'user', '~gifpage|1|24|0||gato', 24, 2],
        [now - 7200000, 'check', 'user', 'Ya al día (0.14.0)', 0, 1]]}
    write_prefs_file('resyst_vk_update', {'connections': json.dumps(log, ensure_ascii=False), 'availManifest': MANIFEST, 'availAt': now - 300000, 'availAuto': True})
    e2e.ensure_ime()
    open_settings()
    scroll_pages('S-home', 9, 'Settings home')
    for page in PAGES:
        open_settings(page)
        scroll_pages(f'S-{page}', 9, f'page {page}')
    # the setup card: another keyboard is the current one
    gboard = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'
    if gboard in sh('ime list -s', check=False):
        sh(f'ime set {gboard}', check=False)
        open_settings()
        shot('S-setup', 'the setup card (another keyboard is the current one)')
        audit('S-setup')
        e2e.ensure_ime()
    # the update card (a stored «available» answer: no network) and its download confirmation
    open_settings('acerca')
    shot('S-update-card', 'an available update: the card')
    audit('S-update-card')
    if tap_text(T('s_dl_install', '9.9.9')):
        shot('D-download-confirm', 'the confirmation before a download')
        audit('D-download-confirm')
        dialog_dismiss(T('s_cancel'))


def section_d():
    # the dialogs
    open_settings('modos')
    if tap_text(T('tema_new'), wait=1.0):
        shot('D-tema-new', 'new theme: name dialog')
        audit('D-tema-new')
        dialog_dismiss(T('tema_cancel'))
    if tap_text(T('tema_rename'), wait=1.0):
        shot('D-tema-rename', 'rename dialog')
        audit('D-tema-rename')
        dialog_dismiss(T('tema_cancel'))
    if tap_text(T('tema_delete'), wait=1.0):
        shot('D-tema-delete', 'delete confirmation')
        audit('D-tema-delete')
        dialog_dismiss(T('tema_cancel'))
    open_settings('privacidad')
    for scene, ctl, cancel in (('D-clear-clip', 'ctl_clear_clip', 's_cancel'), ('D-forget', 'ctl_forget_learned', 's_cancel')):
        if tap_text(T(ctl), exact=False):
            shot(scene, f'{ctl}: confirmation')
            audit(scene)
            dialog_dismiss(T(cancel))
    if tap_text(T('ctl_gif_search'), exact=False):
        shot('D-gif-disclosure', 'GIF search: the disclosure dialog (nothing is turned on)')
        audit('D-gif-disclosure')
        dialog_dismiss(T('gif_cancel'))
    open_settings('acerca')
    if tap_text(T('ctl_reset_all'), exact=False):
        shot('D-reset', 'reset: confirmation')
        audit('D-reset')
        dialog_dismiss(T('s_cancel'))


# ── the keyboard ─────────────────────────────────────────────────────────────────────────────
MANIFEST = json.dumps({'available': True, 'version': '9.9.9', 'versionCode': 999, 'sha256': 'a' * 64, 'url': '/download/resyst-vk-9.9.9.apk',
                       'sizeBytes': 16000000, 'size': '15.3 MB', 'date': '2026-12-01', 'minSdk': 26})


def kb_audit(scene):
    tree = e2e.dump()
    descs = sorted({(n.get('content-desc') or '') for n in tree.iter('node') if n.get('package') == PKG and n.get('class') == 'android.widget.Button' and n.get('content-desc')})
    a11y.setdefault(LANG, {})[scene] = descs
    bad = foreign(descs)
    check('X', f'{scene}: TalkBack names only {"Spanish" if LANG == "es" else "English"}', not bad, bad[:4])
    # key legends on the typing language: the space bar says the typing language, not the interface language
    return descs


def gear():
    return r14.find(T('strip_settings'))


def section_k():
    news_off()
    r14.write_prefs(r14.store('lab'))
    k = r14.host('text')
    shot('K-letters', 'the keyboard, letters (Spanish typing layout)')
    kb_audit('K-letters')
    # the space bar shows the typing language (Spanish here), whatever language the interface speaks
    check('K', 'the space bar shows the typing language, not the interface language', bl.space_label() == 'es', bl.space_label())
    r14.type_seq('h', k)
    r14.hold_and_shoot(r14.key('e'), 'K-longpress', hold_ms=2200, at=1.2, note='e held: the long-press variants')
    r14.press(T('key_backspace'))
    r14.press(T('key_symbols'), wait=0.6)
    shot('K-symbols', 'the symbols layer')
    kb_audit('K-symbols')
    r14.press(T('key_letters'), wait=0.6)
    k = r14.host('text')
    r14.type_seq('cancio', k)
    time.sleep(0.8)
    shot('K-suggestions', 'completions on the bar')
    kb_audit('K-suggestions')
    k = r14.host('text')
    r14.type_seq('paso por tu caza y esta bien ', k)
    r13f.wait_chip(timeout=5.0)
    time.sleep(0.8)
    shot('K-offer', 'context offer chip')
    kb_audit('K-offer')
    k = r14.host('text')
    r14.type_seq('paso por tu caza y esta muy bien ', k)
    t0 = time.time()
    multi = None
    while time.time() - t0 < 6:
        kk = r14.km()
        multi = next((d for d in kk if re.match(r'^(Cambiar|Replace) .+ (y|and) ', d)), None)
        if multi:
            break
        time.sleep(0.4)
    time.sleep(0.6)
    shot('K-two-changes', 'two offers in one chip')
    check('K', 'two context offers in one chip', bool(multi), multi)
    if multi:
        e2e.tap(r14.km()[multi])
        time.sleep(1.0)
        shot('K-undo', 'after the tap: the undo chip')
        kb_audit('K-undo')
    # paste chip + clipboard panel
    k = r14.host('text')
    for txt in ('nos vemos a las 8 en la plaza', 'https://kv.resyst.cl/novedades/', 'dirección: Av. Siempre Viva 742'):
        sh(f"am broadcast -a com.resyst.vk.debug.SET_CLIP --es text '{txt}'", check=False)
        time.sleep(0.8)
    r14.type_seq('hola ', k)
    time.sleep(0.5)
    shot('K-paste-chip', 'the paste chip')
    kb_audit('K-paste-chip')
    e2e.tap(gear())
    time.sleep(0.8)
    shot('K-quick', 'quick panel')
    kb_audit('K-quick')
    n = r14.find(T('qa_edit'))
    if n is not None:
        e2e.tap(n)
        time.sleep(0.7)
        shot('K-edit', 'edit panel')
        kb_audit('K-edit')
        r14.press(T('quick_back_desc'))
    e2e.tap(gear())
    time.sleep(0.7)
    n = r14.find(T('qa_clipboard'))
    if n is not None:
        e2e.tap(n)
        time.sleep(0.8)
        shot('K-clipboard', 'clipboard history')
        kb_audit('K-clipboard')
        row = r14.find(T('clip_paste') + ':')
        if row is not None:
            e2e.tap(row, hold_ms=900)
            time.sleep(0.8)
            shot('K-clipboard-detail', 'a clip: the detail view')
            kb_audit('K-clipboard-detail')
            back = r14.find(T('clip_back_list'))
            if back is not None:
                e2e.tap(back)
                time.sleep(0.6)
        ask = r14.find(T('clip_clear_ask_desc'))
        if ask is not None:
            e2e.tap(ask)
            time.sleep(0.6)
            shot('K-clipboard-clear', 'clear the history: confirmation')
            kb_audit('K-clipboard-clear')
            no = r14.find(T('clip_clear_no_desc'))
            if no is not None:
                e2e.tap(no)
                time.sleep(0.4)
        c = r14.find(T('clip_close_desc')[:20])
        if c is not None:
            e2e.tap(c)
            time.sleep(0.5)
    # emoji
    k = r14.host('text')
    r14.press(T('key_emoji'), wait=1.2)
    shot('K-emoji-recents', 'emoji panel: the recents tab')
    kb_audit('K-emoji-recents')
    tab = r14.find(T('emoji_cat_personas'))
    if tab is not None:
        e2e.tap(tab)
        time.sleep(0.9)
        shot('K-emoji-people', 'emoji panel: People and body')
        kb_audit('K-emoji-people')
        em = next((v for d, v in r14.km().items() if d.startswith('👋')), None)
        if em is not None:
            e2e.tap(em, hold_ms=900)
            time.sleep(0.9)
            shot('K-emoji-tones', 'the skin-tone popup')
            kb_audit('K-emoji-tones')
            close = r14.find(T('emoji_tones_close'))
            if close is not None:
                e2e.tap(close)
                time.sleep(0.5)
    g = r14.find(T('emoji_tab_gif_desc'))
    if g is not None:
        e2e.tap(g)
        time.sleep(0.9)
        shot('K-gif-off', 'GIF tab: off (no request is made)')
        kb_audit('K-gif-off')
        b = r14.find(T('gif_off_button'))
        if b is not None:
            e2e.tap(b)
            time.sleep(0.8)
            shot('K-gif-disclosure', 'GIF tab: what is sent, before turning it on')
            kb_audit('K-gif-disclosure')
            c = r14.find(T('gif_cancel_desc')[:8])
            if c is not None:
                e2e.tap(c)
                time.sleep(0.5)
    r14.press(T('quick_back_desc'), wait=0.6) if r14.find(T('quick_back_desc')) is not None else None
    # one hand
    k = r14.host('text')
    e2e.tap(gear())
    time.sleep(0.6)
    r14.press(T('qa_one_hand'), wait=0.8)
    r14.press(T('quick_back_desc'), wait=0.6) if r14.find(T('quick_back_desc')) is not None else None
    shot('K-onehand', 'one-hand mode, right (the rail)')
    kb_audit('K-onehand')
    e2e.tap(gear())
    time.sleep(0.6)
    r14.press(T('qa_one_hand'), wait=0.8)
    r14.press(T('qa_one_hand'), wait=0.8)
    r14.press(T('quick_back_desc'), wait=0.6) if r14.find(T('quick_back_desc')) is not None else None
    # the update chip and the «Novedades» chip
    write_prefs_file('resyst_vk_update', {'availManifest': MANIFEST, 'availAt': int(time.time() * 1000) - 300000, 'availAuto': True})
    r14.write_prefs(r14.store('lab'))
    e2e.ensure_ime()
    k = r14.host('text')
    time.sleep(1.0)
    shot('K-update-chip', 'an announced update: the chip on the bar')
    kb_audit('K-update-chip')
    remove_prefs_file('resyst_vk_update')
    write_prefs_file('resyst_vk_news', {'seen': '0.14.0'})
    r14.write_prefs(r14.store('lab'))
    e2e.ensure_ime()
    k = r14.host('text')
    time.sleep(1.0)
    shot('K-news-chip', 'the «Novedades» chip after an update')
    kb_audit('K-news-chip')
    news_off()
    # «sin memoria» in a password field, and its toast
    r14.write_prefs(r14.store('lab'))
    k = r14.host('password')
    time.sleep(0.8)
    shot('K-no-memory', 'a password field: the «sin memoria» dot')
    kb_audit('K-no-memory')
    dot = next((v for d, v in r14.km().items() if d.startswith(T('nomem_desc', '')[:8])), None)
    if dot is not None:
        e2e.tap(dot)
        time.sleep(0.5)
        shot('K-no-memory-toast', 'the toast that says why nothing is learned here')
        audit('K-no-memory-toast')


def news_off():
    write_prefs_file('resyst_vk_news', {'seen': '0.14.1-debug'})


# ── large font ───────────────────────────────────────────────────────────────────────────────
def section_l(font_before):
    for scale in ('1.3', '2.0'):
        sh(f'settings put system font_scale {scale}', check=False)
        time.sleep(1.5)
        tag = scale.replace('.', '')
        r14.write_prefs(r14.store('lab'))
        open_settings()
        shot(f'L{tag}-home', f'Settings home at font scale {scale}')
        audit(f'L{tag}-home')
        r14.scroll_down(0.7)
        shot(f'L{tag}-home-2', f'Settings home, scrolled, at font scale {scale}')
        for page in ('privacidad', 'datos'):
            open_settings(page)
            shot(f'L{tag}-{page}', f'page {page} at font scale {scale}')
            audit(f'L{tag}-{page}')
        k = r14.host('text')
        shot(f'L{tag}-keyboard', f'the keyboard at font scale {scale}')
        e2e.tap(gear())
        time.sleep(0.8)
        shot(f'L{tag}-quick', f'quick panel at font scale {scale}')
        kb_audit(f'L{tag}-quick')
        r14.press(T('qa_clipboard'), wait=0.8) if r14.find(T('qa_clipboard')) is not None else None
        shot(f'L{tag}-clipboard', f'clipboard panel at font scale {scale}')
    sh(f'settings put system font_scale {font_before}', check=False)
    time.sleep(1.0)


# ── checks that need no screen ─────────────────────────────────────────────────────────────────
def section_x_store():
    """A stored choice survives a language change byte for byte (TR3)."""
    store = r14.store('forest', **{'t__resyst__shape': 'PILL', 't__resyst__accent': '#e2735a'})
    r14.write_prefs(store)
    before = r14.run_as('cat shared_prefs/resyst_vk_profiles.xml')
    for lang in ('en', 'es', 'en'):
        set_lang(lang)
        open_settings()
        time.sleep(1.0)
        e2e.ensure_ime()
    after = r14.run_as('cat shared_prefs/resyst_vk_profiles.xml')
    check('X', 'the stored themes, style and accent are byte-identical after switching language three times', before == after and 'forest' in after, f'{len(before)} bytes')


def write_index(langs):
    by = {}
    for s in scenes:
        by.setdefault(s['scene'], {})[s['lang']] = s
    order = list(dict.fromkeys(s['scene'] for s in scenes))
    md = ['# r14b: the whole interface in the phone\'s language (Spanish | English)', '',
          'Each row is one screen, the same scene in both languages (build/e2e-r14b/<lang>/<scene>.png).', '',
          '| scene | ' + ' | '.join(langs) + ' | note |', '|---|' + '---|' * len(langs) + '---|']
    cards = []
    for name in order:
        cells = []
        note = next(iter(by[name].values()))['note']
        for l in langs:
            s = by[name].get(l)
            cells.append(f'![{name} {l}]({s["file"]})' if s else '—')
        md.append(f'| `{name}` | ' + ' | '.join(cells) + f' | {note} |')
        imgs = ''.join(f'<figure><figcaption>{l}</figcaption>' + (f'<img src="{by[name][l]["file"]}" loading="lazy">' if l in by[name] else '<p>—</p>') + '</figure>' for l in langs)
        cards.append(f'<section><h3>{html.escape(name)}</h3><p>{html.escape(note)}</p><div class="pair">{imgs}</div></section>')
    open(os.path.join(BASE, 'index.md'), 'w', encoding='utf-8').write('\n'.join(md) + '\n')
    page = ('<!doctype html><meta charset="utf-8"><title>r14b: es | en</title><style>body{font:14px system-ui;background:#111;color:#eee;margin:16px}'
            '.pair{display:flex;gap:12px}figure{margin:0}img{width:360px;border:1px solid #444}h3{margin:18px 0 2px}</style>'
            '<h1>r14b: the whole interface in the phone\'s language</h1>' + ''.join(cards))
    open(os.path.join(BASE, 'index.html'), 'w', encoding='utf-8').write(page)


def main():
    global OUT
    if '--index' in sys.argv:
        path = os.path.join(BASE, 'r14b.json')
        d = json.load(open(path))
        scenes.extend(d['scenes'])
        write_index(d['langs'])
        return 0
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--apk')
    ap.add_argument('--no-install', action='store_true')
    ap.add_argument('--only', default='S,D,K,L,A,X')
    ap.add_argument('--langs', default='es,en')
    a = ap.parse_args()
    if a.serial in FORBIDDEN:
        print(f'refusing: {a.serial} is in VK_E2E_FORBIDDEN_SERIALS')
        return 2
    e2e.ADB.extend(['-s', a.serial])
    for mod in (e2e, r13, r13c, r13f, r14):
        mod.OUT = os.path.join(BASE, 'scratch')
    os.makedirs(os.path.join(BASE, 'scratch'), exist_ok=True)
    r14.rows, r14.scenes = [], []
    langs = a.langs.split(',')
    for l in ('es', 'en'):
        STR[l] = load_strings(l)
    install_aliases()
    apk = a.apk or os.path.join(e2e.ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    only = set(a.only.split(','))
    pre = {
        'ime': sh('settings get secure default_input_method').strip(),
        'night': sh('cmd uimode night').strip(),
        'font_scale': sh('settings get system font_scale').strip(),
        'app_locales': sh(f'cmd locale get-app-locales {PKG}').strip(),
        'system_locale': sh('getprop persist.sys.locale').strip(),
    }
    print('prestate', pre)
    started = time.time()
    try:
        if not a.no_install:
            r13c.install('-r', '-t', '-d', apk)
        sh(f'ime enable {IME}')
        sh(f'ime set {IME}')
        sh('svc power stayon usb', check=False)
        sh('input keyevent KEYCODE_WAKEUP', check=False)
        for lang in langs:
            set_lang(lang)
            e2e.ensure_ime()
            if 'S' in only:
                section_s()
            if 'D' in only:
                section_d()
            if 'K' in only:
                section_k()
            if 'L' in only:
                section_l(pre['font_scale'] if pre['font_scale'] not in ('', 'null') else '1.0')
        if 'X' in only:
            section_x_store()
    finally:
        sh(f'cmd locale set-app-locales {PKG} --locales ""', check=False)
        sh(f'am force-stop {PKG}', check=False)
        sh(f"run-as {PKG} sh -c 'rm -f shared_prefs/resyst_vk_news.xml shared_prefs/resyst_vk_update.xml'", check=False)
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
            'app_locales': sh(f'cmd locale get-app-locales {PKG}', check=False).strip(),
            'system_locale': sh('getprop persist.sys.locale', check=False).strip(),
        }
        print('restored', post)
        res = {'apk': os.path.relpath(apk, e2e.ROOT), 'langs': langs, 'prestate': pre, 'poststate': post, 'restored': {k: post[k] == pre[k] for k in pre},
               'passed': sum(r['ok'] for r in rows), 'total': len(rows), 'seconds': round(time.time() - started, 1),
               'rows': rows, 'scenes': scenes, 'a11y_names': {l: sum(len(v) for v in d.values()) for l, d in a11y.items()}}
        os.makedirs(BASE, exist_ok=True)
        for l, d in a11y.items():
            json.dump(d, open(os.path.join(BASE, f'a11y-{l}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        json.dump(res, open(os.path.join(BASE, 'r14b.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        write_index(langs)
        print(f'✦ {res["passed"]}/{res["total"]} rows → build/e2e-r14b/r14b.json')
    return 0 if all(r['ok'] for r in rows) else 1


if __name__ == '__main__':
    sys.exit(main())
