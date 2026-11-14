Resyst VK lexicon assets: what each file is and its license
============================================================

es.txt, en.txt
    Frequency-ordered word lists adapted from FrequencyWords (Hermit Dave).
    License: CC BY-SA 4.0. See LICENSE-FrequencyWords.txt.

regional/es-419.txt, regional/es-CL.txt
    Resyst VK's own hand-written re-rank rules for the Spanish list (no third-party data).

valid-es.dawg
    Spanish valid-word set (every full form, plus verb + clitic forms), generated from the RLA-ES
    Hunspell dictionary by Santiago Bosio. License: the same choice the dictionary offers,
    GPL-3+ or LGPL-3+ or MPL-1.1+; inside Resyst VK (GPL-3.0-or-later) it is used under GPL-3+.
    Notice and MPL text: LICENSE-Hunspell-es.txt; GPL text: ../LICENSE-GPL-3.txt in this package
    (the LICENSE file of the repository root). The file header says the same: the three-way offer,
    used under GPL-3+.

valid-en.dawg
    English valid-word set (every full form), generated from the en_US Hunspell dictionary, which
    comes from SCOWL (Kevin Atkinson and others). License: the SCOWL copyright and permission
    notices, reproduced in full in LICENSE-Hunspell-en_US.txt.

Source form of both .dawg files: the pinned Hunspell files in lexicon-src/hunspell/ and the
generator scripts/gen_valid_words.py, in the Resyst VK source repository. Nothing in this
directory is downloaded at runtime: the keyboard reads these files from the app package only.
