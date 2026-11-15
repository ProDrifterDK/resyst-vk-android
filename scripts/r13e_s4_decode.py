#!/usr/bin/env python3
"""r13e-s4: decode a live session's touches into the keys Resyst VK 0.11.0 received, the way KeyboardView does it.

KeyboardView (0.11.0):
- the key is the HIT cell under the touch-DOWN: rows of rowH below the strip, a key's cell runs to the gap midpoints,
  spacer / outer padding goes to the nearest key;
- a finger that slides from one char key onto another re-targets to it, so a letter commits where it LIFTS;
- a letter (or ? , .) held >= longPressMs (350 ms) opens its variants popup and, lifted in place, commits the first
  variant (e -> é, ? -> ¿);
- the space bar: a vertical flick (|dy| >= 20 dp, |dy| > 2|dx|, <= 300 ms) switches the keyboard language (es <-> en,
  ResystImeService.onSpaceFlick) and types nothing; a horizontal drag (|dx| > 14 dp, |dx| > |dy|) moves the cursor and
  types nothing; a hold opens the keyboard picker;
- layers: ?123 -> SYMBOLS, =\\< -> SYMBOLS2, ?123 / ABC back; no automatic return to the letters.
The flick / drag test uses the touch's whole path from the raw event log (KeyboardView classifies on every move).
Taps above the keyboard are the page (Firefox: the chat page), recorded as PAGE. Touches come from the raw log with
multi-touch protocol-B slot state (the recorder's taps.json drops touches whose X or Y did not change), keys are put
in the order VK commits them (lift, roll-over, ⌫ on its down), and English has its own letters layout (no ñ, the
middle row shifted half a key).

Per letter it also gives the border keys of the lift point (core/TouchKeys.near, BORDER 0.12 key widths), what
KeyboardView.nearKeys reports to the IME (r13d TB1).

usage: r13e_s4_decode.py <session dir: layout.json, touch.json, raw-getevent.log> [--dump]
       r13e_s4_decode.py keys <session dir> <out json> <number of messages the chat received> [--gestures MODE]
r13f: --gestures live (default, s4-keys.json) | r13f-intent | all-space (see GESTURES).
Prints a JSON list on stdout (or one line per touch with --dump); `keys` writes the per-message key stream.
"""
import json
import os
import re
import sys

LONG_S = 0.350
# VK's density on the owner's phone. KeyboardView lays keys out in dp (padX 3 dp, gap 5 dp, 10-unit rows); layout.json
# (the a11y boxes) fits any dp in 1.78-2.0 equally well (the Pixel 8 Pro's 1008-px mode at default size is 315 dpi =
# 1.97). 1.9 is the value inside that range that reproduces what the chat received: the space swipes of 39 px up
# ("del final" -> "delfinak", "la pena" -> "lapena") are flicks (20 dp = 38 px), one of 29 px sideways ("yay algo" ->
# "yayalgo") is a cursor drag (14 dp = 26.6 px), and the swipes of 36 / 31 / 23 px that left a space live are neither.
# r13d's s3 script used 2.625 (1008 px / 384 dp), which no flick in s1-s3 could contradict.
DP = 1.9
# r13f: SpaceGesture 0.12.0 (docs/failure-modes.md SG1/SG2): a flick needs 40 dp up or down, a cursor drag 24 dp sideways or a
# hold of 200 ms then 9 dp sideways. GESTURES picks how the space-bar gestures of the stream are decoded:
#   live         0.11.0's rule (20 dp flick, 14 dp drag): what the phone did (s4-keys.json)
#   r13f-intent  0.12.0's rule; a flick that is still a flick under it was the owner switching back to Spanish after an
#                accidental switch that 0.12.0 no longer makes: it is dropped and the language never changes
#   all-space    every 0.11.0 gesture becomes a space tap (the literal reading "all 7 are spaces"), Spanish throughout
R13F_FLICK_DP, R13F_CURSOR_DP, R13F_HOLD_S, R13F_HOLD_DP = 40, 24, 0.200, 9
GESTURES = "live"
GAP = 5 * DP  # Density.NORMAL.gapDp
PAD = 3 * DP
WIDTH = 1008
BORDER = 0.12  # core/TouchKeys.BORDER

# KeyboardLayouts rows (label, width in units); SP = spacer (goes to the nearest real key)
BOTTOM = lambda layer_key: [(layer_key, 1.5), (",", 1), ("☺", 1), (" ", 4), (".", 1), ("⏎", 1.5)]
LAYOUTS = {
    ("LET", "es"): [[(c, 1) for c in "qwertyuiop"], [(c, 1) for c in "asdfghjklñ"],
                    [("⇧", 1.5)] + [(c, 1) for c in "zxcvbnm"] + [("⌫", 1.5)], BOTTOM("L:SYM")],
    ("LET", "en"): [[(c, 1) for c in "qwertyuiop"], [("SP", 0.5)] + [(c, 1) for c in "asdfghjkl"] + [("SP", 0.5)],
                    [("⇧", 1.5)] + [(c, 1) for c in "zxcvbnm"] + [("⌫", 1.5)], BOTTOM("L:SYM")],
    ("SYM", None): [[(c, 1) for c in "1234567890"], [(c, 1) for c in ["@", "#", "€", "_", "&", "-", "+", "(", ")", "/"]],
                    [("L:SYM2", 1.5)] + [(c, 1) for c in ["*", '"', "'", ":", ";", "!", "?"]] + [("⌫", 1.5)], BOTTOM("L:LET")],
    ("SYM2", None): [[(c, 1) for c in "~`|\\{}[]<>"], [(c, 1) for c in ["^", "°", "=", "%", "§", "¬", "£", "¥", "$", "¢"]],
                     [("L:SYM", 1.5)] + [(c, 1) for c in ["¿", "¡", "«", "»", "…", "·", "º"]] + [("⌫", 1.5)], BOTTOM("L:LET")],
}
VARIANT0 = {"a": "á", "e": "é", "i": "í", "o": "ó", "u": "ú", "n": "ñ", "c": "ç", "s": "ß", "y": "ý", "l": "ł", "z": "ž",
            "?": "¿", "!": "¡", ".": "…", ",": ";"}


def grid(layer, lang):
    return LAYOUTS[(layer, lang if layer == "LET" else None)]


def geometry(layout):
    """(top of the first key row, row height) on the screen, from the letters layer's a11y boxes: a box is drawn at
    [y0 + gap/2 + dp, y0 + rowH - gap/2] inside its row (KeyboardView.layoutKeys)."""
    q = next(k["box"] for k in layout if k["label"] == "Q")
    last = max(k["box"][3] for k in layout)
    top = q[1] - (GAP / 2 + DP)
    return top, (last + GAP / 2 - top) / 4


def cells_of(rows_spec):
    """Per row, [(label, left, right)] of its real keys (KeyboardView.layoutKeys: x from padX, w = width * unit)."""
    unit = (WIDTH - 2 * PAD) / 10
    out = []
    for row in rows_spec:
        x, cs = PAD, []
        for lab, w in row:
            if lab != "SP":
                cs.append((lab, x, x + w * unit))
            x += w * unit
        out.append(cs)
    return out, unit


def key_at(geo, layer, lang, x, y):
    top, rh = geo
    if y < top:
        return None
    r = min(3, max(0, int((y - top) // rh)))
    cs, _ = cells_of(grid(layer, lang))
    row = cs[r]
    for lab, l, rt in row:
        if l <= x < rt:
            return lab
    return min(row, key=lambda c: min(abs(x - c[1]), abs(x - c[2])))[0]  # boxAt: padding / spacer -> nearest key


def near(geo, lang, x, y, hit):
    """KeyboardView.nearKeys: the other letter cells within BORDER key widths of (x, y)."""
    top, rh = geo
    cs, unit = cells_of(grid("LET", lang))
    rad = BORDER * unit
    s = ""
    for r in range(3):
        for lab, l, rt in cs[r]:
            if len(lab) != 1 or not lab.isalpha() or lab == hit or lab in s:
                continue
            t, b = top + r * rh, top + (r + 1) * rh
            dx = max(l - x, 0, x - rt)
            dy = max(t - y, 0, y - b)
            if dx * dx + dy * dy <= rad * rad:
                s += lab
    return s


def contacts(sess):
    """Every touch of the raw log (multi-touch protocol B). A slot keeps its last X / Y across contacts: the kernel
    sends a coordinate only when it changes, so a new contact at the same X (or Y) as the slot's previous one carries
    no event for it (the recorder's own parser, vk_tapcap.parse_contacts, drops those touches). A new tracking id on
    a busy slot ends the old contact there. Per contact: down / up point, duration, flick / drag classification over
    the whole path (KeyboardView classifies the space bar on every move)."""
    td = json.load(open(os.path.join(sess, "touch.json")))
    sx, sy = td["w"] / (td["maxX"] + 1), td["h"] / (td["maxY"] + 1)
    slot, cur, done, last = 0, {}, [], {}

    def finish(c, t):
        c["t1"] = t
        done.append(c)

    for line in open(os.path.join(sess, "raw-getevent.log"), errors="replace"):
        m = re.match(r"\[\s*([\d.]+)\]\s+(EV_\w+)\s+(\w+)\s+(\w+)", line)
        if not m or m.group(2) != "EV_ABS":
            continue
        t, code, val = float(m.group(1)), m.group(3), m.group(4)
        if code == "ABS_MT_SLOT":
            slot = int(val, 16)
        elif code == "ABS_MT_TRACKING_ID":
            if slot in cur:
                finish(cur.pop(slot), t)
            if val != "ffffffff":
                lx, ly = last.get(slot, (None, None))
                cur[slot] = {"t0": t, "pts": [(lx, ly, t)] if lx is not None and ly is not None else [], "x": lx, "y": ly}
        elif code in ("ABS_MT_POSITION_X", "ABS_MT_POSITION_Y"):
            v = int(val, 16)
            lx, ly = last.get(slot, (None, None))
            last[slot] = (v * sx, ly) if code.endswith("X") else (lx, v * sy)
            if slot in cur:
                c = cur[slot]
                c["x"], c["y"] = last[slot]
                if c["x"] is not None and c["y"] is not None:
                    if c["pts"] and c["pts"][-1][2] == t:
                        c["pts"][-1] = (c["x"], c["y"], t)
                    else:
                        c["pts"].append((c["x"], c["y"], t))
    out = []
    for c in sorted(done, key=lambda c: c["t0"]):
        if not c["pts"]:
            continue
        x0, y0, _ = c["pts"][0]
        flick = cursor = False
        for x, y, tp in c["pts"]:
            ax, ay = abs(x - x0) / DP, abs(y - y0) / DP
            if not flick and not cursor:
                if ay >= 20 and ay > 2 * ax and tp - c["t0"] <= 0.300:
                    flick = True
                elif ax > 14 and ax > ay:
                    cursor = True
        # r13f (SG1/SG2): the same path under 0.12.0's SpaceGesture rule
        flick13f = cursor13f = False
        for x, y, tp in c["pts"]:
            ax, ay, el = abs(x - x0) / DP, abs(y - y0) / DP, tp - c["t0"]
            if not flick13f and not cursor13f:
                if ay >= R13F_FLICK_DP and ay > 2 * ax and el <= 0.300:
                    flick13f = True
                elif ax > ay and (ax >= R13F_CURSOR_DP or (el >= R13F_HOLD_S and ax >= R13F_HOLD_DP)):
                    cursor13f = True
        ux, uy, _ = c["pts"][-1]
        out.append({"t0": c["t0"], "t1": c["t1"], "dur": round(c["t1"] - c["t0"], 3), "x": x0, "y": y0, "ux": ux, "uy": uy,
                    "flick": flick, "cursor": cursor, "flick13f": flick13f, "cursor13f": cursor13f, "pts": c["pts"]})
    return out


def decode(sess):
    taps = sorted(contacts(sess), key=lambda t: t["t0"])
    layout = json.load(open(os.path.join(sess, "layout.json")))
    geo = geometry(layout)
    strip_top = min(k["box"][1] for k in layout)
    on_space = lambda t: t["y"] >= geo[0] and key_at(geo, "LET", "es", t["x"], t["y"]) == " "
    # The language: a flick on the space bar switches it (and the layout: English has no ñ and its middle row is
    # shifted half a key) as soon as KeyboardView classifies the move; the bottom row is the same in every layout.
    flicks = [t["t0"] for t in taps if on_space(t) and t["flick"]] if GESTURES == "live" else []
    lang_at = lambda tt: "es" if sum(1 for f in flicks if f < tt) % 2 == 0 else "en"
    # The order VK commits keys in: a letter on its lift, or when another finger comes down while it is held
    # (KeyboardView.down: fast-typing roll-over, not once its variants popup is open); ⌫ on its down; space, Enter,
    # layer keys on their lift. The key under a touch is read on the layout showing at its DOWN.
    downs = [t["t0"] for t in taps]
    import bisect
    for t in taps:
        b = bisect.bisect_right(downs, t["t0"])
        nxt = downs[b] if b < len(downs) else None
        t["roll"] = nxt if nxt is not None and nxt < t["t1"] and nxt - t["t0"] < LONG_S else None
    layer_changes = []  # (commit time, layer) of the layer keys decoded so far
    out = []
    is_char = lambda kk: kk not in ("⇧", "⌫", "☺", "⏎", " ") and not kk.startswith("L:")
    for t in taps:
        layer = "LET"
        for ct, l in sorted(layer_changes):
            if ct <= t["t0"]:
                layer = l
        lang = lang_at(t["t0"])
        e = {"t0": t["t0"], "dur": t["dur"], "x": t["x"], "y": t["y"], "ux": t["ux"], "uy": t["uy"], "commit": t["t1"]}
        if t["y"] < strip_top - 10:
            e["key"] = "PAGE"
        elif t["y"] < geo[0]:
            e["key"] = "STRIP"
        else:
            k = key_at(geo, layer, lang, t["x"], t["y"])
            if is_char(k):
                if t["uy"] >= geo[0]:
                    k2 = key_at(geo, layer, lang, t["ux"], t["uy"])
                    if is_char(k2) and k2 != k:
                        e["slid_from"] = k
                        k = k2
                if t["dur"] >= LONG_S and k in VARIANT0:
                    e["long"] = True
                    e["base"] = k
                    k = VARIANT0[k]
                if layer == "LET" and len(k) == 1 and k.isalpha() and "base" not in e:
                    e["near"] = near(geo, lang, t["ux"], t["uy"], k)
                if not e.get("long") and t["roll"] is not None:
                    e["commit"] = t["roll"]
                    e["rolled"] = True
            elif k == " " and GESTURES != "live":
                if GESTURES == "r13f-intent" and t["flick13f"]:
                    k = "RESTORE"  # dropped from the stream (see GESTURES)
                elif GESTURES == "r13f-intent" and t["cursor13f"]:
                    k = "CURSOR"
                elif t["dur"] >= LONG_S and not (t["flick"] or t["cursor"]):
                    k = "SPACE_HOLD"
            elif k == " ":
                if t["flick"]:
                    k = "FLICK"
                elif t["cursor"]:
                    k = "CURSOR"
                elif t["dur"] >= LONG_S:
                    k = "SPACE_HOLD"
            elif k == "⌫":
                e["commit"] = t["t0"]
            if k.startswith("L:"):
                layer_changes.append((e["commit"], k[2:]))
            e["key"] = k
            e["layer"] = layer
        e["lang"] = lang
        out.append(e)
    out.sort(key=lambda e: e["commit"])
    for n, e in enumerate(out):
        e["n"] = n
    return out


REPEAT_START_S, REPEAT_S = 0.400, 0.050  # KeyboardView.REPEAT_START_MS / REPEAT_MS
# Each repeat is re-posted 50 ms after the previous one ran (Handler.postAtTime(uptimeMillis() + 50)), so the time
# the IME spends on each ⌫ (commit to Firefox + the bar refresh) adds up. 12 ms per repeat is calibrated on s4's only
# held ⌫ (0.81 s): 7 repeats (not the nominal 9) reproduce what the chat received; any slip in 8.6-18.3 ms gives 7.
REPEAT_SLIP_S = 0.012


def messages(ev, count=None):
    """The key stream per message: a message ends with the Enter that sent it (the page clears the field). Keys only
    (no coordinates): letters with their border keys, ⌫ with its auto-repeats (a held ⌫ commits on the down, then
    every 50 ms after 400 ms), space, marks, FLICK (language toggle), CURSOR, LAYER:x, VAR:x (long-press variant),
    PAGE (a tap on the page), SEND (the Enter that sent the message), ENTER (an Enter on an empty field)."""
    out, cur, t_start = [], [], None
    for e in ev:
        if count is not None and len(out) >= count:
            break
        k = e["key"]
        if k == "STRIP":
            raise SystemExit(f"tap {e['n']}: a bar tap; the replay does not model it")
        if t_start is None:
            t_start = e["t0"]
        t = int(round((e["t0"] - t_start) * 1000))
        if k == "⏎":
            if any(x["k"] not in ("PAGE",) for x in cur):
                cur.append({"k": "SEND", "t": t, "n": e["n"]})
                out.append(cur)
                cur = []
            else:
                cur.append({"k": "ENTER", "t": t, "n": e["n"]})
            continue
        if k == "RESTORE":
            continue
        x = {"k": k, "t": t, "n": e["n"]}
        if k.startswith("L:"):
            x["k"] = "LAYER:" + k[2:]
        elif e.get("long"):
            x["k"] = "VAR:" + k
        elif k == "⌫":
            x["rep"] = 0 if e["dur"] < REPEAT_START_S else int((e["dur"] - REPEAT_START_S) // (REPEAT_S + REPEAT_SLIP_S)) + 1
        if "near" in e:
            x["near"] = e["near"]
        cur.append(x)
    return out


if __name__ == "__main__":
    if "--gestures" in sys.argv:
        i = sys.argv.index("--gestures")
        GESTURES = sys.argv[i + 1]
        assert GESTURES in ("live", "r13f-intent", "all-space"), GESTURES
        del sys.argv[i:i + 2]
    if sys.argv[1] == "keys":
        # keys <session dir> <out json> <number of messages the chat received> [--gestures live|r13f-intent|all-space]
        ev = decode(sys.argv[2])
        msgs = messages(ev, int(sys.argv[4]))
        doc = {"what": "r13e-s4: the owner's s4 key stream as Resyst VK 0.11.0 received it, decoded by scripts/r13e_s4_decode.py "
                       "from the raw touches (VK's hit cells, slides, long-press variants, space flick/drag, held-⌫ repeats). "
                       "One entry per message, ended by the Enter that sent it. t = ms since the session's first key."
                       + ("" if GESTURES == "live" else f" r13f: space-bar gestures decoded with --gestures {GESTURES} (see the script's GESTURES)."),
               "messages": [{"id": f"m{i + 1:02d}", "keys": [{kk: vv for kk, vv in k.items() if kk != "n"} for k in m]} for i, m in enumerate(msgs)]}
        with open(sys.argv[3], "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=0)
            f.write("\n")
        print(f"{len(msgs)} messages, {sum(len(m) for m in msgs)} keys -> {sys.argv[3]}")
        sys.exit(0)
    ev = decode(sys.argv[1])
    if "--dump" in sys.argv:
        prev = None
        for e in ev:
            gap = e["t0"] - prev if prev else 0
            prev = e["t0"]
            extra = (" long" if e.get("long") else "") + (f" slid:{e['slid_from']}" if e.get("slid_from") else "") + (f" near:{e['near']}" if e.get("near") else "")
            print(f"{e['n']:5d} +{gap:6.2f}s {e['key']!r:10} {e['lang']} dur={e['dur']:.3f} ({e['x']:.0f},{e['y']:.0f})->({e['ux']:.0f},{e['uy']:.0f}){extra}")
    else:
        json.dump(ev, sys.stdout, ensure_ascii=False)
