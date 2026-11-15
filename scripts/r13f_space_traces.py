#!/usr/bin/env python3
"""r13f (SG3): every touch on the space bar in the owner's live sessions, as the path KeyboardView classified.

For each session directory (layout.json, touch.json, raw-getevent.log; not committed) it reads the raw touches with
scripts/r13e_s4_decode.py (multi-touch protocol B, VK's own hit cells at dp 1.9) and keeps the touches whose DOWN
point is on the space bar. Per touch: the path relative to the down point in dp (x right, y down; 0.1 dp) with the
time since the down in ms, the duration, and what 0.11.0 made of it (TAP / FLICK / CURSOR / HOLD). No absolute
coordinate, no letter, no text.

The seven s4 gestures are labelled by hand from the decoded stream (r13f/s4-keys.json) and the chat (s4-final.txt);
the labels are evidence, not tuning (docs/failure-modes.md, Round 13f):
  accidental  meant as a space (the chat lost that space): m05 del|final, m11 mu ho|que, m15 yay|algo, m20 la|pena
  deliberate  the owner switching back to Spanish right after an accidental switch: m06, m11 (after "que "), m21

usage: r13f_space_traces.py <out json> s1=<session dir> [s2=<dir> s3=<dir> s4=<dir>]
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r13e_s4_decode as D  # noqa: E402

# the s4 gestures in time order (6 flicks + 1 drag under 0.11.0's rule), checked against s4-keys.json below
S4_LABELS = [
    ("m05", "accidental", "del|final"),
    ("m06", "deliberate", "no s|... (back to Spanish)"),
    ("m11", "accidental", "mu ho|que"),
    ("m11", "deliberate", "que |... (back to Spanish)"),
    ("m15", "accidental", "yay|algo"),
    ("m20", "accidental", "la|pena"),
    ("m21", "deliberate", "ahkra |... (back to Spanish)"),
]


def traces(sess):
    layout = json.load(open(os.path.join(sess, "layout.json")))
    geo = D.geometry(layout)
    out = []
    for t in D.contacts(sess):
        if t["y"] < geo[0] or D.key_at(geo, "LET", "es", t["x"], t["y"]) != " ":
            continue
        x0, y0 = t["x"], t["y"]
        path, last = [], None
        for x, y, tp in t["pts"]:
            p = [round((x - x0) / D.DP, 1), round((y - y0) / D.DP, 1), int(round((tp - t["t0"]) * 1000))]
            if p != last:
                path.append(p)
            last = p
        live = "FLICK" if t["flick"] else "CURSOR" if t["cursor"] else "HOLD" if t["dur"] >= D.LONG_S else "TAP"
        out.append({"dur_ms": int(round(t["dur"] * 1000)), "live": live, "path": path})
    return out


def main():
    out_path = sys.argv[1]
    doc = {"what": "r13f (SG3): every space-bar touch of the owner's live sessions, decoded from the raw touches by "
                   "scripts/r13f_space_traces.py (r13e_s4_decode.py's reader, dp 1.9). path = [dx dp, dy dp, ms since the "
                   "down] (y grows downwards); live = what 0.11.0's SpaceGesture made of it.",
           "dp": D.DP, "sessions": {}}
    for arg in sys.argv[2:]:
        name, sess = arg.split("=", 1)
        tr = traces(sess)
        if name == "s4":
            gestures = [t for t in tr if t["live"] in ("FLICK", "CURSOR")]
            keys = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "test",
                                               "resources", "r13f", "s4-keys.json")))
            stream = [(m["id"], k["k"]) for m in keys["messages"] for k in m["keys"] if k["k"] in ("FLICK", "CURSOR")]
            assert len(gestures) == len(S4_LABELS) == len(stream), (len(gestures), len(stream))
            for g, (mid, intent, ctx), (sid, sk) in zip(gestures, S4_LABELS, stream):
                assert mid == sid and g["live"] == sk, (mid, sid, g["live"], sk)
                g.update({"msg": mid, "intent": intent, "context": ctx})
        doc["sessions"][name] = tr
        print(f"{name}: {len(tr)} space touches, " + ", ".join(f"{k} {sum(1 for t in tr if t['live'] == k)}" for k in ("TAP", "HOLD", "FLICK", "CURSOR")))
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")


if __name__ == "__main__":
    main()
