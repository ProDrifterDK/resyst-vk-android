# r13b held-out prose (P1)

Source: Horacio Quiroga, *Cuentos de amor de locura y de muerte* (1917). The author died in 1937: the text is in the
public domain worldwide (life + 70 years) and in the United States (published before 1930). Transcription: Project
Gutenberg eBook #13507 (https://www.gutenberg.org/cache/epub/13507/pg13507.txt), file sha256 `5fa2d668df2ee2ea4f897a73580e977b841e96ea6922577a8b015e70c51e4968`. The Project Gutenberg header, footer and licence
text are removed; no Project Gutenberg trademark is used. Nothing else from that file is redistributed.

Deterministic normalisation (`scripts/r13b_oracle.py data`): CRLF → LF; the body from the first story heading to the
end marker; headings and index lines dropped; wrapped lines joined into one line per paragraph; `--` → `—`; `_`
(italics) removed; pre-1952 accents on monosyllables updated (fué/dió/vió/fuí → fue/dio/vio/fui).

- `prose-a.txt`: the first 12013 words (paragraphs in order) — clean prose A. Every
  change the corrector makes here is counted as a break. The source has a few typos of its own (e.g. "se rían"); a
  break on one is reported, not excused.
- `chat-source.txt`: the next 4507 words — raw material of the SYNTHETIC chat set
  (2–3 sentences per message, the last without a period, as in owner-notes.md). Synthetic, not the owner's chats.
Neither file was looked at while choosing the decision rules; both are held out from the s1–s3 tuning data.
