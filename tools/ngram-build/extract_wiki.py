#!/usr/bin/env python3
"""r13e: plain prose paragraphs from a Spanish Wikipedia pages-articles dump (stdlib only).

usage: extract_wiki.py <eswiki-...-pages-articles*.xml.bz2> <out.txt> [max_words]

Streams the bz2 XML, keeps namespace-0 non-redirect pages, strips wikitext (templates, tables, references, files,
categories, HTML, links -> their text, bold/italics) and writes one paragraph per line. Lines that are not prose
(headings, lists, table rows, fragments under 6 words or without lowercase letters) are dropped. Deterministic:
same dump, same output. The text keeps its case and punctuation; tokenisation is the builder's job (NgramBuild.java).
Licence of the output: CC BY-SA 4.0 (Wikipedia contributors), see ATTRIBUTION.md.
"""
import bz2
import html
import re
import sys
import xml.etree.ElementTree as ET

SKIP_LINKS = re.compile(r"^(archivo|file|imagen|image|categoría|category|anexo|wikipedia|plantilla|template|"
                        r"portal|ayuda|help|media|especial|special|[a-z]{2,3}(-[a-z]+)?):", re.I)
REDIRECT = re.compile(r"^\s*#(redirecci[oó]n|redirect)", re.I)
COMMENT = re.compile(r"<!--.*?-->", re.S)
REF = re.compile(r"<ref[^>/]*/>|<ref[^>]*>.*?</ref>", re.S | re.I)
TAGBLOCK = re.compile(r"<(math|code|pre|syntaxhighlight|score|timeline|gallery|imagemap|chem|ce|hiero|graph|"
                      r"mapframe|templatedata|references)[^>]*>.*?</\1>", re.S | re.I)
TAG = re.compile(r"</?[a-zA-Z][^>]*>")
EXT = re.compile(r"\[(?:https?:)?//[^\s\]]+(?:\s+([^\]]*))?\]")
BOLD = re.compile(r"'{2,}")
WS = re.compile(r"[ \t\u00a0]+")


def strip_nested(t, op, cl):
    """Removes every balanced op...cl block (templates {{ }}, tables {| |}), innermost first."""
    out, depth, i, n = [], 0, 0, len(t)
    lo, lc = len(op), len(cl)
    start = 0
    while i < n:
        if t.startswith(op, i):
            if depth == 0:
                out.append(t[start:i])
            depth += 1
            i += lo
        elif depth and t.startswith(cl, i):
            depth -= 1
            i += lc
            if depth == 0:
                start = i
        else:
            i += 1
    if depth == 0:
        out.append(t[start:])
    return "".join(out)


def links(t):
    """[[target|text]] -> text, [[target]] -> target; files / categories / interwiki dropped (nested-safe)."""
    out, i, n = [], 0, len(t)
    while i < n:
        j = t.find("[[", i)
        if j < 0:
            out.append(t[i:])
            break
        out.append(t[i:j])
        depth, k = 0, j
        while k < n:
            if t.startswith("[[", k):
                depth += 1
                k += 2
            elif t.startswith("]]", k):
                depth -= 1
                k += 2
                if depth == 0:
                    break
            else:
                k += 1
        inner = t[j + 2:k - 2]
        if not SKIP_LINKS.match(inner) and "[[" not in inner:
            out.append(inner.split("|")[-1])
        i = k
    return "".join(out)


def clean(text):
    t = COMMENT.sub("", text)
    t = TAGBLOCK.sub("", t)
    t = REF.sub("", t)
    t = strip_nested(t, "{{", "}}")
    t = strip_nested(t, "{|", "|}")
    t = links(t)
    t = EXT.sub(lambda m: m.group(1) or "", t)
    t = TAG.sub("", t)
    t = BOLD.sub("", t)
    t = html.unescape(t)
    out = []
    for line in t.split("\n"):
        s = line.strip()
        if not s or s[0] in "=*#:;|!{}[<_" or s.startswith("----"):
            continue
        s = WS.sub(" ", s)
        words = re.findall(r"[^\W\d_]+", s)
        if len(words) < 6 or not re.search(r"[a-záéíóúñ]", s):
            continue
        # a line with leftover markup is dropped rather than cleaned further
        if "{{" in s or "}}" in s or "[[" in s or "]]" in s or "|" in s:
            continue
        out.append(s)
    return out


def main():
    src, dst = sys.argv[1], sys.argv[2]
    max_words = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    pages = kept = words = 0
    ns_tag = None
    with bz2.open(src, "rb") as f, open(dst, "w", encoding="utf-8") as o:
        ctx = ET.iterparse(f, events=("end",))
        title = ns = None
        for _, el in ctx:
            tag = el.tag.rsplit("}", 1)[-1]
            if tag == "title":
                title = el.text
            elif tag == "ns":
                ns = el.text
            elif tag == "text":
                txt = el.text or ""
                if ns == "0" and not REDIRECT.match(txt):
                    pages += 1
                    paras = clean(txt)
                    if paras:
                        kept += 1
                        for p in paras:
                            o.write(p + "\n")
                            words += len(p.split())
            elif tag == "page":
                el.clear()
                if max_words and words >= max_words:
                    break
            if tag in ("revision", "page"):
                el.clear()
    print(f"pages {pages} kept {kept} words(approx) {words}", file=sys.stderr)


if __name__ == "__main__":
    main()
