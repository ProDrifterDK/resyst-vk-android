#!/usr/bin/env python3
"""r13h (G2, docs/failure-modes.md JG1-JG6): merged touches in the owner's live sessions.

A touchscreen may report a finger's lift and the next finger's touch as ONE contact that moves (same slot, same
tracking id, no lift in between). This script reads each session's raw touch log (getevent -lt, multi-touch protocol
B; layout.json, touch.json, raw-getevent.log; not committed) with r13e_s4_decode.py's slot state and keeps one sample
per SYN_REPORT where the contact's position changed, as Android's InputReader would deliver it.

Per keyboard contact (its down point below the suggestion bar) it measures how far the pointer moves against the
previous sample (any age) and within any 12 ms window, in key widths, and applies the shipped guard's thresholds
(core/PointerGuard: JUMP >= 1.0 key width against the previous sample or within 12 ms, or >= 1.3 key widths within
25 ms; FAST >= 0.4 key width within 12 ms). Every JUMP is listed with where it
started, where it landed, its displacement and duration.

Outputs:
  <out json>     per session: contacts, keyboard contacts, the largest 12 ms moves, the jumps (keys, px, key widths, ms)
  --fixture F    app/src/test/resources/r13h/touch-paths.json: every keyboard contact that moved, as a path RELATIVE to
                 its down point ([dx px, dy px, ms since the down]; no absolute coordinate, no key, no text), plus, for
                 the jumps only, the absolute path on the keyboard and the keyboard's geometry (the JVM replay needs
                 the keys a jump started and landed on)

usage: r13h_jumps.py <out json> s1=<session dir> [s2=<dir> s3=<dir> s4=<dir>] [--fixture F]
"""
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r13e_s4_decode as D  # noqa: E402

WINDOW_S = 0.012       # PointerGuard.WINDOW_MS
JUMP_KEYS = 1.0        # PointerGuard.JUMP_KEYS
FAST_KEYS = 0.4        # PointerGuard.FAST_KEYS
LONG_WINDOW_S = 0.025  # PointerGuard.LONG_WINDOW_MS
LONG_JUMP_KEYS = 1.3   # PointerGuard.LONG_JUMP_KEYS


def unit():
    return (D.WIDTH - 2 * D.PAD) / 10


def contacts(sess):
    """Every contact of the raw log: slot, tracking id, start / end time, and one (t, x, y) sample per SYN_REPORT in
    which its position changed (the first sample is the down point, from the slot's last X / Y if the kernel sent no
    new coordinate)."""
    td = json.load(open(os.path.join(sess, "touch.json")))
    sx, sy = td["w"] / (td["maxX"] + 1), td["h"] / (td["maxY"] + 1)
    slot, cur, last, done, dirty = 0, {}, {}, [], set()
    for line in open(os.path.join(sess, "raw-getevent.log"), errors="replace"):
        m = re.match(r"\[\s*([\d.]+)\]\s+(EV_\w+)\s+(\w+)\s+(\w+)", line)
        if not m:
            continue
        t, ty, code, val = float(m.group(1)), m.group(2), m.group(3), m.group(4)
        if ty == "EV_SYN" and code == "SYN_REPORT":
            for s in dirty:
                c = cur.get(s)
                lx, ly = last.get(s, (None, None))
                if c is not None and lx is not None and ly is not None:
                    if not c["pts"] or (c["pts"][-1][1], c["pts"][-1][2]) != (lx, ly):
                        c["pts"].append((t, lx, ly))
            dirty.clear()
            continue
        if ty != "EV_ABS":
            continue
        if code == "ABS_MT_SLOT":
            slot = int(val, 16)
        elif code == "ABS_MT_TRACKING_ID":
            if slot in cur:
                c = cur.pop(slot)
                c["t1"] = t
                done.append(c)
            if val != "ffffffff":
                cur[slot] = {"t0": t, "slot": slot, "pts": []}
                dirty.add(slot)
        elif code in ("ABS_MT_POSITION_X", "ABS_MT_POSITION_Y"):
            v = int(val, 16)
            lx, ly = last.get(slot, (None, None))
            last[slot] = (v * sx, ly) if code.endswith("X") else (lx, v * sy)
            dirty.add(slot)
    return sorted((c for c in done if c["pts"]), key=lambda c: c["t0"])


def guard(pts, kw):
    """The shipped guard over one contact's samples: per sample CALM / FAST / JUMP (LANDING after a jump until a 12 ms
    window moves < FAST_KEYS, then SETTLED). Returns the verdicts and, per sample, the largest move in key widths."""
    out, far_k, landing = [], [], False
    for i, (t, x, y) in enumerate(pts):
        if i == 0:
            out.append("DOWN")
            far_k.append(0.0)
            continue
        win = lwin = 0.0
        for j in range(i - 1, -1, -1):
            if t - pts[j][0] > LONG_WINDOW_S + 1e-9:
                break
            d = math.hypot(x - pts[j][1], y - pts[j][2])
            lwin = max(lwin, d)
            if t - pts[j][0] <= WINDOW_S + 1e-9:
                win = max(win, d)
        prev = math.hypot(x - pts[i - 1][1], y - pts[i - 1][2])
        far_k.append(max(win, prev, lwin * JUMP_KEYS / LONG_JUMP_KEYS) / kw)
        if landing:
            if win < FAST_KEYS * kw:
                landing = False
                out.append("SETTLED")
            else:
                out.append("LANDING")
        elif max(win, prev) >= JUMP_KEYS * kw or lwin >= LONG_JUMP_KEYS * kw:
            landing = True
            out.append("JUMP")
        elif win >= FAST_KEYS * kw:
            out.append("FAST")
        else:
            out.append("CALM")
    return out, far_k


def window_max(pts, w):
    m = 0.0
    for i in range(len(pts)):
        for j in range(i + 1, len(pts)):
            if pts[j][0] - pts[i][0] > w + 1e-9:
                break
            m = max(m, math.hypot(pts[j][1] - pts[i][1], pts[j][2] - pts[i][2]))
    return m


def main():
    args = [a for a in sys.argv[1:]]
    fixture = None
    if "--fixture" in args:
        i = args.index("--fixture")
        fixture = args[i + 1]
        del args[i:i + 2]
    out_path = args[0]
    sessions = dict(a.split("=", 1) for a in args[1:])
    kw = unit()
    report = {"what": "r13h (G2): merged touches in the owner's live sessions, by scripts/r13h_jumps.py (thresholds of "
                      "core/PointerGuard: JUMP >= %.1f key width against the previous sample or within %d ms, FAST >= %.1f "
                      "key width within %d ms); key width %.2f px." % (JUMP_KEYS, WINDOW_S * 1000, FAST_KEYS, WINDOW_S * 1000, kw),
              "key_px": round(kw, 2), "sessions": {}}
    fix = {"what": "r13h (G2): every keyboard contact of s1-s4 whose position changed, as a path relative to its down point "
                   "(p = [dx px, dy px, ms since the down], one sample per raw report that moved it; at_ms = its down, ms "
                   "since the session's first touch; no absolute coordinate, no key, no text), and the jumps found by scripts/r13h_jumps.py with their absolute path on the "
                   "keyboard (screen px) and the keyboard geometry the decoder uses.",
           "key_px": round(kw, 4), "pad_px": round(D.PAD, 4), "width_px": D.WIDTH, "sessions": {}, "jumps": []}
    for name, sess in sessions.items():
        layout = json.load(open(os.path.join(sess, "layout.json")))
        geo = D.geometry(layout)
        fix["top_px"], fix["row_px"] = round(geo[0], 4), round(geo[1], 4)
        cs = contacts(sess)
        kb = [c for c in cs if c["pts"][0][2] >= geo[0]]
        rows, paths, w12, w25, fast = [], [], [], [], 0
        for c in kb:
            pts = c["pts"]
            t0, x0, y0 = pts[0]
            if len(pts) > 1:
                paths.append({"at_ms": int(round((c["t0"] - cs[0]["t0"]) * 1000)),
                              "p": [[round(x - x0, 1), round(y - y0, 1), int(round((t - c["t0"]) * 1000))] for t, x, y in pts]})
            w12.append(window_max(pts, WINDOW_S) / kw)
            w25.append(window_max(pts, LONG_WINDOW_S) / kw)
            verdicts, far = guard(pts, kw)
            fast += "FAST" in verdicts
            if "JUMP" not in verdicts:
                continue
            ji = verdicts.index("JUMP")
            calm = max(i for i in range(ji) if verdicts[i] in ("DOWN", "CALM"))
            si = next((i for i in range(ji + 1, len(pts)) if verdicts[i] == "SETTLED"), len(pts) - 1)
            key = lambda x, y: D.key_at(geo, "LET", "es", x, y) if y >= geo[0] else "BAR"
            a, b, end = pts[calm], pts[si], pts[-1]
            row = {"t0": round(c["t0"], 3), "dur_ms": int(round((c["t1"] - c["t0"]) * 1000)),
                   "start_key": key(x0, y0), "held_ms": int(round((a[0] - c["t0"]) * 1000)), "end_of_first_touch_key": key(a[1], a[2]),
                   "jump_detected_ms": int(round((pts[ji][0] - c["t0"]) * 1000)),
                   "landing_key": key(b[1], b[2]), "lift_key": key(end[1], end[2]),
                   "displacement_px": round(math.hypot(end[1] - a[1], end[2] - a[2]), 1),
                   "displacement_keys": round(math.hypot(end[1] - a[1], end[2] - a[2]) / kw, 2),
                   "jump_ms": round((b[0] - a[0]) * 1000, 1),
                   "move_at_detection_keys": round(far[ji], 2),
                   "verdicts": "".join(v[0] for v in verdicts)}
            rows.append(row)
            fix["jumps"].append({"session": name, "at_ms": int(round((c["t0"] - cs[0]["t0"]) * 1000)), "dur_ms": row["dur_ms"],
                                 "path": [[round(x, 1), round(y, 1), int(round((t - c["t0"]) * 1000))] for t, x, y in pts]})
        top = sorted(w12, reverse=True)[:5]
        top25 = sorted(w25, reverse=True)[:5]
        report["sessions"][name] = {"contacts": len(cs), "keyboard_contacts": len(kb), "moving_keyboard_contacts": len(paths),
                                    "largest_12ms_moves_keys": [round(v, 2) for v in top],
                                    "largest_25ms_moves_keys": [round(v, 2) for v in top25], "contacts_with_fast_samples": fast,
                                    "jumps": rows}
        fix["sessions"][name] = paths
        print(f"{name}: {len(kb)} keyboard contacts ({len(paths)} moved), largest 12 ms moves {[round(v, 2) for v in top]} / 25 ms {[round(v, 2) for v in top25]} key widths, "
              f"{fast} with FAST samples, {len(rows)} jump(s): " + "; ".join(f"{r['start_key']}->{r['lift_key']} {r['displacement_px']} px "
                                                                             f"in {r['jump_ms']} ms" for r in rows))
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    if fixture:
        with open(fixture, "w", encoding="utf-8") as f:
            json.dump(fix, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
        print(f"fixture -> {fixture} ({os.path.getsize(fixture)} bytes)")


if __name__ == "__main__":
    main()
