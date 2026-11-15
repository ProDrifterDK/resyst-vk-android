#!/usr/bin/env python3
"""r13e-s4: the s4 replay fixtures from the decoded key stream.

Inputs (all in the repo): app/src/test/resources/r13e/{s4-intended.txt, s4-final.txt} and app/build/r13e-s4/stream.json
(Round13eS4Test.stream: the key stream of r13e/s4-keys.json driven through 0.11.0's engine in the live field; every
word ending with the letters the engine saw, the ending key, the keyboard language and the touch record).

For every word of the sent text it takes the LAST ending of the word at that place (a word deleted and retyped counts
as retyped), aligns those words with the intended message (one row per intended word; a missed space gives one typed
token for two intended words, gold "w1 w2"; an extra space gives a typed "w1 w2" for one intended word), and the
intended words with what the chat received (final.txt, in typing order).

Outputs:
  app/src/test/resources/r13e/s4-live.json   r13d schema (a flat row list, as r13c/s1-live.json) + msg, lang, near
  app/src/test/resources/r13b/s4-live.json   r13b P1 schema ({"messages": [{id, app, intended, rows}]}) for Round13eOracleTest
and prints the rows a person must look at (alignment blocks that are not 1:1, typed vs final disagreements).

Row fields: left (the message so far: the field is empty after each send), typed, end (" " "," "." "?" or "SEND" =
the Enter that sent the message, which VK 0.11.0 does not treat as a word ending in that field), gold, vk_final (the
word as received; "delfinak" for both del and final when the chat got them merged), cat (typo / accent_or_case / ok),
live_class (typo_fixed / typo_not_fixed_or_wrong_fix / FALSE_CORRECTION / accent_or_case_diff / ok), lang (es / en:
the keyboard language, flipped by space flicks), near (per typed letter, the touch's border keys).
usage: r13e_s4_fixture.py
"""
import difflib
import json
import os
import re
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "test", "resources")
WORD = re.compile(r"[\wáéíóúüñÁÉÍÓÚÜÑ']+")


def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def words(s):
    return [(m.group(0), m.start()) for m in WORD.finditer(s)]


def blocks(a, b):
    """difflib opcodes over folded words, with unequal blocks split into 1:1, 2:1 and 1:2 pieces where possible."""
    sm = difflib.SequenceMatcher(a=[fold(w) for w, _ in a], b=[fold(w) for w, _ in b], autojunk=False)
    out = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal" or (i2 - i1) == (j2 - j1):
            out += [([i], [j]) for i, j in zip(range(i1, i2), range(j1, j2))]
            continue
        ai, bj = list(range(i1, i2)), list(range(j1, j2))
        # a missed space: two intended words typed as one token (concatenation within 2 edits), or the reverse
        while ai and bj:
            if len(ai) >= 2 and lev(fold(a[ai[0]][0] + a[ai[1]][0]), fold(b[bj[0]][0])) <= 2 and len(ai) - 2 >= len(bj) - 1:
                out.append(([ai[0], ai[1]], [bj[0]])); ai, bj = ai[2:], bj[1:]
            elif len(bj) >= 2 and lev(fold(a[ai[0]][0]), fold(b[bj[0]][0] + b[bj[1]][0])) <= 2 and len(bj) - 2 >= len(ai) - 1:
                out.append(([ai[0]], [bj[0], bj[1]])); ai, bj = ai[1:], bj[2:]
            else:
                out.append(([ai[0]], [bj[0]])); ai, bj = ai[1:], bj[1:]
        out += [([i], []) for i in ai] + [([], [j]) for j in bj]
    return out


def lev(x, y):
    p = list(range(len(y) + 1))
    for i, cx in enumerate(x, 1):
        c = [i]
        for j, cy in enumerate(y, 1):
            c.append(min(p[j] + 1, c[j - 1] + 1, p[j - 1] + (cx != cy)))
        p = c
    return p[-1]


def main():
    stream = json.load(open(os.path.join(ROOT, "app", "build", "r13e-s4", "stream.json"), encoding="utf-8"))["live"]
    intended = [l for l in open(os.path.join(RES, "r13e", "s4-intended.txt"), encoding="utf-8").read().split("\n") if l]
    final = [l for l in open(os.path.join(RES, "r13e", "s4-final.txt"), encoding="utf-8").read().split("\n") if l]
    swapped = {}  # final.txt is in typing order
    flat, p1, look = [], [], []
    for mi, m in enumerate(stream["messages"]):
        mid, sent, fin, itx = m["id"], m["sent"], final[swapped.get(mi, mi)], intended[mi]
        evs = [e for e in stream["events"] if e["msg"] == mid]
        sw = words(sent)
        tok = []  # (word as sent, the last ending event at its place)
        for w, s in sw:
            ev = [e for e in evs if e["start"] == s]
            if not ev:
                look.append(f"{mid}: «{w}» at {s} has no word ending (typed into, never ended)")
                tok.append((w, None))
                continue
            tok.append((w, ev[-1]))
        iw, fw = words(itx), words(fin)
        to_final = {}
        for ii, jj in blocks(iw, fw):
            for i in ii:
                to_final[i] = " ".join(fw[j][0] for j in jj) if jj else None
                if len(ii) > 1 and jj:
                    to_final[i] = fw[jj[0]][0]
        rows = []
        for ii, jj in blocks(iw, [(w, 0) for w, _ in tok]):
            if not ii:
                look.append(f"{mid}: typed «{' '.join(tok[j][0] for j in jj)}» has no intended word (extra word)")
                continue
            if not jj:
                look.append(f"{mid}: intended «{' '.join(iw[i][0] for i in ii)}» was not typed (omitted)")
                continue
            evs_j = [tok[j][1] for j in jj]
            if any(e is None for e in evs_j):
                continue
            gold = itx[iw[ii[0]][1]: iw[ii[-1]][1] + len(iw[ii[-1]][0])]
            # the fingers' letters: s4 has no shift press, so a capital in the field is the engine's auto-capital
            typed = " ".join(e["typed"] for e in evs_j).lower()
            near = None
            if all(e["near"] is not None for e in evs_j):
                near = [x for e in evs_j for x in e["near"]]
            got = " ".join(tok[j][0] for j in jj)
            vk = to_final.get(ii[0])
            if len(ii) > 1 or len(jj) > 1:
                look.append(f"{mid}: gold «{gold}» ← typed «{typed}» (end {evs_j[-1]['end']!r}), 0.11.0 replay «{got}», chat «{vk}»")
            ok_t = fold(typed) == fold(gold)
            ok_f = vk is not None and fold(vk) == fold(gold)
            live = ("ok" if ok_f else "FALSE_CORRECTION") if ok_t else ("typo_fixed" if ok_f else "typo_not_fixed_or_wrong_fix")
            cat = "typo" if not ok_t else ("accent_or_case" if vk != gold else "ok")
            if live == "ok" and vk != gold:
                live = "accent_or_case_diff"
            if vk is not None and fold(vk) != fold(got):
                look.append(f"{mid}: «{gold}»: the 0.11.0 replay gives «{got}», the chat received «{vk}» (typed «{typed}», end {evs_j[-1]['end']!r})")
            row = {"session": "s4", "msg": mid, "cat": cat, "live_class": live, "left": itx[:iw[ii[0]][1]], "typed": typed,
                   "end": evs_j[-1]["end"], "gold": gold, "vk_final": vk, "lang": evs_j[-1]["lang"], "near": near}
            rows.append(row)
        flat += rows
        p1.append({"id": mid, "app": "hermes-web", "intended": itx,
                   "rows": [{k: r[k] for k in ("left", "typed", "end", "gold", "lang", "near", "vk_final", "cat")} for r in rows]})
    with open(os.path.join(RES, "r13e", "s4-live.json"), "w", encoding="utf-8") as f:
        json.dump(flat, f, ensure_ascii=False, indent=0)
        f.write("\n")
    with open(os.path.join(RES, "r13b", "s4-live.json"), "w", encoding="utf-8") as f:
        json.dump({"what": "r13e-s4: the owner's chat session s4 (VK 0.11.0, Firefox chat composer), P1's s4 schema; rows from "
                           "scripts/r13e_s4_fixture.py (end SEND = the Enter that sent the message; lang = keyboard language; "
                           "near = touch border keys per letter)", "messages": p1}, f, ensure_ascii=False, indent=0)
        f.write("\n")
    from collections import Counter
    print(f"{len(flat)} rows; cat {dict(Counter(r['cat'] for r in flat))}; live {dict(Counter(r['live_class'] for r in flat))}; "
          f"end {dict(Counter(r['end'] for r in flat))}; lang {dict(Counter(r['lang'] for r in flat))}")
    print("look at:")
    for l in look:
        print("  " + l)


if __name__ == "__main__":
    main()
