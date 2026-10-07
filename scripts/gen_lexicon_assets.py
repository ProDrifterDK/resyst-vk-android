#!/usr/bin/env python3
"""Convert a frequency-ordered lexicon into the plain-text assets the IME loads.

Input: a JS/JSON file holding {"es": [...], "en": [...]} (words already sorted by
frequency, most frequent first) or two FrequencyWords-style "word count" files.
Output: app/src/main/assets/lexicon/{es,en}.txt — one word per line, frequency order.

Usage:
  scripts/gen_lexicon_assets.py --json lexicon.js
  scripts/gen_lexicon_assets.py --es es_50k.txt --en en_50k.txt [--es-n 12000 --en-n 8000]
"""
import argparse
import json
import os
import re

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'lexicon')
# letters with inner apostrophes only: subtitle-tokenizer fragments ('s, 't, 'cause) are not words
WORD_RE = re.compile(r"^[a-záéíóúüñ]+(?:'[a-záéíóúüñ]+)*$")


def from_freq(path, n):
    words = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            w = line.split(' ')[0].strip().lower()
            if WORD_RE.match(w) and w not in words:
                words.append(w)
            if len(words) >= n:
                break
    return words


def from_json(path):
    src = open(path, encoding='utf-8').read()
    start = src.index('= {') + 2 if '= {' in src else src.index('{')
    body = src[start:src.rindex('}') + 1]
    return json.loads(body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--json')
    ap.add_argument('--es')
    ap.add_argument('--en')
    ap.add_argument('--es-n', type=int, default=12000)
    ap.add_argument('--en-n', type=int, default=8000)
    a = ap.parse_args()
    if a.json:
        lex = from_json(a.json)
    else:
        lex = {'es': from_freq(a.es, a.es_n), 'en': from_freq(a.en, a.en_n)}
    os.makedirs(OUT, exist_ok=True)
    for lang in ('es', 'en'):
        seen, words = set(), []
        for w in lex[lang]:
            if w not in seen:
                seen.add(w)
                words.append(w)
        with open(os.path.join(OUT, lang + '.txt'), 'w', encoding='utf-8') as fh:
            fh.write('\n'.join(words) + '\n')
        print(lang, len(words), words[:6])


if __name__ == '__main__':
    main()
