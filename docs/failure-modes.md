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
