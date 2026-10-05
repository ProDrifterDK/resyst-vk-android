# ✦ Failure modes (written before the core code)

Every pure-Kotlin core module was specified by how it could fail first; each entry maps to
at least one JVM unit test in `app/src/test`. The on-device behavior (InputConnection,
rendering, touch) is covered by the E2E script `scripts/e2e.sh`.

## Layout (`KeyboardLayouts`)
- L1 A row's key widths don't add up to the row width → keys misaligned, dead strip at the edge.
- L2 Spanish letters lack `ñ`, or `ñ` is not right after `l` on the home row.
- L3 English letters contain `ñ` or miss a letter a–z.
- L4 A letter appears twice / a letter a–z is missing.
- L5 Symbol layers miss everyday characters or have no way back to letters.
- L6 Bottom row lacks space / enter / layer switch; backspace missing from the layer.
- L7 The 🌐 switch key appears when the system says not to offer it (or vice versa), and
  the row then overflows.
- L8 `topRow = none` still adds a row; `accents` row misses á é í ó ú ñ ¿ ¡.

## Long-press variants (`Variants`)
- V1 Spanish vowels' default long-press (first item) is not the acute accent.
- V2 Shift doesn't uppercase letter variants (É), or mangles symbols.
- V3 Variant lists contain the base key itself or duplicates.
- V4 Uppercasing turns one character into two (ß → SS) and breaks the popup cell.
- V5 Keys without variants crash instead of returning an empty list.

## Engine (`KeyboardEngine`)
- E1 Shift-once survives the first character (whole word capitalized).
- E2 Caps lock is cleared after a character.
- E3 Double-tap shift doesn't lock; tapping while locked doesn't release.
- E4 Shift held as a chord doesn't uppercase while held / sticks after release.
- E5 Layer keys don't route correctly (ABC, ?123, =\<).
- E6 Enter sends an editor action in a multi-line field (or a newline in a search box).
- E7 Auto-capitalization overrides caps lock, or fires in the middle of a word.
- E8 Variants ignore shift, or don't clear shift-once.
- E9 Double-space period fires after punctuation / on triple space / when disabled.

## Suggestions (`Suggest`)
- S1 Empty or non-letter prefix yields suggestions.
- S2 Accent-less input (`cancion`) doesn't find `canción`.
- S3 Ordering ignores frequency; folded matches outrank exact-prefix matches.
- S4 Capitalization of the typed word is not mirrored (`Hol` → `Hola`, `HOL` → `HOLA`).
- S5 The typed word itself is suggested back (useless), but `esta` must still offer `está`.
- S6 The limit is not respected.
- S7 Word extraction before the cursor is wrong (`¿Qué`, `don't`, trailing space).

## Palette (`Palette`)
- P1 Theme colors drift from the desktop source values.
- P2 A low-contrast custom accent is not corrected to ≥ 4.5:1 over the key color.
- P3 The ink chosen for text on the accent is unreadable.
- P4 An unknown theme id crashes instead of falling back to Midnight.

## Profiles (`Profiles`)
- R1 A fresh install doesn't have the four seed profiles in order with their themes.
- R2 Corrupt stored values crash or are kept instead of sanitized.
- R3 Editing one profile leaks into another.
- R4 An invalid active id isn't recovered.
- R5 Save → load loses values.

## Popup geometry (`PopupGeometry`)
- G1 The variants popup leaves the view horizontally.
- G2 The default variant is not over the pressed key.
- G3 Finger → selected index is not clamped.
- G4 The key preview goes above the top of the view.

## Key sound synthesis (`KeySynth`)
- Y1 Samples clip, or contain NaN.
- Y2 A buffer is empty or has an unexpected duration.
- Y3 The WAV header sizes are wrong (SoundPool rejects the file).
