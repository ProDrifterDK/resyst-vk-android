# The context-offer model (`app/src/main/assets/context/wt-m2.vkng`)

The word-trigram model behind the bar's context offers (r13f: `caza` → `casa`, `di` → `si`, `esta` → `está`). Built
on a desktop by the files here; nothing here runs on the phone and the app downloads nothing. The asset is committed
(11,773,356 bytes, stored uncompressed in the APK so the phone memory-maps it) together with its build record
`assets/context/wt-m2.provenance.json` and its licence notice `assets/context/NOTICE.txt`.

```
export NGRAM_WORK=<scratch dir>        # downloads, extracted text, the rebuilt asset (~0.9 GB peak; never committed)
tools/ngram-build/build.py corpus      # download + sha256 check + Wikipedia prose extraction (~5 min, stdlib only)
tools/ngram-build/build.py build       # java -Xmx4g NgramBuild.java ... (~1.5 min)
tools/ngram-build/build.py check       # rebuilt sha256 vs the shipped asset
```

- `extract_wiki.py`: plain prose paragraphs from a pages-articles dump (templates, tables, references, files,
  categories, lists and headings dropped; one paragraph per line). Capped at 80 M words.
- `NgramBuild.java` (JDK 21, single file, no dependencies): tokenizes with the same rules as `core/ContextTokens.kt`
  (golden cases `app/src/test/resources/r13f/offers/tokenizer-cases.tsv`, checked by both sides; the builder refuses
  to run when a case fails), counts unigrams, bigrams and trigrams with integer corpus weights, keeps Witten-Bell
  continuation-type counts computed before pruning, prunes by count, hands every pruned n-gram's mass to the backoff
  (so P(·|h) still sums to 1) and quantises counts to 1/8 of a power of two. Writes the VKNG v2 asset (layout in the
  file's header comment) and a JSON build report.
- `build.py`: the two commands of the r13e spike's `scripts/r13e_oracle.py` that build the asset (`corpus`, `build`),
  with the same pinned inputs and options; the spike's oracle and tallies are not part of the product.
- Builder commit: `ce8792c` (spike branch `spike/r13e-ngram`); the files here are that commit's, unchanged.

## Inputs (pinned)

| input | version | sha256 | licence | attribution |
|---|---|---|---|---|
| `eswiki-20261101-pages-articles1.xml-p1p159400.bz2` | Spanish Wikipedia dump of 2026-11-01, part 1 (page ids 1–159400; 47,669 articles, the first 82.0 M tokens of prose) | `9a76b2a72b88c9fc73dcfdce123fb20539827ca2d92b9992c126e0ef5769a001` | CC BY-SA 4.0 | Wikipedia contributors, *Wikipedia, la enciclopedia libre*, https://es.wikipedia.org |
| `spa_sentences.tsv.bz2` | Tatoeba per-language export, Spanish, downloaded 2026-11-14 (458,844 sentences, 3.3 M tokens; weight ×8) | `06b518dde1ba30a5547d8902c8e458ba0e45e67f22d3ca88855f701edbd5b359` | CC BY 2.0 FR (37 of them CC0) | Tatoeba contributors, https://tatoeba.org |

Extracted text fed to the builder: `eswiki-a1.txt` sha256 `9235077a15506bb1c13eb7605e91d5ff816c53cfc913f77d223f2a493ea1918b`,
`spa_sentences.tsv` sha256 `20d88de1dfca76c535800f1129fc89d36cd4ed60be6e687cd449ae922f9448a8`. Model: V 100,000,
k2 6, k3 9, 1,404,898 bigrams and 1,424,429 trigrams kept. Output sha256
`dae872a0e85e44a3f14b6ef1ad9889852f935b864509fdad8caf9a33aa9487c0`.

Tatoeba's weekly exports are not archived: a later download has another sha256 and gives another asset. The owner's
text never enters the asset; the held-out evaluation texts (Quiroga 1917, the s1–s4 sessions) are in neither input.

## Licences

- **Wikipedia, CC BY-SA 4.0.** An asset of n-gram counts is treated as an adaptation. CC BY-SA 4.0 is one-way
  compatible with **GPL-3.0 only** (Creative Commons' compatibility list names GPLv3, not "or later"). The asset is
  therefore distributed under **GPL-3.0-only**, inside the GPL-3.0-or-later app: the APK as a whole can only be
  passed on under GPL-3.0. Credited in the app's settings credits, `NOTICE.txt` and README.
- **Tatoeba, CC BY 2.0 FR.** Attribution only, no share-alike. The FSF lists CC BY 4.0 as GPL-compatible and says
  nothing about 2.0; compatibility of CC BY 2.0 FR with GPL-3.0 is not confirmed. The owner accepted it.
- No corpus with a non-commercial clause was used.
