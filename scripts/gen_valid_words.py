#!/usr/bin/env python3
"""r13a: expand the pinned Hunspell dictionaries into the valid-word assets (VW1-VW7).

Inputs (nothing else is read): lexicon-src/hunspell/{es_CL,en_US}.{dic,aff}, checked against
lexicon-src/hunspell/SHA256SUMS before anything runs (VW6).

Outputs:
  app/src/main/assets/lexicon/valid-es.dawg, valid-en.dawg   (format below)
  lexicon-src/valid-words.manifest                            (sha256 / counts, read by a JVM test)

What goes in (per language):
  1. Every full form Hunspell derives from a .dic entry: the root (unless ONLYINCOMPOUND), each
     suffix and prefix rule whose condition matches, twofold suffixes (continuation classes) and
     prefix x suffix cross products. Compound words are not generated (en_US COMPOUNDRULE only
     builds ordinals such as 21st). Forms with anything but letters and inner apostrophes are left
     out (digits, periods, hyphens): the keyboard only ever looks up letter words.
  2. Spanish only: verb + clitic forms (VW1). RLA-ES flags infinitive enclitics on 2114 of its
     9246 conjugated verbs, so Hunspell itself rejects "configurarlo". For every conjugated verb
     the bases are taken from that verb's own Hunspell paradigm (never guessed from the
     infinitive, VW2): infinitive, gerund, tu (3sg present), usted / ustedes (present subjunctive),
     nosotros (1pl subjunctive), vosotros (-d); tu imperatives that are not the 3sg present come
     from a closed table (di, haz, pon, ten, ven, sal). One accentuation routine places the
     written accent (configurar + selo -> configurarselo with accent, cuenta + me -> cuentame
     with accent on the stressed e, vestid + os -> vestios with the hiatus accent).

Case and accents are kept exactly as Hunspell writes them; lookups apply Hunspell's case rules
(com.resyst.vk.core.ValidWords).

Asset format (big-endian, read by ValidWords):
  "VKVW"  int version(1)  int words  int root  int arcs  u16 alphabetSize  alphabet (u16 each)
  u16 noticeBytes  notice (UTF-8)  zero padding to a multiple of 4  arcs x int32
  A minimized DAWG. Arc = label (bits 0-7, index into the sorted alphabet) | final (bit 8: a word
  ends after this arc) | last (bit 9: last arc of its node) | target (bits 10-31: index of the
  first arc of the target node, 0 = no arcs). Arc 0 is a sentinel; the root's arcs start at `root`.

Usage:
  python3 scripts/gen_valid_words.py              # regenerate assets + manifest
  python3 scripts/gen_valid_words.py --check      # regenerate in memory, fail if files differ
  python3 scripts/gen_valid_words.py --dump DIR   # also write the plain word lists (for
                                                  # scripts/verify_valid_words.py)
Deterministic: the same inputs give byte-identical outputs (sorted input, no hash-order output,
no dates).
"""
import argparse
import hashlib
import os
import re
import struct
import sys
import unicodedata

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SRC = os.path.join(ROOT, 'lexicon-src', 'hunspell')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'lexicon')
MANIFEST = os.path.join(ROOT, 'lexicon-src', 'valid-words.manifest')

LANGS = (('es', 'es_CL'), ('en', 'en_US'))
NOTICE = {
    'es': 'Resyst VK valid-word set (es). Derived from the RLA-ES Hunspell dictionary by Santiago '
          'Bosio (hunspell-es 1:24.2.1-1), which offers GPL-3+, LGPL-3+ or MPL-1.1+; Resyst VK uses it '
          'under GPL-3+, and so does this file. Source form: lexicon-src/hunspell + '
          'scripts/gen_valid_words.py in the Resyst VK repository.',
    'en': 'Resyst VK valid-word set (en). Derived from the en_US Hunspell dictionary (SCOWL, '
          'Copyright 2000-2011 Kevin Atkinson and others; hunspell-en-us 1:2020.12.07-2). The '
          'SCOWL copyright and permission notice is in LICENSE-Hunspell-en_US.txt.',
}

# aff directives this generator implements or can safely ignore; anything else refuses (VW3/VW6)
IGNORED = {'SET', 'TRY', 'REP', 'MAP', 'KEY', 'WORDCHARS', 'NOSUGGEST', 'ICONV', 'OCONV',
           'COMPOUNDMIN', 'COMPOUNDRULE', 'ONLYINCOMPOUND', 'FLAG', 'NAME', 'VERSION', 'HOME',
           'LANG', 'MAXDIFF', 'ONLYMAXDIFF', 'MAXNGRAMSUGS', 'NOSPLITSUGS', 'SUGSWITHDOTS'}
WORD_RE = re.compile(r"^[^\W\d_]+(?:'[^\W\d_]+)*$")


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        h.update(fh.read())
    return h.hexdigest()


def check_inputs():
    sums = {}
    with open(os.path.join(SRC, 'SHA256SUMS'), encoding='utf-8') as fh:
        for line in fh:
            if line.strip():
                digest, name = line.split()
                sums[name] = digest
    for _, base in LANGS:
        for ext in ('dic', 'aff'):
            name = f'{base}.{ext}'
            got = sha256(os.path.join(SRC, name))
            if sums.get(name) != got:
                sys.exit(f'refusing: {name} sha256 {got} does not match SHA256SUMS ({sums.get(name)})')


# ── Hunspell affix file ─────────────────────────────────────────────────────────────────────

class Rule:
    __slots__ = ('flag', 'strip', 'add', 'cont', 'cond', 'ncond', 'suffix')

    def __init__(self, flag, strip, add, cont, cond, suffix):
        self.flag, self.strip, self.add, self.cont, self.suffix = flag, strip, add, cont, suffix
        self.ncond = 0
        rx = ''
        i = 0
        while i < len(cond):  # Hunspell condition: literal chars, '.', [set], [^set]
            c = cond[i]
            if c == '[':
                j = cond.index(']', i)
                body = cond[i + 1:j]
                neg = body.startswith('^')
                chars = body[1:] if neg else body
                rx += '[' + ('^' if neg else '') + ''.join(re.escape(ch) for ch in chars) + ']'
                i = j + 1
            elif c == '.':
                rx += '.'
                i += 1
            else:
                rx += re.escape(c)
                i += 1
            self.ncond += 1
        self.cond = re.compile(('^' + rx) if not suffix else (rx + '$')) if cond != '.' else None

    def applies(self, word):
        """Hunspell's checkword in reverse: the stem left after removing strip is non-empty."""
        if self.suffix:
            if not word.endswith(self.strip) or len(word) <= len(self.strip):
                return False
        else:
            if not word.startswith(self.strip) or len(word) <= len(self.strip):
                return False
        if len(word) < self.ncond:
            return False
        return self.cond is None or self.cond.search(word) is not None

    def apply(self, word):
        if self.suffix:
            return word[:len(word) - len(self.strip)] + self.add
        return self.add + word[len(self.strip):]


class Aff:
    def __init__(self, path):
        self.sfx = {}    # flag -> (cross, [Rule])
        self.pfx = {}
        self.onlyincompound = None
        self.compound_flags = set()
        pending = None
        with open(path, encoding='utf-8') as fh:
            for raw in fh:
                line = raw.split('#', 1)[0] if raw.lstrip().startswith('#') else raw
                parts = line.split()
                if not parts:
                    continue
                key = parts[0]
                if key in ('SFX', 'PFX'):
                    table = self.sfx if key == 'SFX' else self.pfx
                    flag = parts[1]
                    if pending and pending[0] == key and pending[1] == flag and pending[2] > 0:
                        strip = '' if parts[2] == '0' else parts[2]
                        add, _, cont = parts[3].partition('/')
                        add = '' if add == '0' else add
                        cond = parts[4] if len(parts) > 4 else '.'
                        table[flag][1].append(Rule(flag, strip, add, cont, cond, key == 'SFX'))
                        pending[2] -= 1
                    else:
                        if flag in table:
                            sys.exit(f'refusing: {key} {flag} declared twice')
                        table[flag] = (parts[2] == 'Y', [])
                        pending = [key, flag, int(parts[3])]
                    continue
                if key == 'FLAG' and parts[1] not in ('UTF-8',):
                    sys.exit(f'refusing: FLAG {parts[1]} not supported')
                if key == 'SET' and parts[1] != 'UTF-8':
                    sys.exit(f'refusing: SET {parts[1]} not supported')
                if key == 'ONLYINCOMPOUND':
                    self.onlyincompound = parts[1]
                    continue
                if key == 'COMPOUNDRULE':
                    if len(parts) > 1 and not parts[1].isdigit():
                        self.compound_flags |= {c for c in parts[1] if c not in '*?()'}
                    continue
                if key not in IGNORED:
                    sys.exit(f'refusing: aff directive {key} is not implemented')


def read_dic(path):
    entries = []
    with open(path, encoding='utf-8') as fh:
        next(fh)  # count
        for raw in fh:
            line = raw.rstrip('\n')
            if not line.strip():
                continue
            field = line.split()[0] if (' ' in line or '\t' in line) else line
            word, _, flags = field.partition('/')
            entries.append((word, flags))
    return entries


def expand(aff, word, flags):
    """[(form, outer_flag)] for one .dic entry; outer_flag None = the root itself."""
    out = []
    if aff.onlyincompound and aff.onlyincompound in flags:
        return out
    out.append((word, None))
    crossed = []  # suffixed forms that may still take a cross-product prefix
    for f in flags:
        if f not in aff.sfx:
            continue
        cross, rules = aff.sfx[f]
        for r in rules:
            if not r.applies(word):
                continue
            w1 = r.apply(word)
            out.append((w1, f))
            if cross:
                crossed.append(w1)
            for g in r.cont:  # twofold suffix: g is a suffix class allowed after this suffix
                if g not in aff.sfx:
                    continue
                cross2, rules2 = aff.sfx[g]
                for r2 in rules2:
                    if r2.applies(w1):
                        w2 = r2.apply(w1)
                        out.append((w2, f))
                        if cross and cross2:
                            crossed.append(w2)
    for f in flags:
        if f not in aff.pfx:
            continue
        cross, rules = aff.pfx[f]
        for r in rules:
            if r.applies(word):
                out.append((r.apply(word), f))
            if cross:
                for w1 in crossed:
                    if r.applies(w1):
                        out.append((r.apply(w1), f))
    return out


# ── Spanish enclitics (VW1/VW2) ─────────────────────────────────────────────────────────────

VERB_FLAGS = set('REIX')        # RLA-ES conjugation classes (each rule applies to an infinitive)
PARADIGM_FLAGS = VERB_FLAGS | {'D'}  # + participles
ACC = {'a': 'á', 'e': 'é', 'i': 'í', 'o': 'ó', 'u': 'ú'}
UNACC = {v: k for k, v in ACC.items()}
STRONG = set('aeoáéíóú')        # an accented i/u is a hiatus: it behaves like a strong vowel
VOWELS = set('aeiouáéíóúü')

ALL1 = ('me', 'te', 'se', 'nos', 'os', 'lo', 'la', 'los', 'las', 'le', 'les')
ACCUS = ('lo', 'la', 'los', 'las')
# attested two-clitic clusters (the ones RLA-ES's own enclitic rules use)
ALL2 = tuple((a, b) for a in ('me', 'te', 'se', 'nos', 'os') for b in ACCUS) + \
    tuple(('se', b) for b in ('me', 'te', 'nos', 'os', 'le', 'les'))
# per base: (singles, doubles); a leading '-' drops the base's last letter (nosotros -s, vosotros -d)
SETS = {
    'inf': (ALL1, ALL2),
    'ger': (ALL1, ALL2),
    'tu': (('me', 'te', 'nos', 'lo', 'la', 'los', 'las', 'le', 'les'),
           tuple((a, b) for a in ('me', 'te', 'se', 'nos') for b in ACCUS)),
    'usted': (('me', 'se', 'nos', 'lo', 'la', 'los', 'las', 'le', 'les'),
              tuple((a, b) for a in ('me', 'se', 'nos') for b in ACCUS)),
    'nos': (('lo', 'la', 'los', 'las', 'le', 'les', 'te', '-nos'),
            tuple(('te', b) for b in ACCUS) + tuple(('-se', b) for b in ACCUS) + tuple(('-nos', b) for b in ACCUS)),
    'vos': (('me', 'nos', 'lo', 'la', 'los', 'las', 'le', 'les', '-os'),
            tuple((a, b) for a in ('me', 'nos', 'se') for b in ACCUS)),
}
# tu imperatives that are not the 3sg present (infinitive ending -> imperative ending)
IRREGULAR_TU = (('decir', 'di', True), ('hacer', 'haz', False), ('poner', 'pon', False),
                ('tener', 'ten', False), ('venir', 'ven', False), ('salir', 'sal', False))


def is_vowel(w, i):
    c = w[i]
    if c not in VOWELS:
        return False
    if c == 'u' and i > 0 and w[i - 1] == 'q':
        return False
    if c == 'u' and i > 0 and w[i - 1] == 'g' and i + 1 < len(w) and w[i + 1] in 'eiéí':
        return False
    return True


def nuclei(w):
    """Syllable nuclei as lists of vowel indexes (h and y break groups; strong-strong splits)."""
    res = []
    i = 0
    while i < len(w):
        if not is_vowel(w, i):
            i += 1
            continue
        cur = [i]
        j = i + 1
        while j < len(w) and is_vowel(w, j):
            if w[j] in STRONG and w[cur[-1]] in STRONG:  # two strong vowels (or an accented i/u): hiatus
                res.append(cur)
                cur = [j]
            else:
                cur.append(j)
            j += 1
        res.append(cur)
        i = j
    return res


def stressed(w):
    """Index of the stressed vowel of a single word (written accent, else the default rule)."""
    for i, c in enumerate(w):
        if c in UNACC:
            return i
    ns = nuclei(w)
    if not ns:
        return None
    n = ns[-2] if (len(ns) > 1 and (w[-1] in VOWELS or w[-1] in 'ns')) else ns[-1]
    for i in n:
        if w[i] in 'aeo':
            return i
    return n[-1]


def attach(base, clitics, drop=False):
    """base + clitics with the written accent the result needs (RAE: enclitic forms follow the
    general rules; a stressed i/u next to a strong vowel keeps its hiatus accent)."""
    si = stressed(base)
    if si is None:
        return None
    stem = base[:-1] if drop else base
    stem = stem[:si] + UNACC.get(stem[si], stem[si]) + stem[si + 1:]
    w = stem + clitics
    v = w[si]
    need = False
    if v in 'iu' and ((si + 1 < len(w) and w[si + 1] in 'aeo') or (si > 0 and w[si - 1] in 'aeo')):
        need = True
    else:
        ns = nuclei(w)
        idx = next((k for k, n in enumerate(ns) if si in n), None)
        if idx is None:
            return None
        pos = len(ns) - idx
        if pos >= 3:
            need = True
        elif pos == 2:
            need = w[-1] not in VOWELS and w[-1] not in 'ns'
        else:
            need = w[-1] in VOWELS or w[-1] in 'ns'
    if need:
        w = w[:si] + ACC[v] + w[si + 1:]
    return w


def verb_bases(inf, P):
    """{kind: [base]} found in the verb's own paradigm P (VW2: nothing is guessed)."""
    b = {'inf': [inf]}
    li = len(inf)
    ar = inf.endswith('ar')
    b['ger'] = sorted(g for g in P if g.endswith('ndo') and len(g) >= li + 2)
    irregular = None
    for end, form, exact in IRREGULAR_TU:
        if (inf == end) if exact else inf.endswith(end):
            pre = inf[:len(inf) - len(end)]
            t = pre + form
            if pre and t.endswith('n'):
                t = t[:-2] + ACC[t[-2]] + t[-1]   # compón, mantén, convén
            irregular = t
    if inf in ('ser', 'ir'):
        b['tu'] = []
    elif irregular:
        b['tu'] = [irregular]
    else:
        end, lo, hi = ('a', li - 1, li) if ar else ('e', li - 2, li + 1)
        b['tu'] = sorted(t for t in P if t.endswith(end) and lo <= len(t) <= hi
                         and t + 's' in P and t + 'n' in P)
    end, lo, hi = ('e', li - 2, li + 1) if ar else ('a', li - 2, li + 1)
    us = []
    for s in P:
        if not (s.endswith(end) and lo <= len(s) <= hi and s + 's' in P and s + 'n' in P):
            continue
        if s == inf + 'e' or (ar and s == inf[:-1] + 'se'):  # future / -se imperfect subjunctive
            continue
        if not ar and s.endswith('ía') and not inf.endswith('eír'):  # imperfect indicative
            continue
        if s.endswith('ra') and s[:-2] + 'se' in P:  # -ra imperfect subjunctive (riera)
            continue
        us.append(s)
    b['usted'] = sorted(us) + sorted(s + 'n' for s in us)
    if ar:
        b['nos'] = sorted(n for n in P if n.endswith('emos') and n != inf + 'emos'
                          and li + 2 <= len(n) <= li + 3 and n[:-4] + 'éis' in P)
    else:
        # li + 1: seguir -> sigamos (gu -> g); li + 4: traer -> traigamos
        b['nos'] = sorted(n for n in P if n.endswith('amos') and not n.endswith('íamos')
                          and li + 1 <= len(n) <= li + 4 and n[:-4] + 'áis' in P)
    v = (inf[:-2] + 'íd') if inf.endswith('ír') else (inf[:-1] + 'd')
    b['vos'] = [v] if v in P else []
    return b


def enclitics(inf, P):
    out = []
    for kind, bases in verb_bases(inf, P).items():
        singles, doubles = SETS[kind]
        for base in bases:
            if kind == 'tu' and base not in P:
                out.append(base)  # the irregular tu imperative itself (compón, mantén)
            for c in singles:
                drop = c.startswith('-')
                if drop and kind == 'vos' and inf == 'ir':
                    drop = False  # idos
                w = attach(base, c.lstrip('-'), drop)
                if w:
                    out.append(w)
            for a, c in doubles:
                w = attach(base, a.lstrip('-') + c, a.startswith('-'))
                if w:
                    out.append(w)
    return out


# ── DAWG ────────────────────────────────────────────────────────────────────────────────────

class Node:
    __slots__ = ('final', 'edges', 'id')

    def __init__(self):
        self.final = False
        self.edges = {}
        self.id = -1


def build_dawg(words):
    """Daciuk et al. incremental construction over sorted input: a minimal acyclic automaton."""
    register = {}
    root = Node()
    unchecked = []
    prev = ''
    ids = [0]

    def minimize(down_to):
        while len(unchecked) > down_to:
            parent, ch, child = unchecked.pop()
            sig = (child.final, tuple((c, n.id) for c, n in child.edges.items()))
            hit = register.get(sig)
            if hit is not None:
                parent.edges[ch] = hit
            else:
                ids[0] += 1
                child.id = ids[0]
                register[sig] = child

    for w in words:
        common = 0
        for a, c in zip(w, prev):
            if a != c:
                break
            common += 1
        minimize(common)
        node = unchecked[-1][2] if unchecked else root
        for ch in w[common:]:
            nxt = Node()
            node.edges[ch] = nxt
            unchecked.append((node, ch, nxt))
            node = nxt
        node.final = True
        prev = w
    minimize(0)
    return root, len(register) + 1


def serialize(root, words, notice):
    alphabet = sorted({c for w in words for c in w})
    if len(alphabet) > 256:
        sys.exit(f'refusing: alphabet of {len(alphabet)} symbols does not fit 8 bits')
    if any(ord(c) > 0xFFFF for c in alphabet):
        sys.exit('refusing: a symbol outside the BMP')
    label = {c: i for i, c in enumerate(alphabet)}
    order = []
    seen = set()

    def visit(n):  # post-order, children in label order: deterministic
        if id(n) in seen:
            return
        seen.add(id(n))
        for c in sorted(n.edges):
            visit(n.edges[c])
        order.append(n)

    sys.setrecursionlimit(10000)
    visit(root)
    start = {}
    nxt = 1  # arc 0: sentinel
    for n in order:
        if n.edges:
            start[id(n)] = nxt
            nxt += len(n.edges)
    if nxt >= (1 << 22):
        sys.exit(f'refusing: {nxt} arcs do not fit 22 bits')
    arcs = [0] * nxt
    for n in order:
        if not n.edges:
            continue
        i = start[id(n)]
        keys = sorted(n.edges)
        for k, c in enumerate(keys):
            child = n.edges[c]
            a = label[c] | (0x100 if child.final else 0) | (0x200 if k == len(keys) - 1 else 0)
            a |= start.get(id(child), 0) << 10
            arcs[i + k] = a
    nb = notice.encode('utf-8')
    head = b'VKVW' + struct.pack('>iiii', 1, len(words), start[id(root)], len(arcs))
    head += struct.pack('>H', len(alphabet)) + b''.join(struct.pack('>H', ord(c)) for c in alphabet)
    head += struct.pack('>H', len(nb)) + nb
    head += b'\0' * (-len(head) % 4)
    return head + struct.pack(f'>{len(arcs)}I', *arcs), len(arcs), alphabet


def dawg_words(data):
    """Decode an asset back into its sorted word list (self-check of the serializer)."""
    n_words, root, n_arcs = struct.unpack_from('>iii', data, 8)
    na = struct.unpack_from('>H', data, 20)[0]
    alphabet = [chr(struct.unpack_from('>H', data, 22 + 2 * i)[0]) for i in range(na)]
    p = 22 + 2 * na
    nb = struct.unpack_from('>H', data, p)[0]
    p += 2 + nb
    p += -p % 4
    arcs = struct.unpack_from(f'>{n_arcs}I', data, p)
    out = []

    def walk(i, prefix):
        while True:
            a = arcs[i]
            w = prefix + alphabet[a & 0xFF]
            if a & 0x100:
                out.append(w)
            if a >> 10:
                walk(a >> 10, w)
            if a & 0x200:
                return
            i += 1

    walk(root, '')
    return n_words, out


# ── main ────────────────────────────────────────────────────────────────────────────────────

def generate(lang, base, log):
    aff = Aff(os.path.join(SRC, base + '.aff'))
    entries = read_dic(os.path.join(SRC, base + '.dic'))
    plain = set()
    paradigms = {}  # infinitive -> forms from conjugation flags (es)
    hun_enclitic = {}  # infinitive -> forms RLA-ES's own enclitic flags produce (for the recall check)
    # RLA-ES's own enclitic classes: every rule adds clitics after a verb ending (r, ndo, a vowel,
    # d, n, s) to an infinitive; "-ble" / "-illo" end in le / lo but are not clitics
    clitic_end = re.compile('(me|te|se|nos|os|lo|la|los|las|le|les){1,3}$')

    def enclitic_rule(r):
        m = clitic_end.search(r.add)
        return (m is not None and (m.start() == 0 or r.add[m.start() - 1] in 'aeiouáéíóúrdns')
                and r.cond is not None and r.cond.pattern.endswith('r$'))
    enclitic_flags = {f for f, (_, rules) in aff.sfx.items()
                      if rules and all(enclitic_rule(r) for r in rules)} if lang == 'es' else set()
    for word, flags in entries:
        if aff.compound_flags & set(flags) and not any(ch.isdigit() for ch in word):
            sys.exit(f'refusing: compound-rule flag on a letter word {word}/{flags}')
        forms = expand(aff, word, flags)
        for f, _ in forms:
            plain.add(f)
        if lang == 'es' and set(flags) & VERB_FLAGS and re.search('(ar|er|ir|ír)$', word):
            p = paradigms.setdefault(word, set())
            p.add(word)
            e = hun_enclitic.setdefault(word, set())
            for f, fl in forms:
                if fl in PARADIGM_FLAGS:
                    p.add(f)
                elif fl in enclitic_flags:
                    e.add(f)
    extra = set()
    gen_by_verb = {}
    if lang == 'es':
        for inf in sorted(paradigms):
            g = set(enclitics(inf, paradigms[inf]))
            gen_by_verb[inf] = g
            extra |= g
    non_nfc = sum(1 for w in plain | extra if unicodedata.normalize('NFC', w) != w)
    if non_nfc:
        sys.exit(f'refusing: {non_nfc} forms are not NFC')
    kept = sorted(w for w in plain | extra if WORD_RE.match(w))
    dropped = sorted(w for w in plain | extra if not WORD_RE.match(w))
    log(f'[{lang}] entries={len(entries)} hunspell_forms={len(plain)} enclitic_forms={len(extra)} '
        f'new_enclitic={len(extra - plain)} dropped_nonletter={len(dropped)} words={len(kept)}')
    log(f'[{lang}] dropped sample: {dropped[:12]}')
    if lang == 'es':
        # VW2 measured against RLA-ES's own enclitic forms (verbs that carry enclitic flags)
        flagged = [v for v in sorted(hun_enclitic) if hun_enclitic[v]]
        hun = set().union(*(hun_enclitic[v] for v in flagged))
        gen = set().union(*(gen_by_verb[v] for v in flagged))
        log(f'[es] RLA-ES enclitic flags: {"".join(sorted(enclitic_flags))}')
        miss = sorted(hun - gen)
        wrong = sorted(gen - plain)
        log(f'[es] RLA-ES enclitic forms not generated (sample): {miss[::max(1, len(miss) // 40)][:40]}')
        log(f'[es] generated forms Hunspell rejects (sample): {wrong[::max(1, len(wrong) // 40)][:40]}')
        log(f'[es] enclitic check over {len(flagged)} verbs with RLA-ES enclitic flags: '
            f'RLA-ES forms reproduced {len(hun & gen)}/{len(hun)} ({100 * len(hun & gen) / max(1, len(hun)):.1f}%), '
            f'generated forms Hunspell also accepts {len(gen & plain)}/{len(gen)} ({100 * len(gen & plain) / max(1, len(gen)):.1f}%)')
    return kept, plain, extra


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true', help='fail if the committed outputs differ')
    ap.add_argument('--dump', help='write plain word lists here (for verify_valid_words.py)')
    args = ap.parse_args()
    check_inputs()
    log = lambda s: print(s, file=sys.stderr)
    outputs = {}
    manifest = ['# r13a valid-word assets: written by scripts/gen_valid_words.py, read by ValidWordsTest',
                '# file sha256 words arcs bytes']
    for lang, base in LANGS:
        words, plain, extra = generate(lang, base, log)
        root, nodes = build_dawg(words)
        data, n_arcs, alphabet = serialize(root, words, NOTICE[lang])
        n, back = dawg_words(data)
        if back != words or n != len(words):
            sys.exit(f'refusing: [{lang}] the DAWG does not decode to its input')
        name = f'valid-{lang}.dawg'
        outputs[os.path.join(OUT, name)] = data
        digest = hashlib.sha256(data).hexdigest()
        manifest.append(f'{name} {digest} {len(words)} {n_arcs} {len(data)}')
        log(f'[{lang}] dawg states={nodes} arcs={n_arcs} alphabet={len(alphabet)} bytes={len(data)} sha256={digest}')
        if args.dump:
            os.makedirs(args.dump, exist_ok=True)
            for suffix, ws in (('words', words), ('hunspell', sorted(plain)), ('enclitic', sorted(extra - plain))):
                with open(os.path.join(args.dump, f'{lang}.{suffix}.txt'), 'w', encoding='utf-8') as fh:
                    fh.write('\n'.join(ws) + '\n')
    outputs[MANIFEST] = ('\n'.join(manifest) + '\n').encode('utf-8')
    if args.check:
        bad = [p for p, d in outputs.items() if not os.path.exists(p) or open(p, 'rb').read() != d]
        if bad:
            sys.exit('outputs differ from the committed files: ' + ', '.join(os.path.relpath(p, ROOT) for p in bad))
        log('check: committed outputs are identical')
        return
    for p, d in outputs.items():
        with open(p, 'wb') as fh:
            fh.write(d)
        log(f'wrote {os.path.relpath(p, ROOT)} ({len(d)} bytes)')


if __name__ == '__main__':
    main()
