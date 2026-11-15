#!/usr/bin/env python3
"""Rebuild the context-offer model (app/src/main/assets/context/wt-m2.vkng) from its pinned inputs.

Desktop only, stdlib + a JDK 21; nothing here runs on the phone and the app downloads nothing.

  tools/ngram-build/build.py corpus   download the two pinned inputs into $NGRAM_WORK, check their sha256, extract
                                      the Wikipedia prose (extract_wiki.py) and unpack the Tatoeba export
  tools/ngram-build/build.py build    NgramBuild.java -> $NGRAM_WORK/wt-m2.vkng + wt-m2.json (build report)
  tools/ngram-build/build.py check    compare the rebuilt asset with the shipped one (sha256)

Environment: NGRAM_WORK = scratch directory (downloads, extracted text, the rebuilt asset; ~0.9 GB peak, never
committed). Ported from the r13e spike's scripts/r13e_oracle.py (commands corpus / build), same inputs and options.
Note: Tatoeba's per-language exports are weekly snapshots and are not archived; a later download has another sha256
and gives another asset (the shipped one used the 2026-11-14 export, see assets/context/wt-m2.provenance.json).
"""
import bz2
import hashlib
import os
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
WORK = os.environ.get("NGRAM_WORK", os.path.join(ROOT, "app", "build", "ngram-work"))
SHIPPED = os.path.join(ROOT, "app", "src", "main", "assets", "context", "wt-m2.vkng")
CASES = os.path.join(ROOT, "app", "src", "test", "resources", "r13f", "offers", "tokenizer-cases.tsv")

CORPORA = {
    # name: (url, sha256, licence)
    "eswiki-20261101-pages-articles1.xml-p1p159400.bz2": (
        "https://dumps.wikimedia.org/eswiki/20261101/eswiki-20261101-pages-articles1.xml-p1p159400.bz2",
        "9a76b2a72b88c9fc73dcfdce123fb20539827ca2d92b9992c126e0ef5769a001", "CC BY-SA 4.0"),
    "spa_sentences.tsv.bz2": (
        "https://downloads.tatoeba.org/exports/per_language/spa/spa_sentences.tsv.bz2",
        "06b518dde1ba30a5547d8902c8e458ba0e45e67f22d3ca88855f701edbd5b359", "CC BY 2.0 FR"),
}
# the shipped model: vocabulary 100,000, bigrams kept from weighted count 6, trigrams from 9; Tatoeba weighted x8
MODEL = ("wt-m2", 100000, 6, 9, ["lines:1:eswiki-a1.txt", "tatoeba:8:spa_sentences.tsv"])


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def cmd_corpus():
    os.makedirs(WORK, exist_ok=True)
    for name, (url, h, _) in CORPORA.items():
        p = os.path.join(WORK, name)
        if not os.path.exists(p):
            urllib.request.urlretrieve(url, p)
        if sha(p) != h:
            sys.exit(f"{name}: sha256 mismatch (a newer export?)")
    wiki = os.path.join(WORK, "eswiki-a1.txt")
    if not os.path.exists(wiki):
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "extract_wiki.py"),
                        os.path.join(WORK, "eswiki-20261101-pages-articles1.xml-p1p159400.bz2"), wiki, "80000000"], check=True)
    tat = os.path.join(WORK, "spa_sentences.tsv")
    if not os.path.exists(tat):
        with bz2.open(os.path.join(WORK, "spa_sentences.tsv.bz2"), "rb") as f, open(tat, "wb") as o:
            o.write(f.read())


def cmd_build():
    name, v, k2, k3, specs = MODEL
    cmd = ["nice", "-n", "10", "java", "-Xmx4g", os.path.join(HERE, "NgramBuild.java"),
           "--out", os.path.join(WORK, name + ".vkng"), "--report", os.path.join(WORK, name + ".json"),
           "--vocab", str(v), "--k2", str(k2), "--k3", str(k3), "--shards", "8", "--tmp", os.path.join(WORK, "tmp"),
           "--cases", CASES]
    cmd += [s.split(":", 2)[0] + ":" + s.split(":", 2)[1] + ":" + os.path.join(WORK, s.split(":", 2)[2]) for s in specs]
    if subprocess.run(cmd).returncode:
        sys.exit("build failed")


def cmd_check():
    built = os.path.join(WORK, MODEL[0] + ".vkng")
    a, b = sha(built), sha(SHIPPED)
    print(f"rebuilt {a}\nshipped {b}")
    sys.exit(0 if a == b else 1)


if __name__ == "__main__":
    cmds = {"corpus": cmd_corpus, "build": cmd_build, "check": cmd_check}
    if len(sys.argv) < 2 or any(c not in cmds for c in sys.argv[1:]):
        sys.exit(__doc__)
    for c in sys.argv[1:]:
        cmds[c]()
