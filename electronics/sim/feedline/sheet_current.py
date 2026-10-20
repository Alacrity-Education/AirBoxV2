#!/usr/bin/env python3
"""Total surface current on each copper layer from the H planes just above and just below it (sim_pi.py --hdump).

    python sheet_current.py <run_dir>

The copper layers are zero-thickness sheets in the model, so the current they carry (both faces together) is the jump
of tangential H across them: K = z x (H_above - H_below). For h_top / h_inner / h_in2 with their *_below partner this
writes <run_dir>/k_<plane>_f=<Hz>_abs.vtr (steady-state amplitude, from the complex field) and k_<plane>_f=<Hz>_p=<deg>.vtr
(instantaneous) on the 'above' plane's grid, with point arrays Kvec (Kx, Ky, 0) and K = |Kvec|, both x1000 so they use
the same relative scale as the other views (which scale the raw H vectors by 1000). Needs numpy + vtk.

Superseded for new runs by conductor_current.py (sim_pi.py --kdump): curl H on the sheet itself gives the exact
per-edge conductor current; this H-jump drops the Hz part of the loop, under-reads trace-edge current and leaks
~20 A/m onto the nodes beside a trace (HANDOFF section 6e). Kept for the older --hdump runs.
"""
import glob, os, re, sys
import numpy as np
from vtkmodules.vtkIOXML import vtkXMLRectilinearGridReader, vtkXMLRectilinearGridWriter
from vtkmodules.vtkCommonDataModel import vtkRectilinearGrid
from vtkmodules.util.numpy_support import vtk_to_numpy, numpy_to_vtk

run = sys.argv[1]
ONLY_F = [int(round(float(v) * 1e9)) for v in os.environ.get('FREQS', '').split(',') if v]   # FREQS=0.75 limits the frequencies (GHz)
SCALE = 1.0   # raw H units; paraview_return.py scales all vectors x1000 in its mm transform

def read(fn):
    r = vtkXMLRectilinearGridReader(); r.SetFileName(fn); r.Update(); g = r.GetOutput()
    H = vtk_to_numpy(g.GetPointData().GetArray('H-Field')).astype(float)
    return g, H

def write(fn, grid, kvec, kmag):
    g = vtkRectilinearGrid(); g.CopyStructure(grid)
    a = numpy_to_vtk(np.ascontiguousarray(kvec), deep=1); a.SetName('Kvec'); g.GetPointData().AddArray(a)
    s = numpy_to_vtk(np.ascontiguousarray(kmag), deep=1); s.SetName('K'); g.GetPointData().AddArray(s)
    w = vtkXMLRectilinearGridWriter(); w.SetFileName(fn); w.SetInputData(g); w.Write()

def zcross(dH):   # z x (dHx, dHy, dHz) = (-dHy, dHx, 0)
    k = np.zeros_like(dH); k[:, 0] = -dH[:, 1]; k[:, 1] = dH[:, 0]; return k

for plane in ('h_top', 'h_inner', 'h_in2'):
    below = plane + '_below'
    freqs = sorted({int(re.search(r'f=(\d+)_', f).group(1)) for f in glob.glob(f'{run}/{below}_f=*_abs.vtr')})
    if ONLY_F: freqs = [f for f in freqs if f in ONLY_F]
    for fr in freqs:
        ga, Aa = read(f'{run}/{plane}_f={fr}_abs.vtr'); _, Ag = read(f'{run}/{plane}_f={fr}_arg.vtr')
        gb, Ba = read(f'{run}/{below}_f={fr}_abs.vtr'); _, Bg = read(f'{run}/{below}_f={fr}_arg.vtr')
        if Aa.shape != Ba.shape: sys.exit(f'{plane}/{below}: grids differ {Aa.shape} {Ba.shape}')
        dH = Aa * np.exp(1j * Ag) - Ba * np.exp(1j * Bg)
        kc = zcross(dH) * SCALE
        kmag = np.sqrt(np.abs(kc[:, 0]) ** 2 + np.abs(kc[:, 1]) ** 2)
        write(f'{run}/k_{plane}_f={fr}_abs.vtr', ga, np.abs(kc), kmag)
        n = 0
        for pa in sorted(glob.glob(f'{run}/{plane}_f={fr}_p=*.vtr')):
            pb = pa.replace(f'/{plane}_f=', f'/{below}_f=')
            _, Ha = read(pa); _, Hb = read(pb)
            k = zcross(Ha - Hb) * SCALE
            write(f'{run}/k_{plane}_f={fr}_p={pa.rsplit("_p=", 1)[1]}', ga, k, np.hypot(k[:, 0], k[:, 1])); n += 1
        top_only = np.hypot(*(zcross(Aa * np.exp(1j * Ag)) * SCALE)[:, :2].T.__abs__())
        print(f'{plane} {fr/1e9:g} GHz: {n} phases; 99.9th pct |K| total {np.percentile(kmag, 99.9):.3e} vs above-only {np.percentile(top_only, 99.9):.3e}')
