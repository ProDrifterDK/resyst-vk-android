Fonts bundled with Resyst VK (assets/fonts/)

DMSans-var.ttf
  DM Sans, variable weight. SIL Open Font License 1.1 (OFL.txt). The app's own voice and the default key font.

AtkinsonHyperlegibleNext-var.ttf  (r14, key font "Atkinson (legible)")
  Atkinson Hyperlegible Next, variable weight 200-800, by the Braille Institute of America.
  Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors
  (https://github.com/googlefonts/atkinson-hyperlegible-next).
  SIL Open Font License 1.1 (OFL-AtkinsonHyperlegibleNext.txt).

Nunito-var.ttf  (r14, key font "Nunito (redondeada)")
  Nunito, variable weight 200-1000. Copyright 2014 The Nunito Project Authors (https://github.com/googlefonts/nunito).
  SIL Open Font License 1.1 (OFL-Nunito.txt).

The two r14 fonts are subsets of the upstream files from github.com/google/fonts (ofl/atkinsonhyperlegiblenext,
ofl/nunito), cut with fontTools' pyftsubset to the characters a key, a variant or the bar can draw:
  U+0020-007E, U+00A0-017F, U+2010-2030, U+2039-203E, U+2070-2079, U+20A0-20BF, U+2116, U+2153-2154, U+2200-22FF, U+2605
keeping the wght axis, every name record (copyright and licence included) and the kern / liga / calt / ccmp / locl /
mark / mkmk features. A character outside the subset falls back to Android's own fonts. The OFL allows bundling and
modifying the fonts with software, GPL included, as long as they are not sold on their own and the licence travels
with them; the subsets keep their original names, which the OFL's Reserved Font Name clause allows here because
neither family declares a Reserved Font Name.
