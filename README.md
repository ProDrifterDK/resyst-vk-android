# ✦ Resyst VK for Android

The Resyst Midnight on-screen keyboard, as a system IME for Android.

Native Kotlin implementation of [Resyst VK](https://github.com/ProDrifterDK) — no Flutter, no React Native, no WebView: a plain `InputMethodService` with a `Canvas`-drawn keyboard view. The IME architecture is the only honest way to build a system keyboard on Android: the user enables it once in system settings, and every app that asks for text gets it through `InputConnection.commitText()`.

## Features (v0.1)

- **Spanish + English layouts** (ISO ES: `ñ`, accents row, symbol layers with a way home)
- **Long-press variants** — hold a key, slide, release: á à â ä ã; digits → their symbols; ≤ « and friends
- **Profiles**: Noche (Midnight), Día (Papiro), Juego (Arcade), Escritura (Pizarra) — swappable from the keyboard's profile strip
- **Offline word suggestions** from bundled frequency lexicons (12k ES + 8k EN)
- **Key sound** (synthesized, no samples), shift/caps lock with correct word-capitalization semantics
- **Vector key icons** (drawn Paths — no font-fallback glyph roulette across OEMs)
- **TalkBack-friendly**: every key is an accessibility virtual view

## Install (debug build)

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then on the device:

1. **Settings → System → Languages & input → On-screen keyboard → Manage keyboards** → enable **Resyst VK**
2. Open any app with a text field, switch keyboards (🌐 or long-press space) → pick **Resyst VK**

No analytics, no telemetry, nothing you type in apps ever leaves the phone. There are exactly two network clients. The updater (kv.resyst.cl): one small `release.json` check when the keyboard opens (at most every 3 h, 1 h after a failure; toggle "Buscar actualizaciones automáticamente", default on) and the download you ask for in Settings. The GIF search (KLIPY): off by default; only after you accept the disclosure, and only when you open the GIF tab, search, scroll for more or send a GIF. Every request is listed in Settings › Acerca de › Libro de conexiones. An update result shows only inside the keyboard strip and Settings, never as a status-bar notification.

## Verify on a device/emulator

```bash
scripts/e2e.py            # installs the APK, switches the IME, types via the drawn keys,
                          # asserts committed text, saves screenshots + UI dumps to build/e2e/
./gradlew :app:testDebugUnitTest   # 50 JVM tests (layouts, variants, engine, suggestions, palettes)
```

The E2E locates keys through the keyboard's accessibility tree, so a passing run also proves TalkBack sees every key.

## Project layout

```
app/src/main/kotlin/com/resyst/vk/
  ime/       InputMethodService + Canvas keyboard view, popups, key icons
  core/      Pure-Kotlin engine: layouts, variants, suggestions, palettes, profiles
app/src/test/          JVM unit tests (failure-mode spec first: docs/failure-modes.md)
app/src/main/assets/   Frequency lexicons (es/en)
scripts/e2e.py         On-device E2E (adb + uiautomator, stdlib only)
```

The core is deliberately pure Kotlin (no Android imports) — layouts, variant tables, the suggestion engine and palettes can be unit-tested on the JVM in seconds.

## Status & roadmap

**v0.1.0** — working IME; known issue: the navigation bar can overlap the bottom key row on some devices (fix planned).

Roadmap: settings screen polish, more sound packs, clipboard key, one-handed mode, gesture typing (probably never — this keyboard has a philosophy).

## License

Copyright (C) 2026 Resyst Labs.

Resyst VK is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see [LICENSE](LICENSE) for the full text.

Bundled third-party data keeps its own license, with the notice next to each file:

- Frequency lists (`assets/lexicon/es.txt`, `en.txt`): FrequencyWords by Hermit Dave, CC BY-SA 4.0.
- Spanish valid-word set (`valid-es.dawg`): RLA-ES by Santiago Bosio, GPL-3+ or LGPL-3+ or MPL-1.1+.
- English valid-word set (`valid-en.dawg`): SCOWL by Kevin Atkinson, permissive notice.
- DM Sans font: SIL Open Font License 1.1.
- Emoji data (`assets/emoji/`): the catalog from Unicode Emoji 18.0 (`emoji-test.txt`) and the emoji search's names and keywords from Unicode CLDR 49 (`annotations` and `annotationsDerived`, es / es_419 / en), both © Unicode, Inc. under the Unicode License v3 (notice in `assets/emoji/NOTICE.txt`; generators `scripts/gen_emoji.py`, `scripts/gen_emoji_search.py`, inputs pinned by sha256 in `scripts/data/`). A few search keywords are Resyst's own (`scripts/data/emoji-search-extra.tsv`).
- Key fonts (`assets/fonts/`, subsets): Atkinson Hyperlegible Next by the Braille Institute of America (The Atkinson Hyperlegible Next Project Authors) and Nunito (The Nunito Project Authors), both SIL Open Font License 1.1; the licence texts and the subsetting notes travel with the files.
- Context-offer model (`assets/context/wt-m2.vkng`, "Sugerencias por contexto"): word-trigram counts built from Wikipedia contributors, *Wikipedia, la enciclopedia libre* (CC BY-SA 4.0) and Tatoeba contributors (CC BY 2.0 FR). As an adaptation of CC BY-SA 4.0 material it is licensed **GPL-3.0-only** (the GPL version Creative Commons lists as compatible), inside this GPL-3.0-or-later app: an APK that contains it can be passed on under GPL-3.0. Notice and build provenance (inputs, sha256s, builder commit) in `assets/context/`; the builder is `tools/ngram-build/`.

---

✦ Resyst — *born from a dead Razer Cyclosa and a Sunday of stubbornness.*
