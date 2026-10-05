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

## Round 2 — field feedback (Pixel 6)

### Navigation bar inset (`Insets`)
- N1 The keyboard ignores the navigation bar: the bottom row sits under the gesture pill /
  the 3-button bar (r1 bug: the inset never reached the view, so it added 0 px).
- N2 A hidden navigation bar (immersive, nav bar visibility off) still reserves space.
- N3 A transient bar larger than the stable bar (e.g. a taskbar flash) makes the keyboard jump.
- N4 Garbage inputs (negative values) produce negative padding / a shrinking view.
- N5 The total height forgets the inset, or counts it twice.

### Hide the special-characters row (`KbSettings.hideTopRow`)
- H1 A fresh install hides the row (it must be visible by default).
- H2 Hiding still lays out 5 rows, or removes a letter row instead of the top row.
- H3 Un-hiding forgets which content the row had (accents vs. digits).
- H4 r1 storage with `topRow = NONE` is lost or crashes instead of becoming "hidden".
- H5 The flag doesn't survive save → load, or leaks between profiles.
- H6 Symbol / numpad layers change when the row is hidden.

### Haptics (`Haptics`)
- K1 The in-app toggle is on but nothing vibrates (r1 bug: the view had haptics disabled, so
  every `performHapticFeedback` was suppressed before reaching the vibrator).
- K2 The toggle is off but something still vibrates.
- K3 Every event uses the same pulse (long-press / cursor ticks indistinguishable).
- K4 The pulse is routed through the system "touch feedback" setting, so the in-app switch
  isn't the switch.

### Languages = IME subtypes (`Subtypes`)
- U1 The system picker lists only one language (r1 bug: subtypes were declared but never
  explicitly enabled, so Android only showed the one matching the system locale).
- U2 A `Lang` exists in the app without a subtype in `method.xml` (or vice versa).
- U3 Subtype labels are missing / not "Español (Resyst)", "English (Resyst)".
- U4 Locale tags with region or script (`es_CL`, `es-419`, `en_US`) don't map to a `Lang`;
  unknown languages map to a wrong `Lang` instead of none.
- U5 A user's explicit subtype choice in system settings is overwritten on every start.
- U6 The in-app language is set to a subtype the user disabled → the switch silently fails.
- U7 System picker → app and app → system feed back into each other (ping-pong loop).
