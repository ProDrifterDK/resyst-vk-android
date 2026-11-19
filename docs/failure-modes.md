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

## Round 6 — clipboard: paste chip + history (`ClipboardHistory`, `ClipOffer`, `ClipGate`)

### History store
- C1 An empty or whitespace-only clip is stored.
- C2 A clip over 100 KB (UTF-8) is stored (memory / disk blow-up); exactly 100 KB must be kept.
- C3 Re-reading the same clip (same system timestamp — the IME reads the clipboard at every field
  focus) bumps its count or reorders the list.
- C4 Copying the same text again duplicates it instead of collapsing it (count + 1, moved to the
  top, pin kept).
- C5 More than 25 entries are kept, or eviction drops the newest / a pinned entry instead of the
  oldest unpinned one.
- C6 Pinning has no ceiling, so every slot could be pinned and new copies would be dropped.
- C7 "Purgar tras 1 hora" removes pinned or fresh entries, or keeps stale unpinned ones; with the
  option off nothing is ever purged by age.
- C8 Persistence: a round trip loses text with quotes / newlines / emoji, counts, pins or order;
  corrupt or foreign JSON crashes instead of yielding an empty history; a tampered file sneaks in
  oversized entries, duplicates or more than the caps.
- C9 Deleting / pinning by a stale id touches another entry.
- C10 The panel order is not pinned-first, newest-first within each group.

### Paste offer (chip)
- O1 A secret field (password, visible / web password, numeric PIN) gets a paste chip.
- O2 An empty clipboard or blank text yields a chip.
- O3 A stale clip (older than 5 min) is offered, or the clip just pasted from the chip is offered
  again.
- O4 An image clip is offered to a field that declares no image support; an image-accepting field
  (`image/*`, `image/png`) doesn't get "Pegar imagen"; MIME wildcard matching is wrong (`image/*`
  vs `text/*`, case).
- O5 An image MIME type without a content URI is offered as an image.
- O6 A sensitive clip (`ClipDescription.EXTRA_IS_SENSITIVE`, password managers) shows its text in
  the chip label.
- O7 Newlines or very long text break the chip label (whitespace must collapse, length is capped).

### Capture / read gate
- W1 A copy made while a secret field is focused is captured.
- W2 A copy made while an incognito field (IME_FLAG_NO_PERSONALIZED_LEARNING) is focused is captured.
- W3 Anything is captured with "Historial del portapapeles" off.
- W4 A sensitive clip is captured.
- W5 The history panel opens (history read) in a secret field.
- W6 The clipboard code opens a network connection (the pull-only promise of r5).

### Settings (clipboard)
- Q1 A fresh install / r5 storage doesn't land on history ON, purge OFF.
- Q2 Corrupt values crash or are kept; values don't round-trip; the setting leaks per profile (it is
  device-wide, like the learned data).

## Round 7 — settings IA + the keyboard-switch globe (Pixel 6)

### Settings information architecture (`SettingsIA`)
- I1 A stored setting (any per-profile `ProfileCodec` key, or a device-wide `clip.*` key) has no
  control on any page → it became unreachable in the reorganization.
- I2 A page lists a control id that is neither a stored setting nor a known action (a typo renders
  a blank row or crashes the screen).
- I3 A control appears on two category pages (scattered again); an essential (home) control is
  missing from its own category page, so the full page is incomplete.
- I4 A dependent control (Intensidad, Pack, Volumen, Fila superior, El espacio corrige, Sugerencias
  personales, Purgar tras 1 hora) lives on another page than the switch that reveals it, or is
  listed before it.
- I5 Home grows back into the flat list: more than 8 essentials, or a long-tail setting (leyendas
  secundarias, vista previa de tecla, mayúscula automática, doble espacio) is promoted to home.
- I6 Any control deeper than 2 taps from home (nested pages).
- I7 A device-wide setting or data action (portapapeles, borrar lo aprendido, actualizaciones,
  restablecer) is shown on a per-profile page — the user would think it is per profile — or a
  per-profile setting on a device-wide page.
- I8 Duplicated page ids / titles, or an empty page.
- I9 A destructive action (Borrar lo aprendido, Borrar historial, Restablecer perfiles) sits on home
  or among the essentials.

### The keyboard-switch globe (`ImeSwitcher`)
- G1 The keyboard draws its own 🌐 key in any layer (redundant with long-press space — the
  mistapped key Alan reported).
- G2 The IME treats the system globe as hidden on a version that can't hide it (API < 35: the IME
  navigation bar has no hideable caption bar) and drops the bottom floor → keys under the globe.
- G3 The system globe is gone but the 88 px floor is still reserved (dead strip), or the floor is
  dropped while the globe is still drawn (caption bar still visible).
- G4 Non-gesture navigation (3-button, 2-button) changes: it must keep the pure inset rule.
- G5 Nothing tells the user how to switch keyboards once the system globe is hidden, or that hint
  shows with only one keyboard enabled (nothing to switch to).
- G6 TalkBack users lose the way to switch: the space key exposes no long-click action.

## Round 9 — startup update check + update notice (`UpdateNotice`, `AutoCheckGate`)

One automatic `release.json` GET per process start (the same `Updater.check()` path), surfaced only
inside the keyboard strip and the settings "Actualización" section — never the status bar.

### Once-per-process gate
- A1 The automatic check runs more than once in one process (a second `onCreate` of the IME after a
  rebind, the settings screen opening, a configuration change).
- A2 It runs with "Buscar actualizaciones al iniciar" off.
- A3 It clobbers an update already in flight or done: a pending download id, Downloading, Verifying,
  Ready, or a check the user started by hand (state not Idle).
- A4 Settings opening in a process that already checked fetches again instead of reusing the
  stored state.
- A5 A failed automatic check (offline, timeout, HTTP error, bad JSON) shows an error anywhere:
  it must leave the updater Idle and only log.

### Update chip (keyboard strip)
- N1 A chip appears for anything but "available" (up to date, incompatible, not published, error,
  no check yet).
- N2 A dismissed version comes back in the same version (nagging), or dismissing an old version
  hides a newer one.
- N3 The version number is ellipsized or cut in the chip label (a label must show it whole or the
  chip steps down to the gear badge).
- N4 The chip takes the strip while a word is being typed or while the paste chip is offered
  (suggestions / paste win; the update steps down to a dot on ⚙), or it leaves no room for the
  dismiss target.
- N5 The chip changes the strip height (the keys jump when it appears or leaves).

### Settings + copy
- S1 The toggle is not ON for a fresh install / r8 storage, does not round-trip, is per profile
  (it is device-wide), or has no control on any settings page.
- S2 The privacy copy still says the app never connects by itself while the startup check is on.

## Round 10 — temas + modos (`Tema`, `Mode`, `ProfileCodec` v2) and the settings pages

### Migration v1 → v2 (`ProfileCodec.migrateV1`)
- T1 An r1–r9 store (`p.<id>.*`) loads as the seed: themes, accents, names or icons the user set
  are lost (the vision's hard rule: never reset temas, never erase what was learned).
- T2 The keyboard behaves differently right after the update: the active profile's behavior
  (idioma, sugerencias, vibración, sonido…) is not what the phone runs with.
- T3 The old "Juego" profile was active and the migrated keyboard is suddenly a full one (fila
  superior, sugerencias) — or Juego's no-suggest behavior leaks into the phone settings as the
  user's own choice, so turning the mode off keeps suggestions off forever.
- T4 Partial / corrupt v1 storage (missing keys, junk values, unknown ids, no `order`) crashes or
  is kept instead of falling back per field.
- T5 After the first v2 save, stale `p.*` keys survive, so a later read could migrate again and
  overwrite v2 edits (or an old build reads half-new data).

### Temas + modos (`ProfileStore`)
- T6 A look edit (tema, acento, forma, altura…) leaks into another tema, or a behavior edit
  (vibración, idioma…) is lost when switching tema.
- T7 A mode's overrides are written back into the stored settings (turning the mode off doesn't
  restore the user's settings), or the settings screen edits the mode-overridden view.
- T8 A mode turns on personal learning or suggestions where the user had them off (modes can only
  restrict typing aids, never widen what is learned).
- T9 Every stored key has no control / a control edits a key nobody stores (I1/I2 restated for
  the v2 keys: `t.<id>.<look>`, `phone.*`, `mode`, `active`).

### Settings pages (`SettingsIA.rows`, `SettingsActivity`)
- P1 A dependent (Intensidad, Pack, Volumen, Fila superior, El espacio corrige, Sugerencias
  personales, Purgar) is hidden or enabled while its parent is off (r7 capture: orphan chips).
  It must stay visible, indented and disabled.
- P2 A destructive action is not the last row of its page, or "Borrar lo aprendido" is
  actionable with nothing learned.
- P3 A page the activity renders is missing a control SettingsIA lists (the screen drifts from
  the IA: every `Ctl` must map to a widget tagged `ctl.name`).

## Round 10 — visible privacy (`Profanity`, `ConnectionLog`, `MemoryNotice`, forget APIs)

Bet 3 of `proposals/resyst-vk-vision.md`: what makes Resyst different must be visible on the
keyboard and in settings, not a paragraph.

### Offensive-word filter (`Profanity`, default ON)
- V1 An offensive word is offered as a completion, a prediction, a seed, or applied by space as a
  correction while the filter is on ("mier" → mierda, "fuc" → fuck, "puts" → puta, "shiit" → shit).
- V2 The filter touches what the user types: a typed offensive word is "corrected" to something
  else, deleted, or not learned. It only removes the keyboard's own proposals.
- V3 A word the user has typed ≥ [Bar.HABIT] times stays blocked: then it is theirs and is offered
  again (the personal model decides, not a moral list). The filter off restores r9 exactly.
- V4 Over-blocking: a clean word that merely contains a bad one ("computadora" ⊃ "puta",
  "escultura" ⊃ "culo", "shitake", "assistant") disappears. Matching is per whole word
  (accent/case-insensitive), never substring.

### Connection log (`ConnectionLog`, "Libro de conexiones")
- V5 A request isn't logged: every release.json GET (manual or startup) and every APK download is
  one entry with time, reason (tú / al iniciar), what (consulta / descarga) and outcome. Or an entry
  is logged for something that never touched the network.
- V6 The log grows without bound or a corrupt stored log crashes settings / the keyboard (cap 50
  entries, total counter kept separately; junk entries dropped, never thrown).
- V7 A second network path appears (still: only `Updater.kt` opens connections).

### "Sin memoria" (`MemoryNotice`) + no key bubble on secrets
- V8 The keyboard shows "memory on" (no dot) in a field where nothing is learned: password / PIN,
  incognito (IME_FLAG_NO_PERSONALIZED_LEARNING), opted-out (NO_SUGGESTIONS), suggestions or
  personal suggestions off, or a mode that disables learning — or shows the dot where it learns.
- V9 The reason given is wrong or vague: the most specific one wins (secret > incognito > opt-out >
  setting off).
- V10 The key preview bubble shows the typed character in a secret field (shoulder-surfing), even
  with "Vista previa de tecla" on.

### "Lo que sé de ti" (forget APIs)
- V11 Deleting a learned word leaves it alive anywhere: vocabulary, sentence starters, as a
  continuation of another word, or as a previous word with its own continuations.
- V12 Deleting a remembered email leaves it offered; deleting an emoji recent brings it back on
  the next panel open; a delete of an unknown entry throws or changes anything else.
- V13 The listing leaks across languages, is unbounded (shows every word at once without order),
  or lists anything the keyboard does not actually keep.

## Round 10 — the strip is for suggestions; a quick panel under ⚙ (`StripPlan`, `Quick`, `OneHand`)

UX-3 + UI-1 of `proposals/resyst-vk-vision.md`: the strip keeps only what helps typing; every
other control moves to a panel opened by ⚙, drawn with one vector icon family.

### Strip
- QS1 The strip shows anything but suggestions, the contextual «Pegar» chip, the r9 update chip,
  the «sin memoria» dot and ⚙: the profile/tema chip or the clipboard button come back, or the
  sun/moon shows without the user opting in.
- QS2 «Pegar» takes more than one suggestion slot (with it, two suggestions remain; without it,
  three), or the suggestions overflow [Bar.LIMIT].
- QS3 A store migrated from r1–r9 (where the sun/moon chip defaulted ON) keeps it in the strip.
- QS4 ⚙ no longer reaches Settings (long-press) or loses the r9 update dot.

### Quick panel
- QP1 A tile changes more than it says: Día/noche flips another tema; Tema changes behavior;
  Modo writes its overrides into the phone settings; Altura edits another tema or leaves
  [HEIGHT_MIN, HEIGHT_MAX].
- QP2 A cycle never comes back: Tema (n temas), Modo (3), Altura (4 steps), Una mano (3) must
  return to the start after a full turn; an off-step height snaps to the next step.
- QP3 Portapapeles opens the history in a secret field or with the history off (W5 restated): the
  tile is disabled and says why.
- QP4 A tile is under 48 dp or invisible to TalkBack (each tile = one node: label + state).
- QP5 An announced update is unreachable once the user started typing (the strip chip steps
  down): the panel must offer it.

### One-handed keyboard (`OneHand`)
- QO1 OFF is not exactly the full width; ON narrows the keys below 80 % or leaves a rail under
  48 dp; the keys and the rail overlap or overflow the view.
- QO2 The side is per tema (it is phone-wide), doesn't round-trip, or junk storage isn't OFF.

### Icons (`KeyIcons`)
- QI1 Shift / ⌫ / Enter / ⚙ / sun / moon / clipboard are font glyphs (OEM font roulette, the ⚙
  read as a sun in the r8 captures) or use different stroke weights.
- QI2 The shift states (off / once / locked) are told apart only by color.

## Round 10 — text editing core (`TextEdit`, `KeyboardEngine` undo offer)

### Delete a word (`TextEdit.wordBefore`)
- W1 "Borrar palabra" eats more than the previous word (two words, or text before the space that
  separates it) or less (leaves a fragment of it).
- W2 It splits an emoji / surrogate pair, or deletes nothing when the text ends in punctuation
  or spaces only.
- W3 The accelerated ⌫ (chars → words) starts on a tap or on a run of quick taps: only a held key,
  after 8 auto-repeats, switches to words.
- W4 Word deletion (accelerated ⌫ or the panel/gesture) runs in a secret field: a hidden password
  must only ever lose one character per press.
- W5 Word mode survives releasing ⌫ (the next press starts already deleting words).

### Undo a correction (the "↶" chip)
- U1 The ↶ offer appears without a space-correction, or survives the next key / a cursor move away
  (the text no longer ends with the corrected word + space).
- U2 Tapping it does not restore exactly the typed word + the space, or the next space corrects
  the restored word again.

### Opening ¿ ¡ (`TextEdit.opener`)
- O1 Offered when the sentence already has its opener, in English, in non-prose fields, or when
  `?`/`!` is not closing a sentence (URLs, `a=b?c`, a lone `?`).
- O2 Inserted at the wrong place: before the sentence's leading spaces, inside the previous
  sentence, or the rewrite loses / duplicates characters.
- O3 Offered when the sentence start is outside the text the keyboard can read (window full,
  no terminator): the rewrite could cut the user's text.
## Round 10 — bilingual + regional (bet 4: `SpaceGesture`, `BiLang`, `Regional`)

### Language flick on space (`SpaceGesture`)
- FL1 A vertical flick on space (≥ 20 dp, mostly vertical, < 300 ms) types a space, opens the
  keyboard picker, or does nothing — it must switch ES ⇄ EN and commit nothing.
- FL2 A horizontal drag (cursor) or a slightly diagonal drag is read as a flick, or a flick starts
  cursor mode: the axes must not steal each other (|dy| > 2·|dx| for a flick).
- FL3 A tap with a few dp of jitter, or a slow vertical slide (> 300 ms), switches the language.
- FL4 Long-press space no longer opens the picker (G5/G6), or a flick that is held afterwards also
  opens it (the vertical move cancels the long press).
- FL5 The switch is per profile/tema instead of phone-wide, or doesn't reach the system subtype
  (the next field reverts it).

### Bilingual suggestions (`BiLang`)
- BL1 With nothing typed (or only unknown words) the detected language is not the keyboard's.
- BL2 Three clearly English words on the Spanish keyboard ("I think the") keep Spanish
  completions/corrections, or three Spanish words on the English keyboard keep English ones.
- BL3 One English word inside a Spanish sentence ("voy al meeting") flips the whole sentence to
  English: the window is the last 3 words and a margin is required.
- BL4 A word that exists in the other language ("meeting", "okay" typed on ES; "casa" on EN) is
  "corrected" by space into the keyboard language.
- BL5 The other-language guard blocks real typo fixes: Spanish thumb-typo quality (LexiconEval Q1/Q2)
  drops below the r8 gates.
- BL6 "Sugerencias en dos idiomas" off must be exactly the r9 behavior (one lexicon, no guard).
- BL7 Personal learning escapes the privacy gate (secret / incognito / opt-out / toggle off) because
  of the second language, or a word learned in the detected language is not taken back by the ⌫
  that undoes it (the undo looks in the keyboard language's table).

### Regional Spanish (`Regional`, assets/lexicon/es-419.txt + es-CL.txt)
- RG1 A re-rank rule deletes a word: a demoted word ("vosotros", "ordenador") typed exactly is then
  "corrected" to something else. Demoted words stay in the lexicon, at the tail.
- RG2 A promoted word is duplicated (two ranks), or the re-ranked list loses / adds words other than
  the rule's insertions.
- RG3 Chile/LatAm still prefers peninsular forms (coche over auto/carro, ordenador over computador,
  vosotros forms in completions) or misses everyday Chilean words (cachai, bacán, fome, altiro,
  pololo): those must be known (never corrected away) and completable.
- RG4 España (ES) changes anything: the r8 list must load unchanged.
- RG5 Malformed rule lines crash the load instead of being skipped; region setting is per tema, does
  not round-trip, or has no control.
- RG6 The re-ranked Spanish lexicon breaks the r8 typo gates (Q1–Q4) — promoted words become wrong
  correction targets.


### Edit panel + ⌫ swipe (`EditPad`, `DeleteSwipe`) — the UI half of bet 5
- QE1 An `EditOp` has no button, or a button sends another op (the panel drifts from the core).
- QE2 Copiar / Cortar are actionable in a secret field (a password ends up on the clipboard).
- QE3 Holding ←/→/Borrar palabra does nothing (a 40-character walk needs 40 taps), or holding a
  one-shot op (Copiar, Pegar, Todo, Seleccionar) repeats it.
- QE4 Seleccionar's state is invisible: the user can't tell whether the arrows move or extend.
- QE5 A button is under 48 dp or not a TalkBack node with its name + state.
- QE6 The ⌫ swipe fires on a tap or a tiny wobble (under 24 dp), on a mostly vertical drag, or
  rightwards; or the char repeat keeps deleting after the swipe took over.

## Round 11 — learned words that come back (`PersonalModel` v2, `Bar`, `FieldPolicy`)

Field report (Pixel 6): "aunque tenga palabras aprendidas, no me recomienda las palabras aprendidas".

### Kept words (`PersonalModel.keep`, words.json v2)
- K1 A word whose space-correction the user reverted (⌫ right after the space, or the ↶ chip) is
  corrected again by a later space, or is not offered once its prefix is typed.
- K2 The ↶ chip and the ⌫ revert disagree: the chip leaves the corrected word learned (M10 again)
  or never learns the word the user kept.
- K3 The kept mark is lost: by save → load, by the ⌫ that undoes a later use of the word, by
  vocabulary eviction (kept words go last) or count halving. (The ⌫ that undoes the very space
  that kept it drops it: the user is editing the word again.)
- K4 Migration: a v1 file loses entries, counts, stamps or a language; a v2 file with junk (an
  unknown flag value, count 0 without the kept flag) crashes or keeps the junk; malformed or
  foreign JSON is still an empty model (M9).
- K5 "Olvidar" / "Borrar lo aprendido" leave the kept mark behind (the forgotten word must be
  correctable again), or "Lo que sé de ti" hides a kept word or lists it without saying so.
- K6 Keeping escapes the privacy gate: a revert in a secret, incognito or opted-out field marks a
  word.

### Completion noise gate (`PersonalModel.complete`, `Bar.completions`)
- N1 A word typed once is not offered for its prefix (H1: the old COMPLETE_MIN = 2).
- N2 A word typed once (most likely a typo that space didn't fix) takes slot 1 over a lexicon
  candidate for the same prefix, or pushes the confident fix out of slot 1 (A12).
- N3 Strong personal words (used ≥ 2 times, kept, or a learned continuation of the previous word)
  lose their place before the static lexicon.
- N4 Offers (↶, ¿¡) or a confident fix crowd every personal completion out of the 3 slots.

### Both vocabularies (bilingual on)
- BL8 With "Sugerencias en dos idiomas" on, a word learned while the other language was detected
  is not offered or not protected from space; with it off, the other table leaks in; a word known
  in both tables appears twice; next-word predictions (bigrams) mix languages.

### Prose fields that opt out of suggestions (deliberate change to r8, F8-2)
- X6 A prose field with TYPE_TEXT_FLAG_NO_SUGGESTIONS (Instagram DM) learns (writes) anything, or
  does not offer the words already learned. Handles, opted-out search boxes, password, PIN,
  email, URL, number, phone and incognito fields offer or learn personal words. The "sin memoria"
  mark there must stay (nothing is learned in that field).

## Round 11 — the full emoji catalog (`EmojiCatalog`, `EmojiTones`, scripts/gen_emoji.py)

Field report: "no están todos los emojis en el menú de emojis" (946 curated of 1923 RGI bases).

### Catalog (`EmojiCatalog`, assets/emoji/emoji.txt)
- EC1 The asset does not match the pinned emoji-test.txt: a fully-qualified emoji missing, a
  Component (bare skin tone, hair) shown as an emoji, a minimally-qualified / unqualified duplicate,
  a wrong group, or not in Unicode (CLDR) order.
- EC2 A skin-tone variant shows as its own cell in the grid; a base that has tones offers none on
  long-press; a variant is offered under the wrong base; multi-person mixed tones break the parse.
- EC3 The generator is not reproducible offline (network, unpinned input) or the asset drifts from
  it (the JVM test regenerates the counts from the pinned file).
- EC4 A malformed asset line crashes the keyboard instead of being skipped.
- EC5 Tofu boxes (the hasGlyph filter is lost) or jank: opening the panel or switching a tab must
  measure that tab once (lazy, cached), not all ~1900 emoji on every frame / scroll.
- EC6 Recents: an existing recents file stops loading, a toned pick is stored as its base, or a
  long sequence (ZWJ + tones, subdivision flags) is rejected by `EmojiRecents.valid`.

### Skin-tone defaults (`EmojiTones`, files/personal/emoji_tones.txt)
- ET1 A chosen tone is not shown as that emoji's default next time, or leaks to other emoji;
  choosing the plain (yellow) form does not clear the default.
- ET2 The default is written from an incognito or secret field, survives "Borrar lo aprendido",
  or leaves the device (backup / transfer).
- ET3 Junk in the tones file (a variant of another base, garbage) crashes or shows a wrong emoji:
  dropped on read.
- ET4 A long-press also commits the base, a short tap opens the tones, or TalkBack cannot reach the
  tones (long-click action + one node per tone).

### "Borrar lo aprendido" covers all of files/personal/ (`PersonalSummary`, r11a-fix)

Found on device after review: the row was gated on learned words + emails only, so a user with
emoji recents / tone defaults and no learned words saw "Nada aprendido todavía" on a disabled row
and could not wipe them.
- F1 The wipe row is disabled, or its summary says "Nada aprendido todavía", while anything is kept
  in files/personal/: learned words, remembered emails, emoji recents, chosen skin tones. Each one
  alone must enable the row and be named in the summary (singular/plural Spanish, zero parts
  omitted, "· nada sale del teléfono" kept).
- F2 After the wipe the row still counts the old data (the summary is read before the files are
  gone), or the keyboard keeps showing the old recents / tone defaults from memory: with the
  panel open, or the next time it opens in the same IME process.
- F3 "Lo que sé de ti" lists a tone pair the keyboard would drop on read (stale or junk line in
  emoji_tones.txt), hides the tones when there are no recents, or forgetting one tone rewrites
  the file with anything but the remaining valid pairs. The settings list and the panel decode
  the file the same way (`EmojiTones.decode` against the catalog).
- F4 The device check for F1 depends on test order: it passes only because an earlier section
  left learned words behind (the row was enabled by the words, not the emoji data).

## Round 11b — opt-in GIF search (`KlipyUrls`, `KlipyParse`, `GifOptIn`, `GifQuery`, `ConnectionLog` v2)

Alan's request: GIF search inside the keyboard whose promise is that it never talks to anyone.
Provider: KLIPY (Tenor closed 2026-06-30, GIPHY is paid). Privacy model decided by Alan: opt-in,
OFF by default, a plain disclosure before the first request, no ads, an anonymous resettable ID,
every request in the "Libro de conexiones". This deliberately changes V7: there are now two
network clients, `Updater.kt` and `KlipyClient.kt`, both logged, both allowlisted.

### Key injection (`keystore/klipy.key` → `BuildConfig.KLIPY_KEY`)
- GK1 The key lands in a tracked file (source, test fixture, script, report, docs) or in a log
  line: logcat, the Libro de conexiones, an exception message that carries the request URL.
- GK2 A public clone without `keystore/klipy.key` fails to build, or builds with a GIF tab /
  setting that cannot work (the feature must be hidden entirely when the key is absent).
- GK3 A malformed key file (spaces, quotes, newline) breaks the generated Java source or is sent
  half-trimmed: only `[A-Za-z0-9_-]{16,128}` is accepted, anything else = no key.

### URL allowlist (`KlipyUrls`)
- GA1 A request goes anywhere but `https://api.klipy.com/api/v1/…`, or media loads from anywhere
  but `https://static.klipy.com/…`: other host or look-alike (`api.klipy.com.evil.io`,
  `static.klipy.com@evil.io`, `evil.io/static.klipy.com/`), http, a port, userinfo, a backslash,
  whitespace / control characters, `..` segments, a fragment, or a scheme-relative URL.
- GA2 A redirect is followed (off-host or not): redirects are never followed; a 3xx is an error.
- GA3 A media URL is rewritten, re-encoded or reconstructed instead of used exactly as returned
  (KLIPY rule 1), or a URL that fails the allowlist is "repaired" instead of dropped.
- GA4 A query, customer id or slug breaks out of its parameter / path segment (`&`, `#`, `/`, `?`,
  spaces, non-ASCII): every value is percent-encoded as UTF-8; a slug outside `[A-Za-z0-9_-]`
  sends no share trigger at all.

### Response parsing (`KlipyParse`)
- GP1 Malformed, truncated, oversized, `result:false`, HTML error page or wrong-shape JSON
  crashes the keyboard instead of becoming one calm error line (empty page).
- GP2 Results are reordered, merged with another source, deduplicated or filtered by the app
  (KLIPY rule 4): order is the response order; only items we cannot draw at all (no allowlisted
  media) are skipped, and the count of skipped items is known. Ads are never parsed or drawn
  (monetization is OFF at the platform; an item whose `type` is not `gif` is skipped).
- GP3 `has_next` / `current_page` missing or wrong type → no pagination (never an endless loop
  of page requests).

### Format choice (`KlipyParse.preview` / `insert`)
- GF1 The preview format is chosen by name instead of size: one `sm` webp was 479 KB while its
  `sm` gif was 59 KB. The smallest decodable candidate under the preview cap wins; `xs` is the
  fallback; API 26–27 (no animated decoder) get the static `jpg`.
- GF2 The inserted file is a type the field did not declare (`EditorInfo.contentMimeTypes`), is
  static (`jpg`) or video (`mp4`/`webm`), or is larger than the insert cap (an `hd` gif reached
  5.4 MB). A field that accepts none of `image/gif` / `image/webp` (wildcards honored) gets no
  download at all.

### Opt-in gate + disclosure (`GifOptIn`)
- GO1 Any request (API or media) while the feature is OFF: by default, after "Apagar", after a
  corrupt or partial stored state, or before the disclosure was accepted.
- GO2 The setting turns on without the disclosure, or the disclosure is dismissible into ON
  (back, outside tap, "Cancelar" must all leave it OFF). The disclosure names what is sent (only
  the words typed in the GIF search box, a random ID, the region, the safety level, the IP like
  any connection, which GIF was sent), to whom (KLIPY, a third party), that the app field's text
  is never sent, that every request is in the Libro de conexiones, and how to turn it off.
- GO3 A stored consent for an older disclosure version counts as consent for the current one.
- GO4 The feature runs in the background: a request without a user action in the GIF tab
  (open, search, scroll to the next page, tap a GIF), or one still in flight after the tab
  closed, the panel hid, the field finished, or the feature was turned off.
- GO5 The GIF tab (and therefore any request) appears in a password / PIN / secret field or in
  an incognito field (IME_FLAG_NO_PERSONALIZED_LEARNING).

### Anonymous ID (`GifOptIn`)
- GI1 The ID exists while OFF, survives "Apagar", is not a random v4 UUID, is derived from the
  device / account, or is reused after "Nuevo ID anónimo".
- GI2 The ID leaves the device another way: Android backup or device transfer (the prefs file
  is excluded from both), or a log line in a release build.

### Query buffer (`GifQuery`)
- GQ1 A key typed in the GIF search box reaches the app's InputConnection, or the query is
  pre-filled from the field's text (the field must be untouched and never read for it).
- GQ2 The query is learned by the personal model, space-corrected, double-space-perioded or
  shown as suggestions.
- GQ3 Search runs on every keystroke (the shared testing key allows 100 calls/hour): only the
  search key / ⏎ sends it; an empty or whitespace query means trending; the buffer is bounded
  and ⌫ never splits a surrogate pair.

### Connection log (`ConnectionLog` v2)
- GL1 A KLIPY request is not logged (trending page, search page, share trigger, the chosen GIF's
  download), or a page's thumbnails are logged one entry each (flood) instead of one count on the
  page's entry.
- GL2 An old v1 log (updater only) stops reading after the upgrade, loses its total or first
  date; a v2 entry with junk (unknown kind, negative media) crashes instead of being dropped.
- GL3 The page entry is written only after its thumbnails finish (a process death in between
  hides a request that happened): the entry is written when the API answers and amended with the
  media count later.
- GL4 The updater and the GIF client race on the same stored log and one entry overwrites the
  other (one lock for both writers).

### Errors + rate limit
- GE1 Offline, timeout, HTTP 429, other HTTP errors and malformed JSON do not show one calm line
  ("Sin conexión", "Demasiadas búsquedas, intenta en un rato", …) or are not logged with that
  outcome; any automatic retry (the user taps to retry).
- GE2 Two page requests in flight at once (double tap on search, scroll while loading).

### Commit fallback (`commitContent`)
- GC1 A field that rejects images gets a pasted URL or text; the user is not told (Toast +
  TalkBack); a share trigger is sent for a GIF that was not committed.
- GC2 The temporary full-size file outlives its purpose: more than one kept, not deleted on the
  next pick, or a library of picks builds up. Thumbnails are never written to disk.

### Memory + lifecycle (`GifThumbs`)
- GM1 Decoding is unbounded: no byte cap per thumbnail / page JSON / chosen GIF, no target
  decode size, every page kept forever. Previews keep animating while the panel is hidden;
  in-flight loads continue after a tab change or `onFinishInputView`.
- GM2 A thumbnail that fails to load or decode crashes or leaves a hole that shifts the order
  (the cell stays in place, drawn as a placeholder).

### Attribution + honesty
- GT1 No visible KLIPY attribution in the GIF tab; the search box placeholder is not "Buscar en
  KLIPY"; anything suggests KLIPY made or endorses the keyboard.
- GT2 The Libro de conexiones still says the updater is the only network user.

## Round 11c — the update check runs when the keyboard opens (`OpenCheckGate`), 0.7.1

Found by Alan on device: a published update was never announced. r9 claimed the automatic check
once per process (`AutoCheckGate`, from the IME's `onCreate`), and Android keeps the IME process
alive for days, so after the first check the keyboard never asked again. Reproduced on the emulator
(r11c report): 20 keyboard opens, same pid, one GET. r11c replaces it: the check is evaluated on
every keyboard show and runs at most once per interval, persisted. Pull model unchanged: only the
user opening the keyboard can start it; it sends nothing typed.

### When the gate opens
- OC1 Process lifetime: the gate is evaluated only at process start (the r9 bug), so a release
  published while the IME process lives is never seen. It must be evaluated on every keyboard show
  (`onStartInputView`, `restarting == false`, plus the first show after `onCreate`).
- OC2 Throttle across processes: a process restart (kill, low-memory, app update, reboot) resets
  the throttle and checks again before the interval is over. The last attempt (time + whether it
  reached the server) lives in the updater prefs, not in memory.
- OC3 Clock moved back: a stored last attempt in the future blocks every check until the clock
  catches up (days, years). It counts as elapsed once and is overwritten by that attempt. Clock
  moved forward: at most one extra check, never a burst.
- OC4 Failure backoff: a failed attempt (offline, timeout, HTTP error, unreadable manifest) is
  retried on every open (a phone without signal makes a request per keyboard open) or treated as
  success (12 h without knowing). 12 h after an attempt that reached the server (any decision),
  1 h after a failed one. A process killed mid-check counts as a failed attempt (the start is
  written before the GET).
- OC5 Burst: many opens while a check is in flight, or in the same second, start more than one GET.
- OC6 Toggle off: an open makes a GET with "Buscar actualizaciones automáticamente" off, even with
  the stored time long expired. Turning it on does not fetch from settings; the next open does if due.
- OC7 Updater busy: a check the user started (Checking), Downloading, Verifying, Ready (a verified
  APK waiting to install) or a pending download id from an earlier process gets clobbered by an
  automatic check. Busy skips without consuming the interval; a later open checks once it is free.
- OC8 Secret fields: opening the keyboard on a password / PIN field starts the GET (a request that
  coincides with typing a password) or shows the chip there.
- OC9 Background trigger: any timer, alarm, WorkManager / JobScheduler job, broadcast receiver or
  service start runs the check. Only a keyboard show the user caused may.
- OC10 Manual and automatic share one clock: a check the user started that reached the server is
  followed by an automatic GET on the next open (redundant request). Every `Updater.check()`
  records the attempt; there is still one network path.

### What the later check shows
- OC11 A newer release found later is not announced because this process already showed "up to
  date": Checked(UpToDate) / Checked(Available) / Failed must count as free (not busy), and the new
  decision must refresh the strip chip, the ⚙ dot, the quick-panel line and the settings card
  through the existing listeners.
- OC12 A failed later check erases what is known: the chip of an available update disappears (or
  "Ya al día" turns into the idle button) because the network flickered. A failed automatic check
  leaves the previous state as it was and only logs (A5 generalized).
- OC13 Dismissed version: the periodic recheck re-announces a dismissed version every 12 h
  (nagging). N1 / N2 unchanged: only a strictly newer version comes back.
- OC16 The throttle hides a known update: r9 re-checked on every process start, so an announced
  update came back after a process kill; with a 12 h interval a restart inside it would leave the
  chip gone until the next check. The manifest of the last "available" answer is kept in the
  updater prefs and re-decided against the installed version at the next show, without a request
  (installed meanwhile → no longer available → forgotten; an "up to date" / "not published" answer
  forgets it; a failure keeps it).

- OC17 (found by the r11c E2E, present in 0.7.0) Tapping ⚙ in a fresh IME process before the
  emoji key was ever used crashes the keyboard: `showQuick` lays out every panel, the emoji panel
  still holds `EmojiCatalog.EMPTY` and indexed `groups[0]`. The quick panel (and its update line)
  must open in any process; an empty catalog lays out as an empty grid and caches nothing.

### Copy + log
- OC14 The setting, the line under "Versión instalada", the settings card ("Comprobado …"), the
  install-time notice or the site still say "al iniciar" / "una vez": the copy must say "al abrir
  el teclado, como mucho una vez cada 12 horas" and that nothing typed is sent.
- OC15 The Libro de conexiones loses history: entries stored by 0.7.0 with reason `startup` must
  still decode and render ("al iniciar el teclado") next to the new `open` entries ("al abrir el
  teclado"); the decoder drops unknown reasons, so removing `startup` would silently erase them.

## Round 12 — the minor details (0.8.1): provisional space, off-thread glyphs, settings asset, update origin

Leftovers of the r11 / r11b / r11c reviews (r11a m2 + m5, r11a-fix recheck minor 1, r11c minors 1 + 2).
Written before the code; each one names what a test or a device row must catch.

### The space a suggestion pick adds is provisional (`KeyboardEngine`, Gboard-style)
Found on device since r4 (e2e_r4 "whole sentence built from three picks", failing since 8c9ca89):
pick "Hola" then `,` gave `"Hola , "`.
- PS1 Closing punctuation after a pick keeps the pick's space in front of it (`"Hola ,"`). The
  closing set is exactly `, . ; : ! ? ) ] } » ” …`: the pick's space is removed, the mark committed
  and a provisional space follows it (`"Hola "` + `,` → `"Hola, "`), in one batch edit. The same
  holds for a long-press variant that is in the set (`.` → `…`, `,`, `;`, `:`, `!`, `?`).
- PS2 A space right after a pick or a swap adds a second space (`"Hola, "` + space → `"Hola,  "`).
  That space press commits nothing and ends the provisional state (a further space is a normal one).
- PS3 Double-space period arms off a provisional space: pick + space + space within 1.5 s, or a
  swap + space + space, turns into `". "`. Neither the pick, the swap nor the absorbed space may
  count as the first space of a double-space.
- PS4 The provisional state outlives what it describes: a letter, digit, an opening mark
  (`¿ ¡ ( « “ "`), `@ / - '`, an emoji, enter, a backspace, a cursor move (space-drag, a tap in the
  app, a selection), a paste, the edit panel, a panel opening, a field change or the text before
  the cursor no longer ending where the pick left it — after any of those a closing mark is
  committed as typed, with no deletion. Shift and the ?123 layer keys do not end it (pick → ?123 →
  `?` must give `"Hola? "`).
- PS5 Backspace right after a swap "undoes" the swap (restores `"Hola "`) instead of deleting one
  character: there is no invented undo; ⌫ deletes the provisional space like any other character.
- PS6 A provisional space exists in a URL / email / number / phone / password field. Picks don't
  happen there today; the engine must refuse to arm it outside `FieldKind.TEXT` anyway.
- PS7 Learning double counts or learns a wrong token: the swap leaves the same finished word
  (`"Hola "` → `"Hola, "`), so `Learner` must learn nothing from it (the pick already learned
  "Hola" once); the absorbed space learns nothing; the next word's context is right (`,` keeps the
  link to "hola", `.` / `?` / `!` start a sentence).

### Emoji glyph measuring off the UI thread (`EmojiPanel`)
r11a m2: `cellsOf` ran `Paint.hasGlyph` over a whole tab on the UI thread on its first show
(Pixel 6: Banderas 39–54 ms, Personas ~16 ms; several frames on a low-end phone).
- EG1 A catalog tab is measured on the UI thread (the first switch to Banderas blocks a frame
  budget). All catalog tabs are measured on one background thread with its own `Paint`, never the
  drawing one; the log line `emoji: tab … ms=… thread=…` names a thread that is not `main`.
- EG2 The warm-up starts late (on the tab switch) or in the wrong order: it starts as soon as the
  catalog is set, the current / first tab first, then the rest in order.
- EG3 A tab reached before its cells are ready shows stale cells, crashes or stays empty for good:
  it shows an empty grid (no "Sin emojis disponibles" claim while measuring) and is laid out and
  redrawn when its cells arrive (only if it is still the tab on screen).
- EG4 Stale results land: a result measured for a catalog instance that is no longer the panel's
  is dropped, and queued work for the old catalog is skipped.
- EG5 OC17 regresses: the EMPTY catalog (⚙ before the emoji key in a fresh process) must lay out an
  empty grid, start no measuring and cache nothing.
- EG6 The per-panel cache is lost or re-measured: each tab is measured once per catalog instance
  (the r11 EC5 semantics), re-opening the panel or switching tabs never measures again.
- EG7 A chosen skin tone in a catalog tab triggers a synchronous `hasGlyph` on the UI thread: it
  is shown once known drawable (already known from the tones popup, or measured on the same
  background thread), the base meanwhile. Recents keep their synchronous check (few emoji).

### Settings parses the emoji asset on the main thread (`SettingsActivity`, `EmojiAsset`)
r11a-fix recheck: the privacy wipe row and "Lo que sé de ti" called `EmojiAsset.catalog(this)`
(49 KB `assets/emoji/emoji.txt`) on the main thread on first render.
- SA1 The catalog is parsed on the main thread by settings: it is loaded on a background thread
  and the affected rows re-render when it arrives (the parse logs its thread).
- SA2 The tones count lies while loading: with a non-empty `emoji_tones.txt` and no catalog yet
  the wipe row says "Nada aprendido todavía", is disabled, or omits the tones as if there were
  none, or "Lo que sé de ti" says "0" — while loading it shows a neutral "…" and the wipe row stays
  enabled (the wipe deletes the file whatever its count).
- SA3 A re-render after the activity is gone (finishing / destroyed) crashes or leaks; a
  re-render on another page is needed only when the visible page shows tones.

### Update origin across a restore, and the orphan `autoAt` (`Updater`)
r11c minors: `restore()` hardcoded `checkedAuto = true`.
- UR1 An update found by a MANUAL tap ("tú lo pediste" in the Libro) renders after a process
  restart as "Comprobado al abrir el teclado". The origin is stored next to `availManifest`
  (`availAuto`) when the answer lands, and restored with it; a manual check that an automatic one
  in flight answered (promoted) is manual.
- UR2 A manifest stored by 0.7.1 / 0.8.0 (no origin flag) claims an origin it cannot know: both
  the automatic and the manual path wrote it, so it restores with the neutral "Comprobado · …",
  never "al abrir el teclado".
- UR3 The orphan `autoAt` (written by ≤ 0.7.0, never read since r11c) stays forever, or the
  cleanup removes any other updater pref (attempt clock, pending download id/manifest, dismissed
  version, the stored available manifest).

## Round 13a — the valid-word set (`ValidWords`, scripts/gen_valid_words.py, lexicon-src/hunspell)

VK 0.8.1 decides whether a word exists only from the 25k / 20k frequency lists, so a real word it
does not know is "corrected" (bastion→bastón ×4, anduvieron→mantuvieron, soberania→soberano,
pusiesemos→pudiésemos in session s1). r13a ships a full-form valid-word set per language, expanded
from pinned Hunspell sources, as data for the r13 correction policy. Phase A changes no correction.
Written before the code; each one names what a test or a measurement must catch.

### Coverage (generator)
- VW1 Real forms are missing. Hunspell es itself rejects `configurarlo`: rla-es puts infinitive
  enclitic flags on only 2114 of its 9246 conjugated verbs (`configurar/REDÄ`), so a plain
  expansion leaves most verb + clitic forms out, and 172 words of the shipped 25k list are
  imperative / infinitive + clitic forms Hunspell rejects (permíteme, pruébalo, hagámoslo,
  mantenlo, vámonos …). The generator adds enclitics (infinitive, gerund, tú / usted / ustedes /
  nosotros / vosotros imperatives) to every conjugated verb. The shipped set must contain
  `bastión anduvieron soberanía pusiésemos condujéramos configurarlo dámelo caído también` (es) and
  `bastion without fighting` (en).
- VW2 Typo forms leak in (over-generation). An enclitic rule that guesses a stem or an accent
  invents non-words (`cóntala` for contar, `dícelo` for decir, `hácelo` for hacer), and a typo of
  a common word then counts as "valid". Enclitic bases are taken only from forms the verb's own
  Hunspell paradigm produces (never guessed from the infinitive), irregular tú imperatives come
  from a closed table, and the layer is measured against rla-es's own enclitic forms for the
  verbs that carry them (precision / recall in the generator log). These must stay invalid:
  `calna listl busvamos impoetsnte pkxel clnfkgurarlo`.
- VW3 Hunspell features expanded wrong: a twofold suffix (continuation class) or a
  prefix × suffix cross product is dropped or applied without its condition; en_US ordinal
  compounds (`1st`, `21st`, COMPOUNDRULE) are infinite and digit pieces are ONLYINCOMPOUND. Every
  plain-expansion form must be accepted by a reference Hunspell (spylls), and every word of the
  shipped 25k / 20k lists that Hunspell accepts must be in the set (both checked by the
  generator's `--verify`); forms with digits are left out.
- VW4 Case rules wrong. Lowercase `chile` accepted because `Chile` is in the set, a
  sentence-initial `Casa` / `Caído` rejected, ALL-CAPS `CASA` rejected, or a capitalized-only
  entry rejected in its own form. Hunspell's rules: lowercase input matches lowercase entries
  only; Initial-cap input matches itself or its lowercase entry; ALL-CAPS matches any casing of
  the same letters; mixed case (`iPhone`) matches exactly.
- VW5 Accent folding wrong. ñ folded to n (`ano` must not offer `año`), ü not folded to u
  (`pinguino` → `pingüino`), input in NFD (a + U+0301) or with ’ instead of ' never matches the NFC
  asset. `accentVariants` returns every member equal after removing acute / grave / circumflex /
  diaeresis (ñ kept), under the same case rules: `caido`→[caído], `que`→[que, qué],
  `bastion` (es)→[bastión].

### Reproducibility + licenses
- VW6 The output drifts: the generator reads the system dictionaries (an OS upgrade silently
  changes the APK), iterates a set / dict in hash order, sorts by locale, or stamps a date. It
  reads only `lexicon-src/hunspell/`, checks `SHA256SUMS` first and refuses on a mismatch; two runs
  give byte-identical assets; the asset sha256 is recorded in `lexicon-src/README.md` and a JVM
  test checks the shipped asset against it (a hand edit fails).
- VW7 License drift: derived files ship without their license (es data is MPL-1.1+ and must stay
  MPL with source available; en needs the SCOWL notice), FrequencyWords stays credited as MIT only
  (its word lists are CC-BY-SA-4.0), or the credit copy implies the app downloads or connects to
  anything.

### Runtime (IME process)
- VW8 Asset too big: both languages add more than 4 MB to the APK.
- VW9 Heap blow-up: the set becomes a `HashSet<String>` / per-word objects (a million forms is
  well over 100 MB) or is copied more than once. One immutable buffer per language (memory-mapped
  when the APK stores it uncompressed, one `ByteArray` otherwise), ≤ 12 MB of heap for both.
- VW10 Slow: loaded on the main thread, built lazily at the first lookup, load > 300 ms or a
  lookup > 50 µs on the JVM test machine. Loaded on the existing `Lexicon` io thread next to the
  frequency list; no index is built after load; the first (cold, interpreted) call is made on the
  io thread right after loading, so the first word typed never pays it.
- VW11 Broken or missing asset crashes the keyboard: wrong magic / version, truncated file or a
  failed `openFd` (entry compressed) must fall back (read into one array) or leave the set absent
  (null) with today's behaviour, never throw on the io thread or at a lookup.
- VW12 Behaviour changes in phase A: the set is loaded and reachable (`Lexicon.valid(lang)`) but no
  correction reads it; the full JVM suite passes unchanged and `Suggest` / `Bar` /
  `KeyboardEngine` / `Learner` / `PersonalModel` are not edited.

## Round 13a phase B — the correction core (`TypoModel`, `Suggest`, `Bar.correction`, `KeyboardEngine`, `Rejections`, `PersonalModel` casing evidence, words.json stays v2)

Alan's live session s1 (Pixel 8 Pro, VK 0.8.1, fixture app/src/test/resources/r13/s1-items.json) is the oracle: VK rewrote
real words it did not know (bastion→bastón ×4, anduvieron→mantuvieron, soberania→soberano,
pusiesemos→pudiésemos), left one-key thumb slips (calna, listl, ñarte, busvamos, peha, xaza,
impoetsnte, pillae), and a correction the user deleted later and retyped was corrected again
forever unless ⌫ came right after it. Phase B uses phase A's valid-word sets; no language model.
Written before the code; each one names what the replay / a JVM test / the device run must catch.

### R1 — never rewrite a real word
- RW1 A word valid in an enabled language is replaced by space. Enabled = the writing language,
  plus the other one with "Sugerencias en dos idiomas" on (the vocabularies the bar already reads,
  BL8). `anduvieron` (es) and `bastion` (en) stay; `pr` / `ls` (valid en abbreviations) stay too,
  by design, even though s1 meant por / los. A lowercase name (`english`, `chile`, `google`) is the
  user's word: the lowercase form of a capitalized-only entry counts as valid.
- RW2 Slang and coinages outside Hunspell are rewritten (the experiment broke cachai→caca,
  fome→come, autocorrector→autocorrectivo). Protected besides the valid sets: every word the
  region's rule files promote or insert (`regional/es-CL.txt`, `es-419.txt`), every word of the
  frequency list (r8 A2: only an accent re-spelling may replace it, and only through R2), and the
  user's own words (typed `Bar.HABIT` times or kept, B5/K1).
- RW3 Half-loaded state: before the valid sets are mapped (the first ~100 ms of a process) the r12
  path must run unchanged (a null set = today's behaviour), never R2/R3 against a missing set. The
  other language's protection falls back to its frequency list (BL4) while its set is missing.
- RW4 A protected word loses its bar: it still gets accent variants, completions and the close
  match (not applied).

### R2 — accent completion
- AC1 A non-word with exactly ONE valid accent / diaeresis variant in the writing language is left
  bare (caido, condujeramos, soberania, pusiesemos) or loses to an edit candidate
  (soberania→soberano, pusiesemos→pudiésemos): the single variant wins before any R3 candidate.
- AC2 A word with several valid variants gets one forced (que/qué, esta/está, publico/público/
  publicó): nothing is applied; the bar offers the variants. A valid word never gets here (RW1).
- AC3 `bastion`: the bare form is valid in the other enabled language and the accented one in the
  writing language: nothing is applied and the bar offers `bastión` FIRST.
- AC4 Casing lost: the variant takes the typed casing (`Caido` at a sentence start → `Caído`);
  ALL-CAPS / mixed case / a capitalized word mid-sentence are never touched (A3).
- AC5 ñ treated as an accent: `ano` never becomes `año` through R2 (ñ is a letter); a non-word
  like `manana` may still get ñ through R3 as a cheap typo.

### R3 — non-word correction (keyboard geometry + Spanish sound + frequency)
- NW1 A thumb typo survives: neighbour key (calna, listl, ñarte, busvamos, peha, xaza, pillae), an
  extra key (seriom, tuvom, ltan, acambia, respaldoss), an omission (revisndo, tampco, condueramos),
  two neighbour slips on a long word (impoetsnte, clnfkgurarlo). Gate: ≥ 40 / 47 of s1_typo
  (VK 0.8.1 35, experiment S1 38); every miss listed in the report.
- NW2 The error model ignores the real keyboard: costs come from key-centre distance on the drawn
  layout (evidence/layout.json, Pixel 8 Pro: key pitch 99.7 px, row pitch 123.5 px, so a key above
  or below is 1.24 key widths away, farther than a side neighbour); ES has ñ at the end of the home
  row (ñ↔p, ñ↔l touch), EN's home row is offset by half a key; an extra bottom-row key at the end
  of a word is the thumb aiming at space (tuvom, seriom).
- NW3 Same-sound misspellings are not candidates or cost like a far key (provlema, tamvien,
  suherencia): Spanish only, seseo s/c/z (c before e/i), yeísmo y/ll, silent h (never the h of
  ch), b/v, soft g/j before e/i, k/c/qu — each costs about one neighbour slip.
- NW4 A rare valid form outranks the common word meant: the valid sets are ~90× the 25k list. The
  frequency rank is the prior; a form outside the list needs a long word, a cheap edit and a clear
  margin.
- NW5 Coinages broken by far fixes (autocorrector, grapheneos): the edit budget is bounded by
  length (1 edit under 7 letters, 2 from 7) and two-edit fixes must be thumb-shaped or land on a
  frequency-list word; cachai / fome are safe through RW2, not through luck.
- NW6 Short-word disasters (its/it's class, porq→por): ≤ 4 letters keep the r8 gates (target in
  the top list, cheap typo only, wide margin).
- NW7 R3 adds an accent R2 would not add (pkxel→píxel when `pixel` is valid; esya→está): R3 fixes
  letters; the committed form of the winning word is its bare form when that is valid in an enabled
  language, else its single accented variant, else the frequency list's spelling.
- NW8 English typos in a Spanish sentence (vuild, checl, goohle, commmit): with bilingual on the
  other language's words are candidates too, one notch behind the writing language's.
- NW9 Slow or heavy: the valid-set search runs bounded by edit distance in place in the mapped
  DAWG (no per-word objects), only for non-words; measured per word on the JVM; the device run
  reports per-keystroke handling time before / after. No new heap structure over 1 MB.
- NW10 The r8 synthetic gates (LexiconEvalTest Q1–Q4) regress on the shipped lists; a typo that
  lands on a valid word now counts as a real word (protected by RW1), the thresholds stay.
- NW11 (found probing ~150 Chilean words through the r13 corrector after the first replay) Chilean
  words outside RLA-ES are replaced by a rare valid form or a far list word one edit away:
  zapallo → zápalo and chupalla → chúpala (a doubled ll / rr is Spanish spelling, never a cheap
  slip), chascón → chasco and cachái → cachai (an accent typed by long-press is deliberate: a fix
  that drops it is never applied), piscola → pistola, voh → voy, lesera → mesera, ramadas →
  armadas, chorito → chorlito (curated into regional/es-CL.txt, protected by RW2). Alan's review:
  Chilean writing drops the u after w/hu (wuena → wena), and culiao / culiaos / culiá /
  conchetumadre / weones / weonas are curated too; typed without accent, weon and culia stay.

### R4 — late rejection (`KeyboardEngine` field memory, `Rejections`, files/personal/rejections.json)
- LR1 The user deletes a word VK autocorrected (later in the same field, or after leaving the
  field and coming back to it), retypes the typed form, and space corrects it again.
- LR2 After that the word is corrected again in a NEW field: the retyped form must be keep-marked
  (K1) in the language it was written in.
- LR3 False rejection: retyping a typo whose fix the user kept (the fix still in the text) is not
  a rejection; deleting a word VK did not correct is not one; deleting a correction and typing
  something else is not one.
- LR4 The r10/r11 paths break: ⌫ right after the correction restores the typed word (A10), the ↶
  chip (U1/U2), K1/K2 keep-marks, M10 unlearn.
- LR5 Rejected pairs (typed → fix) are not remembered, so the same fix is applied again once the
  keep-mark is gone (forgotten, evicted): the pair is stored and never applied by space again
  (still offered in the bar). Bounded, atomic, under files/personal/ (excluded from backup),
  removed by "Borrar lo aprendido", counted on its row, listed one by one with ✕ in "Lo que sé de
  ti"; nothing stored from secret / incognito / opted-out fields (X1–X3, K6).
- LR6 The field memory of corrections leaks: typed text kept in memory only (never disk, never
  logs), bounded per field and in number of fields, dropped for another field.
- LR7 A delete path is missed: ⌫ char by char, held ⌫ word mode, the ⌫ swipe (delete word), a
  selection deleted with ⌫, the cursor placed inside the corrected word.

### R5 — learned capitalization (`PersonalModel`, words.json v2 with a 6th field)
- LC1 GrapheneOS / Google / Pixel / Bastión written with that casing twice (mid-sentence, or
  with inner capitals anywhere) and never otherwise: a later lowercase typing does not commit the
  learned casing.
- LC2 Sentence-initial capitals or ALL-CAPS count as casing evidence (`Hoy`, `ESTO`).
- LC3 A contradicted casing is applied: the user also wrote the word lowercase mid-sentence, or
  with another casing, or reverted an applied casing (⌫ / ↶).
- LC4 The casing lands where the user chose: a word typed with capitals / mixed case, a word of
  another key, a secret / incognito field (personal read gate).
- LC5 Persistence: words.json keeps the evidence as a 6th vocabulary field and stays v2, so an
  r11 / r12 reader (a downgrade to 0.8.1) still loads every learned word; files without it load
  unchanged (no evidence yet); "Olvidar" drops it with the word.

### Version
- VR1 0.9.0 / versionCode 11 are the build defaults.

## Round 13c phase A — correction at every word ending, two-strike rejections, short words, two-slip words (0.10.0)

The oracle is two live sessions of the owner typing the same text: s1 (VK 0.8.1, careful typing) and
s2 (VK 0.9.0, everyday typing), fixtures app/src/test/resources/r13c/s1-live.json and s2-live.json
(one row per misspelled word: the intended text before it, what the fingers typed, the key that ended
the word live, the intended word, what VK left). Measured on de1efa4 before any code, by replaying each
row through the real engine + the shipped corrector + the shipped assets: not one word ended by `.`,
`,` or a new line is corrected (s1 0 of 10, s2 0 of 19), while the same engine fixes 9 of those s1
words and 11 of those s2 words when a space ends them. s2 typo rows: 31/78 as ended live, 42/78 if
every word had been ended by a space. Written before the code; each one names what the replay, a JVM
test or the device run must catch.

### E1 — the correction runs at every word ending (`KeyboardEngine`, IME glue)
- EP1 A word ended by punctuation or a new line keeps its typo. The endings are the r12 closing set
  `, . ; : ! ? ) ] } » ” …` (typed keys and long-press variants) and Enter where it inserts a new line
  (multi-line fields). The same correction space would apply runs, in one batch edit, and the mark
  lands right after the corrected word: `lentl,` → `lento,`, `pillae.` → `pillar.`, `tuvom⏎` →
  `tuvo⏎`. An Enter that performs an editor action (send, search, go …) or is sent as a key event
  corrects nothing: the text is leaving the field.
- EP2 The ending path diverges from space. It must go through the same decision as space: real words
  (RW1–RW2), the user's words (B5/K1), stored rejections (LR5), learned casing (R5), the profanity
  filter, the A3 case gates, the A4 standalone guard and the A11 mid-word guard (a letter right after
  the cursor: the IME reads one character after the cursor for these keys too).
- EP3 Undo after an ending. Decision: one ⌫ right after `lento,` gives `lentl,` (the typed word comes
  back, the mark stays, the cursor after it); the ↶ chip offers `↶ lentl` while the text ends with
  `lento,` and restores `lentl,`. Space keeps r10's A10 exactly (`lento ` + ⌫ → `lentl`, the space goes
  too). Both are a rejection (E2). Only right after: any other key ends the undo, as for space;
  deleting the fix later and retyping it is the late rejection (LR1), which now also fires when the
  retyped word is ended by punctuation or a new line.
- EP4 r12 regressions. A pick's provisional space + a closing mark still swaps (`Hola ` + `,` →
  `Hola, `), a space right after is still absorbed, double-space period still works (`lento` + space +
  space → `lento. `), and none of these try to correct (no word before the cursor).
- EP5 A period inside a token is taken for a sentence end: `resyst.cl`, `informe.pdf`, `v2.1`. If a
  letter or digit is typed right after the `.` whose ending corrected a word, the correction is
  withdrawn in the same edit (`resist.` + `c` → `resyst.c`): not a rejection (no strike, no keep
  mark), and nothing to unlearn (a word is learned only once whitespace follows it, Tokens.finished).
- EP6 Abbreviations: `ej.`, `pág.`, `etc.`, `aprox.`, `depto.`, `núm.`, `tel.`, `prof.`, `sr.` are valid
  in RLA-ES (the valid-word set keeps them without their dot) and stay as typed; a JVM row pins a list
  of them followed by `.`.
- EP7 Learning double counts or learns the wrong token: the corrected word is learned once (when the
  whitespace after its mark comes, as for any word followed by punctuation since r4); a ⌫ undo right
  after the mark leaves the typed word to be learned instead, once (TS3); the next word's context is
  right (`,` keeps the link to the previous word, `.` `!` `?` and the new line start a sentence).
- EP8 Latency: the ending key now runs the correction search. Per-key handling time before / after
  from the debuggable keytime log on the device; the search time per word from the replay.

### E2 — two-strike rejections (`Rejections`, `KeyboardEngine`, `Learner`, IME glue)
Owner's report: `lentl` + space → `lento` in WhatsApp; he deleted it and retyped `lentl`, and from then
on `lentl` was never corrected anywhere: r13a's late rejection keep-marked the word after ONE
rejection. This changes r11's K1 ("reverting a correction makes the word yours") from one rejection
to two.
- TS1 One rejection makes the word the user's everywhere. A first rejection (⌫ right after, ↶, or a
  late delete + retype, after any word ending) protects the typed word in the current field only:
  the engine's per-field memory (LR6 rules: memory only, at most 16 words, dropped when another editor
  starts, kept when the same editor restarts). Another field still corrects it.
- TS2 The first strike is forgotten, so the second never counts. The first rejection of a pair
  (typed → fix, lowercase) is stored as a strike in files/personal/rejections.json; a rejection of a
  pair that already has a strike — necessarily in another field, since the word is protected in the
  first one, or after the IME process restarted — makes it permanent: stored as a rejected pair (space
  never applies it again, LR5) and the typed word keep-marked (K1) in the language it was written in.
- TS3 A first rejection loses the word: it is learned as one ordinary use (a one-off completion, not
  kept), the fix's learn is taken back (M10).
- TS3b (found by the first JVM run) B5 turns one rejection into a permanent one: the word stays as
  typed in field A, so typing it there again is a second use, and r10's B5 ("typed twice is the
  user's") then stops space everywhere — the owner's complaint again by another path. While a pair has
  a pending strike, the uses of its typed word do not make it the user's for that fix; its second
  rejection decides (kept / stored words still win as before).
- TS4 0.9.0 data is dropped or reinterpreted: every `rejected` pair of a 0.9.0 rejections.json stays
  permanent and every keep mark stays. Strikes are a new `strikes` array in the same v1 file: 0.9.0
  ignores it when reading, so going back to 0.9.0 keeps every permanent pair (it drops the strikes).
- TS5 Strikes leak or grow: bounded (oldest first), written only through the learning write gate
  (nothing from secret / incognito / opted-out fields), wiped by "Borrar lo aprendido", counted on its
  row, listed one by one with ✕ in "Lo que sé de ti" saying what each means (once: corrected again in
  other fields; twice: never applied again); ✕ forgets either.
- TS6 Double counting: one correction gives at most one rejection (⌫ or ↶, whichever comes first);
  the same pair rejected again in the same field cannot happen while the word is protected there.
- TS7 The bar loses the fix: a rejected fix (once or for good) is still offered in the bar.

### E3 — two-letter words (`Suggest`, regional data)
- SW1 Two-letter non-words one key away from a very frequent word stay: `nl` `dd` `sd` `jn` `ys` `ek`.
  Rule: exactly two letters, lowercase (or the sentence-start capital); candidates are the two-letter
  words among the writing language's 64 most frequent list words, reached by replacing one letter with
  a key that touches it on the drawn layout (TypoModel.near); score = TypoModel substitution cost +
  the frequency prior; applied only when the best leads the next candidate by a margin (a tie such as
  `ze` → se / de stays). Numbers in the r13c report: the 64-word floor holds the everyday function
  words (de … he, ranks 0–61); the next two-letter words are va 99, oh 100, mí 103, ti, ir, ni, eh.
- SW2 A real two-letter word is touched: valid in the writing language (incl. lowercase names), in its
  frequency list (any rank), a regional word, or common in the other enabled language (BL4: its top
  8000 — of, is, ok, ls) is never corrected. Unlike longer words, the other language's Hunspell set
  does not protect a two-letter string: en_US accepts 308 of the 729 lowercase two-letter strings
  (42%, initialisms: dd, sd, bd, cb …), es 107 (15%), so "valid in English" says nothing about `sd`
  typed in Spanish. `pr` (en list 8084) has no frequent Spanish neighbour and stays.
- SW3 Single letters are corrected: never. Every lone consonant is chat shorthand or a unit (q k d t x
  m s g), and a e o u y are words.
- SW4 Chat shorthand is rewritten: Chilean / Latin American shorthand one key from a top word
  (`ql` → al, `ws` → es, `td` → te) is curated into regional/es-CL.txt as known words (RW2); xd, wn
  are already there; pq xq tb ns bn nd have no frequent neighbour. A JVM row pins them.
- SW5 English: the same rule over the English list (its two-letter words in the top 64).

### E4 — two slips in a longer word (`Suggest`)
- TW1 A 6-letter word with two slips stays (csmvia → cambia, hoohle → google): the edit budget is one
  edit under 6 letters and two from 6 (was 7). A two-edit fix of a 6-letter word must be two cheap
  slips (cost ≤ 1.1: two touching keys, or a slip and a swap / double / extra-near key), land on a
  list word in the top 12000 (r13a's 5–6 letter rank) and keep r13a's 6-letter margin.
- TW2 The rank gate reads one language only: a word in both lists (branch: es 21670, en 3865) is
  judged by its best rank among the enabled languages.
- TW3 Rare forms (outside both lists) get two-edit fixes on 6–9 letters: no — r13a's NW5 stays (two
  edits only from 10 letters, nothing else within two edits), so Chilean words and coinages of 6–9
  letters are not exposed to new rewrites.
- TW4 Shorter words change: ≤ 5 letters keep r13a's budget (one edit) and margins (NW6).
- TW5 Long words are never searched: MAX_FUZZY_LEN 16 cuts esternlcleidomastoideo (22 letters, one
  slip from a valid form); the search covers up to 24 letters (Suggest.MAX_LEN), rows sized for it.
- TW6 Speed: 6-letter words now search two edits in the list and the valid-word DAWG; the replay times
  every search, the device run reports per-key handling time before / after.
- TW7 (found replaying s2) A long word with three slips stays: impoersnte (the brief's own example: e
  for r, r for t, s for a) and dkcionafio (k for i, one c of cc, f for r) are three edits from
  importante / diccionario. From 10 letters, when the first search has no confident answer, a second
  pass looks within three edits bounded by cost; a three-edit fix must land on a list word, cost at
  most three cheap slips (1.6) and lead everything that pass sees by r13a's rare-form margin (0.4).
- TW8 (found replaying s2) Spanish sound rules score English words: the other language's list is
  scanned with the writing language's edit rules, so `hoohle` reads as `hole` with a silent h (cheaper
  than the two slips to `google`). Spanish same-sound spellings apply only while Spanish words are
  scanned (the list and DAWG of a Spanish lexicon); English words are scored by the keys alone.

### E5 — a capital inside a word (`KeyboardEngine` auto-capital, `Bar` learned casing)
Live s2: the owner typed `t r a s ⌫ a e` + space and VK left `trAe`. No shift key was pressed in the
whole session, and the space corrector never adds an inner capital to a lowercase typing (it copies
only a first capital or ALL-CAPS), so the capital came from one of two paths, both closed:
- MC1 Auto-capital fires inside a word: the IME's AUTO shift follows the editor's caps-mode answer,
  which can be stale right after a ⌫ or a fast edit. AUTO shift capitalizes only a letter that starts a
  word; typed right after a letter, digit or apostrophe it types lowercase (a user's shift / caps lock
  is never overridden).
- MC2 A learned casing with an inner capital is applied to a dictionary word: `trAe` committed (by the
  path above, or a slip) is learned as casing evidence for `trae`, and r13a's R5 then turns every
  `trae` into `trAe`. A learned casing with a capital after the first letter is applied (space and bar)
  only to a word that is not a word of an enabled language in lowercase, or whose cased form is itself
  a valid spelling (iPhone). GrapheneOS, Pixel, Bastión keep working.
- MC3 A fix puts a capital inside a word: the corrector's fix of a lowercase or sentence-capital typing
  never has an inner capital unless the target's own spelling has it; a JVM row checks the replayed
  sessions' commits.

### E6 — typo + missing accent (`Suggest`)
- TA1 The fix of a typo is a valid accented form outside the frequency list and is held back by a list
  word one edit further: pusiesemls → pusiésemos (one slip, l for o) vs pudiésemos (two edits, list
  19105) differ by 0.09 in total because every form outside the list gets the flat prior of rank
  250 000, so the margin gate leaves the typo. Rule: for a valid form outside the list with ONE cheap
  slip (≤ 0.6) on 7+ letters, a rival that needs another edit and costs at least one more side slip
  (0.4) is not "close"; the margin is read against the rest. r13a's rare-form gates stay (one edit,
  5+ letters, cost ≤ 0.8, 0.4 margin over the close rivals).
- TA2 Over-correction into rare inflections (NW4). Tried first and rejected: a "family prior" (a rare
  form whose first max(5, ⌈len/2⌉) letters start a list word gets that word's rank) — 5-letter stems
  match unrelated lemmas (probad ← probablemente, aburras ← aburrido) and broke two s1 fixes
  (abieras → abiertas, probae → probar). The rule above changes no prior; the replay gates of r13a
  (s1_ok 181/181, ctx_keep 35/35, s1_typo ≥ 41, Chilean words) must hold.
- TA3 condikeramls → condijéramos (valid, condecir) instead of condujéramos: the report says whether
  geometry or frequency can prefer condujéramos; no word is hardcoded.
- TA4 (found replaying s2) A typo of a word spelled differently in the two languages lands on the
  other language's spelling: baation (Spanish sentence) → bastion, because NW7 commits the bare
  spelling whenever it is valid in an enabled language. When the family was found among the writing
  language's words, its bare spelling is valid only in the other language, and the writing language
  has exactly one accented spelling, that spelling is committed (baation → bastión). pkxel → pixel
  stays (pixel is valid in Spanish), vuild → build stays (found among English words).

### Version
- VR2 0.10.0 / versionCode 12 are the build defaults.

## Round 13c phase B — licenses inside the APK, and "Novedades" after an update (0.10.0)

The repo is GPL-3.0-or-later (`LICENSE`, README "License", de1efa4). Written before the code; each
item names what a JVM test or the device run must catch.

### L1 — what the APK says about its license (`SettingsActivity`, `assets/`, `valid-es.dawg`)
- LI1 The settings credit line still says "diccionarios Hunspell RLA-ES (MPL)". After r13c-B it says
  the Spanish dictionary is used under the GPL (RLA-ES offers GPL, LGPL or MPL), and a second line says
  Resyst VK itself is free software under GPL 3 with its source on GitHub. Caught by: a JVM test that
  reads the settings source and the repo README "License" section (the claims must agree), and a
  screenshot of the footer.
- LI2 The GPL-3 text does not travel in the APK (the repo root `LICENSE` is not packaged). It ships as
  `assets/LICENSE-GPL-3.txt`. Caught by: a JVM test that the asset and the repo root `LICENSE` are the
  same bytes, so a later edit of one without the other fails the build.
- LI3 `valid-es.dawg` embeds a header notice that says "used under MPL-1.1+ ... This file is MPL-1.1+",
  which contradicts the three-way offer used under GPL-3+. The header is part of the file, so changing it
  means regenerating the DAWG, only through `scripts/gen_valid_words.py` (header text only). Caught by:
  `Round13ValidWordsTest` (manifest sha256 / bytes, word count, notice string), a second generator run
  with the same sha256, `gen_valid_words.py --check`. If the regenerated word set differed (words or
  arcs not equal to the manifest's 2 267 583 / 115 825), stop: that would be a behaviour change.
- LI4 `assets/lexicon/README.txt`, `lexicon-src/README.md`, the README "License" section and the
  settings credits disagree. Caught by: one JVM test over all four.
- LI5 The source link in settings opens the browser through an intent. That is not a connection the
  keyboard makes (the browser makes it), so the "Libro de conexiones" does not record it and its copy
  stays about the keyboard's own requests; the link text says it opens the browser. A failed intent
  (no browser) must not crash settings.

### N6 — "Novedades de la X" (`WhatsNew`, IME glue, `KeyboardView` chip)
- WN1 Shown the first time the keyboard opens after VK was updated to a new versionName, never on a
  fresh install. The only persisted fact is the last versionName seen (one string, local, no network).
  On a fresh install the first keyboard start writes the baseline silently. An update from a build
  that never stored it (0.9.0) has no stored version: it is told from a fresh install by the package's
  lastUpdateTime being later than its firstInstallTime. Caught by: a JVM truth table (fresh, update
  from a build without the key, update from an older stored version, same version, downgrade,
  unparseable) and the device run (0.9.0 → 0.10.0 shows; an uninstall + install does not).
- WN2 Never in a secret field (password, PIN) or an incognito field. A notice not shown stays pending
  (it is not consumed by a field that could not show it). Caught by: the pure `visible()` table.
- WN3 It disappears for good once tapped or dismissed (✕), and once the user types with it on screen
  (they saw it and went on: a quiet strip never nags on every field). A notice that was never on a
  strip (secret field, paste offer, an update chip) stays pending. A tap whose browser intent fails
  keeps it pending (nothing was shown to the user). Caught by: JVM transitions, device run (once; the
  second open shows nothing).
- WN4 It never takes the strip from the update chip: an announced update (r9) wins; the notice waits.
  It never takes it from typing: a typed character, a paste offer, a secret field hide it at once
  (no amber dot: a news item is not worth a badge).
- WN5 It reuses the update chip's look (accent-soft pill, amber hairline, ✕ cell), with a ✦ in the
  disc instead of the download arrow, so it reads as VK's own. Every label tier carries the whole
  version ("Novedades de la 0.10.0", then shorter, then the bare version): a cut version is a bug.
  The action line says it opens the browser. A debug build's "-debug" suffix is not shown.
- WN6 Tapping it opens `https://kv.resyst.cl/novedades/` with an ACTION_VIEW intent. That is the only
  thing it does: no request by the keyboard, nothing in the connection book. TalkBack reads what it
  does ("Novedades de la versión X. Toca para abrir en el navegador").
- WN7 No setting toggle: the notice is once per version and dismissible, and the settings structure
  has no "notices" group the row would belong to. Adding a row would also add a search-index entry and
  a stored key for a message that already goes away by itself.
- WN8 "Clear data" on an updated app starts with no stored version while the package times still say
  "updated": the notice shows once more. That is accepted (it is the first run after wiping, and one
  dismiss ends it).

## Round 13d — the deterministic misses of live session s3, and learned words in "Sin memoria" fields (0.11.0)

The oracle is the owner's third live session, s3 (Pixel 8 Pro, VK 0.10.0 in a Google Keep note, everyday
typing), committed unchanged as app/src/test/resources/r13d/s3-live.json next to r13c's s1 / s2. Measured
on 361d784 (= the published 0.10.0) before any code, by replaying every row through the real engine + the
shipped corrector + the shipped assets, and by reading the raw touches (taps.json) against VK's own hit
cells (KeyboardView.boxAt: a row is `stripH + r·rowH`, a key's cell runs to the gap midpoints; a letter is
committed where the finger lifts, after any slide):
- `auer` + space as the first word of an empty note stays `Auer`. The field start, the auto-capital and
  the first bar refresh are not involved (the replay with an empty `before` gives `Auer` too, and so does
  `auer` mid-sentence): en_US Hunspell lists the surname `Auer`, and r13a's RW1 rule "the lowercase form
  of a capitalized-only entry is the user's word" is applied to the OTHER language's set, so a Spanish
  typo one key from `ayer` is protected as an English name.
- `pof` → `por` (rank 11, one vertical slip, 0.53) is not applied: English `of` (rank 8, an extra key in
  front, 0.65 + the other-language notch) is 0.38 behind and ≤ 4 letters need 0.45.
- `iueho` (VK received exactly that: final.txt keeps it): two edits on five letters are never searched
  (r13c starts at six). Its first touch landed at the corner where i, k, u and j meet (0.2 px from k's
  cell, about 8 px from u's and j's, about 100 px from l's). From the letters alone it is a tie: i→j and
  i→l are mirror diagonals on the ES layout (j sits under u), so `luego` (rank 196) and `juego` (432)
  cost exactly the same two slips and `luego` would win. Only the touch tells them apart.
- `yatde` → `yate` (rank 8923, one extra key next to e) because `tarde` (rank 243, two slips: y for t, t
  for r) needs two edits and five letters search one.
- `nuneros` → `juntos`: VK received `juneros`. The first touch went down on n and slid 10 px up into j's
  cell before lifting, so VK committed j (the touch decoder, which reads the down point, says n). From
  `juneros`, `juntos` (an extra e next to r + r for t: 1.05) leads `números` (j for n on the first
  letter + n for m: 1.19) by 0.35 with frequency, over the 0.25 a 7-letter word needs.
- In the Keep field (TEXT | CAP_SENTENCES | MULTI_LINE | NO_SUGGESTIONS: prose, opted out) the learned
  model is ALREADY read since r11 (X6: `FieldPolicy.offerWords`); learning is closed (`personalWords`).
  So `grapheneos` staying lowercase is not the read gate: r13a's learned casing needs two consistent
  cased uses typed in fields that learn (words learned before 0.9.0 carry no casing evidence). And
  `gtapheneos` → GrapheneOS has no path at all: learned words are never correction targets.
- Rows the live build's own engine cannot reproduce from the decoded touches (VK received other keys,
  or the touch recorder dropped a touch): `nuneros` (juneros), `gome` (lifted on the f/g border),
  `hohle` (both h touches 0.5 px from g's cell), `tuvbo` (`tuvo⌫bo` with v on the v/b border), `pllar`,
  `cas`, `haolla`, `po` (fixed live, but the replay of 361d784 cannot reach the live word from the
  decoded letters), and the misaligned `l` (really the `l importante` near the end). Reported as noise,
  never tuned for. Out of scope (real words, need context): de/la, yan/tan, vacan/bacán, pata/para,
  nieva/nueva, di/Si.

Written before the code; each item names what the replay, a JVM test or the device run must catch.

### F1 — the first word of a field (`Suggest` R1 protection)
- FW1 A Spanish typo that equals a capitalized-only entry of the OTHER enabled language (an English
  surname or place: `auer` ↔ Auer, one slip from `ayer`) is protected as a name and never corrected.
  Rule: a lowercase typing is protected by the other language's set only through a lowercase entry
  there, or — for a name — when that lowercase form is in the other language's frequency list (any
  rank: `google` 9778, `english`); the writing language's own names keep r13a's RW1 (`chile`, `maría`).
- FW2 The first word of a field is treated differently from the same word mid-sentence: the replay
  with an empty `before` and the device (an empty field, auto-capital on) must give `Ayer`; the
  capitalized typing (`Auer` from the auto-capital at the text start) is corrected and keeps its case.
- FW3 A real word of the other language or a common name there is now corrected: `english`, `google`,
  `the`, `for`, `bastion`, `anduvieron` (es) and every r13a / r13c protection row still stay.

### T3 — three-letter words (`Suggest`)
- T3-1 A three-letter non-word one touching key from a very frequent three-letter word stays (`pof` →
  `por`) because an extra- or missing-key reading in the other language (`of`) is within ≤ 4 letters'
  0.45 margin. Rule (same discipline as SW1): exactly three letters, a non-word; the best spelling family
  of the regular search is a three-letter word among the writing language's [THREE_TOP] most frequent
  list words, reached by ONE substitution of a touching key (cost ≤ 0.6 with the first-letter penalty);
  every other family competes (substitutions, an extra or a missing key, the other language one notch
  behind) and the best must lead the next by [THREE_GAP] (0.3; 0.45 stays for everything else). The
  floor comes from the lists: ranks 0–199 hold the everyday three-letter words (es: que 1, por 11, una
  14 … tus 181, hoy 199; en: you 0, the 2 … big 191, lot 192); the next ones are oye 204, mal 217, aún 221.
- T3-2 A real three-letter word is touched: valid in the writing language (incl. lowercase names), in its
  list (any rank), regional, valid in the other language (lowercase entry; en_US accepts 6% of the
  three-letter a–z strings, es 2.3%, unlike the 42% of two-letter ones) or a common name there (FW1):
  `pon`, `fue`, `sol`, `the`, `for`, `ves`, `mía` never change. A JVM test walks every three-letter word of
  both lists, the regional files and every a–z / ñ string the valid sets accept.
- T3-3 Ties are broken: `cas` (vas by a slip 1.21, casa by a missing key 1.34) and `ze`-like triples stay;
  the bar offers the close candidates.
- T3-4 Chat shorthand of three letters is rewritten by the new rule (`xfa`, `pls`, `tmb`, `bkn`, `wna`,
  `ctm`, `ntp`, `tqm`, `npi`, `dsp`): the report lists which shorthand changes between 361d784 and this
  build (`dps` → `dos` is r13a's regular path, unchanged).

### W5 — two slips on five letters (`Suggest`)
- W5-1 A five-letter word with two slips stays (`iueho` → `juego` once the touch says j, T B below).
  Five letters now search two edits. A two-edit fix of a five-letter word must be two cheap slips (cost
  ≤ 1.1), land on a list word in the top 12000 (r13a's 5–6 letter rank), be the ONLY spelling family
  within that cost, and lead the next family by r13a's five-letter margin (0.35).
- W5-2 The two-edit families are rivals of a one-edit fix too: `yatde` → `yate` (one extra key, rank 8923)
  must see `tarde` (two slips, rank 243); when they are close the word stays and the bar offers both.
- W5-3 False fixes: the report gives the rate over (a) every five-letter word of the shipped valid sets
  and lists (protected: must be 0), (b) synthetic two-slip typos of the five-letter list words (wrong
  word vs right word vs left, 361d784 vs this build), (c) the s1 / s2 / s3 `ok` rows and r13a's s1_ok /
  ctx_keep gates (all intact).
- W5-4 Speed: five-letter words now search two edits in the list and the DAWG; the replay times every
  word, the device run reports per-key handling time.

### TB — the touch says which neighbour (`TouchKeys`, `KeyboardView`, IME glue, `TypoModel`)
- TB1 The corrector only sees letters, so a touch on the border of two (or four) keys is scored like a
  touch in the middle of the key it fell in: `iueho` cannot become `juego` (tie with `luego`), `juneros`
  cannot become `números`. Rule: when a letter key commits, the keyboard records the other letter keys
  whose hit cell lies within [TouchKeys.BORDER] key widths (0.12 ≈ 12 px on the Pixel 8 Pro: the 10 px
  visual gap plus a pixel) of the point where the finger lifted (or, for a roll-over commit, where it
  was last); for that letter of that word, typing it instead of one of those keys costs [TypoModel.BORDER]
  (0.16, a substitution at distance 0) with no first-letter penalty. Everything else keeps the geometry.
- TB2 The record outlives its word or drifts from the text: it is held for the word being typed only
  (letters typed by key or long-press variant), cleared on a word end, a pick, a field change, a cursor
  the user moves; ⌫ drops its last letter; it is used only when its letters spell exactly the word being
  corrected (lowercase), else ignored. Memory only, at most [Tokens.MAX_LEN] small strings, never logged,
  never persisted, never sent.
- TB3 The bar and space disagree: the bar's confident first item and space read the same record.
- TB4 Border evidence rewrites a real word or adds a rewrite the letters alone would refuse into a word
  that is not the touched neighbour: it only lowers the cost of the touched neighbour at that position;
  R1 protection, the rank gates, the margins and the two-strike rejections are unchanged.
- TB5 The replay cannot show it: s3's raw touches (taps.json, not committed) give, for every fixture row,
  the border keys of each letter by VK's own cells (scripts/r13d_touch_keys.py → r13d/s3-touch.json:
  letters only, no coordinates, no text beyond the fixture's own `typed`); the replay runs every s3 row
  with and without them.
- TB6 Device: a tap on the i / k / u / j corner then `ueho` + `,` gives `juego,`; a tap in the middle of i
  then `ueho,` stays `iueho,` (no border, the tie stays).

### WC — wrong corrections (`Suggest`, `Bar`)
- WC1 `yatde` → `yate`: covered by W5-2 (tarde is now a rival; when close, the word stays).
- WC2 `juneros` → `juntos`: two readings of two edits each (an extra key + a slip vs a first-letter slip
  + a slip) and frequency decides by 0.35. Rule: a fix that needs two edits must lead the next family by
  r13a's rare-form margin (0.4) whatever the length (two edits leave more readings than one); with the
  touch (TB1) `números` costs 0.56 and is applied.
- WC3 Leaving the word loses the choice: when the best is not applied and the next family is within the
  margin, the bar offers both (`yatde`: tarde, yate; `juneros`: juntos, números), best first.
- WC4 r13c's gains break: s1 ≥ 43/49 with every non-noise row fixed, s2 ≥ 55/78, every `ok` row of s1 /
  s2 / s3 intact, r13a's s1_ok 181/181 and ctx_keep 35/35, Chilean words, LexiconEvalTest, endings,
  two-strike, short words.

### OR — learned words in opted-out fields: read, never written (`Writing`, `Suggest`, `PersonalModel`, IME glue)
- OR1 A typo of a word the user taught the keyboard and no dictionary knows (`gtapheneos` for GrapheneOS)
  is never corrected: learned words are not correction targets. Rule: the user's own words (typed
  `Bar.HABIT` times or kept, in the vocabularies read, BL8) that are no word of an enabled language
  compete in the correction search like a list word of rank [Suggest.USER_RANK] (1000), spelled with the
  learned casing when its evidence holds (R5: GrapheneOS), else lowercase; same edit budgets, gates and
  margins as any list word.
- OR2 The read gate leaks: learned targets, learned casing, rejections and personal completions come only
  through the read gate (`FieldPolicy.offerWords`); incognito and secret fields read none of them (a
  model holding GrapheneOS: `grapheneos` and `gtapheneos` stay as typed there).
- OR3 An opted-out field writes: in a field with FLAG_NO_SUGGESTIONS nothing typed is stored — no new
  word, no use, no casing evidence, no keep mark, no strike or rejected pair. A JVM test types and rejects
  corrections in such a field and compares words.json / rejections.json (as PersonalStore writes them)
  byte for byte; the device run compares the files read with `run-as` before / after.
- OR4 Slow on every key: the learned targets are rebuilt only when the model changes (a change counter
  in memory) or the vocabularies change, and each lexicon keeps only the targets that are no word of it.
- OR5 A learned target rewrites a real word or another learned word: R1 protection runs first (a typed
  word that is valid / listed / regional / the user's own stays), and two learned targets that are close
  leave the word.
- OR6 The "Sin memoria" chip says the keyboard does not use what it learned: in an opted-out field it now
  says it uses what it already learned and does not store what is typed there ("No guarda lo que
  escribes aquí"); settings text that states where words are learned says the same. Secret / incognito /
  mode / setting-off texts are unchanged.
- OR7 Emoji recents and tone defaults: unchanged (stored in opted-out fields since r10, not in incognito
  or secret ones); they are not typed text and are not part of words.json / rejections.json.

### Version
- VR3 0.11.0 / versionCode 13 are the build defaults.

## Round 13f — chat typing from live session s4 (0.12.0)

The oracle is the owner's fourth live session, s4 (Pixel 8 Pro, VK 0.11.0, the Hermes web chat composer in
Firefox, 22 messages, each sent with VK's own Enter), committed unchanged as app/src/test/resources/r13f/
(s4-live.json, s4-keys.json, s4-final.txt, s4-intended.txt; decoded from the raw touches by
scripts/r13e_s4_decode.py). The field is inputType 0x4c0a1: TEXT | CAP_SENTENCES | AUTO_CORRECT |
IME_MULTI_LINE without MULTI_LINE, so VK treats it as single-line and its Enter is the field's action key.
Measured on 8ffd08a (= the published 0.11.0) before any code:
- `salgo` (the first word after a message that ended `?⏎`), `el actual` (after `? `) and `¿que` stayed
  lowercase. Each time the mark was typed on the symbols layer; the editor's caps answer arrived while the
  symbols layer was showing (`updateAutoCap` gives no shift off the letters layer), and the ABC key only
  rebuilds the layout: nothing re-reads the caps answer when the letters come back.
- `¿que` also needs the openers rule: Android's TextUtils.getCapsMode stops at `¿` / `¡` (category Po), so
  even a re-read caps answer is "no" right after a sentence-initial opener.
- `lenyo⏎` was sent uncorrected: KeyboardEngine runs the word-end correction on Enter only where Enter is a
  new line; in a single-line field the Enter goes out (editor action or KEYCODE_ENTER) with the typo.
- Space-bar gestures (`SpaceGesture`: a vertical move of 20 dp within 300 ms = language flick, a horizontal
  move over 14 dp = cursor drag). s4 has 234 touches on the space bar: 6 classified as flicks, 1 as a drag.
  Their traces (scripts/r13f_space_traces.py → r13f/s4-space-traces.json) split into two groups:
  - 4 accidental, intended as a space: m05 `del|final` (20.5 dp up, 83 ms), m11 `mu ho|que` (26.1 dp up, 95 ms),
    m20 `la|pena` (20.5 dp up, 66 ms) and the drag m15 `yay|algo` (15.4 dp sideways, 75 ms). They made
    `delfinak` (plus l→k: the English layout's middle row is shifted half a key), `lapena`, `yayalgo`.
  - 3 deliberate, the owner switching back to Spanish right after noticing an accidental switch: m06
    (114 dp up), m11 (87 dp up, right after `que `), m21 (64 dp up, right after `ahkra `). Each follows an
    accidental flick and restores Spanish; as spaces they would have typed `no s e`, `que  `, `ahkra  `.
  - s1-s3 (796 space touches): no flick, no drag; the largest moves are 11 dp sideways, 15 dp up.

Written before the code; each item names what a JVM test, the replay or the device run must catch.

### F1 — the auto-capital after a layer return and in an emptied field (`KeyboardEngine`, IME glue)
- CL1 Back on the letters layer the shift keeps the state it got while another layer was showing (OFF), so a
  sentence start typed after `?`, `¿`, `!` or any symbol stays lowercase. Rule: the engine keeps the last
  caps answer it was given; a layer key that brings the letters back re-applies it (ONCE / LOCKED / a held
  shift are never overridden); the IME also re-reads the editor on that return.
- CL2 A field emptied from outside (the page clears the composer after a send: the IME sees only a
  selection update) keeps a stale "no caps" answer from the editor. Rule: a cursor at offset 0 with nothing
  before it is a sentence start (the IME's own answer is OR-ed with the editor's; an editor that cannot
  read its text is not affected because the offset comes from the selection update).
- CL3 The re-read capitalizes mid-sentence: returning from symbols after `3`, `,`, `@`, `#` or an emoji
  keeps lowercase (the caps answer there is "no").

### F2 — the capital after a sentence-initial ¿ / ¡ (`AutoCaps`)
- OP1 `¿que` at a sentence start stays lowercase. Rule: when the text before the cursor ends with one or
  more `¿` / `¡` (with quotes or brackets around them), Android's own sentence rule is applied to the text
  before the openers: start of field, a new line, or `.` `?` `!` followed by a blank (not an abbreviation's
  or an ellipsis' period, Android's own check).
- OP2 A mid-sentence opener is capitalized: `Oye, ¿vienes`, `y ¿por qué`, `dime ¿cuál` keep lowercase.
- OP3 Literary lowercase is capitalized. Measured on r13b's held-out prose (Quiroga 1917, 12,013 words, 318
  openers): the r13e-s4 measurement reports 122 right / 25 wrong; the report lists the 25 and which a
  clean rule removes (the ellipsis `...` is no sentence end for Android: OP1 follows it), with the final
  counts. Series of exclamations or questions written in lowercase (`¡Pum! ¡todo`) need meaning, not a rule.
- OP4 The rule leaks outside auto-capital fields: it only feeds `updateAutoCap`, which keeps its gates
  (CAP_SENTENCES / CAP_WORDS / CAP_CHARACTERS, the auto-capital setting, the letters layer).

### F3 — the word is corrected before VK's own Enter sends it (`KeyboardEngine`, IME glue)
- SE1 In a field where Enter is the action key (single-line, or IME_MULTI_LINE without MULTI_LINE) the word
  in progress goes out as typed. Rule: Enter ends the word like a space does — the same correction
  (`correctionFor`: rejected words, this field's protected words, late retypes, two-strike rejections and
  the touch record all apply), committed in one batch edit (delete + commit) BEFORE the editor action or the
  KEYCODE_ENTER event; nothing else is added (no space, no mark).
- SE2 The action runs inside the batch and the app reads the old text: the batch is closed before the action
  / key event is sent.
- SE3 Undo semantics change: the correction is remembered like any word-end correction (⌫ right after it,
  while the text is still there, restores the typed word: a search box that keeps its text); a sent message
  cannot be undone. Decision: the correction at Enter is NOT more conservative than at a space — the report
  gives the false-correction count of every s1-s4 `ok` row ended by Enter in a single-line field (must be
  0, like at a space) against the typos it fixes.
- SE1 replaces the last sentence of r13c's EP1 ("an Enter that performs an editor action or is sent as a key event
  corrects nothing: the text is leaving the field"): leaving is exactly why the typo must be fixed first.
- SE4 Enter corrects where it must not: a multi-line field keeps r13c's EP1 (the newline after the fix);
  fields without corrections (secret, email, URL, opted-out handles: no corrector) send as typed; the GIF
  search box keeps its own Enter.

### F4 — the space bar is less trigger-happy (`SpaceGesture`, `KeyboardView`)
- SG1 A tap whose finger drifts 20-26 dp up switches the language (and the layout) and types nothing.
  Rule: a language flick needs a deliberate vertical swipe: |dy| ≥ 40 dp (about 0.6 of a key row; s4's
  accidental moves reach 26 dp, its deliberate swipes start at 64 dp; with s4's density uncertainty,
  1.78-2.0, they stay 28 vs 61 dp), |dy| > 2·|dx|, within 300 ms.
- SG2 A tap that slides 15 dp sideways enters cursor mode and types nothing. Rule: a cursor drag is either a
  hold-then-drag (the finger down ≥ 200 ms — every space tap in s1-s4 lasted ≤ 153 ms — then ≥ 9 dp
  sideways, one cursor step) or a long drag (≥ 24 dp sideways); |dx| > |dy| as before. The drag is counted
  from where the finger landed, so the travel spent deciding is not lost.
- SG3 The fixtures are not the owner's: s4-space-traces.json is every s4 space-bar touch from the raw log
  (relative path in dp and ms; no absolute coordinates, no text); the 4 accidental gestures must be spaces,
  the 3 deliberate ones must stay flicks, and every other space touch of s1-s4 must stay a space.
- SG4 A changed language is invisible: the space bar keeps showing the active language's name and the
  English layout (no ñ) shows at once; TalkBack hears "English" / "Español" (unchanged).
- SG5 The long press (keyboard picker) and a flick or drag steal each other: a flick or a drag cancels the
  pending long press (unchanged); a hold-then-drag starts before the default long press (350 ms).

### Version
- VR4 0.12.0 / versionCode 14 are the build defaults. "Novedades" is the strip chip that opens the website
  for any new versionName (r13c WN1-WN8); there is no per-version text in the app to add.

## Round 13f (offers) — context offers in the suggestion bar (0.12.0)

r13e measured a word-trigram context checker (spike `spike/r13e-ngram`, commit ce8792c; report r13e.md) and r13e-s4
froze its offer point on the owner's chat session s4: mode O@1 (the offer is decided once the next word of the same
sentence is typed, and stays on the bar until the sentence moves on), τ_offer 6, model `wt-m2`, K 2, θ_flag −8,
contrast guard on, keep-lists on. On s4: 15 offers made, 14 right (93 %), recall 14/25 in-reach targets. The owner
accepted shipping it as OFFERS ONLY: a just-typed real word that is probably the wrong one for its context (`caza` →
`casa`, `di` → `si`, `yan` → `tan`, `pata` → `para`, `esta` → `está`, `mas` → `más`, `que` → `qué`) gets its
alternative as a bar chip; one tap replaces it. The keyboard never edits by itself on context. The spike reader
kept the model on the JVM heap (~41 MB); the IME process lives for days.

Written before the code; each item names what a JVM test, the offline replay or the device run must catch.

### CO — a wrong offer, or an edit nobody asked for (`ContextCheck`, `ContextOffers`)
- CO1 The shipped path drifts from what was measured. Rule: port, don't retune — the same tokenizer, candidates,
  Stage 0 (K 2, θ −8, contrast guard, keep-lists, I6 shape rule), window score and Δ, at the frozen point. The
  offline test runs the SHIPPED decision (the live text cut at the end of the next word, as the IME sees it) and
  must give s4 15 made / 14 right and recall 14/25, and the r13e precision on the 315 set and held-out prose A / A'
  (pooled 168/199); every difference is listed with its cause.
- CO2 The keyboard edits on context by itself. Rule: the checker returns a proposal; only a tap on the chip edits.
  A JVM test drives the whole s4 key stream through the engine with the offers controller attached at every word
  end and compares every field text with a run without it (identical).
- CO3 Offers on English sentences (the owner writes both): only when the writing language is Spanish and the
  sentence is not mostly English (r13b's rule: words valid only in English must not outnumber words valid only in
  Spanish).
- CO4 Offers on the user's own words: never on the keep-lists (r13b's Chilean seed, the region's protected words,
  the words the user kept against a correction and the words he taught that no dictionary knows), never on ALL CAPS
  or inner-capital words. A word the user merely types often is not protected: `esta` typed for `está` every day
  must still get its offer.
- CO5 "No es lo mismo casa que caza": the contrast guard skips an alternative already in the sentence or the one
  before. The live text has no future words (the spike's guard saw the whole sentence), so an offer whose
  alternative is typed later in the same sentence is withdrawn.

### CH — the chip hides a better word suggestion (`KeyboardView`, IME glue)
- CH1 The chip pushes out what space would apply or the ↶ undo. Rule: the chip takes ONE slot, the last; the first
  suggestion (the confident fix space applies), the ↶ chip and the ¿…? chip keep theirs.
- CH2 The chip reads as a completion (tapped for the wrong reason) or is invisible to TalkBack: a pill with a swap
  mark, in VK's accent, labelled with the replacement (`casa`), described "Cambiar caza por casa" (English:
  "Replace caza with casa"); its own tap target.

### ST — a stale offer (`ContextOffers`, IME glue)
- ST1 The sentence moved on (`.` `?` `!` `…`, a new line, Enter / send, the field emptied): the offer disappears.
- ST2 The cursor moved (a tap elsewhere, a selection, ⌫ into the word, another field, the keyboard hidden): the offer
  disappears. A tap on a chip never edits blind: the target is re-read from the field (same sentence, same words up
  to it, same spelling) and the edit is computed from that reading; otherwise the tap does nothing.
- ST3 A decision computed on a background thread arrives after the text changed: shown only if the live text still
  holds the target in an open sentence.
- ST4 The tap moves the cursor or loses text: the earlier word is replaced and the text typed after it is kept, in
  one batch edit; the cursor ends where it was (after the same text); text after the cursor is untouched; the case
  follows the typed word (`Caza` → `Casa`); the chip then disappears.
- ST5 The same refused offer keeps coming back: an offer the user left until its sentence moved on is a "kept the
  original"; after two for the same pair in a field, that pair is not offered again in that field. Memory only,
  bounded, cleared on another field.

### HP — heap, RAM, time (`ContextNgram`, IME glue)
- HP1 The model on the Java heap. Rule: the asset ships uncompressed (noCompress `vkng`) and is memory-mapped
  (clean file-backed pages the kernel can drop); the reader keeps only small index arrays. Budget: Java heap delta
  ≤ 5 MB after loading (JVM test; device: dumpsys meminfo before / after, plus the debug build's own GC'd heap log).
- HP2 Loaded when not needed: lazily, at the first completed word that may get an offer; never with the toggle off, with
  suggestions off, in a field without offers or when not writing Spanish; released when the IME is destroyed and
  when the toggle goes off.
- HP3 Growth over days: no cache grows with use; the per-field memory of kept pairs is bounded and cleared with the
  field; a decision allocates only short-lived garbage.
- HP4 The APK grows past budget: ≤ 18 MB with the asset stored (+11.8 MB).
- HP5 Typing slows down: ≤ 2 ms per decision on the Pixel 6 (debug build log, times only); the key press only
  schedules it (a snapshot of the text and of the user's keep words); the decision runs on its own thread.
  Found while measuring (after the code): the debug package is debuggable, which ART runs interpreted (~8 ms a
  decision, 0.84 ms for the same code non-debuggable and compiled), so the budget is measured on a non-debuggable
  build of the same commit for the debug package (e2e_r13f.py --timing-apk); and the word end that completes a word
  computes its confusion set ahead (it is the next decision's target), so a decision waits on the n-gram part only.

### AS — the asset (`ContextNgram`, `tools/ngram-build`)
- AS1 Missing, compressed, truncated or foreign asset: no offers, one log line, no crash, no retry loop. The reader
  checks the magic, the version, every count and the exact length.
- AS2 The wrong model ships: a JVM test pins the shipped file's sha256 (dae872a0…87c0) and its provenance record.
- AS3 Tokenizer drift between the builder and the phone: the builder's golden cases run against the shipped
  tokenizer.
- AS4 Reader drift: the mapped reader gives bit-identical log-probabilities to the spike's array reader (random
  queries over the vocabulary and every context of the offline replay).

### PV — privacy and fields (IME glue, `FieldPolicy`)
- PV1 Secret (password, PIN) and incognito (IME_FLAG_NO_PERSONALIZED_LEARNING) fields: no offer, no model query.
- PV2 Opted-out ("Sin memoria") prose fields: offers work and nothing is stored. Offers store nothing anywhere
  (no words.json / rejections.json change, no new file); the kept-pair memory is RAM only.
- PV3 Fields that are not prose to the corrector (email, URL, number, phone, handles, a search box that opted out):
  no offers (the space corrector's own gate, `FieldPolicy.suggestions`).
- PV4 The switch: "Sugerencias por contexto" (default on), under Sugerencias; off = no chip and the model released.
- PV5 Nothing leaves the phone: no network, no new permission; the debug log carries times, never text.

### LC — licences (`assets/context/`, settings credits, README)
- LC1 The data is used without credit: "Wikipedia contributors, Wikipedia, la enciclopedia libre (CC BY-SA 4.0)" and
  "Tatoeba contributors (CC BY 2.0 FR)" in the settings credits next to the dictionaries; a NOTICE and the build
  provenance (inputs, sha256s, builder commit) next to the asset; README's third-party section says the asset is
  GPL-3.0-only (CC BY-SA 4.0 → GPLv3) inside a GPL-3.0-or-later app. The owner accepted both licences, including the
  unconfirmed Tatoeba compatibility.

## Round 13h — space-bar gestures, merged touches and multi-offer apply (0.12.1)

The owner's feedback on 0.12.0 (Pixel 8 Pro, gesture navigation):
1. The language flick (a vertical swipe on the space bar) collides with Android's swipe-up-to-home gesture: off.
2. Context offers for earlier words arrive a little later than for the current word, before he finishes: kept.
3. Typing fast, the caret sometimes jumps to the start of the line, right around a space and the next letter.
4. Two or more pending offers in one sentence should be applied with one tap.

Measured before any code, on the raw touch logs of s1-s4 (getevent -lt, protocol B; scripts/r13h_jumps.py reads them
with r13e_s4_decode.py's slot state, one sample per SYN_REPORT of a live contact, as Android's InputReader delivers a
MOVE whenever an axis changes):
- Between two consecutive reports (4.2 ms) no keyboard contact moves more than 0.86 key widths; within 12 ms, every
  keyboard touch of s1-s4 (6,434) stays under 0.35 key widths except four: three page scrolls of s2 (below) and ONE
  typing touch: s4, the `n` of `cinco` (m07 `antes de las cinco`).
  That contact's finger rested on `n` for 75 ms; at +78.7 ms the report shows the contact area collapsing (touch major
  0xb9 → 0x4c, pressure 52 → 13: the finger lifting), then the same tracking id travels 322 px (3.2 key widths) left in
  25 ms (steps 53, 86, 81, 55, 33, 18 px: the firmware smoothing a jump) with a huge new contact (touch major 0x2f1,
  pressure 127: the next finger landing) and rests on `c` until it lifts 45 ms later. Two touches, one pointer: the
  owner's hypothesis, proven once in 1,364 s4 keyboard touches. 0.11.0 took it as a slide of the `n` onto `c`: `cico`
  went into the chat and the corrector made it `cinco` by luck.
- No space→letter merge is in s1-s4 (0.11.0 era). Under 0.12.0's rules the same jump starting on the space bar is a
  cursor drag as soon as it passes 24 dp sideways (|dx| > |dy|): it sends one DPAD step per 9 dp of the jump, more
  than ten for a jump to the left half of the keyboard, i.e. the caret runs towards the start of the line, and the
  space and the next letter are lost. A jump mostly upwards (to the row above space) is a flick: the language changes.
  This matches report 3. The three fast upward swipes of s2 (2.2 key widths in 25 ms) are page scrolls made before the
  keyboard was up (nothing they would have typed is in s2's text).
- Manual language switches that exist besides the flick: Settings home › Idioma (one tap from the settings home);
  a long press on the space bar opens Android's keyboard picker, which lists Resyst VK's Español and English subtypes
  (SubtypeSync enables both once on API 34+; picking one switches VK through onCurrentInputMethodSubtypeChanged), with
  a globe drawn on the space bar and TalkBack's "Mantén pulsado para cambiar de teclado" when the system's own switch
  button is hidden; the bilingual suggestions follow the language typed without changing the layout.

Written before the code; each item names what a JVM test, the replay or the device run must catch.

### G1 — the language flick becomes a setting, off by default (`KbSettings`, `SpaceGesture`, `KeyboardView`, Settings)
- LF1 An upward swipe from the space bar switches the language when the owner meant the home gesture. Rule: a switch
  "Cambiar idioma deslizando la barra espaciadora" (EN "Switch language by swiping the space bar"), stored phone-wide
  as `phone.spaceFlick`, default OFF; with it off no space-bar gesture ever changes the language.
- LF2 Existing users keep the flick after the update. Rule: 0.12.0 stores had no such key, so they read as off (the
  one-time migration is the default itself: nothing to rewrite, and a later "on" is stored and kept); a JVM test
  decodes a 0.12.0-shaped store. Novedades says so.
- LF3 With the flick off, what an upward swipe types. Decided on s1-s4's 1,030 space touches: drifts while tapping
  reach 26 dp (meant as spaces, SG1), deliberate swipes start at 64 dp. So with the switch off a vertical move under
  40 dp is still a tap (a space), and a vertical swipe of 40 dp or more (|dy| > 2·|dx|, any duration) types nothing:
  it was not a tap (the home gesture that started on the keyboard, or a habit flick). On: SG1 unchanged.
- LF4 No manual way to switch left. Rule: the switch's summary and the bilingual summary name the long press on the
  space bar (Android's picker) and Settings › Idioma; the device run proves the long press lists both languages and
  that picking English switches the layout. The cursor drag and the long press are unchanged by the switch.

### G2 — a merged touch is two touches (`PointerGuard`, `HeldKey`, `KeyboardView`)
- JG1 Two fingers reported as one moving pointer act as a drag: a cursor run (space), a word delete (⌫ swipe), a
  roll-over that drops the first key (letters), a lost key (Enter, layer keys: they commit where they lift, the second
  key never comes). Rule: every sample (MotionEvent history included) of a held key's pointer is checked; a move of
  ≥ 1 key width against the previous sample (any age: a real finger moving that far is reported on the way) or against
  any sample of the last 12 ms, or of ≥ 1.3 key widths within 25 ms (a shorter jump smoothed over the firmware's
  ~25 ms), is a JUMP: the touch ends at the last calm sample (its tap: the space if no gesture had
  started, the letter it was on, ⌫'s repeat stops, Enter / layer key as tapped) and a new touch starts where the pointer
  settles (or lifts). Every key type, also while a long-press popup is open (the selected variant is committed).
- JG2 The decision comes too late: the smoothed jump crosses 24 dp before it reaches a key width, so the drag (and its
  DPAD steps) or a roll-over would already have happened. Rule: a sample that moved ≥ 0.4 key widths within 12 ms is
  FAST: its effects (gesture classification, cursor steps, ⌫ swipe, letter roll-over, popup selection) wait; they are
  applied at the next calm sample or at the lift, or dropped by a JUMP. Found on the device (after the code): `input
  swipe` (and any digitizer slower than 12 ms a report) can deliver the first move 15-25 ms after the down, so a
  window of 12 ms holds no earlier sample and the ⌫ swipe fired before the jump was seen; FAST is that speed against
  any sample of the last 25 ms (the distance scaled by 12 ms / its age). Besides the merge, two touches of s1-s4 reach
  it: s4's 108 ms deliberate flick and an s3 lift-off slide that stays on its key; their effect comes at the lift.
- JG3 The new touch inherits the tail of the smoothing (60-100 px more): a space landing would become a drag. Rule: after
  a JUMP the pointer is LANDING until a 12 ms window moves less than 0.4 key widths, at least 12 ms after the jump (a
  window that has not yet filled is no proof of rest); the new touch's down point and time are taken there, without
  the jump's history (a lift while landing is a tap at the lift point).
- JG4 Real gestures broken by the guard: no typing touch of s1-s4 may be a JUMP (relative paths of every moving
  keyboard contact, committed without absolute coordinates; s2's three page scrolls, made before the keyboard was up,
  are jumps and are listed as such); every s1-s4 space touch classifies as with 0.12.0 (SG3);
  a hold-then-drag, a 30 dp quick drag, a deliberate flick and a ⌫ swipe still work (JVM and device).
- JG5 Defence in depth: whatever slips through, no single sample moves the caret more than 3 steps (the excess of a
  sample is dropped, not carried over).
- JG6 Thresholds tuned on one event. Rule: the guard is set from what real fingers did (typing: max 0.34 key widths in
  12 ms and 0.47 in 25 ms over 6,434 keyboard touches; the owner's deliberate flicks: 0.49 in 12 ms, 0.93 in 25 ms)
  with a margin to the merge (1.67 key widths in 12 ms, 3.27 in 25 ms); the report lists the per-session counts and
  the margins. A smoothed merge shorter than ~1.3 key widths (a letter and the space below it) cannot be told from a
  quick slide or a flick: it stays what 0.12.0 made of it, with JG2's deferral and JG5's cap.

### G3 — one tap applies every pending offer of the sentence (`ContextOffers`, `KeyboardView`, IME glue)
- MO1 A newer offer replaces the older one (0.12.0 showed one chip at a time): two wrong words, one offer. Rule: every
  offer still live in the open sentence is kept (ordered by position, one per target word; a new decision for the
  same word replaces it); stale ones drop out as before (ST1-ST3, CO5) one by one.
- MO2 The chip with ≥ 2 offers reads e.g. "2 cambios" (EN "2 changes"); TalkBack: "Cambiar caza por casa y esta por
  está" (EN "Replace caza with casa and esta with está"); one offer: the 0.12.0 chip unchanged.
- MO3 One tap applies some of them, or edits blind: every offer is re-located in the field at the tap; those still
  live are applied, in one batch edit (delete from the earliest target, commit the sentence with every replacement and
  the text typed after the last one); the cursor ends after the same text; text after the cursor is untouched.
- MO4 No way back: after a tap the bar offers "↶" with the original words while the text before the cursor still ends
  with what the tap wrote; one tap restores every word in one batch edit (the cursor after the same text); any edit or
  cursor move ends the offer. A restored pair counts as a kept original (ST5). Found on the device (after the code): a
  same-length swap (`caza` → `casa`, `esta` → `está`) leaves the selection where it was, so the editor sends no
  selection update and the bar was never refreshed (no ↶); the IME refreshes the bar itself after the tap and the ↶.
- MO5 Privacy as 0.12.0: never in secret or incognito fields, nothing stored in opted-out fields; the ↶ record lives in
  RAM, cleared with the field, the keyboard hidden, or the switch.

### Version
- VR5 0.12.1 / versionCode 15 are the build defaults. "Novedades" is the strip chip that opens the website for the
  new versionName (r13c WN1-WN8); the website text (docs/novedades-0.12.1.md, for the site) says the flick is off and
  where to turn it on.
