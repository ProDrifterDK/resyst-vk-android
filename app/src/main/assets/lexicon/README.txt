Resyst VK lexicon assets: what each file is and its license
============================================================

es.txt, en.txt
    Frequency-ordered word lists adapted from FrequencyWords (Hermit Dave).
    License: CC BY-SA 4.0. See LICENSE-FrequencyWords.txt.

regional/es-419.txt, regional/es-CL.txt
    Resyst VK's own hand-written re-rank rules for the Spanish list (no third-party data).

valid-es.dawg
    Spanish valid-word set (every full form, plus verb + clitic forms), generated from the RLA-ES
    Hunspell dictionary by Santiago Bosio. License: MPL-1.1 or later (Mozilla Public License),
    the option Resyst VK takes from the dictionary's GPL-3+ / LGPL-3+ / MPL-1.1+ offer; this file
    stays under MPL-1.1+. Notice and license text: LICENSE-Hunspell-es.txt. The license notice is
    also embedded in the file header.

valid-en.dawg
    English valid-word set (every full form), generated from the en_US Hunspell dictionary, which
    comes from SCOWL (Kevin Atkinson and others). License: the SCOWL copyright and permission
    notices, reproduced in full in LICENSE-Hunspell-en_US.txt.

Source form of both .dawg files: the pinned Hunspell files in lexicon-src/hunspell/ and the
generator scripts/gen_valid_words.py, in the Resyst VK source repository. Nothing in this
directory is downloaded at runtime: the keyboard reads these files from the app package only.
