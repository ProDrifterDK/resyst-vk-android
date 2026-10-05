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

No permissions. No network. No analytics. A keyboard that reads your keystrokes should earn trust by shipping none of those.

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

---

✦ Resyst — *born from a dead Razer Cyclosa and a Sunday of stubbornness.*
