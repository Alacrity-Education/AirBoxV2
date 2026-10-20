#!/usr/bin/env python3
"""Real conductor current (whole copper depth, conductor_current.py) on each copper layer + via currents, one frequency.

    tools/py.sh conductor_current.py single board20n/nport_gap_p0/run_0              # once -> run_0/cc/
    DUMP_DIR=board20n/nport_gap_p0/run_0 FREQ=0.725 paraview --script paraview_conductor.py
    DUMP_DIR=board20n/cc_super_<parts> FREQ=0.725 paraview --script paraview_conductor.py   # superposed (network fitted)

Layout (2 x 2, as approved for paraview_return.py): In1.Cu (GND1) | F.Cu / In2.Cu (GND2) | 3-D stack (In2 / In1 / F.Cu,
via barrels, vertical via-current arrows). The top views (linked cameras) show the copper surface itself coloured by
the amplitude of the depth-integrated sheet current |K| (A/m for 1 V incident at the modem; log scale, same in every
view), only where the layer has copper, with a surface LIC texture of the instantaneous current Re(K exp(j phase)):
the texture streaks follow the current lines, so closed loops (trace -> port -> GND1 return -> vias -> GND2 ...)
read directly as closed streak patterns, at every point of the copper and independent of arrow density; sparse
arrows on top give the direction (LIC has none). Play the animation to sweep the phase: the .pvd series has one step
per phase (time = degrees) and a Python Calculator forms Re((Kre + j Kim) exp(j phase)) on the fly.

Options (environment):
  DUMP_DIR   run dir (uses <run>/cc) or conductor_current.py output dir (has meta.json)
  MODE       single | superposed: only checked against meta.json (label shown in the views)
  FREQ       GHz (nearest dumped frequency)
  PHASE      deg, or 'auto' (default) = the instant of maximum feed (port 0) current
  COLOR      signed (default): every current representation (copper surfaces, LIC, arrows, 3-D surfaces, via arrows)
             is coloured by the INSTANTANEOUS current on the Cold-and-Hot scale: sheets by the component along the local
             feed direction (nearest segment of <variant>/feed_path.json from feed_path.py; red/yellow = toward the
             antenna, blue/cyan = toward the modem, black = ~0 or flowing across the feed), vias by Iz (red = up).
             It changes every frame. amplitude: the previous view (|K| amplitude, Viridis, fixed over the cycle).
  SCALE      log (default): colour = sign x log10(|value| / floor) over DECADES, so weak GND2 currents stay visible |
             lin: linear, clipped at the top
  STYLE      lic (default: LIC texture + sparse arrows) | arrows (plain colour + arrows)
  LAYOUT     grid (default, 2 x 2) | vertical (4 panes stacked: 3-D, F.Cu, In1.Cu, In2.Cu)
             (the LIC segfaults in the ParaView 6.2 GUI on atmega (Xwayland, QT_QPA_PLATFORM=xcb); pvpython offscreen is fine.
             On atmega use STYLE=arrows: tools/paraview_remote.sh paraview_conductor.py DUMP_DIR=... FREQ=... STYLE=arrows)
  VIEW       x0,x1,y0,y1 (mm) top-view window; default = the ports' bounding box + 3 mm (board20n: RF area)
  DECADES=3  log colour range below the top; PCT=99.9: top = this percentile of |K| on F.Cu copper
  ARROW_DX=0.3 (mm) arrow spacing (uniform resample); ARROW=0.35 (mm) arrow length; ARROW_MIN=0.03 (x top)
  Z3=4       3-D view height exaggeration; VIA_MIN=0.02: via arrows above this fraction of the largest |I|;
  STRONG=0.03  3-D view shows the copper current surfaces only where |K| > STRONG x top (the main loops)
  SHOT=<prefix> save <prefix>_2x2.png (headless test: pvpython --force-offscreen-rendering paraview_conductor.py)
"""
import builtins, json, os
from pathlib import Path
from paraview.simple import *  # type: ignore #noqa: F403
from vtkmodules.vtkIOXML import vtkXMLRectilinearGridReader
from vtkmodules.util.numpy_support import vtk_to_numpy
import numpy as np

path = Path(os.environ.get('DUMP_DIR', '.')).resolve()
if not (path / 'meta.json').exists() and (path / 'cc' / 'meta.json').exists(): path = path / 'cc'
meta = json.load(open(path / 'meta.json'))
MODE = os.environ.get('MODE')
if MODE and (MODE == 'single') != (meta['mode'] == 'single'):
    print(f'warning: MODE={MODE} but {path} holds a {meta["mode"]} result ({meta["label"]})')
freqs = meta['freqs']
FREQ = builtins.min(freqs, key=lambda f: abs(f - float(os.environ.get('FREQ', '0.75')) * 1e9))
FTAG = f'f={int(round(FREQ))}'
PHASE = os.environ.get('PHASE', 'auto')
STYLE = os.environ.get('STYLE', 'lic')
LAYOUT = os.environ.get('LAYOUT', 'grid')        # grid (2 x 2) | vertical (stack, top to bottom: 3-D, F.Cu, In1.Cu, In2.Cu)
DECADES = float(os.environ.get('DECADES', '3'))
PCT = float(os.environ.get('PCT', '99.9'))
ARROW_DX = float(os.environ.get('ARROW_DX', '0.3'))
ARROW = float(os.environ.get('ARROW', '0.35'))
ARROW_MIN = float(os.environ.get('ARROW_MIN', '0.03'))
Z3 = float(os.environ.get('Z3', '4'))
VIA_MIN = float(os.environ.get('VIA_MIN', '0.02'))
STRONG = float(os.environ.get('STRONG', '0.03'))
SHOT = os.environ.get('SHOT')
COLOR = os.environ.get('COLOR', 'signed')        # signed: instantaneous current along the feed, Cold-and-Hot | amplitude
SCALE = os.environ.get('SCALE', 'log')           # signed colour: log (sign x log10 over DECADES) | lin (clipped at the top)
variant = next(p for p in [path, *path.parents] if (p / 'copper3d').exists() or (p / 'ems').exists())
cdir = Path(os.environ.get('COPPER_DIR', str(variant / 'copper3d'))).resolve()
FEED = None
if COLOR == 'signed':
    fp = variant / 'feed_path.json'               # feed_path.py: oriented feed centreline, modem -> antenna
    if fp.exists(): FEED = json.load(open(fp))['points']
    else: print(f'warning: {fp} missing (run: python3 feed_path.py {variant.name}); colouring by amplitude'); COLOR = 'amplitude'
SIGNED = COLOR == 'signed'
CARR = 'Kc' if SIGNED else 'K'                    # colour array of every current representation
paraview.simple._DisableFirstRenderCameraReset()  # type: ignore #noqa: F405
COPPER, RFCU = [0.55, 0.36, 0.22], [0.75, 0.55, 0.30]
# layer -> (title, copper z slab in the PLY frame (mm), surface z offset for the current surface)
LAYER_Z = {'F_Cu': 0.035, 'In1_Cu': -0.0814, 'In2_Cu': -1.3616, 'B_Cu': -1.4882}   # = conductor_current.py LAYERS
PANELS = {'In1_Cu': ('In1.Cu (GND1)', (-0.105, -0.07)), 'F_Cu': ('F.Cu (trace + pour)', (-0.01, 0.045)),
          'In2_Cu': ('In2.Cu (GND2)', (-1.39, -1.35))}

# ---- phase -> snapshot index
import re as _re
nph = len(_re.findall('<DataSet', (path / f'K_F_Cu_{FTAG}.pvd').read_text()))
if PHASE == 'auto': phase = meta.get('feed_phase_deg', {}).get(f'{FREQ:.0f}', 0.0) % 360
else: phase = float(PHASE) % 360
CYC = float(meta.get('cycles', 1))               # phase_series.py / conductor_current.py --cycles: periods spanned by the series
STEP = 360.0 * CYC / nph if nph else 360.0       # degrees per frame
pidx = int(round(phase / STEP)) % nph if nph else 0

# ---- top-view window
if 'VIEW' in os.environ: VIEW = [float(v) for v in os.environ['VIEW'].split(',')]
elif meta.get('port_boxes'):
    b = list(meta['port_boxes'].values())
    # include J11 (on board20n it sits below the modem port): pads from <variant>_pads.json next to the variant folder
    pj = next((p.parent / f'{p.name}_pads.json' for p in path.parents if (p.parent / f'{p.name}_pads.json').exists()), None)
    if pj:
        pads = json.load(open(pj))['pads']
        b += [[pd['x0'], pd['x1'], pd['y0'], pd['y1']] for k, pd in pads.items() if k.startswith('J11.')]
    b = np.array(b)
    VIEW = [b[:, 0].min() - 3, b[:, 1].max() + 3, b[:, 2].min() - 3, b[:, 3].max() + 3]
else: VIEW = None

def voi(fn):
    """index window of the grid covering VIEW (+1 mm) -> ExtractSubset VOI (keeps the whole-board files cheap)."""
    r = vtkXMLRectilinearGridReader(); r.SetFileName(str(fn)); r.UpdateInformation()
    r.Update(); g = r.GetOutput()
    x, y = vtk_to_numpy(g.GetXCoordinates()), vtk_to_numpy(g.GetYCoordinates())
    if not VIEW: return [0, len(x) - 1, 0, len(y) - 1, 0, 0]
    ix = np.where((x >= VIEW[0] - 1) & (x <= VIEW[1] + 1))[0]; iy = np.where((y >= VIEW[2] - 1) & (y <= VIEW[3] + 1))[0]
    return [int(ix[0]), int(ix[-1]), int(iy[0]), int(iy[-1]), 0, 0]

def thresh(src, name, array, lo, hi, allpts=False):
    th = Threshold(registrationName=name, Input=src)  # type: ignore #noqa: F405
    th.Scalars = ['POINTS', array]
    try:
        th.LowerThreshold, th.UpperThreshold = lo, hi
    except AttributeError:
        th.ThresholdRange = [lo, hi]
    if allpts:
        th.AllScalars = 1
    return th

def copper_look(d, rf=False, opacity=1.0):
    d.ColorArrayName = ['POINTS', '']
    d.AmbientColor = d.DiffuseColor = RFCU if rf else COPPER
    d.Specular = 0.3
    d.Opacity = opacity

# ---- layout: 2 x 2 ----
v1 = GetActiveViewOrCreate('RenderView')  # type: ignore #noqa: F405
layout = GetLayout(v1)  # type: ignore #noqa: F405
if layout is None:
    layout = CreateLayout('conductor current')  # type: ignore #noqa: F405
    AssignViewToLayout(view=v1, layout=layout, hint=0)  # type: ignore #noqa: F405
if LAYOUT == 'vertical':   # cells (binary tree): 1 = top quarter, 5, 13, 14 below it
    layout.SplitVertical(0, 0.25)
    layout.SplitVertical(2, 1.0 / 3)
    layout.SplitVertical(6, 0.5)
    v4 = v1                                       # the first view sits in cell 1 (top): the 3-D stack
    v2, v1, v3 = (CreateView('RenderView') for _ in range(3))  # type: ignore #noqa: F405
    AssignViewToLayout(view=v2, layout=layout, hint=5)   # F.Cu  # type: ignore #noqa: F405
    AssignViewToLayout(view=v1, layout=layout, hint=13)  # In1.Cu  # type: ignore #noqa: F405
    AssignViewToLayout(view=v3, layout=layout, hint=14)  # In2.Cu  # type: ignore #noqa: F405
else:
    layout.SplitHorizontal(0, 0.5)
    layout.SplitVertical(1, 0.5)
    layout.SplitVertical(2, 0.5)
    v2, v3, v4 = (CreateView('RenderView') for _ in range(3))  # type: ignore #noqa: F405
    AssignViewToLayout(view=v2, layout=layout, hint=5)  # type: ignore #noqa: F405
    AssignViewToLayout(view=v3, layout=layout, hint=4)  # type: ignore #noqa: F405
    AssignViewToLayout(view=v4, layout=layout, hint=6)  # type: ignore #noqa: F405
VIEWS = {'In1_Cu': v1, 'F_Cu': v2, 'In2_Cu': v3}
for v in (v1, v2, v3, v4):
    v.Background = [0.1, 0.1, 0.12]
    try:
        v.UseColorPaletteForBackground = 0
    except AttributeError:
        pass
    v.OrientationAxesVisibility = 0

# ---- copper (KiCad PLY, z aligned to the openEMS frame): dim base under the current surface + 3-D stack ----
dz = json.load(open(cdir / 'align.json'))['dz_mm']
cu = {}
for name in ('copper_gnd', 'copper_rf'):
    r = PLYReader(registrationName=name + '_kicad', FileNames=[str(cdir / (name + '.ply'))])  # type: ignore #noqa: F405
    t = Transform(registrationName=name, Input=r)  # type: ignore #noqa: F405
    t.Transform.Translate = [0, 0, dz]
    if VIEW:
        bx = Clip(registrationName=name + ' window', Input=t)  # type: ignore #noqa: F405
        bx.ClipType = 'Box'; bx.ClipType.Position = [VIEW[0] - 1, VIEW[2] - 1, -2]
        bx.ClipType.Length = [VIEW[1] - VIEW[0] + 2, VIEW[3] - VIEW[2] + 2, 4]; bx.Invert = 1
        try: bx.Crinkleclip = 0
        except AttributeError: pass
        t = bx
    cz = Calculator(registrationName=name + ' z', Input=t)  # type: ignore #noqa: F405
    cz.ResultArrayName = 'zmm'
    cz.Function = 'coordsZ'
    cu[name] = cz
    c1 = Clip(registrationName=name + ' above In2', Input=t)  # type: ignore #noqa: F405
    c1.ClipType = 'Plane'; c1.ClipType.Origin = [0, 0, -1.39]; c1.ClipType.Normal = [0, 0, 1]; c1.Invert = 0
    c2 = Clip(registrationName=name + ' In2..F.Cu', Input=c1)  # type: ignore #noqa: F405
    c2.ClipType = 'Plane'; c2.ClipType.Origin = [0, 0, 0.045]; c2.ClipType.Normal = [0, 0, 1]; c2.Invert = 1
    d = Show(c2, v4); copper_look(d, rf=(name == 'copper_rf'), opacity=0.25); d.Scale = [1, 1, Z3]  # type: ignore #noqa: F405
for layer, (title, (z0, z1)) in PANELS.items():
    for name in ('copper_gnd', 'copper_rf'):
        th = thresh(cu[name], f'{name} {layer}', 'zmm', z0, z1, allpts=True)
        th.UpdatePipeline()
        if th.GetDataInformation().GetNumberOfPoints() == 0: continue
        copper_look(Show(th, VIEWS[layer]), rf=(name == 'copper_rf'))  # type: ignore #noqa: F405

# ---- current surfaces ----
def fetch_K_percentile(fn, pct):
    r = vtkXMLRectilinearGridReader(); r.SetFileName(str(fn)); r.Update(); g = r.GetOutput()
    K = vtk_to_numpy(g.GetPointData().GetArray('K')); c = vtk_to_numpy(g.GetPointData().GetArray('copper'))
    return float(np.percentile(K[c > 0], pct)) if np.any(c > 0) else float(K.max())

PHASOR = 'Kre*cos(t_value*0.017453292519943295) - Kim*sin(t_value*0.017453292519943295)'   # Re(K exp(j phase))
SIGN_SCRIPT = r'''
import numpy as np
from vtkmodules.numpy_interface import dataset_adapter as dsa
from vtkmodules.util.numpy_support import vtk_to_numpy
FEED = np.array(%(feed)r, dtype=float); LO, TOP, DEC, LIN = %(lo)r, %(top)r, %(dec)r, %(lin)r
out = self.GetOutputDataObject(0); out.ShallowCopy(self.GetInputDataObject(0, 0))
if hasattr(out, 'GetXCoordinates'):     # rectilinear grid: x fastest
    X, Y = np.meshgrid(vtk_to_numpy(out.GetXCoordinates()), vtk_to_numpy(out.GetYCoordinates()))
    nz = out.GetDimensions()[2]
    P = np.tile(np.c_[X.ravel(), Y.ravel()], (nz, 1))
else:
    P = np.asarray(dsa.WrapDataObject(out).Points)[:, :2]
w = dsa.WrapDataObject(out)
K = np.asarray(w.PointData['Know'])[:, :2]
A, B = FEED[:-1], FEED[1:]; D = B - A; L2 = np.maximum((D ** 2).sum(1), 1e-12); T = D / np.sqrt(L2)[:, None]
idx = np.empty(len(P), int)
for i in range(0, len(P), 20000):       # nearest feed segment per point (chunked)
    q = P[i:i + 20000]
    u = np.clip(((q[:, None, :] - A[None]) * D[None]).sum(2) / L2[None], 0, 1)
    d2 = ((A[None] + u[..., None] * D[None] - q[:, None, :]) ** 2).sum(2)
    idx[i:i + 20000] = d2.argmin(1)
s = (K * T[idx]).sum(1)
if LIN: c = np.clip(s / TOP, -1, 1)
else: c = np.sign(s) * np.clip(np.log10(np.maximum(np.abs(s), 1e-300) / LO) / DEC, 0, 1)
w.PointData.append(s, 'Ks'); w.PointData.append(c, 'Kc')
'''
top = fetch_K_percentile(path / f'K_F_Cu_{FTAG}.vtr', PCT)
lo = top * 10 ** -DECADES
glyph_disps, surf_disps = [], []
for layer, (title, _) in PANELS.items():
    rd = PVDReader(registrationName=f'K {layer}', FileName=str(path / f'K_{layer}_{FTAG}.pvd'))  # type: ignore #noqa: F405
    ex0 = ExtractSubset(registrationName=f'K {layer} window', Input=rd)  # type: ignore #noqa: F405
    ex0.VOI = voi(path / f'K_{layer}_{FTAG}.vtr')
    ex = PythonCalculator(registrationName=f'K {layer} at phase', Input=ex0)  # type: ignore #noqa: F405
    ex.Expression = PHASOR                           # time of the .pvd series = phase in degrees
    ex.ArrayName = 'Know'
    ex = Calculator(registrationName=f'K {layer} |K now|', Input=ex)  # type: ignore #noqa: F405
    ex.ResultArrayName = 'Kt'; ex.Function = 'mag(Know)'
    if SIGNED:   # Ks = Re(K e^{j phase}) . (direction of the nearest feed segment, modem -> antenna); Kc = colour value
        sg = ProgrammableFilter(registrationName=f'K {layer} along feed', Input=ex)  # type: ignore #noqa: F405
        sg.Script = SIGN_SCRIPT % dict(feed=FEED, lo=lo, top=top, dec=DECADES, lin=(SCALE == 'lin'))
        sg.CopyArrays = 1
        ex = sg
    oncu = thresh(ex, f'K {layer} on copper', 'copper', 1e-3, 2, allpts=True)     # cells whose 4 nodes touch copper
    surf = ExtractSurface(registrationName=f'K {layer} surface', Input=oncu)  # type: ignore #noqa: F405
    strong = thresh(oncu, f'K {layer} strong (3-D)', 'K', STRONG * top, 1e30)   # 3-D view: only the main current paths
    for view, zsc, src in ((VIEWS[layer], 1, surf), (v4, Z3, strong)):
        d = Show(src, view)  # type: ignore #noqa: F405
        ColorBy(d, ('POINTS', CARR))  # type: ignore #noqa: F405
        d.Scale = [1, 1, zsc]; d.Translation = [0, 0, 0.002 * zsc]
        if STYLE in ('lic', 'both') and view is not v4:
            d.SetRepresentationType('Surface LIC')
            d.SelectInputVectors = ['POINTS', 'Know']
            d.NormalizeVectors = 1
            d.NumberOfSteps = 40
            d.StepSize = 0.4
            d.EnhanceContrast = 'Off'          # contrast enhancement is computed per render tile: blocky patches
            d.ColorMode = 'Blend'
            d.LICIntensity = 0.6
            d.MaskOnSurface = 1; d.MaskThreshold = lo        # no streaks where the current is below the colour floor
            d.MaskIntensity = 0.0
        surf_disps.append((d, view))
    if True:   # sparse direction arrows in every style (LIC streaks have no sign)
        sub = ResampleToImage(registrationName=f'K {layer} arrow grid', Input=ex)  # type: ignore #noqa: F405
        b = VIEW or [0, 30, 0, 30]                  # uniform arrow spacing (the FDTD grid is very non-uniform)
        sub.UseInputBounds = 0
        sub.SamplingBounds = [b[0], b[1], b[2], b[3], LAYER_Z[layer], LAYER_Z[layer]]
        sub.SamplingDimensions = [builtins.max(2, int((b[1] - b[0]) / ARROW_DX)), builtins.max(2, int((b[3] - b[2]) / ARROW_DX)), 1]
        pts = thresh(sub, f'K {layer} arrow points', 'copper', 0.5, 2)
        big = thresh(pts, f'K {layer} flowing', 'Kt', ARROW_MIN * top, 1e30)
        g = Glyph(registrationName=f'K {layer} arrows', Input=big, GlyphType='Arrow')  # type: ignore #noqa: F405
        g.OrientationArray = ['POINTS', 'Know']
        g.ScaleArray = ['POINTS', 'No scale array']
        g.ScaleFactor = ARROW
        g.GlyphType.TipRadius = 0.2; g.GlyphType.ShaftRadius = 0.06; g.GlyphType.TipLength = 0.4
        g.GlyphMode = 'All Points'
        gd = Show(g, VIEWS[layer])  # type: ignore #noqa: F405
        gd.Translation = [0, 0, 0.02]
        if SIGNED:   # same Cold-and-Hot scale as the surface; dark edges keep them readable on their own colour
            ColorBy(gd, ('POINTS', CARR))  # type: ignore #noqa: F405
            gd.SetRepresentationType('Surface With Edges'); gd.EdgeColor = [0.05, 0.05, 0.05]; gd.Opacity = 1.0
            glyph_disps.append((gd, VIEWS[layer]))
        else:
            gd.ColorArrayName = ['POINTS', '']; gd.AmbientColor = gd.DiffuseColor = [1, 1, 1]; gd.Opacity = 0.9   # white: readable on any colour
    txt = Text(registrationName=f'title {layer}')  # type: ignore #noqa: F405
    txt.Text = f'{title}  {FREQ / 1e9:g} GHz'
    td = Show(txt, VIEWS[layer]); td.WindowLocation = 'Upper Left Corner'; td.FontSize = 12  # type: ignore #noqa: F405

# ---- via current arrows (3-D view) ----
vfiles = (path / f'via_{FTAG}.pvd').exists()
via_disps = []
if vfiles:
    vr0 = PVDReader(registrationName='via current', FileName=str(path / f'via_{FTAG}.pvd'))  # type: ignore #noqa: F405
    vr = PythonCalculator(registrationName='via current at phase', Input=vr0)  # type: ignore #noqa: F405
    vr.Expression = 'make_vector(0*Ire, 0*Ire, Ire*cos(t_value*0.017453292519943295) - Iim*sin(t_value*0.017453292519943295))'
    vr.ArrayName = 'Inow'
    vr0.UpdatePipeline(0.0)
    imax = vr0.PointData['I'].GetRange()[1]
    vc = ProgrammableFilter(registrationName='via current signed', Input=vr)  # type: ignore #noqa: F405
    vc.Script = ('import numpy as np\nfrom vtkmodules.numpy_interface import dataset_adapter as dsa\n'
                 'out = self.GetOutputDataObject(0); out.ShallowCopy(self.GetInputDataObject(0, 0)); w = dsa.WrapDataObject(out)\n'
                 'z = np.asarray(w.PointData["Inow"])[:, 2]\n'
                 f'ILO, DEC, LIN, IMAX = {imax * 10 ** -DECADES!r}, {DECADES!r}, {SCALE == "lin"!r}, {imax!r}\n'
                 'c = np.clip(z / IMAX, -1, 1) if LIN else np.sign(z) * np.clip(np.log10(np.maximum(np.abs(z), 1e-300) / ILO) / DEC, 0, 1)\n'
                 'w.PointData.append(c, "Ic")\n')
    vc.CopyArrays = 1
    vr = vc
    seg_len = {0: 0.06, 1: 1.1, 2: 0.06}
    for seg in (0, 1):
        s = thresh(vr, f'via seg {seg}', 'segment', seg - 0.1, seg + 0.1)
        s2 = thresh(s, f'via seg {seg} flowing', 'I', VIA_MIN * imax, 1e30)
        t = Transform(registrationName=f'via seg {seg} z', Input=s2)  # type: ignore #noqa: F405
        t.Transform.Scale = [1, 1, Z3]
        g = Glyph(registrationName=f'via seg {seg} arrows', Input=t, GlyphType='Arrow')  # type: ignore #noqa: F405
        g.OrientationArray = ['POINTS', 'Inow']
        g.ScaleArray = ['POINTS', 'No scale array']
        g.ScaleFactor = seg_len[seg] * Z3
        g.GlyphMode = 'All Points'
        g.GlyphTransform.Translate = [-0.5, 0, 0]
        rel = 0.12 / (seg_len[seg] * Z3)
        g.GlyphType.TipRadius = builtins.min(0.45, rel); g.GlyphType.ShaftRadius = builtins.min(0.2, rel * 0.4); g.GlyphType.TipLength = 0.3
        gd = Show(g, v4)  # type: ignore #noqa: F405
        ColorBy(gd, ('POINTS', 'Ic' if SIGNED else 'I'))  # type: ignore #noqa: F405
        via_disps.append(gd)
t4 = Text(registrationName='title 3d')  # type: ignore #noqa: F405
import textwrap  # noqa: E402
t4.Text = f'In2 / In1 / F.Cu + via current  {FREQ / 1e9:g} GHz  (z x{Z3:g})\n' + '\n'.join(textwrap.wrap(meta['label'], 52))
d4 = Show(t4, v4); d4.WindowLocation = 'Upper Left Corner'; d4.Justification = 'Left'; d4.FontSize = 10  # type: ignore #noqa: F405

# ---- colour scales ----
def preset(lut, names):
    for p in names:
        try:
            if lut.ApplyPreset(p, True): return
        except Exception:
            pass
def signed_labels(lut, tp, fl):   # tick labels for the -1..1 colour value (signed log or linear)
    vals = [-1, -0.5, 0, 0.5, 1]
    if SCALE == 'lin': txt = [f'{v * tp:+.2g}' for v in vals]
    else: txt = [f'{"+" if v > 0 else "-"}{fl * 10 ** (abs(v) * DECADES):.2g}' if v else f'|x|<{fl:.2g}' for v in vals]
    lut.Annotations = [x for v, s in zip(vals, txt) for x in (str(v), s)]
lut = GetColorTransferFunction(CARR)  # type: ignore #noqa: F405
if SIGNED:   # Cold and Hot: blue = against the feed direction, red = toward the antenna, black = ~0 / perpendicular
    preset(lut, ('Cold and Hot', 'Cool to Warm'))
    lut.AutomaticRescaleRangeMode = 'Never'
    lut.RescaleTransferFunction(-1.0, 1.0); lut.UseLogScale = 0
    signed_labels(lut, top, lo)
else:
    preset(lut, ('Viridis (matplotlib)', 'Viridis', 'Jet'))
    lut.AutomaticRescaleRangeMode = 'Never'
    lut.RescaleTransferFunction(lo, top)
    lut.UseLogScale = 1
if vfiles:
    ilut = GetColorTransferFunction('Ic' if SIGNED else 'I')  # type: ignore #noqa: F405
    preset(ilut, ('Cold and Hot', 'Cool to Warm'))
    ilut.AutomaticRescaleRangeMode = 'Never'
    if SIGNED: ilut.RescaleTransferFunction(-1.0, 1.0); ilut.UseLogScale = 0; signed_labels(ilut, imax, imax * 10 ** -DECADES)
    else: ilut.RescaleTransferFunction(VIA_MIN * imax, imax); ilut.UseLogScale = 1
    for gd in via_disps:
        gd.SetScalarBarVisibility(v4, True)
    sb = GetScalarBar(ilut, v4); sb.Title = 'I via, + up (A)' if SIGNED else '|I| via (A)'; sb.ComponentTitle = ''  # type: ignore #noqa: F405
    if SIGNED: sb.DrawTickLabels = 0; sb.AddRangeLabels = 0; sb.DrawAnnotations = 1
    sb.WindowLocation = 'Any Location'; sb.Position = [0.88, 0.08] if LAYOUT == 'vertical' else [0.82, 0.05]; sb.ScalarBarLength = 0.8 if LAYOUT == 'vertical' else 0.35; sb.RangeLabelFormat = '%-#.2g'; sb.LabelFormat = '%-#.2g'; sb.TitleFontSize = sb.LabelFontSize = 9
for d, view in surf_disps:
    d.SetScalarBarVisibility(view, view is not v4)
    sb = GetScalarBar(lut, view)  # type: ignore #noqa: F405
    sb.Title = 'K along feed, + to antenna (A/m)' if SIGNED else '|K| (A/m)'; sb.ComponentTitle = ''
    if SIGNED: sb.DrawTickLabels = 0; sb.AddRangeLabels = 0; sb.DrawAnnotations = 1
    sb.WindowLocation = 'Any Location'; sb.Position = [0.86, 0.08] if LAYOUT == 'vertical' else [0.74 if SIGNED else 0.80, 0.45]; sb.ScalarBarLength = 0.8 if LAYOUT == 'vertical' else 0.45; sb.RangeLabelFormat = '%-#.2g'; sb.LabelFormat = '%-#.2g'
    sb.TitleFontSize = sb.LabelFontSize = 9

# ---- phase and cameras ----
scene = GetAnimationScene()  # type: ignore #noqa: F405
scene.UpdateAnimationUsingDataTimeSteps()
tk = GetTimeKeeper()  # type: ignore #noqa: F405
if nph and len(tk.TimestepValues) > pidx:
    scene.AnimationTime = tk.TimestepValues[pidx]
ph = Text(registrationName='phase')  # type: ignore #noqa: F405
ph.Text = f'start {pidx * STEP:.0f} deg ({"max feed current" if PHASE == "auto" else "PHASE"}), {nph} frames = {CYC:g} periods'
phd = Show(ph, v2); phd.WindowLocation = 'Lower Left Corner'; phd.FontSize = 10  # type: ignore #noqa: F405
cx, cy = ((VIEW[0] + VIEW[1]) / 2, (VIEW[2] + VIEW[3]) / 2) if VIEW else (17, 12)
span = builtins.max(VIEW[1] - VIEW[0], VIEW[3] - VIEW[2]) if VIEW else 30
for v in (v1, v2, v3):
    v.CameraParallelProjection = 1
    v.CameraFocalPoint = [cx, cy, 0]; v.CameraPosition = [cx, cy, 50]; v.CameraViewUp = [0, 1, 0]
    v.CameraParallelScale = 0.5 * (VIEW[3] - VIEW[2]) * 1.05 if VIEW else 15
AddCameraLink(v1, v2, 'planes12')  # type: ignore #noqa: F405
AddCameraLink(v1, v3, 'planes13')  # type: ignore #noqa: F405
v4.CameraFocalPoint = [cx, cy, -2.5]; v4.CameraPosition = [cx - 0.55 * span, cy - 0.9 * span, 0.55 * span]; v4.CameraViewUp = [0, 0, 1]
SetActiveView(v2)  # type: ignore #noqa: F405
for v in (v1, v2, v3, v4): Render(v)  # type: ignore #noqa: F405
if SHOT:
    tag, res = ('stack', [1600, 2400]) if LAYOUT == 'vertical' else ('2x2', [2400, 1600])
    SaveScreenshot(f'{SHOT}_{tag}.png', layout, ImageResolution=res)  # type: ignore #noqa: F405
    print('saved', f'{SHOT}_{tag}.png')
