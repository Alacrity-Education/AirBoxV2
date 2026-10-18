#!/usr/bin/env python3
"""Connect the simulated feed + matching-land copper (10-port, from sim_pi.py) with the L/C values and the
measured antenna, and report the match seen by the modem.

    python analyze_pi.py [variant=pi] [L5=24n C5=2.4p L6=1n C8=0.3p]   (use - for not fitted)

Port order (see <variant>/nport/ports.json): 0 modem, 1 antenna (pad1-pad2), 2 C5.1 (ANT_MID side),
3 C5.2 (ANT_FEED side), 4 L6.1 (RF side), 5 L6.2 (ANT_MID side), 6 L5.1, 7 C8.1 (feed-side land of each shunt
part; the other land is ground copper; the DNI lands C6/C7 are floating copper in the EM model). Needs numpy + scikit-rf.
"""
import os, sys, glob, json
import numpy as np
import skrf as rf
from skrf.media import DefinedGammaZ0
from skrf.circuit import Circuit

HERE = os.path.dirname(os.path.abspath(__file__))
variant = next((a for a in sys.argv[1:] if '=' not in a), 'pi')
vals = dict(L5=24e-9, C5=2.4e-12, L6=1.0e-9, C8=0.3e-12)
def parse(v):
    if v in ('-', 'DNI', 'none', 'open'): return None
    m = {'p': 1e-12, 'n': 1e-9, 'u': 1e-6}; return float(v[:-1]) * m[v[-1]] if v[-1] in m else float(v)
vals.update(C6=None, C7=None)
for a in sys.argv[1:]:
    if '=' in a and not a.startswith('out='): k, v = a.split('='); vals[k] = parse(v)
BANDS = [('B28/12', 703, 748), ('B5', 824, 849), ('B8', 880, 915), ('B3', 1710, 1785), ('B2', 1850, 1910), ('B1', 1920, 1980), ('B7', 2500, 2570), ('n78', 3400, 3600)]
db = lambda x: 20 * np.log10(np.abs(x))

sub = next((a.split('=')[1] for a in sys.argv[1:] if a.startswith('out=')), 'nport')   # out=<folder> under the variant (default nport)
em = rf.Network(glob.glob(os.path.join(HERE, variant, sub, 'feed_pi.s*p'))[0])
pinfo = json.load(open(os.path.join(HERE, variant, sub, 'ports.json')))
GAP = any(p.get('gap') for p in pinfo)            # sim_pi.py --gap=1: one port per part, terminated by the part's 1-port impedance
pidx = {p['name']: p['index'] for p in pinfo}
em = em['0.65-4.0GHz']
fr = em.frequency
ant = rf.Network(os.path.join(HERE, 'sparam', 'Ant_P822601_S_parameters.s1p')).interpolate(fr); ant.name = 'ant'
med = DefinedGammaZ0(frequency=fr, z0=50)

def match(L5, C5, L6, C8, C6=None, C7=None):
    """Return the 1-port network seen by the modem (port 0) with the parts connected to the EM copper."""
    modem = Circuit.Port(fr, name='modem', z0=50)
    if GAP:
        def one(name, kind, val):
            if val is None: n = med.open()
            else: n = (med.inductor(val) if kind == 'L' else med.capacitor(val)) ** med.short()
            n.name = name; return n
        cnx = [[(modem, 0), (em, 0)], [(em, pidx['antenna']), (ant, 0)]]
        for name, kind, val in (('C5', 'C', C5), ('L6', 'L', L6), ('L5', 'L', L5), ('C8', 'C', C8), ('C6', 'C', C6), ('C7', 'C', C7)):
            if name in pidx: cnx.append([(em, pidx[name]), (one(name, kind, val), 0)])
        return Circuit(cnx).network
    gnd = Circuit.Ground(fr, name='gnd')
    def L(name, val): n = med.inductor(val); n.name = name; return n
    def C(name, val): n = med.capacitor(val); n.name = name; return n
    def op(name): n = med.open(); n.name = name; return n
    c5, l6 = C('C5', C5), L('L6', L6)
    cnx = [[(modem, 0), (em, 0)], [(em, 1), (ant, 0)],
           [(em, 2), (c5, 0)], [(em, 3), (c5, 1)],
           [(em, 4), (l6, 0)], [(em, 5), (l6, 1)]]
    shunts = []
    for idx, name, val, kind in ((6, 'L5', L5, 'L'), (7, 'C8', C8, 'C')):
        if val is None:
            cnx.append([(em, idx), (op('open_' + name), 0)])
        else:
            n = L(name, val) if kind == 'L' else C(name, val); shunts.append(n)
            cnx.append([(em, idx), (n, 0)])
    cnx.append([(n, 1) for n in shunts] + [(gnd, 0)])
    return Circuit(cnx).network

def worst(g): return [db(g[(fr.f >= lo * 1e6) & (fr.f <= hi * 1e6)]).max() for _, lo, hi in BANDS]
def row(name, g): print(f'{name:44s}', ' '.join(f'{v:7.1f}' for v in worst(g)))

print(f'EM model: {variant}/{sub} ({em.nports} ports, {"gap" if GAP else "land-to-GND1"} ports). Worst |S11| at the modem per band, dB')
print(f'{"":44s}', ' '.join(f'{n:>7s}' for n, _, _ in BANDS))
row('antenna alone (no feed, no matching)', ant.s[:, 0, 0])
row(f'feed+lands, L5={vals["L5"]*1e9 if vals["L5"] else 0:g}n C5={vals["C5"]*1e12:g}p L6={vals["L6"]*1e9:g}n C8={vals["C8"]*1e12 if vals["C8"] else 0:g}p C6={vals["C6"]*1e12 if vals["C6"] else 0:g}p C7={vals["C7"]*1e12 if vals["C7"] else 0:g}p', match(**vals).s[:, 0, 0])
if all('=' not in a or a.startswith('out=') for a in sys.argv[1:]):
    row('feed+lands, appendix-1: 24n/2.4p/4.7n/-', match(24e-9, 2.4e-12, 4.7e-9, None).s[:, 0, 0])
    row('feed+lands, 22n/2.4p/3.3n/-', match(22e-9, 2.4e-12, 3.3e-9, None).s[:, 0, 0])
    row('feed+lands, 0R jumpers only (C5,L6 short)', match(None, 1e-3, 1e-12, None).s[:, 0, 0])
    # small search over E12-ish values for the best worst-case across the bands you use
    best = []
    for L5 in (15e-9, 18e-9, 22e-9, 27e-9, 33e-9, None):
        for C5 in (1.5e-12, 1.8e-12, 2.2e-12, 2.7e-12, 3.3e-12, 4.7e-12):
            for L6 in (1e-12, 1e-9, 1.5e-9, 2.2e-9, 3.3e-9, 4.7e-9):
                for C8 in (None, 0.3e-12, 0.5e-12, 0.8e-12, 1.0e-12):
                    w = worst(match(L5, C5, L6, C8).s[:, 0, 0]); best.append((max(w), np.mean(w), L5, C5, L6, C8))
    best.sort(key=lambda t: (t[0], t[1]))
    for mx, mn, L5, C5, L6, C8 in best[:5]:
        row(f'best: L5={L5*1e9 if L5 else 0:g}n C5={C5*1e12:g}p L6={L6*1e9:g}n C8={C8*1e12 if C8 else 0:g}p', match(L5, C5, L6, C8).s[:, 0, 0])
