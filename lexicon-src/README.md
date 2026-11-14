# lexicon-src — sources of the valid-word sets (r13a)

`scripts/gen_valid_words.py` turns the pinned Hunspell files in `hunspell/` into
`app/src/main/assets/lexicon/valid-{es,en}.dawg` and writes `valid-words.manifest` (file, sha256,
words, arcs, bytes). The outputs are committed: a Gradle build never runs Python.
`Round13ValidWordsTest` fails if a shipped asset no longer matches the manifest (hand edit, or a
generator change that was not re-run).

## Regenerate / check

```
python3 scripts/gen_valid_words.py            # rewrite the assets + manifest (stdlib only, ~20 s, ~0.7 GB RAM)
python3 scripts/gen_valid_words.py --check    # regenerate in memory, fail if the committed files differ
python3 scripts/gen_valid_words.py --dump DIR # also write the word lists, then:
<venv with spylls 0.1.7>/bin/python scripts/verify_valid_words.py DIR
```

Deterministic: sorted input, no hash-order output, no dates; two runs give byte-identical files.

## Recorded outputs (r13a)

| file | sha256 | words | arcs | bytes |
|---|---|---|---|---|
| `valid-es.dawg` | `609c76b5216cf30d3620ccf6169d6ac917637d067b36df2d4a91817107e1cf11` | 2 267 583 | 115 825 | 463 760 |
| `valid-en.dawg` | `d7070048a31249f66957b7bd4e52f9d6c3452debb40ee46383f266ef7c2a2b8a` | 166 768 | 140 814 | 563 624 |

r13c: only the Spanish header notice changed (it said "used under MPL-1.1+"; it now names the
three-way offer and the GPL-3+ use), so `valid-es.dawg` grew by 56 bytes and its sha256 changed
(was `085aaf96…8ef9`, 463 704 bytes); words, arcs and states are identical, `valid-en.dawg` is
byte-identical. Two generator runs gave the same sha256.

Spanish = 656 262 Hunspell forms + 1 611 322 verb + clitic forms the dictionary does not list
(1 669 307 generated, the rest already in Hunspell); one form with periods (`DD.HH.`) is left
out. English = 166 788 Hunspell forms minus 20 digit forms (`1st`, `2nd`, …).

Checks at generation time (generator log): the enclitic layer reproduces 34 051 of the 34 092
forms RLA-ES's own enclitic flags produce (99.9 %); the 41 it does not are RLA-ES misspellings
such as `traiéndolo` (for trayéndolo) and `segúyalo` (for síguelo). `verify_valid_words.py`:
every plain form (656 262 es, 166 788 en) is accepted by spylls on the same files, and every word
of the shipped 25k / 20k frequency lists that spylls accepts is in the set.

## Licenses of the derived files

- `valid-es.dawg`: GPL-3+ or LGPL-3+ or MPL-1.1+, the same choice RLA-ES (Santiago Bosio) offers;
  Resyst VK, licensed GPL-3.0-or-later, uses it under GPL-3+. See `hunspell/NOTICE-es.txt` and the
  shipped `assets/lexicon/LICENSE-Hunspell-es.txt`. This directory plus the generator is its
  source form.
- `valid-en.dawg`: SCOWL copyright and permission notice, see `hunspell/NOTICE-en_US-SCOWL.txt`
  and the shipped `assets/lexicon/LICENSE-Hunspell-en_US.txt`.

Each asset also carries its notice in its header (`ValidWords.notice`).
