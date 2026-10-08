# ✦ Resyst VK Android — Visión y catálogo de propuestas

Auditoría de producto, solo lectura, sobre HEAD `715eb9f` (r8 completo; r9 "aviso de actualización" a medio camino en el árbol de trabajo: `core/UpdateNotice.kt` sin trackear + diff en `docs/failure-modes.md`). Nada de esto está implementado; cada ítem es una propuesta para aprobar o rechazar.

**Evidencia usada.** Lectura completa de `core/`, `ime/`, `settings/`, manifest, tests (236 `@Test`), reportes r1–r8b y los prompts de cada ronda; análisis visual de las capturas existentes en `build/e2e-r8/` (teclado Pixel 6 gestual, panel emoji, modo día), `build/e2e-r7/` (ajustes) y `build/e2e-clipboard/` (chip + panel); búsquedas por rango en los léxicos `es.txt`/`en.txt` (el número de línea es el rango de frecuencia). **No hice**: build, ejecución en el Pixel 6 (está conectado, no lo toqué), medición de latencia en teléfono. Donde digo "medido" es JVM o captura; donde digo "probable" es inferencia.

Convenciones: Impacto alto/medio/bajo · Esfuerzo S (un día o dos) / M (una o dos semanas) / L (un mes o más) · referencias `archivo:línea`.

---

## 1. Resumen ejecutivo — las 5 apuestas, en orden

**1. Ajustes: construir lo que `SettingsIA` ya define, y simplificar perfiles → temas + modos.**
La queja #1 de Alan en r7 ("todo el menú está desparramado") sigue abierta: `SettingsIA.kt` describe home + 6 páginas y lo verifican 9 tests, pero `SettingsActivity.kt:173-247` sigue renderizando la lista plana r7 (ningún `import` de `SettingsIA` en `settings/`). Encima, 24 ajustes × 4 perfiles multiplican la superficie: "Noche" y "Día" son temas, no perfiles, y el chip día/noche ya los cubre. Propuesta: pintar las páginas desde los datos, y colapsar perfiles a **Temas** (apariencia) + **Modos** opcionales (Código, Juego) con el resto de ajustes a nivel teléfono. Impacto alto · M (páginas) + M (migración de codec, tolerante como siempre).

**2. La barra es para sugerencias; todo lo demás va a un panel rápido bajo ⚙.**
Hoy la franja de 42 dp (`KeyboardView.kt:154`) reparte seis tipos de ítem (`StripKind`, `:156`): chip de perfil "✦ ☾" (críptico en la captura), Pegar, 3 sugerencias, portapapeles, sol/luna, ⚙. Con Pegar activo quedan 2 sugerencias; cada ronda quiere su botón. Propuesta: franja = sugerencias + ⚙; una pulsación larga/toque en ⚙ abre un **panel rápido** (día/noche, tema/modo, portapapeles, una mano, altura, panel de edición, ajustes). Más una **iconografía única**: `KeyIcons.kt:12` ya tiene 13 iconos vectoriales y solo se usan 2 (`KeyboardView.kt:997,1020`); shift/⌫/⚙ siguen siendo glifos de fuente del sistema (`:843,1003,1017`) — el ⚙ se confunde con el sol del día/noche en la captura. Impacto alto · M.

**3. La privacidad como feature visible, no como párrafo.**
Lo que diferencia a Resyst no se ve en el teclado. Propuesta: página **"Lo que sé de ti"** (palabras aprendidas, correos, emojis recientes: ver y borrar una a una), **"Libro de conexiones"** (contador y fechas de cada GET al servidor — hay un único camino de red, `Updater.kt`, se puede contar), indicador **"sin memoria"** cuando el campo es incógnito/secreto/opt-out, **sin burbuja de tecla en contraseñas** (hoy `drawPreview` solo mira `settings.popups`, `KeyboardView.kt:780`), y **filtro de palabras ofensivas por defecto** en completados (el léxico de subtítulos trae `mierda` en el rango 216, `puta` 514, `culo` 860; EN `shit` 277, `fuck` 291: escribir "mier" o "fuc" propone eso primero, `Suggest.kt:34-56` no filtra). Impacto alto (marca y confianza) · M.

**4. Escribir en dos idiomas sin pasar por el selector del sistema.**
Cambiar ES↔EN exige pulsación larga en espacio → selector modal de Android. El público (Chile, tecnología) mezcla idiomas a diario. Propuesta por etapas: (a) **flick vertical en espacio** alterna ES/EN dentro de Resyst (S); (b) **sugerencias bilingües**: ambos léxicos cargados, corrección solo en el idioma detectado por las últimas palabras (L); (c) **léxico regional**: el ES actual es España-céntrico (`coche` 407 vs `carro` 2405, `ordenador` 3209 vs `computador` 15967, `vosotros` 760; `cachai`, `bacán`, `fome`, `altiro`, `pololo`, `weón` no existen) → lista de re-rango es-419/es-CL (~2k palabras) seleccionable (M). Impacto alto para el público objetivo.

**5. Edición de texto de verdad.**
Hoy: cursor por arrastre en espacio (`ResystImeService.kt:392`), ⌫ de a un carácter (`:667-675`), deshacer corrección solo con ⌫ y sin pista visual (`KeyboardEngine.kt:139`). Propuesta: **panel de edición** (flechas, seleccionar, todo, copiar, cortar, pegar, borrar palabra), **⌫ que acelera a palabras** tras N repeticiones y **deslizar en ⌫** para borrar palabra, chip **"↶ palabra"** tras una corrección, y **signos de apertura automáticos**: al cerrar con `?`/`!` una frase sin `¿`/`¡`, ofrecer insertarlo al inicio (delicia Spanish-first). Impacto alto, uso diario · M.

Menciones: atajos de texto (las "macros" prometidas en r1 y nunca hechas), exportar/importar lo aprendido (hoy excluido del backup sin alternativa → cambiar de teléfono = perderlo), acento "Del sistema" (Material You) y modo oscuro "Automático", y un *flavor* "libre" sin `INTERNET` para IzzyOnDroid/F-Droid.

---

## 2. Catálogo completo

### 2.1 UX

**UX-1 · Páginas de ajustes desde `SettingsIA`.**
Problema: IA definida y testeada, UI plana; las capturas r7 muestran chips huérfanos sin su label al hacer scroll, dependencias no expresadas (Intensidad/Pack/Purgar no se indentan ni atenúan), "Borrar lo aprendido" clicable con 0 palabras (`SettingsActivity.kt:351-370`).
Propuesta: home (≤ 8 esenciales + selector + vista previa) y 6 páginas, renderizadas desde `SettingsIA.PAGES`; dependientes indentados y atenuados cuando el padre está off; destructivos al final de su página con confirmación; "Borrar" deshabilitado en vacío. El test I1–I9 pasa a verificar también la UI (recorrer la jerarquía de vistas por `tag = ctl.name`).
Impacto alto · Esfuerzo M · Riesgos: `scripts/e2e_r6.py find_setting` necesita page-awareness (ya anotado en r7/r8).

**UX-2 · Perfiles → Temas + Modos.**
Problema: 4 perfiles × 24 campos (`Profiles.kt:11-44`); "Juego"/"Escritura" opacos (nada explica qué cambian, captura r7); Noche/Día duplican el toggle día/noche; en la captura la tarjeta "Día" (clara) parece la activa en vez de "Noche".
Propuesta: `theme/accent/shape/cap/font/density/height/subLegends/dayNightChip/altTheme` quedan en **Tema**; `lang/topRow/hideTopRow/emojiKey/suggest/spaceCorrects/personal/autoCap/doubleSpace/sound*/haptic*/popups/longPressMs` pasan a **teléfono**; un **Modo** es un override explícito y nombrado (Código: sin corrección, fila de símbolos, Tab/Esc; Juego: compacto, sin sugerencias). El chip ✦ de la franja desaparece (ver UX-3); el modo se activa desde el panel rápido o por app (F-9).
Impacto alto · M/L · Riesgos: migración (`ProfileCodec` v1→v2: tomar los valores del perfil activo como globales, conservar temas); hay que decidirlo antes de r10 porque cada ronda añade campos per-profile.

**UX-3 · Franja = sugerencias + ⚙; panel rápido.**
Problema: 6 `StripKind`; con Pegar, 2 sugerencias (`KeyboardView.kt:434-440`); chip "✦ ☾" sin label; sol/luna y ⚙ son dos "círculos con rayos" grises (captura Pixel 6).
Propuesta: quitar PROFILE, CLIP y DAYNIGHT de la franja; ⚙ toque = panel rápido (grid 2×4 sobre las teclas, mismo patrón `ClipboardPanel`/`EmojiPanel`): Día/Noche, Tema, Modo, Portapapeles, Una mano, Altura, Edición, Ajustes. Pegar sigue siendo contextual (gana a la sugerencia 3, no a las 3). Chip de actualización (r9) cabe como fila superior del panel + punto en ⚙, nunca ocupando la franja mientras se escribe (coincide con N4 del diff r9).
Impacto alto · M · Riesgos: una pulsación más para día/noche (Alan lo pidió en la franja en r8 — mantener como opción `dayNightChip`).

**UX-4 · Cambio de idioma sin selector del sistema.**
Propuesta: flick vertical (≥ 20 dp, < 200 ms) sobre espacio alterna el `Lang` del perfil/teléfono y lo sincroniza con el subtype (`Subtypes.reconcile` ya existe); pulsación larga sigue abriendo el selector (G5/G6 intactos). Animación de la etiqueta "✦ español" → "✦ english".
Impacto alto · S · Riesgos: conflicto con el arrastre horizontal del cursor (umbral 14 dp horizontal, `KeyboardView.kt:564`) → exigir |dy| > 2·|dx|.

**UX-5 · Deshacer corrección visible.**
Problema: A10 funciona, pero nadie sabe que ⌫ restaura; TalkBack ni se entera de que "teh" pasó a "The" (no hay `announceForAccessibility` en el código).
Propuesta: tras una corrección por espacio, la primera sugerencia es "↶ teh" durante una edición; toque = revertir y marcar `rejected`. Anuncio a11y "Corregido a The" y pulso háptico distinto (`HapticEvent.CORRECTION`, hoy solo KEY/LONG_PRESS/CURSOR_TICK en `Haptics.kt:3`).
Impacto medio-alto · S.

**UX-6 · ⌫ inteligente.**
Propuesta: repetición 50 ms (`REPEAT_MS`) acelera a borrar palabras completas tras ~8 repeticiones; deslizar a la izquierda sobre ⌫ borra la palabra anterior (`deleteSurroundingText` hasta el límite via `Tokens`). Nunca en campos secretos (borrar de a palabras una contraseña oculta es desorientador).
Impacto medio · S.

**UX-7 · Panel de edición.**
Propuesta: desde el panel rápido o pulsación larga en `?123`: ← → ↑ ↓, Inicio/Fin, Seleccionar (toggle: las flechas extienden con `KEYCODE_SHIFT_LEFT` meta), Todo, Copiar, Cortar, Pegar, Borrar palabra. `performContextMenuAction(android.R.id.selectAll/copy/cut/paste)` + `sendDownUpKeyEvents`.
Impacto alto (correo, código) · M · Riesgos: apps que no implementan context-menu actions (degradar con `getSelectedText` + `commitText`).

**UX-8 · Signos de apertura automáticos (¿ ¡).**
Propuesta: al escribir `?`/`!` cerrando una frase cuyo inicio (ventana de 64 chars, `WINDOW`) no tiene `¿`/`¡`, ofrecer como sugerencia "¿…?" que inserta el signo al inicio de la frase (dos `Out`: mover/insertar necesita `setSelection` o `deleteSurroundingText` + recommit del tramo). Opción en Escritura, default ON en ES, nunca en EN ni en campos no-prosa (`FieldPolicy.prose`).
Impacto medio, muy de marca · M.

**UX-9 · Atajos de texto (las macros de r1).**
Propuesta: pares abreviatura → texto (`@@` → correo, `dirr` → dirección, `fir` → firma), expandidos al espacio como una corrección (misma vía `Corrector`, deshacible con ⌫). Guardados en `files/personal/` (excluidos de backup, borrados con "Borrar lo aprendido"). Unificar con los fijados del portapapeles: un fijado puede recibir una abreviatura.
Impacto alto para quien escribe correos · M.

**UX-10 · Portapapeles: búsqueda, cierre claro, borrar seguro.**
Problema (captura): "ABC" para cerrar es jerga; "Borrar todo" (rojo) pegado a "ABC"; sin búsqueda; nada insinúa la pulsación larga.
Propuesta: cerrar con "‹ Teclado"; "Borrar todo" al final de la lista; filtro por texto al escribir con el panel abierto; icono ⋮ o chip "fijado" visible en cada fila; tipo detectado (URL muestra dominio, número, correo) con acción contextual.
Impacto medio · S.

**UX-11 · Emoji: búsqueda en español, tonos de piel, objetivos de 48 dp.**
Problema: tabs de 44 dp (`EmojiPanel.kt:36`) y celdas ~36-40 dp; sin búsqueda; sin tonos; catálogo Unicode ≤ 11 (`Emoji.kt`); "Recientes" vacío sin estado.
Propuesta: fila de búsqueda por nombre ES/EN (anotaciones CLDR recortadas al catálogo, ~60–100 KB), pulsación larga = tonos, tabs 48 dp con label al seleccionar, estado vacío "Aquí aparecen los que uses", y **emoji sugerido por palabra** en la barra ("feliz" → 😊, mapa local de ~300 palabras, costo de privacidad cero).
Impacto medio-alto · M.

**UX-12 · Campos conscientes de sí mismos.**
Propuestas S cada una: contraseñas → fila de números arriba y sin burbujas; URL/EMAIL → pulsación larga en `.` ofrece `.com .cl .es .net .org` (hoy `Variants.kt` no tiene ninguno) y tecla `www.`; NUMBER con `FLAG_DECIMAL`/`FLAG_SIGNED` → numpad con `.`/`-` solo cuando el campo los acepta (hoy siempre `.`, `*`, `#`, `KeyboardLayouts.kt:75-85`); EN → fila superior por defecto `NUMBERS` (hoy la fila de acentos española aparece sobre "english", `:38`); con `TopRow.NUMBERS`, ocultar los dígitos-hint del QWERTY (hoy se duplican, `:42`).
Impacto medio · S.

**UX-13 · Modo una mano y margen inferior.**
Propuesta: teclado al 85 % anclado a izquierda/derecha con flecha en el canal (panel rápido); ajuste "Margen inferior" 0–40 dp (hoy solo `heightScale` 0.8–1.3). Ambos a nivel teléfono.
Impacto medio-alto en pantallas altas · M.

### 2.2 UI

**UI-1 · Iconografía única (vectorial).**
Problema: README promete "sin ruleta de glifos", pero shift `⇧/⬆`, ⌫, ⚙ y las etiquetas de Enter (`⌕ ➤ → ⇥ ⇤ ✓ ⏎`, `ResystImeService.kt:740`) son glifos de fuente; `KeyIcons` tiene ENTER/BACKSPACE/SHIFT/SHIFT_ON/SHIFT_LOCK/SEARCH/SEND/DONE/NEXT/PREVIOUS/GO sin usar. Grosor de trazo distinto en cada ícono (captura modo día).
Propuesta: usar `KeyIcons` para todo; un grosor (0.09·size) y un tamaño por familia; el ⚙ vectorial con dientes rectos para no parecer sol; estado shift (off/once/lock) por relleno + barra, no por glifo.
Impacto medio-alto (coherencia) · S.

**UI-2 · Temas claros con bordes.**
Problema (captura Sepia/Papiro): teclas crema sobre fondo crema, modificadores casi iguales, sub-leyendas ilegibles; `Palette.ensureContrast` garantiza acento vs tecla (4.5:1) pero nadie garantiza tecla vs fondo ni texto secundario.
Propuesta: en temas claros, `KeyCap.RAISED` pinta `edge` como contorno de 1 dp; test nuevo en `PaletteProfilesTest`: contraste `key/bg ≥ 1.25:1`, `keyMod/key ≥ 1.15:1`, `muted/key ≥ 3:1` para los 8 temas.
Impacto medio · S.

**UI-3 · Microanimaciones (hay cero).**
Problema: `grep Animator` → nada. Tecla presionada, chips que aparecen, paneles, cambio de tema: todo instantáneo.
Propuesta: un `Choreographer`-driven `Anim` en `KeyboardView` (80–120 ms): press scale/color de tecla, fade+slide de chips, slide de paneles, crossfade de paleta al cambiar día/noche, y respetar `ANIMATOR_DURATION_SCALE = 0` (quitar animaciones del sistema).
Impacto medio (percepción de calidad) · M.

**UI-4 · Jerarquía de la barra de sugerencias.**
Problema: 1ª sugerencia cambia color y peso a la vez; 3 huecos fijos aunque haya 2; divisores casi invisibles.
Propuesta: la corrección confiable con subrayado/acento y peso, las demás peso normal mismo color; huecos adaptativos al número de sugerencias; 5 en apaisado (`Bar.LIMIT` fijo en 3).
Impacto medio · S.

**UI-5 · Acento "Del sistema" y oscuro "Automático".**
Propuesta: opción de acento Material You (`android.R.color.system_accent1_*`, API 31+, sin red) pasando por `ensureContrast`; tema con modo "Automático" que sigue `Configuration.uiMode` (hoy no se lee, `grep uiMode` → nada) sobre los twins existentes (`Themes.TWIN`).
Impacto medio-alto (delicia barata) · S.

**UI-6 · Pantalla de ajustes: detalles de la captura r7.**
Paso 1 completado sigue pareciendo botón (atenuar/colapsar); párrafo de privacidad → 3 viñetas; tarjetas de perfil con indicador de activo que gane al tema de la tarjeta; "Idioma" chips vs enlace "Idiomas en el selector…" ambiguos (renombrar "Idiomas que ofrece Android" con chevron); slider Altura con min/max visibles; "Doble espacio = punto" sin subtítulo.
Impacto medio · S.

**UI-7 · Accesibilidad más allá del mínimo.**
Problema: `describe()` siempre en español aunque el layout sea EN (`KeyboardView.kt:1107`); sin anuncios de corrección/shift; tabs emoji < 48 dp; teclado sin borde con el fondo de la app en temas oscuros (captura: tres negros casi iguales).
Propuesta: descripciones por idioma; `announceForAccessibility` en corrección, bloqueo de mayúsculas y cambio de capa; objetivo mínimo 48 dp en paneles; línea `edge` de 1 dp en el borde superior del teclado; opción "Texto grande" (+15 % en leyendas).
Impacto medio · S/M.

### 2.3 Features

**F-1 · Sugerencias bilingües (ES+EN simultáneos).** Dos `Suggest` cargados; detección por las últimas 3 palabras (ambas tablas `rankOf`); corrección solo en el idioma detectado; aprendizaje sigue por `Lang`. Impacto alto · L · Riesgo: latencia ×2 en espacio → requiere el índice por longitud (D-3).

**F-2 · Léxico regional es-419 / es-CL.** Lista de re-rango (~2k) aplicada al cargar (`Lexicon.kt`), seleccionable: "Español (Chile)", "Español (Latinoamérica)", "Español (España)". Fuente: corpus propio o listas abiertas; nunca de los usuarios. Impacto alto para el público · M.

**F-3 · Filtro de palabras ofensivas (default ON).** Lista local ~200 ES / ~150 EN; afecta solo a completados y predicciones, nunca a lo que el usuario escribe ni a su modelo personal (si la escribe 2 veces, `HABIT`, vuelve a ofrecerse). Toggle en Escritura. Impacto alto (riesgo reputacional hoy) · S.

**F-4 · "Lo que sé de ti".** Página a nivel teléfono: palabras aprendidas por idioma con conteo (`PersonalModel.vocab`), correos (`ValueMemory`), emojis recientes, historial del portapapeles; borrar individual; búsqueda. Impacto alto (confianza) · M.

**F-5 · Libro de conexiones.** Cada `Updater.check()/download()` apunta fecha, motivo (tú / inicio) y resultado en prefs; la página Acerca de muestra "Conexiones a internet desde la instalación: N" con la lista. Es la única manera de que el usuario *vea* la promesa. Impacto alto (marca) · S.

**F-6 · Indicador "sin memoria".** Punto/ícono en la franja cuando `personalWords() == null` por campo incógnito, opt-out, secreto o toggle off; toque explica por qué. Impacto medio · S.

**F-7 · Exportar / importar lo aprendido.** Archivo cifrado (AES-GCM con frase del usuario) vía `ACTION_CREATE_DOCUMENT`/`OPEN_DOCUMENT` (sin permisos); incluye modelo, correos, atajos, fijados, temas. Resuelve "cambié de teléfono y lo perdí" sin tocar la red. Impacto alto · M.

**F-8 · Niveles de corrección.** Suave / Normal / Agresiva mapeados a `frequentRank/minGap/allowance` (`Suggest.kt:218-239`); `LexiconEvalTest` ya mide fixed/wrong por nivel. Impacto medio · S/M.

**F-9 · Modo por app.** `EditorInfo.packageName` ya llega (`ResystImeService.kt:240`); mapa local app → Modo (Termux → Código, juego → Juego). Solo nombres de paquete que el usuario asigna, on-device. Impacto medio-alto · M.

**F-10 · Modo Código / terminal.** Fila extra Tab · Esc · Ctrl · ← ↓ ↑ → · `|` `~` `/` (`sendDownUpKeyEvents` TAB/ESCAPE, Ctrl como meta en `KeyEvent`), sin corrección, fuente TECH. Nicho pero on-brand (Resyst Labs, un Razer muerto). Impacto medio · M.

**F-11 · Flavor "libre" + IzzyOnDroid.** `productFlavors { directo; libre }`: *libre* sin `INTERNET` ni `REQUEST_INSTALL_PACKAGES` ni `Updater` (la ausencia del permiso es verificable por cualquiera y F-Droid la muestra como insignia). *directo* mantiene kv.resyst.cl. Impacto alto (confianza) · M.

**F-12 · Clipboard: tipo detectado y acciones.** URL → "Abrir" no (saldría del teclado), pero sí "pegar dominio"; número → pegar sin espacios; correo → añadir a `ValueMemory`. Impacto bajo-medio · S.

### 2.4 Filosofía

**P-1 · El updater: de "pull puro" a "una consulta honesta" — pero no por proceso.**
r9 (en curso) añade un GET al iniciar el proceso del IME. Android mata y relanza el proceso del teclado muchas veces al día → varios GETs diarios con IP + hora = un latido identificable, aunque no lleve datos. Propuesta: gate adicional **≥ 24 h desde `lastAutoCheckAt`** (ya previsto en prefs por el spec r9), jitter de minutos, `User-Agent` genérico (ya), copia S2 exacta ("al iniciar, como mucho una vez al día"). Con F-11 el usuario que no quiera *ninguna* conexión tiene una build que no puede hacerla. Impacto alto · S.

**P-2 · Ajustes por perfil como default es la causa raíz del desorden.** Ver UX-2. Mientras cada ronda añada campos a `KbSettings`, el "menú desordenado" vuelve. Decisión de producto, no de UI.

**P-3 · Spanish-first es correcto; el hueco es bilingüe y regional.** No hace falta layout-first (QWERTZ/AZERTY): en móvil el layout es QWERTY y la diferencia la hace el léxico y la fila de acentos. Lo que sí falta: cambio rápido ES↔EN (UX-4), bilingüe (F-1), regional (F-2), y **strings en recursos**: hoy toda la UI y las descripciones a11y son literales Kotlin en español; un usuario con layout EN recibe TalkBack en español. Extraer a `strings.xml` (es + en) es M y prerequisito de cualquier listado público.

**P-4 · Léxico de subtítulos como única fuente.** Trae nombres propios (`mary` 1213, `mark` 1562, `marty` 3235, `shirley` 5502 EN), groserías arriba, sesgo peninsular, y nada de español digital (`porfa`, `jaja`, `tb`, `wn` ausentes). Los umbrales r8 y `HABIT=2` protegen bastante, pero el usuario nuevo recibe un teclado que "no habla como él". Propuesta: segunda fuente abierta (Wikipedia/CC-100 filtrado) mezclada por rango, filtro de nombres propios por capitalización en corpus, F-2 y F-3. Impacto alto · M.

**P-5 · Deuda técnica que toca la UX.**
- D-1 `KeyboardView.kt` (1230 líneas) mezcla franja, teclas, popups, dos paneles, touch y a11y → cada panel nuevo (rápido, edición) lo engorda. Extraer `Strip`, `KeyGrid`, `PanelHost` con la misma interfaz `hits/draw/hitAt`. M.
- D-2 `SettingsActivity.render()` reconstruye todo el árbol en cada cambio (`removeAllViews`) → sin animaciones, scroll parcheado con `post`. Migrar a páginas con vistas estables (prerequisito de UX-1). M.
- D-3 `Suggest` en hilo principal, 5–8 ms JVM por corrección larga (r8b) → 10–30 ms probable en gama baja = jank en la pulsación de espacio. Índice por longitud (`abs(len-ft.len) ≤ maxD` ya es el filtro; precomputar cubetas). S.
- D-4 Iconos a medias (UI-1). S.
- D-5 r9 a medio commitear en el árbol (`UpdateNotice.kt`, diff de failure-modes): cerrar o descartar antes de cualquier otra lane. Coordinación.
- D-6 `Lexicon` carga async: hasta que termina (`get()` devuelve null) no hay corrección; medir en frío y, si > 300 ms, precargar en `onCreate` del servicio (hoy `warm` ocurre en `applySettings`). S.

### 2.5 Wild (si el presupuesto no importara), por impacto

**W-1 · Escritura por deslizamiento, on-device.** El README dice "probablemente nunca — este teclado tiene una filosofía"; la filosofía es de privacidad y el swipe de FlorisBoard es 100 % offline. El conflicto real es estético y de esfuerzo (decodificador de trayectoria + léxico, L+). No lo descartaría para siempre; sí lo pondría detrás de los 5 apuestas. Impacto alto para un segmento · L.

**W-2 · Dictado on-device.** `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+) es offline y gratis; Whisper-tiny cuantizado (~40 MB) sería 100 % nuestro. Problema: `RECORD_AUDIO` en un teclado asusta más que `INTERNET`. Solo como flavor aparte o módulo descargable bajo demanda (y eso ya es red). Impacto medio-alto · M/L.

**W-3 · Apaisado dividido y flotante.** Split con numpad al centro en landscape (hoy solo filas de 40 dp); flotante arrastrable. Impacto medio · M (split) / L (flotante).

**W-4 · Pequeño modelo de lenguaje on-device para predicción.** Un n-grama de 3 órdenes comprimido (~2–4 MB) o un LM minúsculo cuantizado reemplazando `Seeds` (25 entradas a mano). Mejora real de predicción sin red; cuidado con APK y batería. Impacto medio-alto · L.

**W-5 · Teclado desktop ↔ Android: compartir temas por archivo.** El proyecto hermano Linux comparte paleta; exportar un tema `.resysttheme` (JSON) e importarlo en ambos. Sin red (archivo). Impacto bajo-medio, muy de marca · S/M.

---

## 3. Qué NO hacer

- **Nada que necesite servidor para funcionar**: sincronización, cuentas, GIF/stickers (Tenor/Giphy), traducción, gramática o sugerencias por LLM remoto, *crash reporting* "anónimo". Rompe la única promesa que vende el producto; con una sola excepción el relato se cae.
- **Notificaciones en la barra de estado** para actualizaciones (necesita `POST_NOTIFICATIONS`); el spec r9 ya lo prohíbe — mantenerlo.
- **Consulta de actualización por proceso** sin tope de 24 h (P-1).
- **Añadir `RECORD_AUDIO`** al flavor principal (W-2).
- **Más chrome permanente en la franja.** Cada feature quiere su botón; la franja es para escribir. Todo lo no textual va al panel rápido.
- **Más temas antes de arreglar contraste claro e iconos** (UI-1, UI-2). Ocho temas con bordes lavados valen menos que cinco impecables.
- **Una sexta fila** (números + acentos a la vez). La altura ya es el recurso más caro; los dígitos están en pulsación larga/hints.
- **Reescribir el IME en Compose/Flutter.** La vista Canvas es la arquitectura correcta para un IME (ventana, insets, a11y virtual). Una reescritura no se ve y rompe lo que funciona.
- **Pasar a `setComposingText`** sin una necesidad concreta: el commit-por-carácter actual evita los spans de composición que fallan en apps raras; A10 ya da el deshacer.
- **Seguir añadiendo campos per-profile** hasta decidir UX-2; y **nunca** una migración que borre lo aprendido o resetee temas (el codec tolerante es un activo).
- **Swipe typing o voz antes que las apuestas 1–5.** Son grandes, llamativas y menos frecuentes que editar texto, cambiar de idioma o entender el menú.
- **"Mejorar" el léxico con datos de uso.** Ni agregados, ni opt-in. La única fuente de aprendizaje colectivo es un corpus abierto curado en el repo.

---

## 4. Fin de semana vs trimestre

**Un fin de semana (S, independientes entre sí):**
UI-1 iconos vectoriales · UI-2 bordes en temas claros + test de contraste · UI-5 acento Material You + oscuro Automático · UX-4 flick de idioma en espacio · UX-5 chip "↶" + háptico de corrección · UX-6 ⌫ por palabras · UX-12 contraseñas sin burbuja + fila de números, `.com .cl` en URL/EMAIL, hints duplicados, fila EN · UX-10 cierre/borrar/búsqueda del portapapeles · F-3 filtro ofensivo · F-5 libro de conexiones · F-6 indicador sin memoria · P-1 tope de 24 h en la auto-consulta · UI-6 detalles de ajustes (paso 1, viñetas, tarjeta activa, "Borrar" deshabilitado) · D-3 índice por longitud · D-6 medir carga del léxico en frío.

**Dos semanas (M, una lane cada uno):**
UX-1 páginas de ajustes (sobre D-2) · UX-3 panel rápido + franja limpia · UX-7 panel de edición · UX-8 ¿¡ automáticos · UX-9 atajos de texto · UX-11 emoji (búsqueda, tonos, 48 dp, emoji por palabra) · UX-13 una mano + margen · F-4 "Lo que sé de ti" · F-7 exportar/importar · F-8 niveles de corrección · F-9 modo por app · F-10 modo Código · F-11 flavor libre + IzzyOnDroid · P-3 strings a recursos · UI-3 animaciones · UI-7 a11y · D-1 partir `KeyboardView`.

**Un trimestre (L, con decisión previa de Alan):**
UX-2/P-2 perfiles → temas + modos (migración) · F-1 bilingüe (necesita D-3) · F-2 + P-4 léxico regional y segunda fuente · W-1 deslizamiento · W-4 LM on-device.

**Orden sugerido si solo hay una lane:** P-1 y D-5 (cerrar r9 honestamente) → fin de semana completo (visible, barato, sin decisiones) → UX-3 + UI-1 (la cara del teclado) → UX-1 sobre la decisión UX-2 → UX-7/UX-5/UX-6 (editar) → F-3/F-4/F-5/F-6 (privacidad visible) → UX-4 → F-11.

---

✦ Resyst — lo que escribes no sale del teléfono. Que se note.
