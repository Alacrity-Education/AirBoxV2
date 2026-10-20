#!/usr/bin/env python3
"""Real conductor current (whole copper depth) on every copper layer and in every via, from sim_pi.py --kdump runs.

    tools/py.sh conductor_current.py single <run_dir> [options]                 # one excitation, other ports 50 ohm
    tools/py.sh conductor_current.py super  <run_dir> <run_dir> ... [L5=22n C5=3.6p L6=2.2n C8=0.5p C6=- C7=-] [options]
    tools/py.sh conductor_current.py check  <run_dir> [options]                  # validation numbers (needs --hdump too)

    e.g. tools/py.sh conductor_current.py single board20n/nport_gap_p0/run_0 --freqs=0.725,1.75
         tools/py.sh conductor_current.py super board20n/nport_gap_p*/run_* L5=22n C5=3.6p L6=2.2n C8=0.5p C6=- C7=-

What it computes. Copper is an openEMS ConductingSheet: a zero-thickness sheet on one z mesh line whose conductivity
and thickness enter the E-edge update (skin effect included). --kdump dumps curl H (openEMS dump type 13, frequency
domain) ON that mesh plane. openEMS evaluates it per E edge as (loop integral of H around the edge's dual face) / (dual
face area), i.e. the total current crossing the dual face divided by its area. For an edge lying in the sheet the dual
face spans dz_dual = (z[k+1] - z[k-1]) / 2 in z, which encloses the whole sheet, so

    K = J_tangential(z_sheet) * dz_dual          (A/m: the current of the whole conductor depth per unit width)

is exact on the FDTD grid, both faces of the copper included. dz_dual comes from the SAME z mesh the solver used:
gerber2ems' geometry.xml z lines plus sim_pi.py's --mask change (moves/adds a line at each solder-mask surface;
reproduced in sim_zlines(), give --mask=0 for runs without --mask). Jz on the mid-dielectric planes (j_pp1 F.Cu-In1,
j_core In1-In2, j_pp2 In2-B.Cu) summed over the dual faces around a via (each node goes to its nearest drill hole,
within --via_r) is the via current: by Stokes on the dual grid that sum IS the H loop integral around the region.

Normalisation: the fields are divided by the incident wave uf_inc (V, 50 ohm reference, openEMS CalcPort) of the
excited port at that frequency, so `single` shows the currents for a 1 V incident wave at the excited port with every
other EM port terminated in 50 ohm (the raw openEMS situation; labelled so). `super` builds the S-matrix at the dump
frequencies from the runs' waves.npz, terminates the gap ports with the given parts (1-ports, as analyze_pi.py does),
the antenna port with the measured antenna (sparam/Ant_P822601_S_parameters.s1p) and drives port 0 (modem) with a
50 ohm source of 1 V incident wave, solves for the incident wave at every EM port and superposes the runs with those
weights (exactly: the run weights solve A c = a with A[:, k] = all ports' uf_inc in run k, so imperfect 50 ohm
terminations in the runs are accounted for). Fields are linear in the excitation, so this is the real current with
the matching network fitted, for any part values (no new EM run).

Outputs (coordinates in mm, sim frame; arrays float32), in <run_dir>/cc/ (single) or --out (super, default
<variant>/cc_super_<parts>/):
  K_<layer>_f=<Hz>.vtr          point arrays K = |K| amplitude (A/m, sqrt(|Kx|^2+|Ky|^2)), Kvec = Re K at phase 0,
                                Kre/Kim (complex parts), copper = copper coverage of the node's dual cell (0..1, from
                                the gerber2ems layer PNG), placed at the copper's top face z (KiCad PLY frame)
  K_<layer>_f=<Hz>.pvd          phase animation: --phases steps over one cycle, time = phase (deg), every step pointing
                                at the .vtr above; paraview_conductor.py forms Re((Kre + j Kim) exp(j phase)) on the fly
                                (--snapshots=1 also writes explicit K_<layer>_f=<Hz>_p=<deg>.vtr files: Kvec, Kt, K,
                                copper; ~8 MB each on the whole board, hence off by default)
  via_f=<Hz>.vtp/.pvd           one point per via and dielectric segment (mid height): I = |I| (A), Ire/Iim,
                                Ivec = (0, 0, Re I) (+ = up), segment 0 F.Cu-In1 / 1 In1-In2 / 2 In2-B.Cu, dia (mm)
  meta.json                     mode, label, frequencies, port waves/currents, phase of the feed current, part values
Display values are node-interpolated (Kx linearly along x between its two edge samples, Ky along y; the dump itself is
raw Yee-edge data: sim_pi.py uses dump mode 0 = no interpolation). Integrals (check mode) use the raw edge values.

Options: --freqs=0.75,1.8 (GHz; default all dumped), --phases=18, --snapshots=1, --crop=x0,x1,y0,y1 (mm, outputs only),
--mask=20 (solder-mask thickness in um used by the run, 0 = none; default 20 = the production setting),
--drl=<PTH.drl> (default <variant>/fab/*-PTH.drl), --via_r=0.35 (mm), --out=<dir>, --ant=<s1p>, --src=0 (driven port).
Needs numpy + vtk (+ scikit-rf/scipy for super): the gerber2ems container's python (tools/py.sh).
"""
import glob, json, os, re, sys
from builtins import max as builtins_max
from pathlib import Path
import numpy as np
from vtkmodules.vtkIOXML import vtkXMLRectilinearGridReader, vtkXMLRectilinearGridWriter, vtkXMLPolyDataWriter
from vtkmodules.vtkIOImage import vtkPNGReader
from vtkmodules.vtkCommonDataModel import vtkRectilinearGrid, vtkPolyData, vtkCellArray
from vtkmodules.vtkCommonCore import vtkPoints
from vtkmodules.util.numpy_support import vtk_to_numpy, numpy_to_vtk

HERE = Path(__file__).resolve().parent
UNIT = 1e-7                       # geometry.xml drawing unit (m)
Z_GND1 = -764                     # sim_pi.py: In1.Cu sheet (drawing units); In2 = Z_GND1 - 12650
# layer -> (kdump plane, gerber2ems PNG, display z in mm = copper top face in the KiCad PLY frame of export_copper3d.py)
LAYERS = {'F_Cu': ('j_F_Cu', 'F_Cu.png', 0.035), 'In1_Cu': ('j_In1_Cu', 'In1_Cu.png', -0.0814),
          'In2_Cu': ('j_In2_Cu', 'In2_Cu.png', -1.3616), 'B_Cu': ('j_B_Cu', 'B_Cu.png', -1.4882)}
SEGMENTS = [('j_pp1', -0.043), ('j_core', -0.729), ('j_pp2', -1.415)]   # via segments, display z (mm)

mode = sys.argv[1] if len(sys.argv) > 1 else ''
if mode not in ('single', 'super', 'check'): sys.exit(__doc__)
pos = [a for a in sys.argv[2:] if not a.startswith('--') and '=' not in a]
opts = dict(a[2:].split('=', 1) for a in sys.argv[2:] if a.startswith('--') and '=' in a)
parts_arg = dict(a.split('=', 1) for a in sys.argv[2:] if not a.startswith('--') and '=' in a)
runs = [Path(p).resolve() for p in pos]
if not runs: sys.exit('no run dir given')
variant_dir = runs[0].parents[1]
MASK_UM = float(opts.get('mask', 20))
NPH = int(opts.get('phases', 18))
SNAP = opts.get('snapshots', '0') == '1'
CROP = [float(v) for v in opts['crop'].split(',')] if 'crop' in opts else None
ONLY_F = [float(v) * 1e9 for v in opts['freqs'].split(',')] if 'freqs' in opts else None
VIA_R = float(opts.get('via_r', 0.35))

# ---------------------------------------------------------------- mesh (exactly as sim_pi.py built it)
def grid_lines(ax):
    xml = (variant_dir / 'ems' / 'geometry' / 'geometry.xml').read_text()
    m = re.search(r'<%sLines[^>]*>(.*?)</%sLines>' % (ax, ax), xml, re.S)
    return sorted({float(v) for v in m.group(1).split(',')})

def sim_zlines():
    """z mesh lines (drawing units) as in sim_pi.py build(): geometry.xml + the --mask line moves (keep in sync!)."""
    zl = grid_lines('Z')
    if MASK_UM > 0:
        stack = json.load(open(variant_dir / 'fab' / 'stackup.json'))['layers']
        ZB = -round(sum(l['thickness'] for l in stack if l['type'] in ('prepreg', 'core')) * 1e4)
        t_m = MASK_UM * 10
        for zt in (t_m, ZB - t_m):
            near = [z for z in zl if abs(z - zt) < t_m and abs(z) > 1 and abs(z - ZB) > 1]
            if near: zl.remove(min(near, key=lambda z: abs(z - zt)))
            zl.append(zt)
        zl = sorted(zl)
    return np.array(zl)

XL, YL, ZL = np.array(grid_lines('X')), np.array(grid_lines('Y')), sim_zlines()
def dual_w(lines):                       # openEMS node width (dual edge length) per primary line
    w = np.empty(len(lines)); w[1:-1] = (lines[2:] - lines[:-2]) / 2; w[0] = lines[1] - lines[0]; w[-1] = lines[-1] - lines[-2]
    return w
DXD, DYD = dual_w(XL) * UNIT, dual_w(YL) * UNIT       # m
def dz_dual(z_units):
    k = int(np.argmin(np.abs(ZL - z_units)))
    if abs(ZL[k] - z_units) > 1: raise SystemExit(f'plane z {z_units} not on the reconstructed z mesh (wrong --mask?)')
    return (ZL[k + 1] - ZL[k - 1]) / 2 * UNIT, k

def index_of(lines, coords_m, what):
    u = coords_m / UNIT; i = np.searchsorted(lines, u - 0.5); i = np.clip(i, 0, len(lines) - 1)
    if np.max(np.abs(lines[i] - u)) > 0.5: raise SystemExit(f'{what}: dump coordinates are not on the mesh lines')
    return i

# ---------------------------------------------------------------- dumps
def fd_freqs(run, plane):
    fs = {}
    for fn in glob.glob(str(run / f'{plane}_f=*_abs.vtr')):
        s = re.search(r'_f=([0-9.e+]+)_abs', fn).group(1); fs[float(s)] = s
    return fs

def read_vtr(fn, array=None):
    r = vtkXMLRectilinearGridReader(); r.SetFileName(str(fn)); r.Update(); g = r.GetOutput()
    x, y, z = (vtk_to_numpy(c).astype(float) for c in (g.GetXCoordinates(), g.GetYCoordinates(), g.GetZCoordinates()))
    pd = g.GetPointData(); a = pd.GetArray(array) if array else pd.GetArray(0)
    v = vtk_to_numpy(a).astype(float).reshape(len(z), len(y), len(x), -1)
    return x, y, z, v

def load_plane(run, plane, fstr):
    """complex field (ny, nx, 3) and its mesh indices; abs * exp(j arg) per component (openEMS FD dump)."""
    x, y, z, A = read_vtr(run / f'{plane}_f={fstr}_abs.vtr')
    _, _, _, P = read_vtr(run / f'{plane}_f={fstr}_arg.vtr')
    if len(z) != 1: raise SystemExit(f'{plane}: not a plane')
    return index_of(XL, x, plane), index_of(YL, y, plane), z[0], (A * np.exp(1j * P))[0]

def node_interp(F, comp, lines):
    """Yee-edge samples (index i = edge i..i+1) -> nodes, linear along the component's own axis (openEMS node mode)."""
    out = F.copy(); d = np.diff(lines)
    if comp == 0:
        w_up = d[:-1] / (d[:-1] + d[1:])       # weight of edge i-1 at node i: delta_i / (delta_i + delta_{i-1})
        out[:, 1:-1] = F[:, 1:-1] * (1 - w_up) + F[:, :-2] * w_up; out[:, -1] = F[:, -2]
    else:
        w_up = (d[:-1] / (d[:-1] + d[1:]))[:, None]
        out[1:-1, :] = F[1:-1, :] * (1 - w_up) + F[:-2, :] * w_up; out[-1, :] = F[-2, :]
    return out

# ---------------------------------------------------------------- copper masks (gerber2ems layer PNG, 5 um/pixel)
_png = {}
def copper_mask(png):
    if png not in _png:
        fn = variant_dir / 'ems' / 'geometry' / png
        if not fn.exists(): _png[png] = None; return None
        r = vtkPNGReader(); r.SetFileName(str(fn)); r.Update(); im = r.GetOutput()
        nx, ny, _ = im.GetDimensions()
        a = vtk_to_numpy(im.GetPointData().GetScalars()).reshape(ny, nx, -1)
        _png[png] = (a[..., 1] > 128) & (a[..., 0] < 128)       # copper = green; VTK row 0 = y = 0 (bottom)
    return _png[png]

def coverage(png, ix, iy, n=4):
    """fraction of each node's dual cell that is copper (n x n samples of the 5 um layer image)."""
    m = copper_mask(png)
    if m is None: return np.ones((len(iy), len(ix)), np.float32)
    x, y = XL[ix] * UNIT * 1e3, YL[iy] * UNIT * 1e3                 # mm
    hx, hy = DXD[ix] * 1e3 / 2, DYD[iy] * 1e3 / 2
    cov = np.zeros((len(iy), len(ix)), np.float32)
    for s in (np.arange(n) + 0.5) / n * 2 - 1:
        for t in (np.arange(n) + 0.5) / n * 2 - 1:
            px = ((x + s * hx) / 0.005).astype(int); py = ((y + t * hy) / 0.005).astype(int)
            okx = (px >= 0) & (px < m.shape[1]); oky = (py >= 0) & (py < m.shape[0])
            sub = m[np.clip(py, 0, m.shape[0] - 1)][:, np.clip(px, 0, m.shape[1] - 1)] & oky[:, None] & okx[None, :]
            cov += sub
    return cov / n / n

# ---------------------------------------------------------------- vias (drill file)
def read_drill():
    fn = opts.get('drl') or next(iter(sorted(glob.glob(str(variant_dir / 'fab' / '*-PTH.drl')))), None)
    if not fn: return np.zeros((0, 2)), np.zeros(0)
    tools, cur, pts, dia = {}, None, [], []
    for line in open(fn):
        line = line.strip()
        m = re.match(r'T(\d+)C([\d.]+)', line)
        if m: tools[m.group(1)] = float(m.group(2)); continue
        m = re.match(r'T(\d+)$', line)
        if m: cur = tools.get(m.group(1)); continue
        m = re.match(r'X(-?[\d.]+)Y(-?[\d.]+)', line)
        if m: pts.append((float(m.group(1)), float(m.group(2)))); dia.append(cur or 0.3)
    return np.array(pts), np.array(dia)

def via_currents(Jz_list, ix, iy, vias, dia):
    """Iz per via for each segment plane: sum Jz * dual area over the nodes nearest to that via (within VIA_R)."""
    from scipy.spatial import cKDTree
    x, y = XL[ix] * UNIT * 1e3, YL[iy] * UNIT * 1e3
    X, Y = np.meshgrid(x, y)
    d, owner = cKDTree(vias).query(np.c_[X.ravel(), Y.ravel()])
    reach = np.maximum(VIA_R, dia / 2 + 0.1)
    ok = d <= reach[owner]
    area = (DYD[iy][:, None] * DXD[ix][None, :]).ravel()
    return [np.bincount(owner[ok], weights=(Jz.ravel() * area)[ok].real, minlength=len(vias)) +
            1j * np.bincount(owner[ok], weights=(Jz.ravel() * area)[ok].imag, minlength=len(vias)) for Jz in Jz_list]

# ---------------------------------------------------------------- writers
def crop_idx(ix, iy):
    if not CROP: return slice(None), slice(None)
    x, y = XL[ix] * UNIT * 1e3, YL[iy] * UNIT * 1e3
    sx = np.where((x >= CROP[0]) & (x <= CROP[1]))[0]; sy = np.where((y >= CROP[2]) & (y <= CROP[3]))[0]
    return slice(sx[0], sx[-1] + 1), slice(sy[0], sy[-1] + 1)

def write_grid(fn, x_mm, y_mm, z_mm, arrays):
    g = vtkRectilinearGrid(); g.SetDimensions(len(x_mm), len(y_mm), 1)
    for setter, c in ((g.SetXCoordinates, x_mm), (g.SetYCoordinates, y_mm), (g.SetZCoordinates, np.array([z_mm]))):
        setter(numpy_to_vtk(np.ascontiguousarray(c, dtype=np.float64), deep=1))
    for name, a in arrays.items():
        a = np.ascontiguousarray(a.reshape(len(x_mm) * len(y_mm), -1).squeeze(), dtype=np.float32)
        v = numpy_to_vtk(a, deep=1); v.SetName(name); g.GetPointData().AddArray(v)
    g.GetPointData().SetActiveScalars('K')
    w = vtkXMLRectilinearGridWriter(); w.SetFileName(str(fn)); w.SetInputData(g); w.SetDataModeToAppended(); w.EncodeAppendedDataOff()
    w.SetCompressorTypeToZLib(); w.Write()

def write_pvd(fn, data_file, phases):
    """phase 'time series' that points every step at the same file: time = phase (deg); ParaView computes
    Re((Kre + j Kim) exp(j phase)) with a Python Calculator (t_value), so no per-phase copies are needed."""
    open(fn, 'w').write('<?xml version="1.0"?>\n<VTKFile type="Collection" version="0.1">\n<Collection>\n' +
                        ''.join(f'<DataSet timestep="{p:g}" file="{data_file}"/>\n' for p in phases) + '</Collection>\n</VTKFile>\n')

def write_points(fn, P, arrays):
    pts = vtkPoints(); pts.SetData(numpy_to_vtk(np.ascontiguousarray(P, dtype=np.float32), deep=1))
    verts = vtkCellArray()
    for i in range(len(P)): verts.InsertNextCell(1); verts.InsertCellPoint(i)
    pd = vtkPolyData(); pd.SetPoints(pts); pd.SetVerts(verts)
    for name, a in arrays.items():
        v = numpy_to_vtk(np.ascontiguousarray(a, dtype=np.float32), deep=1); v.SetName(name); pd.GetPointData().AddArray(v)
    w = vtkXMLPolyDataWriter(); w.SetFileName(str(fn)); w.SetInputData(pd); w.Write()

# ---------------------------------------------------------------- per-run sources
def waves(run):
    w = np.load(run / 'waves.npz', allow_pickle=True)
    return dict(f=w['f'], names=[str(n) for n in w['names']], excite=int(w['excite']),
                uf_inc=w['uf_inc'], uf_ref=w['uf_ref'], uf_tot=w['uf_tot'], if_tot=w['if_tot'])

def fidx(wv, f):
    i = int(np.argmin(np.abs(wv['f'] - f)))
    if abs(wv['f'][i] - f) > 1e3: raise SystemExit(f'frequency {f} not in waves.npz {wv["f"]}')
    return i

def layer_K(run, layer, fstr):
    """raw complex sheet current (ny, nx, 2) in A/m at the Yee edges (unnormalised), plus mesh indices."""
    plane = LAYERS[layer][0]
    ix, iy, z, J = load_plane(run, plane, fstr)
    dzd, _ = dz_dual(z / UNIT)
    return ix, iy, J[..., :2] * dzd, dzd

def seg_Jz(run, seg, fstr):
    ix, iy, z, J = load_plane(run, seg, fstr)
    return ix, iy, J[..., 2]

# ---------------------------------------------------------------- the two field modes
def emit(outdir, runs_w, label, extra_meta):
    """runs_w: list of (run, {f: complex weight}). Writes K grids, via files and meta.json."""
    outdir.mkdir(parents=True, exist_ok=True)
    fmap = fd_freqs(runs_w[0][0], 'j_F_Cu')
    freqs = sorted(f for f in fmap if (ONLY_F is None or any(abs(f - g) < 1e6 for g in ONLY_F)))
    layers = [l for l in LAYERS if (runs_w[0][0] / f'{LAYERS[l][0]}_f={fmap[freqs[0]]}_abs.vtr').exists()]
    vias, dia = read_drill()
    phases = np.arange(NPH) * 360.0 / NPH if NPH else []
    pj = runs_w[0][0].parent / 'ports.json'
    meta = dict(mode=mode, label=label, freqs=freqs, layers=layers, mask_um=MASK_UM, runs=[str(r) for r, _ in runs_w],
                port_boxes={p['name']: p['box_mm'] for p in json.load(open(pj))} if pj.exists() else {}, crop=CROP, **extra_meta)
    for f in freqs:
        fs = fmap[f]; tag = f'f={int(round(f))}'
        print(f'--- {f / 1e9:g} GHz', flush=True)
        for layer in layers:
            K = None
            for run, wts in runs_w:
                ix, iy, Kr, dzd = layer_K(run, layer, fs)
                K = Kr * wts[f] if K is None else K + Kr * wts[f]
            Kn = np.stack([node_interp(K[..., 0], 0, XL[ix]), node_interp(K[..., 1], 1, YL[iy])], -1)
            cov = coverage(LAYERS[layer][1], ix, iy)
            sx, sy = crop_idx(ix, iy)
            Kc, cv = Kn[sy, sx], cov[sy, sx]
            x_mm, y_mm = XL[ix][sx] * UNIT * 1e3, YL[iy][sy] * UNIT * 1e3
            z_mm = LAYERS[layer][2]
            amp = np.sqrt(np.abs(Kc[..., 0]) ** 2 + np.abs(Kc[..., 1]) ** 2)
            def vec(c): return np.concatenate([c, np.zeros(c.shape[:2] + (1,))], -1)
            write_grid(outdir / f'K_{layer}_{tag}.vtr', x_mm, y_mm, z_mm,
                       dict(K=amp, Kvec=vec(Kc.real), Kre=vec(Kc.real), Kim=vec(Kc.imag), copper=cv))
            write_pvd(outdir / f'K_{layer}_{tag}.pvd', f'K_{layer}_{tag}.vtr', phases)
            for p in (phases if SNAP else []):
                Kp = (Kc * np.exp(1j * np.deg2rad(p))).real
                write_grid(outdir / f'K_{layer}_{tag}_p={int(p):03d}.vtr', x_mm, y_mm, z_mm,
                           dict(K=amp, Kvec=vec(Kp), Kt=np.hypot(Kp[..., 0], Kp[..., 1]), copper=cv))
            on = cv > 0
            print(f'    {layer:7s} dz_dual {dzd * 1e6:5.1f} um  |K| on copper: 99.9th pct {np.percentile(amp[on], 99.9) if on.any() else 0:.3e} A/m, '
                  f'max {amp[on].max() if on.any() else 0:.3e}', flush=True)
        if len(vias):
            segs = [s for s, _ in SEGMENTS if (runs_w[0][0] / f'{s}_f={fs}_abs.vtr').exists()]
            I = None
            for run, wts in runs_w:
                planes = [seg_Jz(run, s, fs) for s in segs]
                Is = via_currents([p[2] for p in planes], planes[0][0], planes[0][1], vias, dia)
                Is = np.array(Is) * wts[f]
                I = Is if I is None else I + Is
            keep = np.ones(len(vias), bool)
            if CROP: keep = (vias[:, 0] >= CROP[0]) & (vias[:, 0] <= CROP[1]) & (vias[:, 1] >= CROP[2]) & (vias[:, 1] <= CROP[3])
            P = np.concatenate([np.c_[vias[keep], np.full(keep.sum(), dict(SEGMENTS)[s])] for s in segs])
            Ic = np.concatenate([I[n][keep] for n in range(len(segs))])
            seg_id = np.concatenate([np.full(keep.sum(), [x for x, _ in SEGMENTS].index(s)) for s in segs])
            dd = np.concatenate([dia[keep]] * len(segs))
            z3 = lambda v: np.c_[np.zeros(len(v)), np.zeros(len(v)), v]
            write_points(outdir / f'via_{tag}.vtp', P, dict(I=np.abs(Ic), Ire=Ic.real, Iim=Ic.imag, Ivec=z3(Ic.real), segment=seg_id, dia=dd))
            write_pvd(outdir / f'via_{tag}.pvd', f'via_{tag}.vtp', phases)
            for p in (phases if SNAP else []):
                Ip = (Ic * np.exp(1j * np.deg2rad(p))).real
                write_points(outdir / f'via_{tag}_p={int(p):03d}.vtp', P, dict(I=np.abs(Ic), It=Ip, Ivec=z3(Ip), segment=seg_id, dia=dd))
            top = np.argsort(-np.abs(I[0]))[:5]
            print(f'    vias: {len(vias)} holes; largest |I| F.Cu-In1:', ', '.join(f'({vias[i,0]:.2f},{vias[i,1]:.2f}) {abs(I[0][i])*1e3:.2f} mA' for i in top), flush=True)
    json.dump(meta, open(outdir / 'meta.json', 'w'), indent=1, default=lambda o: o.tolist() if hasattr(o, 'tolist') else str(o))
    print('wrote', outdir)

def parse_val(v):
    if v in ('-', 'DNI', 'none', 'open'): return None
    m = {'p': 1e-12, 'n': 1e-9, 'u': 1e-6}; return float(v[:-1]) * m[v[-1]] if v[-1] in m else float(v)

def port_meta(wv, f, scale):
    i = fidx(wv, f)
    return dict(names=wv['names'], uf_inc=(wv['uf_inc'][:, i] * scale), uf_ref=(wv['uf_ref'][:, i] * scale),
                if_tot=(wv['if_tot'][:, i] * scale))

def cplx(v): return [[float(np.real(a)), float(np.imag(a))] for a in np.atleast_1d(v)]

if mode == 'single':
    run = runs[0]; wv = waves(run); k = wv['excite']
    fs = sorted(fd_freqs(run, 'j_F_Cu'))
    wts = {f: 1 / wv['uf_inc'][k, fidx(wv, f)] for f in fs}
    ports = {f'{f:.0f}': {kk: cplx(v) for kk, v in port_meta(wv, f, wts[f]).items() if kk != 'names'} for f in fs}
    feed_phase = {f'{f:.0f}': float(-np.angle(wv['if_tot'][0, fidx(wv, f)] * wts[f], deg=True)) for f in fs}
    label = f'1 V incident at port {k} ({wv["names"][k]}), all other ports 50 ohm terminated'
    emit(Path(opts.get('out', run / 'cc')), [(run, wts)], label,
         dict(port_names=wv['names'], ports=ports, feed_phase_deg=feed_phase, excite=k))

elif mode == 'super':
    import skrf as rf
    W = [waves(r) for r in runs]
    order = sorted(range(len(runs)), key=lambda i: W[i]['excite'])
    runs, W = [runs[i] for i in order], [W[i] for i in order]
    names = W[0]['names']; N = len(names)
    if [w['excite'] for w in W] != list(range(N)): sys.exit(f'need one run per port 0..{N-1}, got excitations {[w["excite"] for w in W]}')
    if any(w['names'] != names for w in W): sys.exit('port lists differ between runs')
    vals = dict(L5='22n', C5='3.6p', L6='2.2n', C8='0.5p', C6='-', C7='-'); vals.update(parts_arg)
    SRC = int(opts.get('src', 0))
    ant = rf.Network(opts.get('ant', str(HERE / 'sparam' / 'Ant_P822601_S_parameters.s1p')))
    fs = sorted(fd_freqs(runs[0], 'j_F_Cu'))
    wts_all = [dict() for _ in runs]; ports, feed_phase, report = {}, {}, []
    for f in fs:
        i = [fidx(w, f) for w in W]
        A = np.array([W[k]['uf_inc'][:, i[k]] for k in range(N)]).T     # A[:, k] = incident waves of run k
        B = np.array([W[k]['uf_ref'][:, i[k]] for k in range(N)]).T
        S = B @ np.linalg.inv(A)
        w = 2 * np.pi * f; G = np.zeros(N, complex)
        for p, n in enumerate(names):
            if p == SRC: G[p] = 0.0                                           # 50 ohm source
            elif n == 'antenna':
                G[p] = ant.interpolate(rf.Frequency.from_f([f], unit='Hz')).s[0, 0, 0]
            else:
                if n not in vals: sys.exit(f'no value for port {n} (give {n}=<value> or {n}=-)')
                v = parse_val(vals[n])
                if v is None: G[p] = 1.0
                elif v == 0: G[p] = -1.0
                else:
                    Z = 1j * w * v if n.startswith('L') else 1 / (1j * w * v)
                    G[p] = (Z - 50) / (Z + 50)
        e = np.zeros(N, complex); e[SRC] = 1.0
        a = np.linalg.solve(np.eye(N) - np.diag(G) @ S, e)                   # incident waves (1 V incident at SRC)
        b = S @ a
        c = np.linalg.solve(A, a)                                           # weights of the runs' raw fields
        for k in range(N): wts_all[k][f] = c[k]
        Ipt = (a - b) / 50
        ports[f'{f:.0f}'] = dict(a=cplx(a), b=cplx(b), uf_tot=cplx(a + b), if_tot=cplx(Ipt), gamma=cplx(G))
        feed_phase[f'{f:.0f}'] = float(-np.angle(Ipt[SRC], deg=True))
        # S from waves vs the solver's S.npz of the runs (interpolated) as a consistency print
        srow = []
        for k in range(N):
            for cand in sorted(glob.glob(str(runs[k].parent / 'S*.npz'))):
                d = np.load(cand, allow_pickle=True)
                if np.all(np.isfinite(d['S'][:, k])):
                    srow.append(np.max(np.abs(np.array([np.interp(f, d['f'], d['S'][j, k].real) + 1j * np.interp(f, d['f'], d['S'][j, k].imag) for j in range(N)]) - S[:, k])))
                    break
        report.append(f'{f/1e9:6.3f} GHz  |S11| at the modem with the network: {20*np.log10(abs(b[SRC]/a[SRC])):6.1f} dB  '
                      f'power to the antenna port: {(abs(b[names.index("antenna")])**2 - abs(a[names.index("antenna")])**2)/ (abs(a[SRC])**2) * 100 if "antenna" in names else 0:5.1f} % of incident  '
                      f'|a_k|: ' + ' '.join(f'{n}={abs(x):.3f}' for n, x in zip(names, a)) +
                      (f'  max|S_waves - S.npz| {max(srow):.1e}' if srow else ''))
    print('\n'.join(report), flush=True)
    tag = '_'.join(f'{k}{v}' for k, v in sorted(vals.items()) if k in names).replace('-', 'x') or 'parts'
    label = 'modem 1 V incident (50 ohm source), parts ' + ' '.join(f'{k}={v}' for k, v in sorted(vals.items()) if k in names) + ', measured antenna'
    emit(Path(opts.get('out', variant_dir / f'cc_super_{tag}')), list(zip(runs, wts_all)), label,
         dict(port_names=names, ports=ports, feed_phase_deg=feed_phase, parts={k: v for k, v in vals.items() if k in names}, excite=SRC))

else:   # ---------------------------------------------------------------- check: validation numbers
    run = runs[0]; wv = waves(run); k = wv['excite']
    pinfo = json.load(open(run.parent / 'ports.json'))
    pb = pinfo[k]['box_mm']; xc, yc = (pb[0] + pb[1]) / 2, (pb[2] + pb[3]) / 2
    fsel = sorted(fd_freqs(run, 'j_F_Cu'))
    if ONLY_F: fsel = [f for f in fsel if any(abs(f - g) < 1e6 for g in ONLY_F)]
    print(f'run {run}  excite {k} ({wv["names"][k]})  z mesh: {len(ZL)} lines, mask {MASK_UM:g} um')
    for layer in LAYERS:
        try: print(f'    {layer}: dz_dual = {dz_dual({"F_Cu": 0, "In1_Cu": Z_GND1, "In2_Cu": Z_GND1 - 12650}.get(layer, ZL[np.argmin(np.abs(ZL + 14178))]))[0] * 1e6:.2f} um')
        except SystemExit as e: print('   ', layer, e)
    # (0) the H planes' z positions as recorded by openEMS (dual mesh) must sit on the reconstructed z mesh midpoints
    zmid = (ZL[1:] + ZL[:-1]) / 2
    for h in ('h_top', 'h_top_below', 'h_inner', 'h_inner_below', 'h_in2', 'h_in2_below'):
        fl = sorted(glob.glob(str(run / f'{h}_f=*_abs.vtr')))
        if fl:
            z = read_vtr(fl[0])[2][0] / UNIT
            print(f'    {h:14s} z = {z * 0.1:8.2f} um; nearest reconstructed dual line {zmid[np.argmin(np.abs(zmid - z))] * 0.1:8.2f} um')
    m_f = copper_mask('F_Cu.png')
    def cu_at(x, y, png='F_Cu.png'):
        m = copper_mask(png); px, py = int(x / 0.005), int(y / 0.005)
        return bool(m[py, px]) if 0 <= py < m.shape[0] and 0 <= px < m.shape[1] else False
    # direction of the feed line away from the port: the side with copper
    cands = [(0, 1), (0, -1), (1, 0), (-1, 0)]
    dirn = next((d for d in cands if cu_at(xc + 0.3 * d[0], yc + 0.3 * d[1]) and not cu_at(xc - 0.3 * d[0], yc - 0.3 * d[1])), None) if m_f is not None else None
    if 'dir' in opts: dirn = tuple(int(v) for v in opts['dir'].split(','))      # --dir=0,1: feed direction from the port
    if dirn is None: sys.exit('cannot tell the feed direction from the F.Cu mask; give --dir=dx,dy')
    print(f'    port box x {pb[0]:.3f}-{pb[1]:.3f} y {pb[2]:.3f}-{pb[3]:.3f} mm; feed leaves along {dirn}; '
          f'mask check: copper at port {cu_at(xc, yc + 0.05 * dirn[1])}, 0.2 mm beside the trace {cu_at(xc + 0.2 * abs(dirn[1]), yc + 0.2 * abs(dirn[0]))} (F.Cu)')
    ax = 1 if dirn[0] == 0 else 0     # current component along the feed
    straight = 0.05
    while cu_at(xc + (straight + 0.05) * dirn[0], yc + (straight + 0.05) * dirn[1]) and straight < 5: straight += 0.05
    straight -= 0.25                  # stay clear of the first bend / junction
    print(f'    straight feed from the port: {straight:.2f} mm (cuts kept inside it)')
    for f in fsel:
        fs = fd_freqs(run, 'j_F_Cu')[f]; i = fidx(wv, f); nrm = 1 / wv['uf_inc'][k, i]
        Ip = wv['if_tot'][k, i] * nrm
        print(f'--- {f/1e9:g} GHz: port {k} current if_tot = {abs(Ip)*1e3:.3f} mA at {np.angle(Ip, deg=True):+.1f} deg (1 V incident)')
        KK = {}
        for layer in ('F_Cu', 'In1_Cu', 'In2_Cu'):
            ix, iy, Kr, dzd = layer_K(run, layer, fs); KK[layer] = (ix, iy, Kr * nrm)
        ix, iy = KK['F_Cu'][0], KK['F_Cu'][1]
        x = XL[ix] * UNIT * 1e3; y = YL[iy] * UNIT * 1e3
        # (ii) KCL: current through cuts across the feed at several distances from the port
        hw = (pb[1] - pb[0]) / 2 if ax == 1 else (pb[3] - pb[2]) / 2
        print(f'    (ii) cut d [mm] | trace (F.Cu |t-c|<={hw:.3f}+cell) | F.Cu rest +-3 mm | In1 +-3 mm | In2 +-3 mm | sum | trace/if_tot')
        for d in [d for d in (0.1, 0.2, 0.4, 0.8, 1.6, 3.2) if d <= straight] or [0.1]:
            if ax == 1:   # feed along y: edge row j with y_j < yc + d < y_j+1, integrate Ky over x
                j = np.searchsorted(y, yc + d * dirn[1]) - 1; t, wdt = x, DXD[ix] * 1e3
                rowsel = lambda Kr: Kr[j, :, 1]; tc = xc
            else:
                j = np.searchsorted(x, xc + d * dirn[0]) - 1; t, wdt = y, DYD[iy] * 1e3
                rowsel = lambda Kr: Kr[:, j, 0]; tc = yc
            cell = np.max(np.diff(t)[np.abs(t[:-1] - tc) < 0.3]) if np.any(np.abs(t[:-1] - tc) < 0.3) else 0.02
            tr = np.abs(t - tc) <= hw + 0.5 * cell
            win = np.abs(t - tc) <= 3.0
            sgn = dirn[ax]
            Itr = np.sum(rowsel(KK['F_Cu'][2])[tr] * wdt[tr] * 1e-3) * sgn
            If_rest = np.sum(rowsel(KK['F_Cu'][2])[win & ~tr] * wdt[win & ~tr] * 1e-3) * sgn
            I1 = np.sum(rowsel(KK['In1_Cu'][2])[win] * wdt[win] * 1e-3) * sgn
            I2 = np.sum(rowsel(KK['In2_Cu'][2])[win] * wdt[win] * 1e-3) * sgn
            r = Itr / Ip
            print(f'        {d:4.1f} | {abs(Itr)*1e3:7.3f} mA {np.angle(Itr, deg=True):+7.1f} | {abs(If_rest)*1e3:7.3f} mA {np.angle(If_rest, deg=True):+7.1f} | '
                  f'{abs(I1)*1e3:7.3f} mA {np.angle(I1, deg=True):+7.1f} | {abs(I2)*1e3:6.3f} mA | {abs(Itr+If_rest+I1+I2)*1e3:6.3f} mA | {abs(r):.4f} {np.angle(r, deg=True):+6.1f} deg')
        # (iii) raw-edge vs node-interpolated integral along the feed (alternation between neighbouring cuts = half-cell artefact)
        Kn_ax = node_interp(KK['F_Cu'][2][..., ax], ax, (YL if ax == 1 else XL)[iy if ax == 1 else ix])
        rows = []
        for d in np.arange(0.05, builtins_max(straight, 0.1), 0.025):
            if ax == 1:
                j = np.searchsorted(y, yc + d * dirn[1]) - 1; jn = int(np.argmin(np.abs(y - (yc + d * dirn[1]))))
                raw = np.sum(KK['F_Cu'][2][j, :, 1][tr] * DXD[ix][tr]); nod = np.sum(Kn_ax[jn, :][tr] * DXD[ix][tr])
            else:
                j = np.searchsorted(x, xc + d * dirn[0]) - 1; jn = int(np.argmin(np.abs(x - (xc + d * dirn[0]))))
                raw = np.sum(KK['F_Cu'][2][:, j, 0][tr] * DYD[iy][tr]); nod = np.sum(Kn_ax[:, jn][tr] * DYD[iy][tr])
            rows.append((d, raw * dirn[ax], nod * dirn[ax]))
        rr = np.array([abs(r[1]) for r in rows]); nn = np.array([abs(r[2]) for r in rows])
        print(f'    (iii) trace current along the feed 0.05..{straight:.2f} mm, cuts every 25 um (raw edges): {rr.min()*1e3:.3f}..{rr.max()*1e3:.3f} mA, '
              f'max step between neighbouring cuts {np.max(np.abs(np.diff(rr)))*1e3:.4f} mA; node-interpolated vs raw: max |diff| '
              f'{np.max(np.abs(np.array([r[1]-r[2] for r in rows])))*1e3:.4f} mA')
        # profile across the trace at d = 0.4 mm
        if ax == 1:
            j = np.searchsorted(y, yc + 0.4 * dirn[1]) - 1; sel = np.abs(x - xc) <= hw + 0.1
            prof = [(x[q] - xc, abs(KK['F_Cu'][2][j, q, 1])) for q in np.where(sel)[0]]
            print('          profile across the trace at 0.4 mm (offset mm: |K| A/m):', ', '.join(f'{o:+.3f}: {v:.2f}' for o, v in prof))
        # (i) curl-H sheet current vs the H-jump method (sheet_current.py): same Yee edges, index by index
        for layer, (hp, hb) in (('F_Cu', ('h_top', 'h_top_below')), ('In1_Cu', ('h_inner', 'h_inner_below')), ('In2_Cu', ('h_in2', 'h_in2_below'))):
            fa = run / f'{hp}_f={fs}_abs.vtr'
            if not fa.exists(): continue
            def loadH(p):
                xh, yh, zh, A = read_vtr(run / f'{p}_f={fs}_abs.vtr'); _, _, _, P = read_vtr(run / f'{p}_f={fs}_arg.vtr')
                # dual-mesh coordinates (x_i + x_i+1)/2 -> primary index i
                dual = lambda L: np.r_[(L[1:] + L[:-1]) / 2, L[-1] + (L[-1] - L[-2]) / 2]   # openEMS dual lines
                xm, ym = dual(XL), dual(YL)
                return index_of(xm, xh, p), index_of(ym, yh, p), (A * np.exp(1j * P))[0]
            hx_i, hy_i, Ha = loadH(hp); _, _, Hb = loadH(hb)
            dH = (Ha - Hb) * nrm
            Kj = np.stack([-dH[..., 1], dH[..., 0]], -1)      # z x dH
            kx_i, ky_i, Kc = KK[layer]
            # common index window
            cx = np.intersect1d(hx_i, kx_i); cy = np.intersect1d(hy_i, ky_i)
            A1 = Kc[np.searchsorted(ky_i, cy)][:, np.searchsorted(kx_i, cx)]
            A2 = Kj[np.searchsorted(hy_i, cy)][:, np.searchsorted(hx_i, cx)]
            cov = coverage(LAYERS[layer][1], cx, cy) > 0
            a1 = np.sqrt(np.sum(np.abs(A1) ** 2, -1))[cov]; a2 = np.sqrt(np.sum(np.abs(A2) ** 2, -1))[cov]
            err = np.sqrt(np.sum(np.abs(A1 - A2)[cov] ** 2) / np.sum(np.abs(A2)[cov] ** 2))
            sel = a2 > 0.01 * np.percentile(a2, 99.9)
            if layer == 'F_Cu' and ax == 1:   # the same trace cut (d = 0.4 mm) integrated with both methods
                jr = np.searchsorted(YL[cy] * UNIT * 1e3, yc + 0.4 * dirn[1]) - 1; xs = XL[cx] * UNIT * 1e3; trc = np.abs(xs - xc) <= hw + 0.03
                ia = np.sum(A1[jr, trc, 1] * DXD[cx][trc]) * dirn[1]; ib = np.sum(A2[jr, trc, 1] * DXD[cx][trc]) * dirn[1]
                print(f'    (i) trace cut at 0.4 mm: curl-H {abs(ia)*1e3:.3f} mA, H-jump {abs(ib)*1e3:.3f} mA ({np.angle(ib/ia, deg=True):+.1f} deg); '
                      f'if_tot {abs(Ip)*1e3:.3f} mA')
                print('        profile (offset mm: |K| curl-H / H-jump A/m):', ', '.join(f'{o:+.3f}: {abs(u):.1f}/{abs(v):.1f}' for o, u, v in zip(xs[trc] - xc, A1[jr, trc, 1], A2[jr, trc, 1])))
            print(f'    (i) {layer}: curl-H K vs H-jump K on {cov.sum()} copper nodes: relative L2 difference {err:.3f}; '
                  f'99.9th pct {np.percentile(a1, 99.9):.2f} vs {np.percentile(a2, 99.9):.2f} A/m; median ratio (|K|>1% of peak) {np.median(a1[sel]/a2[sel]):.3f}')
