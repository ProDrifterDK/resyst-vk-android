#!/usr/bin/env python3
"""r13a: check the valid-word lists against a reference Hunspell (VW1-VW5). Not part of the build.

Needs spylls (pure-Python Hunspell, MIT): run with the vk-lm venv, after
  python3 scripts/gen_valid_words.py --dump DIR
  <venv>/bin/python scripts/verify_valid_words.py DIR

Checks (each prints PASS/FAIL; exit 1 on any FAIL):
  V1 every plain-expansion form is accepted by spylls on the same pinned .dic/.aff (no invented
     forms from the affix engine).
  V2 every word of the shipped 25k / 20k frequency lists that spylls accepts (as typed or
     capitalized, Hunspell's case rules) is in the set under the same rules (nothing dropped).
  V3 the required words are valid, the typo words are not.
  V4 session s1 evidence (r13-vk-launch/evidence/items_phon.json): how many good words are valid
     and how many typos would count as valid ("leak"), plain Hunspell vs. with the enclitic layer.
"""
import json
import os
import sys

from spylls.hunspell import Dictionary

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SRC = os.path.join(ROOT, 'lexicon-src', 'hunspell')
EVIDENCE = os.environ.get('VK_S1_EVIDENCE', '')

dump = sys.argv[1]
fails = []


def check(name, ok, detail=''):
    print(f"{'PASS' if ok else 'FAIL'} {name} {detail}")
    if not ok:
        fails.append(name)


def load(lang, suffix):
    with open(os.path.join(dump, f'{lang}.{suffix}.txt'), encoding='utf-8') as fh:
        return [w for w in fh.read().split('\n') if w]


def valid(s, w):
    """Hunspell case rules, as ValidWords applies them."""
    w = w.replace('\u2019', "'")
    if w in s:
        return True
    letters = [c for c in w if c.isalpha()]
    up = sum(1 for c in letters if c.isupper())
    if up == 0:
        return False
    if w[0].isupper() and up == 1:
        return w.lower() in s
    if up == len(letters):
        lw = w.lower()
        return lw in s or lw[:1].upper() + lw[1:] in s
    return False


dicts = {'es': Dictionary.from_files(os.path.join(SRC, 'es_CL')),
         'en': Dictionary.from_files(os.path.join(SRC, 'en_US'))}
sets = {}
for lang in ('es', 'en'):
    words = load(lang, 'words')
    s = set(words)
    sets[lang] = s
    plain = load(lang, 'hunspell')
    d = dicts[lang]
    rejected = [w for w in plain if w in s and not d.lookup(w)]
    check(f'V1[{lang}] plain forms accepted by spylls', not rejected,
          f'{len(plain) - len(rejected)}/{len(plain)} sample={rejected[:10]}')
    freq = [l.strip() for l in open(os.path.join(ROOT, 'app/src/main/assets/lexicon', f'{lang}.txt'), encoding='utf-8') if l.strip()]
    # one-letter tokens are skipped: spylls 0.1.7 accepts any capital single letter through a bogus
    # root (good_forms('T') -> root 'abadiato'); the .dic has no such entries
    hun_ok = [w for w in freq if len(w) > 1 and (d.lookup(w) or d.lookup(w[:1].upper() + w[1:]))]
    missing = [w for w in hun_ok if not (valid(s, w) or valid(s, w[:1].upper() + w[1:]))]
    check(f'V2[{lang}] frequency-list words Hunspell accepts are in the set', not missing,
          f'{len(hun_ok) - len(missing)}/{len(hun_ok)} of {len(freq)} sample={missing[:10]}')

REQ = {'es': 'bastión anduvieron soberanía pusiésemos condujéramos configurarlo dámelo caído también'.split(),
       'en': 'bastion without fighting'.split()}
BAD = 'calna listl busvamos impoetsnte pkxel clnfkgurarlo'.split()
for lang, ws in REQ.items():
    miss = [w for w in ws if not valid(sets[lang], w)]
    check(f'V3[{lang}] required words valid', not miss, f'missing={miss}')
for lang in ('es', 'en'):
    leak = [w for w in BAD if valid(sets[lang], w)]
    check(f'V3[{lang}] typo words invalid', not leak, f'valid={leak}')

if os.path.exists(EVIDENCE):
    items = json.load(open(EVIDENCE, encoding='utf-8'))
    plain_es = set(load('es', 'hunspell')) & sets['es']
    full = sets['es'] | sets['en']
    plain = plain_es | sets['en']
    rows = {}
    for it in items:
        cat, typed, gold = it['cat'], it['typed'], it['gold']
        r = rows.setdefault(cat, {'n': 0, 'gold_valid_plain': 0, 'gold_valid': 0, 'typed_valid_plain': 0, 'typed_valid': 0, 'typed_leak_new': []})
        r['n'] += 1
        r['gold_valid_plain'] += valid(plain, gold) or valid(plain, gold[:1].upper() + gold[1:])
        r['gold_valid'] += valid(full, gold) or valid(full, gold[:1].upper() + gold[1:])
        tp = valid(plain, typed)
        tf = valid(full, typed)
        r['typed_valid_plain'] += tp
        r['typed_valid'] += tf
        if tf and not tp:
            r['typed_leak_new'].append(f'{typed}->{gold}')
    print('V4 session s1 evidence (es+en sets; plain = Hunspell only, full = + enclitic layer):')
    for cat in sorted(rows):
        r = rows[cat]
        print(f"   {cat:11s} n={r['n']:3d} gold valid {r['gold_valid_plain']:3d} -> {r['gold_valid']:3d}   "
              f"typed valid {r['typed_valid_plain']:3d} -> {r['typed_valid']:3d}   new typed-valid by enclitics: {r['typed_leak_new']}")
else:
    print('V4 skipped: evidence file not found')

print('RESULT', 'FAIL ' + ', '.join(fails) if fails else 'PASS')
sys.exit(1 if fails else 0)
