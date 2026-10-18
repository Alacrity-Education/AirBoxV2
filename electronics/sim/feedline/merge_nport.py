#!/usr/bin/env python3
"""Merge S.npz files from sim_pi.py runs that each simulated a subset of the ports (e.g. on different machines).

    python merge_nport.py <variant>/<out> S_other.npz [S_more.npz ...]

Columns that are finite in any input are copied into <variant>/<out>/S.npz (port names must match); when every
column is present, <variant>/<out>/feed_pi.s<N>p is written. Needs numpy + scikit-rf.
"""
import os, sys
import numpy as np

out = sys.argv[1].rstrip('/')
main = os.path.join(out, 'S.npz')
srcs = ([main] if os.path.exists(main) else []) + sys.argv[2:]
S = names = f = None
for p in srcs:
    d = np.load(p, allow_pickle=True)
    if S is None:
        S, names, f = d['S'].copy(), list(d['names']), d['f']
        continue
    if list(d['names']) != names or d['S'].shape != S.shape: sys.exit(f'{p}: port list or shape differs')
    for k in range(S.shape[1]):
        if np.all(np.isfinite(d['S'][:, k])) and not np.all(np.isfinite(S[:, k])): S[:, k] = d['S'][:, k]
done = [k for k in range(S.shape[1]) if np.all(np.isfinite(S[:, k]))]
np.savez(main, f=f, S=S, names=names)
print('columns present:', done, 'of', S.shape[1])
if len(done) == S.shape[1]:
    import skrf as rf
    n = rf.Network(frequency=rf.Frequency.from_f(f, unit='Hz'), s=S.transpose(2, 0, 1), z0=50, name='feed_pi')
    n.write_touchstone(os.path.join(out, 'feed_pi'))
    print('wrote', os.path.join(out, 'feed_pi.s%dp' % S.shape[1]))
