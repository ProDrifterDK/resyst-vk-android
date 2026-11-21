#!/usr/bin/env python3
"""✦ Resyst VK — r15 E2E index: build/e2e-r15/index.md + index.html from r15-before.json, r15-after.json and the site
checks (site/*.json, site/*.png). Pairs every scene by (name, language): before | after. Relative paths only.

Usage: scripts/e2e_r15_index.py
"""
import html
import json
import os
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
BASE = os.path.join(ROOT, 'build', 'e2e-r15')


def load(name):
    p = os.path.join(BASE, name)
    return json.load(open(p, encoding='utf-8')) if os.path.exists(p) else {}


def apk_sizes():
    d = os.path.join(BASE, 'apk')
    out = {}
    for f in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        if not f.endswith('.apk'):
            continue
        z = zipfile.ZipFile(os.path.join(d, f))
        out[f] = {'file_bytes': os.path.getsize(os.path.join(d, f)), 'entries_compressed_bytes': sum(i.compress_size for i in z.infolist()),
                  'search_txt': next(({'compressed': i.compress_size, 'raw': i.file_size} for i in z.infolist() if i.filename == 'assets/emoji/search.txt'), None)}
    return out


def main():
    before, after = load('r15-before.json'), load('r15-after.json')
    scenes = {}
    for side, data in (('before', before), ('after', after)):
        for s in data.get('scenes', []):
            scenes.setdefault((s['scene'], s['lang']), {})[side] = s
    order = sorted(scenes, key=lambda k: (k[0][0], k[1], k[0]))
    rows = after.get('rows', []) + before.get('rows', [])
    ok = sum(1 for r in after.get('rows', []) if r['ok'])
    n = len(after.get('rows', []))
    sizes = apk_sizes()
    json.dump(sizes, open(os.path.join(BASE, 'apk-sizes.json'), 'w'), indent=1)
    site = {'no_flash': load('site/site-no-flash.json'), 'lang': load('site/site-lang-checks.json'), 'toggle': load('site/site-toggle.json')}
    md = ['# r15 E2E (0.15.0) — Pixel 6, before (b786e93, 0.14.1) and after', '',
          f'After: {ok}/{n} rows pass. Before rows: {sum(1 for r in before.get("rows", []) if r["ok"])}/{len(before.get("rows", []))}.', '',
          '## Scenes (before | after)', '', '| scene | lang | before | after | note |', '|---|---|---|---|---|']
    for k in order:
        b, a = scenes[k].get('before'), scenes[k].get('after')
        note = (a or b)['note']
        md.append(f"| {k[0]} | {k[1]} | {('[' + b['file'] + '](' + b['file'] + ')') if b else '—'} | {('[' + a['file'] + '](' + a['file'] + ')') if a else '—'} | {note} |")
    others = []
    for name, rel in (('r14b (both languages)', 'r14b/r14b.json'), ('r14b index', 'r14b/index.md'), ('r14', 'r14/after/r14-after.json')):
        d = load(rel) if rel.endswith('.json') else None
        if os.path.exists(os.path.join(BASE, rel)):
            summary = ''
            if d is not None:
                rr = d.get('rows', [])
                summary = f" — {sum(1 for r in rr if r.get('ok'))}/{len(rr)} rows, apk {d.get('apk', '?')}"
            others.append(f'- {name}: [{rel}]({rel}){summary}')
    md += ['', '## The r14 and r14b suites on the 0.15.0 build', ''] + (others or ['- not run']) + [
        '', 'r14b ran in full on b979499 (118/118); its K and L sections (the keyboard and large font, where the quick panel',
        'shows) were re-run on 7f2c027 and merged. r14 ran on 7f2c027. The main-source diff b979499..7f2c027 is three quick-panel files.']
    md += ['', '## Rows', '', '| ok | label | lang | section | check | detail |', '|---|---|---|---|---|---|']
    for r in rows:
        md.append(f"| {'PASS' if r['ok'] else 'FAIL'} | {r.get('label', '')} | {r['lang']} | {r['section']} | {r['check']} | {r['detail'][:160].replace('|', '/')} |")
    md += ['', '## Metrics (after)', '', '```json', json.dumps(after.get('metrics', {}), ensure_ascii=False, indent=1)[:20000], '```', '',
           '## Metrics (before)', '', '```json', json.dumps(before.get('metrics', {}), ensure_ascii=False, indent=1)[:8000], '```', '',
           '## APK', '', '```json', json.dumps(sizes, indent=1), '```', '',
           '## Site (?lang=en), headless Chrome 390 / 1440 px', '']
    for f in sorted(os.listdir(os.path.join(BASE, 'site'))) if os.path.isdir(os.path.join(BASE, 'site')) else []:
        if f.endswith('.png'):
            md.append(f'- [site/{f}](site/{f})')
    md += ['', '```json', json.dumps(site, indent=1)[:6000], '```', '']
    open(os.path.join(BASE, 'index.md'), 'w', encoding='utf-8').write('\n'.join(md))
    # html: the pairs side by side
    h = ['<!doctype html><meta charset="utf-8"><title>r15 E2E</title><style>body{font:14px system-ui;background:#111;color:#ddd;margin:16px}'
         'td{vertical-align:top;padding:6px}img{width:300px;border:1px solid #333}.ok{color:#5e5}.bad{color:#f66}a{color:#9cf}</style>',
         f'<h1>r15 E2E (0.15.0) — Pixel 6</h1><p>After: {ok}/{n} rows pass.</p><table><tr><th>scene</th><th>before</th><th>after</th></tr>']
    for k in order:
        b, a = scenes[k].get('before'), scenes[k].get('after')
        cell = lambda s: ('<a href="%s"><img src="%s"></a>' % (s['file'], s['file']) if s['file'].endswith('.png') else '<a href="%s">%s</a>' % (s['file'], s['file'])) if s else '—'
        h.append(f'<tr><td><b>{html.escape(k[0])}</b><br>{k[1]}<br>{html.escape((a or b)["note"])}</td><td>{cell(b)}</td><td>{cell(a)}</td></tr>')
    h.append('</table><h2>Rows</h2><table>')
    for r in rows:
        h.append(f"<tr><td class={'ok' if r['ok'] else 'bad'}>{'PASS' if r['ok'] else 'FAIL'}</td><td>{r.get('label', '')}</td><td>{r['lang']}</td><td>{r['section']}</td><td>{html.escape(r['check'])}</td><td>{html.escape(r['detail'][:300])}</td></tr>")
    h.append('</table><h2>Site</h2>')
    for f in sorted(os.listdir(os.path.join(BASE, 'site'))) if os.path.isdir(os.path.join(BASE, 'site')) else []:
        if f.endswith('.png'):
            h.append(f'<a href="site/{f}"><img src="site/{f}" title="{f}"></a>')
    open(os.path.join(BASE, 'index.html'), 'w', encoding='utf-8').write('\n'.join(h))
    print(f'index: {len(order)} scene pairs, after {ok}/{n} rows → {os.path.join(BASE, "index.md")}')


if __name__ == '__main__':
    main()
