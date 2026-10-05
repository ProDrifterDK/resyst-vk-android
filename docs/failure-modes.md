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

## Round 3 — field feedback (Pixel 6)

### Vibration strength (`HapticStrength`, `Haptics.spec`)
- K5 A strength level produces an out-of-range amplitude (0 = silent, > 255 = rejected by
  the platform) or a non-positive duration.
- K6 Two levels feel the same: amplitude doesn't strictly grow LOW < MEDIUM < HIGH for every
  pulse (e.g. HEAVY clamps at 255 on both MEDIUM and HIGH).
- K7 On a motor without amplitude control the levels collapse into one: duration must also
  grow with the level so the fallback still has a knob.
- K8 Strength erases the pulse identity: at a given level HEAVY ≤ CLICK or CLICK ≤ TICK.
- K9 A fresh install / r2 storage without the key / garbage value doesn't land on MEDIUM, or
  the level doesn't survive save → load.
- K10 The wrong playback mechanism is picked (primitives used where unsupported → silence;
  amplitude one-shot where the motor ignores amplitude → all levels identical).

### Autocorrect + space applies the correction (`Suggest.correction`, `KeyboardEngine`)
- A1 Obvious typos aren't corrected (`casaa`, `qie`, `tambein`, `grcias`, `manana`).
- A2 A word that is in the lexicon gets "corrected" (`casa`, `cosa`, `esta` → `está`).
  Exception, by design: an accent-dropped spelling the subtitle lexicon also contains
  (`tambien`) is fixed only when the accented form is ≥ 10× more frequent.
- A3 Case is lost (`Casaa` at a sentence start → `casa`), or a capitalized word in
  mid-sentence (a proper noun the user typed on purpose), an ALL-CAPS word (acronym) or mixed
  case (`iPhone`) is corrected.
- A4 Single characters, digits, words glued to digits / `@` / `/` are corrected.
- A5 A far word is "corrected" into an unrelated one (distance too large for its length).
- A6 Ties pick the rarer word, or ignore keyboard adjacency (`cssa` → `cosa` instead of `casa`).
- A7 Space-commit off still changes the text; on, it doesn't replace the typed word.
- A8 After a manual suggestion pick, the next space applies a second correction.
- A9 Correction breaks double-space: `casaa␣␣` must end as `casa. `.
- A10 The user can't undo: backspace right after a correction must restore the typed word
  (and that word must not be re-corrected afterwards).
- A11 Cursor in the middle of a word (letters after the cursor) triggers a correction.
- A12 The suggestion bar doesn't show the correction first, so space applies something the
  user never saw.

## Round 4 — next-word prediction + personal learning (Pixel 6)

### Personal n-gram memory (`PersonalModel`, `Predictor`)
- M1 A learned pair isn't predicted after its first word; a comma between them (`hola, `)
  breaks the link, or a sentence terminator (`. ! ? …` / newline) does NOT break it.
- M2 Ranking ignores counts (a one-off outranks a habit) or ties ignore recency.
- M3 Unbounded growth: continuations per word, sentence starters, remembered previous words
  or known words exceed their caps; eviction drops the entry just learned instead of the
  weakest/oldest one; counts grow forever (no decay) so old habits can never be displaced.
- M4 Keys aren't folded: `Hola`, `hola,` and `HOLA` become different words; the shown form
  loses the user's own case (a proper noun typed mid-sentence) or keeps an auto-capital that
  only came from the sentence start.
- M5 Junk is learned: 1-char tokens, numbers, mixed alphanumerics (`abc123`), words glued to
  `@` `.` `/` digits (pieces of emails/URLs), over-long tokens.
- M6 The chain breaks: after learning a→b→c, next(a) isn't b or next(b) isn't c.
- M7 Sentence starters: the user's usual opener isn't first at a sentence start; words from the
  middle of a sentence leak into the starters.
- M8 A truncated read window (the text before the cursor starts mid-word) learns or predicts
  from a fragment.
- M9 Persistence: save → load loses entries, counts, recency or a language; corrupt / foreign
  JSON crashes instead of yielding an empty model; languages leak into each other.
- M10 Backspace undoing a space-correction leaves the corrected word learned.

### Suggestion bar merge
- B1 Cursor right after a committed word (or at a sentence start) shows nothing / generic
  words instead of the user's learned continuations.
- B2 While a word is being typed the confident correction is no longer first (A12 regression),
  or personal prefix matches don't outrank static completions.
- B3 The same word appears twice (personal `cómo` + lexicon `cómo`).
- B4 Case: a prediction at a sentence start isn't capitalized (shift AUTO), caps lock isn't
  honored, or the typed prefix's case isn't mirrored.
- B5 A word the user types habitually (not in the lexicon) is "corrected" away by space.
- B6 The cursor inside a word (letters after it) still shows predictions.

### Field-value memory (`ValueMemory`)
- F1 Partial or invalid values are remembered (no `@`, whitespace, absurd length).
- F2 Prefix match is case-sensitive, or the value equal to what is already typed is offered.
- F3 Picking a value commits only a suffix, appends a space, or duplicates the typed part.
- F4 More than the cap per kind is kept, or eviction drops a frequent value instead of the
  weakest/oldest; kinds mix (email values offered in a text field).
- F5 Corrupt storage crashes instead of yielding an empty memory.

### Privacy gate (`PersonalGate`, settings)
- X1 Password / visible-password / web-password / numeric-PIN fields learn or show personal data.
- X2 A field flagged IME_FLAG_NO_PERSONALIZED_LEARNING (incognito) learns or shows personal data.
- X3 "Sugerencias personales" off (or "Sugerencias" off) still learns or suggests — words or values.
- X4 "Borrar lo aprendido" leaves either store (words or values) behind.
- X5 The toggle isn't ON on a fresh install / r3 storage, doesn't round-trip, or leaks between profiles.

## Round 5 — self-update (`UpdateChecker`)

The app's only network use: on an explicit tap, fetch `https://kv.resyst.cl/release.json`, decide,
and (on a second explicit tap) download the APK and hand it to Android's installer.

### Manifest parsing
- U1 Invalid JSON, a non-object root (array, string, `null`) or an empty body crashes instead of
  yielding an error decision.
- U2 A required field (`versionCode` or `version`, `sha256`, `url`) missing or of the wrong type
  (string versionCode, numeric sha) is treated as "update available" or crashes.
- U3 `available: false` (or missing) still offers an update.
- U4 A `sha256` that isn't 64 hex chars is accepted (verification would become meaningless);
  uppercase hex is rejected or compared case-sensitively.
- U5 The download URL escapes the release origin: `http:`, another host, a lookalike host
  (`kv.resyst.cl.evil.com`, `evil.com/kv.resyst.cl`), userinfo (`kv.resyst.cl@evil.com`),
  protocol-relative `//evil.com/x.apk`, `javascript:`/`file:`/`content:`, or a non-`.apk` path.
  A relative `/download/x.apk` must resolve against `https://kv.resyst.cl`.
- U6 An oversized `size`/`sizeBytes` or negative numbers are accepted.

### Version comparison
- V1 Equal or LOWER `versionCode` is offered as an update (downgrade path).
- V2 Without `versionCode`, the version-name fallback compares as strings (`0.10.0` < `0.9.0`),
  treats `0.2` ≠ `0.2.0`, or lets a pre-release (`0.3.0-alpha`) outrank its release (`0.3.0`).
- V3 The local `-debug` name suffix makes a debug build look older/newer than its release.

### Decision
- D1 A device below the manifest's `minSdk` (or `minAndroid` when `minSdk` is absent) is offered
  an update it cannot install; it must get "incompatible", not "available".
- D2 Any error path (bad manifest, HTTP failure) surfaces as "up to date" — the user would wrongly
  believe they're current.

### Download verification
- H1 The downloaded bytes are handed to the installer without matching the manifest SHA-256, or a
  size mismatch / empty file passes.
- H2 Verification hashes a different file than the one the installer receives (TOCTOU): the
  verified copy must be the one served to the installer.
