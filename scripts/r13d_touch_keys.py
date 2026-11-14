#!/usr/bin/env python3
"""r13d (TB5): the touch evidence of a live session, per fixture row, as VK's keyboard records it.

For every row of a session fixture (app/src/test/resources/r13c|r13d/sN-live.json) this finds the touches
that typed the row's `typed` word in the session's raw touches (taps.json, not committed) and gives, for
each letter, the OTHER letter keys whose HIT cell lies within core/TouchKeys.BORDER key widths of the
point where the finger lifted — exactly what KeyboardView.nearKeys reports to the IME on the device.
Letters only: no coordinates and no text beyond the fixture's own `typed` go into the output.

VK's hit cells (KeyboardView.boxAt / buildBoxes): a key's box is drawn inset by gap/2 inside its cell
(plus 1 dp at the top), so a row's cell spans [box.bottom + gap/2 - rowH, box.bottom + gap/2] and a key's
cell runs to the midpoints of the gaps beside it. On the Pixel 8 Pro letters layer (layout.json) the row
pitch is 123 px, gap + dp = 11 px and dp = 2.625 (1008 px / 384 dp), so the row boundary sits 4.2 px
below a box's bottom edge: a touch at y = 1818 is still i (s3 `iueho`: final.txt keeps the i), one at
y = 1820 is k (s3 `caido`: VK left `cakdo`).

usage: r13d_touch_keys.py <evidence dir (taps.json, layout.json)> <fixture json> <out json>
"""
import json
import os
import re
import sys

BORDER = 0.12  # core/TouchKeys.BORDER
DP = 2.625


def cells_from_layout(layout):
    keys = {k['label'].lower(): k['box'] for k in layout if len(k['label']) == 1 and k['label'].isalpha()}
    rows = {}
    for c, b in keys.items():
        rows.setdefault(b[1], []).append((b[0], b[2], c, b))
    tops = sorted(rows)
    pitch = (tops[-1] - tops[0]) / (len(tops) - 1)
    gaps = []
    for t in tops:
        r = sorted(rows[t])
        gaps += [r[i + 1][0] - r[i][1] for i in range(len(r) - 1)]
    vgap = tops[1] - rows[tops[0]][0][3][3]  # next row's top - this row's bottom = gap + dp
    gap = vgap - DP
    cells = []
    widths = []
    for t in tops:
        r = sorted(rows[t])
        bottom = r[0][3][3] + gap / 2
        top = bottom - pitch
        for i, (l, rt, c, _) in enumerate(r):
            left = (r[i - 1][1] + l) / 2 if i > 0 else l - gap / 2
            right = (rt + r[i + 1][0]) / 2 if i < len(r) - 1 else rt + gap / 2
            cells.append((c, left, top, right, bottom))
            widths.append(right - left)
    key_width = sorted(widths)[len(widths) // 2]
    return cells, key_width


def near(cells, key_width, x, y, hit):
    rad = BORDER * key_width
    out = ''
    for c, l, t, r, b in cells:
        if c == hit or c in out:
            continue
        dx = max(l - x, 0, x - r)
        dy = max(t - y, 0, y - b)
        if dx * dx + dy * dy <= rad * rad:
            out += c
    return out


def sym(k):
    if not k:
        return ''
    if k == 'Borrar':
        return '\b'
    if k.startswith('Espacio'):
        return ' '
    return {'coma': ',', 'punto': '.', 'Nueva línea': '\n'}.get(k, '§' if k.startswith('Sugerencia') else (k.lower() if len(k) == 1 else ''))


def main():
    ev, fixture, out = sys.argv[1:4]
    taps = json.load(open(os.path.join(ev, 'taps.json'), encoding='utf-8'))
    cells, key_width = cells_from_layout(json.load(open(os.path.join(ev, 'layout.json'), encoding='utf-8')))
    rows = json.load(open(fixture, encoding='utf-8'))
    stream = []  # (char, tap index), backspaces applied (make_session_fixture.py's stream)
    for i, t in enumerate(taps):
        c = sym(t.get('key'))
        if c == '\b':
            if stream:
                stream.pop()
        elif c:
            stream.append((c, i))
    text = ''.join(c for c, _ in stream)
    seen = {}
    result = []
    found = 0
    for row in rows:
        typed = row['typed'].lower()
        k = seen.get(typed, 0)
        seen[typed] = k + 1
        hits = [m.start() for m in re.finditer(r'(?<![a-zñáéíóúü])' + re.escape(typed) + r'(?=[ ,.\n])', text)]
        if not hits:
            result.append(None)
            continue
        p = hits[min(k, len(hits) - 1)]
        per = []
        for j, (c, i) in enumerate(stream[p:p + len(typed)]):
            t = taps[i]
            per.append(near(cells, key_width, t['ux'], t['uy'], c))
        result.append({'typed': row['typed'], 'near': per})
        found += 1
    json.dump(result, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    with_border = sum(1 for r in result if r and any(r['near']))
    print(f'{found}/{len(rows)} rows located, {with_border} with a border touch; key width {key_width:.1f} px, radius {BORDER * key_width:.1f} px')


if __name__ == '__main__':
    main()
