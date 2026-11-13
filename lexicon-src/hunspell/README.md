# Pinned Hunspell inputs (r13a)

The only inputs of `scripts/gen_valid_words.py`. The generator never reads `/usr/share/hunspell`:
it checks `SHA256SUMS` here first and refuses to run on any mismatch, so an OS upgrade cannot
change the APK.

| File | Debian package | Upstream | License used by VK |
|---|---|---|---|
| `es_CL.dic`, `es_CL.aff` | `hunspell-es` 1:24.2.1-1 (source `libreoffice-dictionaries` 1:24.2.1-1) | RLA-ES, Santiago Bosio | MPL-1.1+ (of GPL-3+ / LGPL-3+ / MPL-1.1+), see `NOTICE-es.txt`, `LICENSE-es-MPL-1.1.txt` |
| `en_US.dic`, `en_US.aff` | `hunspell-en-us` 1:2020.12.07-2 | SCOWL, Kevin Atkinson et al. | SCOWL permissive notice, kept in full in `NOTICE-en_US-SCOWL.txt` |

On Debian `es_CL.{dic,aff}` are symlinks to `es_ES.{dic,aff}` (one Spanish dictionary for every
`es_*` locale); these are byte copies of the link targets. `SHA256SUMS` equals the hashes recorded
by main in `r13-vk-launch/evidence/hunspell-sha256.txt`.

Refresh procedure (deliberate, never automatic): copy the new files here, update `SHA256SUMS`
and this table, re-run the generator, review the entry-count change it prints, and update the
asset hashes in `lexicon-src/README.md`.
