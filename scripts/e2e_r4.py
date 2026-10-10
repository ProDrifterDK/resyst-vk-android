#!/usr/bin/env python3
"""✦ Resyst VK — round 4 on a real device: next-word prediction + personal learning.

Drives the drawn keyboard through its accessibility tree (same helpers as e2e.py) and proves:
  1. cold start: "Hola, " predicts "cómo"; picking it chains to "estás" (generic seeds)
  2. learning: after teaching "Hola, cómo vas" twice, the chain prefers the user's "vas",
     and a fresh field offers the user's opener "Hola" at the sentence start
  3. persistence: the learned chain survives killing the keyboard process
  4. email values: a synthetic address entered twice is offered by prefix; a pick commits it whole
  5. privacy: a password field shows no suggestions and its text is never written to disk
  6. "Borrar lo aprendido" (settings) wipes both stores on disk and in memory

Test data is synthetic (user@example.com). Starts by wiping only the learned files.
Artifacts: build/e2e-r4/results.json, build/e2e-r4/*.png

Usage: scripts/e2e_r4.py --serial SERIAL
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import e2e  # noqa: E402
import e2e_r10_bilingual as bl  # noqa: E402  (space flick → keyboard language)
from e2e import adb, sh, dump, keys, field_text, tap, type_word, PKG, IME, SETTINGS  # noqa: E402

OUT = os.path.join(e2e.ROOT, 'build', 'e2e-r4')
e2e.OUT = OUT
results = e2e.results
record = e2e.record
EMAIL = 'user@example.com'


def bar():
    return [d.split(': ', 1)[1] for d in keys(dump()) if d.startswith('Sugerencia: ')]


def pick(word):
    km = keys(dump())
    tap(km['Sugerencia: ' + word])
    time.sleep(0.6)


def press(name):
    # phones label the space key 'Espacio. Mantén pulsado para cambiar de teclado' (same fix as the
    # r10 scripts, 17fcf25): exact name first, else the one key whose name starts with it
    km = keys(dump())
    node = km.get(name)
    if node is None:
        node = next(n for d, n in km.items() if d.startswith(name))
    tap(node)
    time.sleep(0.3)


def shot(name):
    e2e.shot(name)


def personal_files():
    return sh(f'run-as {PKG} ls files/personal 2>/dev/null', check=False).split()


def personal_dump():
    return sh(f'run-as {PKG} cat files/personal/words.json files/personal/values.json 2>/dev/null', check=False)


def restart_keyboard():
    sh(f'am force-stop {PKG}')
    time.sleep(0.8)
    sh(f'ime enable {IME}')
    sh(f'ime set {IME}')
    spanish()


def spanish():
    """The checks below are the Spanish seeds (cómo → estás). Since r10 a cold keyboard pulls the
    system subtype (Subtypes.reconcile), which may be English: flick to Spanish like the r10 scripts."""
    e2e.open_host('text')
    bl.set_lang('es')
    record('keyboard is on Spanish for the run', bl.space_label() == 'es', str(bl.phone_lang()))


def type_hola_comma(km):
    type_word('hola', km)
    press('coma')
    press('Espacio')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    a = ap.parse_args()
    e2e.ADB.extend(['-s', a.serial])
    os.makedirs(OUT, exist_ok=True)
    started = time.time()
    model = sh('getprop ro.product.model').strip()
    sdk = sh('getprop ro.build.version.sdk').strip()
    print(f'✦ device: {model} (API {sdk})')
    try:
        # clean slate for the learned stores only (profiles/settings untouched)
        sh(f'run-as {PKG} rm -rf files/personal', check=False)
        restart_keyboard()

        # 1 — cold start: generic seeds chain
        tree, km = e2e.open_host('text')
        type_hola_comma(km)
        b = bar()
        shot('01-cold-hola-comma')
        record('cold start: "Hola, " predicts cómo', b[:1] == ['cómo'], repr(b))
        if 'cómo' in b:
            pick('cómo')
            b = bar()
            record('picking cómo chains to estás', b[:1] == ['estás'], repr(b))
            record('picked prediction committed + space', field_text(dump()) == 'Hola, cómo ', repr(field_text(dump())))

        # 2 — teach "Hola, cómo vas" twice (pick the prediction, type the continuation)
        for i in range(2):
            tree, km = e2e.open_host('text')
            type_hola_comma(km)
            pick('cómo')
            type_word('vas')
            press('Espacio')
            record(f'teach #{i + 1}: field text', field_text(dump()) == 'Hola, cómo vas ', repr(field_text(dump())))
        tree, km = e2e.open_host('text')
        b = bar()
        shot('02-learned-starter')
        record('fresh field: the user\'s opener "Hola" is suggested at sentence start', b[:1] == ['Hola'], repr(b))
        pick('Hola')
        press('coma')
        press('Espacio')
        b = bar()
        record('learned: "Hola, " still predicts cómo first', b[:1] == ['cómo'], repr(b))
        pick('cómo')
        b = bar()
        shot('03-learned-chain')
        record('learned chain: after cómo, the user\'s "vas" ranks above the seed "estás"', b[:2] == ['vas', 'estás'], repr(b))
        pick('vas')
        record('whole sentence built from three picks', field_text(dump()) == 'Hola, cómo vas ', repr(field_text(dump())))

        # 3 — persistence across a keyboard process kill
        e2e.open_host('text')  # field switch flushes the debounced save
        time.sleep(2.0)
        files = personal_files()
        record('learned words written to private storage', 'words.json' in files, repr(files))
        restart_keyboard()
        tree, km = e2e.open_host('text')
        time.sleep(1.0)
        type_hola_comma(km)
        pick('cómo')
        b = bar()
        record('after killing the keyboard process the learned chain is back', b[:1] == ['vas'], repr(b))

        # 4 — email value memory
        for i in range(2):
            tree, km = e2e.open_host('email')
            for part in ['user', 'arroba', 'example', 'punto', 'com']:
                if part in ('arroba', 'punto'):
                    tap(km[part])
                else:
                    type_word(part, km)
            txt = field_text(dump())
            record(f'email #{i + 1} typed', txt == EMAIL, repr(txt))
            press('Listo')  # IME_ACTION_DONE → remember the whole value
        tree, km = e2e.open_host('email')
        b = bar()
        record('empty email field offers the remembered address', b == [EMAIL], repr(b))
        type_word('us', km)
        time.sleep(0.5)
        b = bar()
        shot('04-email-prefix')
        record('prefix "us" offers the full address', b == [EMAIL], repr(b))
        pick(EMAIL)
        txt = field_text(dump())
        record('picking it commits the whole value (no trailing space)', txt == EMAIL, repr(txt))
        type_word('x', keys(dump()))
        record('nothing offered once the typed text diverges', bar() == [], repr(bar()))

        # 5 — privacy: password field
        tree, km = e2e.open_host('password')
        record('password field: bar is empty at start', bar() == [], repr(bar()))
        type_word('zumbido', km)
        press('Espacio')
        type_word('hola', keys(dump()))
        press('Espacio')
        b = bar()
        shot('05-password')
        record('password field: no predictions after a word', b == [], repr(b))
        type_word('us', keys(dump()))
        record('password field: no email values by prefix', bar() == [], repr(bar()))
        e2e.open_host('text')  # leave the field → any pending save runs
        time.sleep(2.0)
        stored = personal_dump()
        record('password text never reaches the learned store', 'zumbido' not in stored, f'{len(stored)} bytes stored')
        record('email field text is not learned as words', '"user"' not in stored and '"example"' not in stored)

        # 6 — settings: the toggle is there; "Borrar lo aprendido" wipes everything. Since r10 (e4c0bae)
        # settings are pages: the toggle lives on "escritura", the wipe on "privacidad".
        # A typed address + a password field make Android autofill (Google Password Manager on the
        # Pixel 6) raise "Save password?" over the next screen: dismiss it, never save.
        def dismiss_autofill():
            for n in dump().iter('node'):
                if (n.get('text') or '') in ('Not now', 'Ahora no', 'No, gracias', 'No thanks') and n.get('package') != PKG:
                    tap(n)
                    time.sleep(0.8)
                    return True
            return False

        def page_rows(page, wanted):
            sh(f'am start -W -f 0x10008000 -n {SETTINGS} --es com.resyst.vk.page {page}')
            time.sleep(1.5)
            if dismiss_autofill():
                print('  · autofill "save password" sheet dismissed (Not now)')
            got = {}
            for _ in range(8):
                for n in dump().iter('node'):
                    if n.get('text', '') in wanted:
                        got[n.get('text')] = n
                if all(w in got for w in wanted):
                    break
                sh('input swipe 540 1700 540 900 300')
                time.sleep(0.6)
            return got
        found = page_rows('escritura', ['Sugerencias personales'])
        found.update(page_rows('privacidad', ['Borrar lo aprendido']))
        record('settings: "Sugerencias personales" toggle present', 'Sugerencias personales' in found)
        record('settings: "Borrar lo aprendido" present', 'Borrar lo aprendido' in found)
        if 'Borrar lo aprendido' in found:
            st = dump()
            row = next(n for n in st.iter('node') if n.get('text') == 'Borrar lo aprendido')
            # r11a-fix (F1): the summary names each kind kept ("N palabras · 1 correo · … · nada sale
            # del teléfono"), no longer "N palabras · M correos aprendidos"
            summary = [n.get('text') for n in st.iter('node') if 'palabra' in n.get('text', '') and
                       'nada sale del teléfono' in n.get('text', '') and not n.get('text', '').startswith('Nada aprendido')]
            record('settings shows what is stored', bool(summary), repr(summary))
            shot('06-settings-personal')
            tap(row)
            time.sleep(0.8)
            dlg = dump()
            btn = next((n for n in dlg.iter('node') if n.get('text', '').lower() == 'borrar'), None)
            shot('07-confirm')
            record('confirm dialog shown', btn is not None)
            if btn is not None:
                tap(btn)
                time.sleep(1.0)
            files = personal_files()
            record('wipe deletes the files on disk', files == [], repr(files))
            st = dump()
            # r10 wording for an empty store: "Nada aprendido todavía · nada sale del teléfono"
            after = [n.get('text') or '' for n in st.iter('node') if 'aprendid' in n.get('text', '')]
            record('settings now shows 0 learned', any(t.startswith('Nada aprendido todavía') for t in after), repr(after))
            tree, km = e2e.open_host('text')
            record('after wipe: no learned opener at sentence start', bar() == [], repr(bar()))
            type_hola_comma(km)
            pick('cómo')
            b = bar()
            record('after wipe: chain is back to the generic seed', b[:1] == ['estás'] and 'vas' not in b, repr(b))
            tree, km = e2e.open_host('email')
            record('after wipe: no remembered email', bar() == [], repr(bar()))
    finally:
        passed = sum(r['ok'] for r in results)
        summary = {
            'device': model, 'api': sdk, 'passed': passed, 'total': len(results),
            'seconds': round(time.time() - started, 1), 'checks': results,
        }
        json.dump(summary, open(os.path.join(OUT, 'results.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'✦ {passed}/{len(results)} checks passed → build/e2e-r4/results.json')
    return 0 if results and all(r['ok'] for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
