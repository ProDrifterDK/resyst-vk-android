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
