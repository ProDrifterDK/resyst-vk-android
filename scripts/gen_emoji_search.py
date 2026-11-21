#!/usr/bin/env python3
"""✦ Resyst VK — generate the emoji search index asset from the pinned CLDR 49 annotations.

Inputs (pinned, offline, sha256 checked on the upstream bytes):
  scripts/data/cldr-49/annotations/{es,es_419,en}.xml.gz         CLDR 49 annotations (names + keywords)
  scripts/data/cldr-49/annotationsDerived/{es,es_419,en}.xml.gz  CLDR 49 derived annotations (flags, keycaps, sequences)
  scripts/data/cldr-49/LICENSE                                    the CLDR repository's licence (Unicode License v3)
  scripts/data/emoji-search-extra.tsv                             Resyst's own added keywords (each with its reason)
  app/src/main/assets/emoji/emoji.txt                             the catalog: which emoji exist and their order
Output: app/src/main/assets/emoji/search.txt, read by core/EmojiSearch.kt.

Only the catalog's base cells are indexed (a toned variant is found through its base and shown in the user's tone).
Spanish = es + es_419 (the es_419 name wins when it has its own; keywords are the union); English = en.

Folding (the same in core/EmojiSearch.fold): NFD, combining marks dropped, lower case; a token is a run of letters
(L*) and decimal digits (Nd). Every name and keyword gives its words; a whole name and a whole keyword are tokens too
(words joined by one space), so an exact name or keyword outranks a word and a word outranks a prefix.

Format (UTF-8; lines starting with '# ' are comments):
  =E <n>                       then n lines: <emoji> TAB <Spanish name> TAB <English name>   (catalog order; id = line)
  =T <m>                       then m lines: <token> TAB <posting> [ <posting>...]          (sorted by UTF-16 units)
  a posting is <id><kind>: kind n = the whole name, k = a whole keyword, w = a word of the name, p = a word of a
  multi-word keyword (one posting per token and emoji: the strongest kind).

Usage: scripts/gen_emoji_search.py [--check]   (--check: exit 1 if the asset differs from a fresh run)
"""
import gzip
import hashlib
import os
import sys
import unicodedata
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
DATA = os.path.join(ROOT, 'scripts', 'data', 'cldr-49')
EXTRA = os.path.join(ROOT, 'scripts', 'data', 'emoji-search-extra.tsv')
CATALOG = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'emoji', 'emoji.txt')
DST = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'emoji', 'search.txt')
# sha256 of the upstream files (https://github.com/unicode-org/cldr, tag release-49, common/…), before gzip
PINNED = {
    'annotations/en.xml': '14d0e9bb5003c085250df4ac7bd7ba1e26d92e74174d7a3a2f42dc16e07fb155',
    'annotations/es.xml': 'af090fb0b18517b5692a710e74ff52dcbffe1c18ff5de54e35d8e5f3ba97edd7',
    'annotations/es_419.xml': '02ebf92b9ce337c686ee3071f7b08899acca0b05402157d5ad044613f8315899',
    'annotationsDerived/en.xml': 'a4a6c1a4c341d94179cb116623c5f44c47400a5bc55da483423ef7430ecf622e',
    'annotationsDerived/es.xml': 'f436e202a35e123c53afce849662bb6ac8b14bc4225a378394cc00502c5c4aa8',
    'annotationsDerived/es_419.xml': '1630b5a9708a6d0c2e345534e91a856e0c3064ac6a26f3d18bb5a255fdaa8b4f',
    'LICENSE': '65eb33419e22a297550a811b5b535f5e532f9a7e0114b516c0ea18afd53c3804',
}
GROUPS = ['caras', 'personas', 'naturaleza', 'comida', 'viajes', 'actividades', 'objetos', 'simbolos', 'banderas']
INHERIT = '↑↑↑'
KIND_RANK = {'n': 0, 'k': 1, 'w': 2, 'p': 3}


def read_pinned(rel):
    path = os.path.join(DATA, rel)
    raw = open(path, 'rb').read() if rel == 'LICENSE' else gzip.decompress(open(path + '.gz', 'rb').read())
    digest = hashlib.sha256(raw).hexdigest()
    if digest != PINNED[rel]:
        sys.exit(f'{rel}: sha256 {digest} != pinned {PINNED[rel]}')
    return raw


def annotations(rel):
    """cp (U+FE0F removed, as CLDR stores it) -> (tts name or None, [keywords]) ; '↑↑↑' (inherit) is dropped."""
    out = {}
    for a in ET.fromstring(read_pinned(rel)).iter('annotation'):
        cp = a.get('cp')
        text = (a.text or '').strip()
        name, kws = out.get(cp, (None, []))
        if a.get('type') == 'tts':
            if text and text != INHERIT:
                name = text
        else:
            kws = kws + [k.strip() for k in text.split('|') if k.strip() and k.strip() != INHERIT]
        out[cp] = (name, kws)
    return out


def fold(s):
    s = unicodedata.normalize('NFD', s)
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn').lower()


def is_word_char(c):
    return unicodedata.category(c) in ('Lu', 'Ll', 'Lt', 'Lm', 'Lo', 'Nd')


def words(s):
    out, cur = [], []
    for c in fold(s):
        if is_word_char(c):
            cur.append(c)
        elif cur:
            out.append(''.join(cur))
            cur = []
    if cur:
        out.append(''.join(cur))
    return out


def catalog_bases():
    seen, groups, current = set(), {g: [] for g in GROUPS}, None
    for raw in open(CATALOG, encoding='utf-8').read().splitlines():
        line = raw.strip()
        if not line or line == '#' or line.startswith('# '):
            continue
        if line.startswith('@'):
            current = line[1:] if line[1:] in groups else None
            continue
        if current is None:
            continue
        base = line.split(' ')[0]
        if base in seen:
            continue
        seen.add(base)
        groups[current].append(base)
    return [b for g in GROUPS for b in groups[g]]


def extra():
    out = {}
    for line in open(EXTRA, encoding='utf-8').read().splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) != 4 or parts[1] not in ('es', 'en') or not parts[3].strip():
            sys.exit(f'{EXTRA}: bad line (emoji, es|en, keywords, reason): {line!r}')
        out.setdefault((parts[0].replace('\ufe0f', ''), parts[1]), []).extend(k.strip() for k in parts[2].split('|') if k.strip())
    return out


def generate():
    read_pinned('LICENSE')
    es, es419, en = annotations('annotations/es.xml'), annotations('annotations/es_419.xml'), annotations('annotations/en.xml')
    des, des419, den = (annotations('annotationsDerived/es.xml'), annotations('annotationsDerived/es_419.xml'),
                        annotations('annotationsDerived/en.xml'))
    more = extra()
    bases = catalog_bases()
    rows, postings = [], {}
    missing = {'es': 0, 'en': 0}
    used_extra = set()

    def add(token, eid, kind):
        if not token:
            return
        p = postings.setdefault(token, {})
        if eid not in p or KIND_RANK[kind] < KIND_RANK[p[eid]]:
            p[eid] = kind

    for eid, base in enumerate(bases):
        cp = base.replace('\ufe0f', '')
        names, kw = {}, {}
        for lang, sources in (('es', (es419, es, des419, des)), ('en', (en, den))):
            name, keys = None, []
            for src in sources:
                n, k = src.get(cp, (None, []))
                if name is None and n:
                    name = n
                keys += k
            if (cp, lang) in more:
                keys += more[(cp, lang)]
                used_extra.add((cp, lang))
            if name is None:
                missing[lang] += 1
            names[lang] = name or ''
            kw[lang] = list(dict.fromkeys(keys))
        for lang in ('es', 'en'):
            if names[lang]:
                ws = words(names[lang])
                add(' '.join(ws), eid, 'n')
                for w in ws:
                    add(w, eid, 'w')
            for k in kw[lang]:
                ws = words(k)
                add(' '.join(ws), eid, 'k')
                if len(ws) > 1:
                    for w in ws:
                        add(w, eid, 'p')
        rows.append((base, names['es'].replace('\t', ' '), names['en'].replace('\t', ' ')))
    unused = set(more) - used_extra
    if unused:
        sys.exit(f'{EXTRA}: emoji not in the catalog: {sorted(unused)}')
    # Kotlin's String.compareTo orders by UTF-16 code units: sort the same way
    tokens = sorted(postings, key=lambda t: t.encode('utf-16-be'))
    lines = [
        '# Generated by scripts/gen_emoji_search.py from Unicode CLDR 49 (annotations + annotationsDerived: es, es_419, en)',
        '# and scripts/data/emoji-search-extra.tsv. Do not edit by hand. CLDR data © Unicode, Inc., Unicode License v3:',
        '# see NOTICE.txt in this directory. Ids = the base cells of emoji.txt in catalog order.',
        f'=E {len(rows)}',
    ]
    lines += ['\t'.join(r) for r in rows]
    lines.append(f'=T {len(tokens)}')
    for t in tokens:
        lines.append(t + '\t' + ' '.join(f'{eid}{kind}' for eid, kind in sorted(postings[t].items())))
    text = '\n'.join(lines) + '\n'
    stats = {'emoji': len(rows), 'tokens': len(tokens), 'postings': sum(len(p) for p in postings.values()),
             'no_es_name': missing['es'], 'no_en_name': missing['en'], 'bytes': len(text.encode('utf-8'))}
    return text, stats


def main():
    text, stats = generate()
    if '--check' in sys.argv:
        same = os.path.exists(DST) and open(DST, encoding='utf-8').read() == text
        print(('OK ' if same else 'DIFFERS ') + str(stats))
        sys.exit(0 if same else 1)
    with open(DST, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print(f'wrote {os.path.relpath(DST, ROOT)}: {stats}')


if __name__ == '__main__':
    main()
