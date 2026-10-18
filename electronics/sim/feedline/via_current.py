#!/usr/bin/env python3
"""Vertical current in each via from the mid-prepreg and mid-core H planes of a sim_pi.py --hdump run (Ampere loop).

    python via_current.py pi_void_neck/nport_h2/run_0 pi_void_neck/fab/<board>-PTH.drl

For every plated hole in the drill file and every frequency/phase file of h_pp (F.Cu-In1 segment) and h_core (In1-In2
segment), integrates H around a circle of radius R_RING around the via: I = loop integral H.dl (positive = current
flowing up, +z). Writes <run_dir>/via_<plane>_f=<Hz>_abs.vtp (|I| from the complex field) and _p=<deg>.vtp (signed,
instantaneous) with point vectors Kvec = (0, 0, I / (2 pi r_barrel)) and scalar K = |Kvec|, both x1000 so they sit on
the same relative scale as the plane arrows in paraview_return.py (whose transform scales the H vectors by 1000).
Needs numpy + vtk (the gerber2ems container's python has both).
"""
import glob, math, os, re, sys
import numpy as np
from vtkmodules.vtkIOXML import vtkXMLRectilinearGridReader, vtkXMLPolyDataWriter
from vtkmodules.vtkCommonDataModel import vtkPolyData, vtkCellArray
from vtkmodules.vtkCommonCore import vtkPoints
from vtkmodules.util.numpy_support import vtk_to_numpy, numpy_to_vtk

run, drl = sys.argv[1], sys.argv[2]
ONLY_F = [int(round(float(v) * 1e9)) for v in os.environ.get('FREQS', '').split(',') if v]   # FREQS=0.75 limits the frequencies (GHz)
R_RING, R_BARREL, N = 0.30e-3, 0.15e-3, 48
SCALE = 1000.0

vias = []
for line in open(drl):
    m = re.match(r'X(-?[\d.]+)Y(-?[\d.]+)', line.strip())
    if m: vias.append((float(m.group(1)) * 1e-3, float(m.group(2)) * 1e-3))
vias = np.array(vias)
print(len(vias), 'vias')

def read(fn):
    r = vtkXMLRectilinearGridReader(); r.SetFileName(fn); r.Update(); g = r.GetOutput()
    x = vtk_to_numpy(g.GetXCoordinates()); y = vtk_to_numpy(g.GetYCoordinates()); z = vtk_to_numpy(g.GetZCoordinates())
    H = vtk_to_numpy(g.GetPointData().GetArray('H-Field')).reshape(len(z), len(y), len(x), 3)[0]
    return x, y, float(z[0]), H

def interp(x, y, F, px, py):
    i = np.clip(np.searchsorted(x, px) - 1, 0, len(x) - 2); j = np.clip(np.searchsorted(y, py) - 1, 0, len(y) - 2)
    tx = (px - x[i]) / (x[i + 1] - x[i]); ty = (py - y[j]) / (y[j + 1] - y[j])
    return ((1 - tx) * (1 - ty))[..., None] * F[j, i] + (tx * (1 - ty))[..., None] * F[j, i + 1] + \
           ((1 - tx) * ty)[..., None] * F[j + 1, i] + (tx * ty)[..., None] * F[j + 1, i + 1]

phi = np.linspace(0, 2 * np.pi, N, endpoint=False)
def loop_current(x, y, H):
    px = vias[:, 0:1] + R_RING * np.cos(phi); py = vias[:, 1:2] + R_RING * np.sin(phi)
    h = interp(x, y, H, px, py)                                     # (nvia, N, 3)
    hphi = -h[..., 0] * np.sin(phi) + h[..., 1] * np.cos(phi)
    return hphi.sum(axis=1) * R_RING * (2 * np.pi / N)              # A (relative), + = up

def write(fn, z, I):
    K = I / (2 * np.pi * R_BARREL) * SCALE
    pts = vtkPoints()
    for (vx, vy) in vias: pts.InsertNextPoint(vx * 1e3, vy * 1e3, z * 1e3)   # mm, same frame as the views
    verts = vtkCellArray()
    for i in range(len(vias)): verts.InsertNextCell(1); verts.InsertCellPoint(i)
    pd = vtkPolyData(); pd.SetPoints(pts); pd.SetVerts(verts)
    vec = np.zeros((len(vias), 3)); vec[:, 2] = np.real(K)
    a = numpy_to_vtk(vec, deep=1); a.SetName('Kvec'); pd.GetPointData().AddArray(a)
    s = numpy_to_vtk(np.abs(K).astype(float), deep=1); s.SetName('K'); pd.GetPointData().AddArray(s)
    w = vtkXMLPolyDataWriter(); w.SetFileName(fn); w.SetInputData(pd); w.Write()

for plane in ('h_pp', 'h_core'):
    freqs = sorted({int(m.group(1)) for f in glob.glob(f'{run}/{plane}_f=*_abs.vtr') for m in [re.search(r'f=(\d+)_', f)]})
    if ONLY_F: freqs = [f for f in freqs if f in ONLY_F]
    for fr in freqs:
        x, y, z, Ha = read(f'{run}/{plane}_f={fr}_abs.vtr'); _, _, _, Hg = read(f'{run}/{plane}_f={fr}_arg.vtr')
        Ic = loop_current(x, y, Ha * np.exp(1j * Hg))                # complex: |I| is the steady-state amplitude
        write(f'{run}/via_{plane}_f={fr}_abs.vtp', z, np.abs(Ic))
        for pf in sorted(glob.glob(f'{run}/{plane}_f={fr}_p=*.vtr')):
            x, y, z, Hp = read(pf)
            write(f'{run}/via_{plane}_f={fr}_p={pf.rsplit("_p=", 1)[1][:-4]}.vtp', z, loop_current(x, y, Hp))
        top = np.argsort(-np.abs(Ic))[:4]
        print(f'{plane} {fr/1e9:g} GHz z={z*1e6:.0f} um: largest |I| at', [(round(vias[i,0]*1e3, 2), round(vias[i,1]*1e3, 2), f'{abs(Ic[i]):.2e}') for i in top])
