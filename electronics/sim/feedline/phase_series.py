#!/usr/bin/env python3
"""Rewrite the phase animation (.pvd series) of a conductor_current.py output without recomputing anything.

    python3 phase_series.py <cc_dir> [--phases=100] [--cycles=5] [--freqs=0.725,1.75]

Every .pvd step points at the same K_<layer>_f=<Hz>.vtr / via_f=<Hz>.vtp (the view forms Re(K e^{j phase}) from the
stored complex amplitude), so more frames or more periods only need new .pvd files: <phases> steps spanning
<cycles> RF periods (time = phase in degrees, 0 .. 360*cycles). Records phases/cycles in meta.json.
"""
import json, re, sys
from pathlib import Path
import numpy as np

d = Path(sys.argv[1])
if not (d / 'meta.json').exists() and (d / 'cc' / 'meta.json').exists(): d = d / 'cc'
opts = dict(a[2:].split('=', 1) for a in sys.argv[2:] if a.startswith('--'))
nph, cyc = int(opts.get('phases', 100)), float(opts.get('cycles', 1))
only = [float(v) * 1e9 for v in opts['freqs'].split(',')] if 'freqs' in opts else None
phases = np.arange(nph) * 360.0 * cyc / nph
n = 0
for pvd in sorted(d.glob('*_f=*.pvd')):
    f = float(re.search(r'_f=(\d+)\.pvd$', pvd.name).group(1))
    if only and min(abs(f - o) for o in only) > 1e6: continue
    data = pvd.name[:-4] + ('.vtp' if pvd.name.startswith('via_') else '.vtr')
    pvd.write_text('<?xml version="1.0"?>\n<VTKFile type="Collection" version="0.1">\n<Collection>\n'
                   + ''.join(f'<DataSet timestep="{p:g}" file="{data}"/>\n' for p in phases) + '</Collection>\n</VTKFile>\n')
    n += 1
meta = json.load(open(d / 'meta.json')); meta['cycles'] = cyc; meta['phases'] = nph
json.dump(meta, open(d / 'meta.json', 'w'), indent=1)
print(f'{n} .pvd files rewritten: {nph} steps over {cyc:g} cycles ({360 * cyc / nph:g} deg per frame) in {d}')
