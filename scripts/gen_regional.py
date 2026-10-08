#!/usr/bin/env python3
"""✦ Resyst VK — r10 regional Spanish re-rank rules (bet 4, F-2).

Writes app/src/main/assets/lexicon/regional/es-419.txt from:
  1. curated Latin American ⇄ Spain pairs: the LatAm word takes the peninsular word's rank
     (never lower than its own — Regional.parse);
  2. every vosotros form found in es.txt (-áis/-éis/-íais conjugations, -ad/-ed/-id imperatives
     whose infinitive is a word, enclitic imperatives -adme/-aos…, vuestro/os): demoted to the tail;
  3. peninsular words Latin America doesn't write: demoted to the tail.
es-CL.txt is hand-curated (Chilean everyday + digital Spanish); this script only counts it.

Rules never delete a word: a demoted word stays known (typed exactly it is never "corrected").
Curated in the repo, never learned from users. Format: see core/Regional.kt.

Usage: scripts/gen_regional.py   (idempotent; prints rule counts)
"""
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
LEX = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'lexicon')
OUT = os.path.join(LEX, 'regional')

# latam word = peninsular word (the latam word lands where the peninsular one was)
PAIRS = [
    ('carro', 'coche'), ('auto', 'coches'), ('autos', 'coches'), ('computadora', 'ordenador'),
    ('computador', 'ordenadores'), ('celular', 'móvil'), ('celulares', 'móviles'), ('jugo', 'zumo'),
    ('lentes', 'gafas'), ('anteojos', 'gafas'), ('durazno', 'melocotón'), ('estacionar', 'aparcar'),
    ('estacionamiento', 'aparcamiento'), ('mesero', 'camarero'), ('mesera', 'camarera'),
    ('refrigerador', 'frigorífico'), ('suéter', 'jersey'), ('encendedor', 'mechero'),
    ('torta', 'tarta'), ('frijoles', 'judías'), ('fósforo', 'cerilla'), ('enojado', 'enfadado'),
    ('enojada', 'enfadada'), ('enojo', 'enfado'), ('enojar', 'enfadar'), ('boleto', 'billete'),
    ('lindo', 'bonito'), ('linda', 'bonita'), ('manejar', 'conducir'), ('plata', 'pasta'),
    ('papas', 'patatas'), ('departamento', 'piso'), ('arveja', 'guisante'), ('arvejas', 'guisantes'),
    ('palta', 'aguacate'), ('poroto', 'judía'), ('porotos', 'alubias'), ('choclo', 'maíz'),
    ('maní', 'cacahuete'), ('buzo', 'chándal'), ('living', 'salón'), ('closet', 'armario'),
    ('bus', 'autobús'), ('pasaje', 'billete'), ('vereda', 'acera'), ('cuadra', 'manzana'),
    ('rentar', 'alquilar'), ('arriendo', 'alquiler'), ('arrendar', 'alquilar'), ('jalar', 'tirar'),
    ('flojo', 'vago'), ('pararse', 'levantarse'), ('demorar', 'tardar'), ('demora', 'tarda'),
    ('ahorita', 'enseguida'), ('acá', 'aquí'), ('allá', 'allí'), ('nomás', 'solamente'),
    ('tomar', 'coger'), ('agarrar', 'coger'), ('chao', 'adiós'), ('chau', 'adiós'),
    ('genial', 'guay'), ('bacán', 'guay'), ('chévere', 'mola'), ('plomero', 'fontanero'),
    ('gasfíter', 'fontanero'), ('heladera', 'nevera'), ('licencia', 'carné'), ('carnet', 'carné'),
    ('computación', 'informática'), ('ustedes', 'vosotros'), ('su', 'vuestro'), ('sus', 'vuestros'),
    ('están', 'estáis'), ('tienen', 'tenéis'), ('saben', 'sabéis'), ('pueden', 'podéis'),
    ('quieren', 'queréis'), ('hacen', 'hacéis'), ('son', 'sois'), ('vamos', 'vais'),
]

# peninsular words / slang a Latin American keyboard should not volunteer (still known)
DEMOTE = [
    'coche', 'coches', 'ordenador', 'ordenadores', 'móvil', 'móviles', 'zumo', 'gafas', 'patata',
    'patatas', 'melocotón', 'aparcar', 'aparcamiento', 'camarero', 'camarera', 'frigorífico',
    'jersey', 'mechero', 'tarta', 'judías', 'cerilla', 'enfadado', 'enfadada', 'enfado', 'enfadar',
    'guay', 'mola', 'molar', 'flipar', 'flipa', 'currar', 'curro', 'chaval', 'chavala', 'chavales',
    'majo', 'maja', 'tronco', 'mogollón', 'ostras', 'hostia', 'hostias', 'gilipollas', 'gilipollez',
    'cabreado', 'cabrear', 'coger', 'cogeré', 'cogido', 'coge', 'guisante', 'guisantes', 'alubias',
    'cacahuete', 'cacahuetes', 'chándal', 'cazadora', 'fontanero', 'carné',
    'vosotros', 'vosotras', 'vuestro', 'vuestra', 'vuestros', 'vuestras', 'os', 'sois', 'vais',
]

KEEP = {'dieciséis', 'veintiséis', 'merced', 'intimidad', 'red', 'sed', 'pared', 'césped', 'huésped'}


def vosotros_forms(words):
    s = set(words)
    out = []
    for w in words:
        if w in KEEP or len(w) < 4:
            continue
        if re.search(r'(áis|éis|íais|abais|asteis|isteis)$', w):
            out.append(w)
            continue
        if len(w) < 5:
            continue
        # imperatives (mirad, venid, haced) and enclitic ones (dejadme, decidle): infinitive is a word
        m = re.match(r'^(.+?)([aei])d(me|le|les|lo|la|los|las|nos)?$', w)
        if m and (m.group(1) + m.group(2) + 'r') in s:
            out.append(w)
            continue
        # reflexive imperatives (sentaos, moveos) — but not noun plurals (correos ← correo)
        m = re.match(r'^(.+?)([aeí])os$', w)
        if m and (m.group(1) + m.group(2).replace('í', 'i') + 'r') in s and w[:-1] not in s:
            out.append(w)
    return out


def main():
    words = [l.strip() for l in open(os.path.join(LEX, 'es.txt'), encoding='utf-8') if l.strip()]
    s = set(words)
    lines = ['# ✦ es-419 re-rank rules — generated by scripts/gen_regional.py (do not edit by hand)',
             '# "a = b": a takes b\'s rank (never lower than its own) · "-w": w goes to the tail (still known)']
    seen = []
    for a, b in PAIRS:
        if b in s and a != b and (a, b) not in seen:
            seen.append((a, b))
            lines.append(f'{a} = {b}')
    promoted = {a for a, _ in seen}
    demote = []
    for w in DEMOTE + vosotros_forms(words):
        if w in s and w not in demote and w not in promoted:
            demote.append(w)
    lines += ['-' + w for w in demote]
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'es-419.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    cl = os.path.join(OUT, 'es-CL.txt')
    n_cl = sum(1 for l in open(cl, encoding='utf-8') if l.split('#')[0].strip()) if os.path.exists(cl) else 0
    print(f'es-419: {len(seen)} pairs + {len(demote)} demotions = {len(seen) + len(demote)} rules; es-CL: {n_cl} rules')


if __name__ == '__main__':
    main()
