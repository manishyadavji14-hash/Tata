# Builds 6 practice papers + concept notes from tools/nonstop500_data.txt
# Run: python3 tools/nonstop500.py
import os, random
os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SRC = 'UP SUPERTET 2026 NONSTOP 500 Final Revision – रोहित वैद्यवान सर (YouTube: https://youtu.be/aTkCc1ZFtjc)'
parts, gi = [], 0
for raw in open('tools/nonstop500_data.txt', encoding='utf8'):
    line = raw.rstrip('\n').strip()
    if not line or line.startswith('# Source') or line.startswith('# Format') or line.startswith('# Run'):
        continue
    if line.startswith('### '): parts[-1]['topics'][-1]['groups'].append({'name': line[4:], 'qs': []}); continue
    if line.startswith('## '): parts[-1]['topics'].append({'name': line[3:], 'groups': []}); continue
    if line.startswith('# '): parts.append({'name': line[2:], 'topics': []}); continue
    f = line.split('|')
    assert len(f) in (5, 6), line
    stem, ans, wrong, expl = f[0], f[1], f[2:5], (f[5] if len(f) == 6 else '').strip()
    opts = [ans] + wrong
    assert len(set(o.strip() for o in opts)) == 4 and all(o.strip() for o in opts), line
    gi += 1
    random.Random(1000 + gi).shuffle(opts)
    parts[-1]['topics'][-1]['groups'][-1]['qs'].append((stem, ans, opts, expl))
L = 'abcd'
total = 0
for pi, p in enumerate(parts, 1):
    qs = [(t['name'], g['name'], q) for t in p['topics'] for g in t['groups'] for q in g['qs']]
    n = len(qs); total += n
    out = [f'@id rohit_nonstop500_p{pi}',
           f'@title NONSTOP 500 प्रैक्टिस सेट {pi}/{len(parts)} – {p["name"]} ({n} प्रश्न, रोहित वैद्यवान सर)',
           f'@duration {n}', '@pos 1', '@neg 0',
           f'@instr {n} प्रश्न, प्रत्येक 1 अंक; नकारात्मक अंकन नहीं। प्रश्न "{SRC}" वीडियो से लिए गए हैं; सभी उत्तर मानक स्रोतों से जाँचे गए हैं और वीडियो की त्रुटियाँ "सुधार" के साथ ठीक की गई हैं।',
           '@key ' + ''.join(L[q[2].index(q[1])].upper() for _, _, q in qs), '']
    cur = None
    for i, (tn, gn, (stem, ans, opts, expl)) in enumerate(qs, 1):
        if tn != cur: out.append(f'# {tn}'); cur = tn
        sol = f'उत्तर: ({L[opts.index(ans)]}) {ans}।' + (f' {expl}' if expl else '')
        out += [f'{i} {gn}', f'? {stem}'] + [f'- {o}' for o in opts] + [f'= {sol}', f'~ {tn} – {gn}']
    open(f'papers/nonstop500_part{pi}.txt', 'w', encoding='utf8').write('\n'.join(out) + '\n')
    print(f'papers/nonstop500_part{pi}.txt', n)
print('total', total)

# ---- concept notes ----
def short(s, n=46):
    return s if len(s) <= n else s[:n - 1].rstrip() + '…'
import re
def label(stem):
    m = re.search(r'"([^"]+)"', stem) or re.search(r'\(([^()]*[A-Za-z][^()]*)\)', stem)
    if m: return short(m.group(1), 32)
    return short(' '.join(stem.rstrip('?').split()[:4]), 32)
N = ['@title NONSTOP 500 फाइनल रिवीज़न (UP SUPERTET 2026): CDP, शिक्षाशास्त्र, जीवन कौशल – प्रश्न-उत्तर नोट्स (आरेख सहित)',
     f'@source {SRC} – सभी उत्तर क्रॉस-चेक; "सुधार" वाले बिंदु वीडियो में बोली गई त्रुटियों का संशोधन हैं',
     '@id rohit_nonstop500', '']
for p in parts:
    N += [f'# {p["name"]}', '']
    for t in p['topics']:
        N += [f'## {t["name"]}', '### 🧠 एक नज़र में', '@tree', t['name']]
        for g in t['groups']:
            N.append(f'  {g["name"]}')
            seen = []
            for stem, ans, _, _ in g['qs']:
                a = f'{label(stem)}: {short(ans, 30)}'
                if a not in seen: seen.append(a)
            for a in seen[:6]: N.append(f'    {a}')
            if len(seen) > 6: N.append(f'    … और {len(seen) - 6}')
        N += ['@end', '']
        for g in t['groups']:
            N += [f'### {g["name"]}', '|प्रश्न|उत्तर|']
            notes = []
            for stem, ans, _, expl in g['qs']:
                N.append(f'|{stem}|{ans}|')
                if expl: notes.append(('! ' if 'सुधार' in expl else '- ') + f'{short(ans, 40)}: {expl}')
            N += notes + ['']
open('papers/concepts/nonstop500_revision.txt', 'w', encoding='utf8').write('\n'.join(N) + '\n')
print('papers/concepts/nonstop500_revision.txt', sum(len(t['groups']) for p in parts for t in p['topics']), 'concept cards')
