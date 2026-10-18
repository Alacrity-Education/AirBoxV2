#!/usr/bin/env python3
"""Return current on the ground planes and in the vias from a sim_pi.py --hdump run, at one frequency.

    python via_current.py <run_dir> <variant>/fab/<board>-PTH.drl      # once, writes via_*.vtp into the run dir
    python sheet_current.py <run_dir>                                     # once, if the run has the *_below planes
    DUMP_DIR=pi_void_neck/nport_h2/run_0 FREQ=0.75 paraview --script paraview_return.py

Layout (2 x 2): In1.Cu (GND1) | F.Cu (pour + trace) / In2.Cu (GND2) | 3-D stack. The three top views (linked cameras)
show each layer's 3-D copper (KiCad export) with arrows of the surface current K = z x H just above it, only where
that layer has copper (mask from gerber2ems' layer images), coloured by |K| (log scale, same in every view). The 3-D
view stacks In2 / In1 / F.Cu with the via barrels between them and adds vertical arrows for the via current in the
F.Cu-In1 and In1-In2 segments (Ampere loop around each via, via_current.py). Arrows are the current at one phase of
the cycle; play the animation to sweep the phase. Units are relative (frequency-domain amplitudes of the pulse).
Options (environment): FREQ (GHz), DECADES=3, PCT=99.9, STRIDE=3, ARROW_MIN=0.05, ARROW=0.3 (mm), Z3=4 (3-D view
height exaggeration), MASK=1.
"""
import builtins, json, os, re
from pathlib import Path
from paraview.simple import *  # type: ignore #noqa: F403
from paraview import servermanager as sm
from vtkmodules.numpy_interface import dataset_adapter as dsa
import numpy as np

path = Path(os.environ.get('DUMP_DIR', '.')).resolve()
FREQ = int(round(float(os.environ.get('FREQ', '0.75')) * 1e9))
DECADES = float(os.environ.get('DECADES', '3'))
PCT = float(os.environ.get('PCT', '99.9'))
STRIDE = int(os.environ.get('STRIDE', '3'))
ARROW_MIN = float(os.environ.get('ARROW_MIN', '0.05'))
ARROW = float(os.environ.get('ARROW', '0.3'))
Z3 = float(os.environ.get('Z3', '4'))
MASK = os.environ.get('MASK', '1') == '1'
variant = path.parent.parent
cdir = Path(os.environ.get('COPPER_DIR', str(variant / 'copper3d'))).resolve()
gdir = variant / 'ems' / 'geometry'
paraview.simple._DisableFirstRenderCameraReset()  # type: ignore #noqa: F405
COPPER, RFCU = [0.80, 0.52, 0.30], [0.95, 0.72, 0.35]
# plane -> (title, copper z slab in mm (KiCad export, openEMS frame), gerber2ems layer image for the mask)
PLANES = {'h_inner': ('In1.Cu (GND1)', (-0.105, -0.07), 'In1_Cu.png'),
          'h_top': ('F.Cu (pour + trace)', (-0.01, 0.045), 'F_Cu.png'),
          'h_in2': ('In2.Cu (GND2)', (-1.39, -1.35), 'In2_Cu.png')}

def fd_files(prefix):
    files = sorted(path.glob(f'{prefix}_f={FREQ}_*'))
    if not files:
        raise SystemExit(f'no {prefix} files at {FREQ} Hz in {path}')
    return [str(f) for f in files if '_abs.' in f.name], [str(f) for f in files if '_p=' in f.name]

def mm(src, name):
    t = Transform(registrationName=name, Input=src)  # type: ignore #noqa: F405
    t.Transform.Scale = [1000, 1000, 1000]          # openEMS dumps are in metres (H vectors scale too: relative units)
    try:
        t.TransformAllInputVectors = 1              # every vector array (H-Field, Kvec) x1000, consistently
    except AttributeError:
        pass
    return t

def thresh(src, name, array, lo, hi, allpts=False):
    th = Threshold(registrationName=name, Input=src)  # type: ignore #noqa: F405
    th.Scalars = ['POINTS', array]
    try:
        th.LowerThreshold, th.UpperThreshold = lo, hi
    except AttributeError:
        th.ThresholdRange = [lo, hi]
    if allpts:
        try:
            th.AllScalars = 1
        except AttributeError:
            pass
    return th

def copper_look(d, rf=False, opacity=1.0):
    d.ColorArrayName = ['POINTS', '']
    d.AmbientColor = d.DiffuseColor = RFCU if rf else COPPER
    d.Specular = 0.4
    d.Opacity = opacity

# ---- layout: 2 x 2 ----
v1 = GetActiveViewOrCreate('RenderView')  # type: ignore #noqa: F405
layout = GetLayout(v1)  # type: ignore #noqa: F405
if layout is None:
    layout = CreateLayout('return current')  # type: ignore #noqa: F405
    AssignViewToLayout(view=v1, layout=layout, hint=0)  # type: ignore #noqa: F405
layout.SplitHorizontal(0, 0.5)
layout.SplitVertical(1, 0.5)
layout.SplitVertical(2, 0.5)
v2, v3, v4 = (CreateView('RenderView') for _ in range(3))  # type: ignore #noqa: F405
AssignViewToLayout(view=v2, layout=layout, hint=5)  # type: ignore #noqa: F405
AssignViewToLayout(view=v3, layout=layout, hint=4)  # type: ignore #noqa: F405
AssignViewToLayout(view=v4, layout=layout, hint=6)  # type: ignore #noqa: F405
VIEWS = {'h_inner': v1, 'h_top': v2, 'h_in2': v3}
for v in (v1, v2, v3, v4):
    v.Background = [0.1, 0.1, 0.12]
    try:
        v.UseColorPaletteForBackground = 0
    except AttributeError:
        pass
    v.OrientationAxesVisibility = 0

# ---- copper (KiCad PLY, z aligned to the openEMS frame) ----
dz = json.load(open(cdir / 'align.json'))['dz_mm']
cu = {}
for name in ('copper_gnd', 'copper_rf'):
    r = PLYReader(registrationName=name + '_kicad', FileNames=[str(cdir / (name + '.ply'))])  # type: ignore #noqa: F405
    t = Transform(registrationName=name, Input=r)  # type: ignore #noqa: F405
    t.Transform.Translate = [0, 0, dz]
    cz = Calculator(registrationName=name + ' z', Input=t)  # type: ignore #noqa: F405
    cz.ResultArrayName = 'zmm'
    cz.Function = 'coordsZ'
    cu[name] = cz
    # 3-D view: everything from In2.Cu to F.Cu, via barrels cut at those layers
    c1 = Clip(registrationName=name + ' above In2', Input=t)  # type: ignore #noqa: F405
    c1.ClipType = 'Plane'; c1.ClipType.Origin = [0, 0, -1.39]; c1.ClipType.Normal = [0, 0, 1]; c1.Invert = 0
    c2 = Clip(registrationName=name + ' In2..F.Cu', Input=c1)  # type: ignore #noqa: F405
    c2.ClipType = 'Plane'; c2.ClipType.Origin = [0, 0, 0.045]; c2.ClipType.Normal = [0, 0, 1]; c2.Invert = 1
    d = Show(c2, v4); copper_look(d, rf=(name == 'copper_rf'), opacity=0.45); d.Scale = [1, 1, Z3]  # type: ignore #noqa: F405
for plane, (title, (z0, z1), _) in PLANES.items():
    for name in ('copper_gnd', 'copper_rf'):
        th = thresh(cu[name], f'{name} {plane} layer', 'zmm', z0, z1, allpts=True)
        th.UpdatePipeline()
        if th.GetDataInformation().GetNumberOfPoints() == 0: continue
        copper_look(Show(th, VIEWS[plane]), rf=(name == 'copper_rf'))  # type: ignore #noqa: F405

# ---- plane arrows: K = z x H at one phase, masked to the layer's copper, coloured by |K| ----
MASK_SCRIPT = r'''
import builtins, numpy as np
from vtkmodules.vtkIOImage import vtkPNGReader
from vtkmodules.util.numpy_support import vtk_to_numpy
from vtkmodules.numpy_interface import dataset_adapter as dsa
key = 'cu_mask_' + PNG
if not hasattr(builtins, key):
    r = vtkPNGReader(); r.SetFileName(PNG); r.Update(); im = r.GetOutput()
    nx, ny, _ = im.GetDimensions()
    a = vtk_to_numpy(im.GetPointData().GetScalars()).reshape(ny, nx, -1)
    setattr(builtins, key, (a[..., 1] > 128) & (a[..., 0] < 128))   # gerber2ems: copper = green; row 0 = bottom (y = 0)
m = getattr(builtins, key)
out = self.GetOutputDataObject(0); out.ShallowCopy(self.GetInputDataObject(0, 0))
w = dsa.WrapDataObject(out); P = np.asarray(w.Points)
col = np.clip((P[:, 0] / PIX).astype(int), 0, m.shape[1] - 1); row = np.clip((P[:, 1] / PIX).astype(int), 0, m.shape[0] - 1)
cu = m[row, col].astype(float)
K = np.asarray(w.PointData['K']) * cu
w.PointData.append(K, 'K')
w.PointData.append(cu, 'copper')
'''
peak = 0.0
pcts = {}
glyph_disps, thresholds = [], []
for plane, (title, _, png) in PLANES.items():
    view = VIEWS[plane]
    kfiles = sorted(path.glob(f'k_{plane}_f={FREQ}_*.vtr'))   # sheet_current.py: K = z x (H_above - H_below), both faces
    if kfiles:
        a = mm(XMLRectilinearGridReader(registrationName=f'raw k {plane} abs', FileName=[str(f) for f in kfiles if '_abs.' in f.name]), f'{plane} abs mm')  # type: ignore #noqa: F405
        k = Calculator(registrationName=f'{plane} |K|', Input=a)  # type: ignore #noqa: F405
        k.ResultArrayName = 'K'
        k.Function = 'mag(Kvec)'
        k.UpdatePipeline()
        kvals = np.array(dsa.WrapDataObject(sm.Fetch(k)).PointData['K'])
        kv = mm(XMLRectilinearGridReader(registrationName=f'raw k {plane} phase', FileName=[str(f) for f in kfiles if '_p=' in f.name]), f'{plane} K vector')  # type: ignore #noqa: F405
        kv.UpdatePipeline()
    else:                                                      # only the plane above: K = z x H_above
        absf, phase = fd_files(plane)
        a = mm(XMLRectilinearGridReader(registrationName=f'raw {plane} abs', FileName=absf), f'{plane} abs mm')  # type: ignore #noqa: F405
        k = Calculator(registrationName=f'{plane} |K|', Input=a)  # type: ignore #noqa: F405
        k.ResultArrayName = 'K'
        k.Function = 'sqrt("H-Field_X"^2+"H-Field_Y"^2)'
        k.UpdatePipeline()
        kvals = np.array(dsa.WrapDataObject(sm.Fetch(k)).PointData['K'])
        p = mm(XMLRectilinearGridReader(registrationName=f'raw {plane} phase', FileName=phase), f'{plane} phase mm')  # type: ignore #noqa: F405
        kv = Calculator(registrationName=f'{plane} K vector', Input=p)  # type: ignore #noqa: F405
        kv.ResultArrayName = 'Kvec'
        kv.Function = '-"H-Field_Y"*iHat+"H-Field_X"*jHat'
        kv.UpdatePipeline()
    pcts[plane] = float(np.percentile(kvals, PCT))
    if plane != 'h_in2': peak = builtins.max(peak, pcts[plane])   # colour scale from the top layers; In2 is much weaker
    ex = ExtractSubset(registrationName=f'{plane} K thinned', Input=kv)  # type: ignore #noqa: F405
    ex.VOI = list(kv.GetDataInformation().GetExtent())
    ex.SampleRateI = ex.SampleRateJ = STRIDE
    ex.SampleRateK = 1
    km = Calculator(registrationName=f'{plane} K now', Input=ex)  # type: ignore #noqa: F405
    km.ResultArrayName = 'K'
    km.Function = 'mag(Kvec)'
    src = km
    if MASK and (gdir / png).exists():
        mk = ProgrammableFilter(registrationName=f'{plane} on copper', Input=km)  # type: ignore #noqa: F405
        mk.Script = f'PNG = {str(gdir / png)!r}\nPIX = 0.005\n' + MASK_SCRIPT
        mk.CopyArrays = 1
        src = mk
    th = thresh(src, f'{plane} K flowing', 'K', 0, 1e30)
    thresholds.append((th, plane))
    g = Glyph(registrationName=f'{plane} K arrows', Input=th, GlyphType='Arrow')  # type: ignore #noqa: F405
    g.OrientationArray = ['POINTS', 'Kvec']
    g.ScaleArray = ['POINTS', 'No scale array']
    g.ScaleFactor = ARROW
    g.GlyphMode = 'All Points'
    for view, zsc in ((VIEWS[plane], 1), (v4, Z3)):
        gd = Show(g, view)  # type: ignore #noqa: F405
        ColorBy(gd, ('POINTS', 'K'))  # type: ignore #noqa: F405
        gd.Scale = [1, 1, zsc]
        gd.Translation = [0, 0, 0.01 * zsc]
        glyph_disps.append((gd, view))
    txt = Text(registrationName=f'title {plane}')  # type: ignore #noqa: F405
    txt.Text = f'{title}  {FREQ / 1e9:g} GHz'
    td = Show(txt, VIEWS[plane]); td.WindowLocation = 'Upper Left Corner'; td.FontSize = 12  # type: ignore #noqa: F405

# ---- via current arrows (3-D view): F.Cu-In1 (mid prepreg) and In1-In2 (mid core) segments ----
seg_len = {'h_pp': 0.06, 'h_core': 1.1}   # mm, arrow length ~ 0.8 x segment height (before Z3)
for seg in ('h_pp', 'h_core'):
    files = sorted(path.glob(f'via_{seg}_f={FREQ}_p=*.vtp'))
    if not files:
        print('no via current files; run via_current.py first'); break
    r = XMLPolyDataReader(registrationName=f'via {seg}', FileName=[str(f) for f in files])  # type: ignore #noqa: F405
    t = Transform(registrationName=f'via {seg} z', Input=r)  # type: ignore #noqa: F405
    t.Transform.Scale = [1, 1, Z3]          # place at the exaggerated height; arrows themselves not stretched
    th = thresh(t, f'via {seg} flowing', 'K', 0, 1e30)
    thresholds.append((th, 'via'))
    g = Glyph(registrationName=f'via {seg} arrows', Input=th, GlyphType='Arrow')  # type: ignore #noqa: F405
    g.OrientationArray = ['POINTS', 'Kvec']
    g.ScaleArray = ['POINTS', 'No scale array']
    g.ScaleFactor = seg_len[seg] * Z3
    g.GlyphMode = 'All Points'
    g.GlyphTransform.Translate = [-0.5, 0, 0]   # centre the arrow on the segment
    rel = 0.12 / (seg_len[seg] * Z3)         # ~0.12 mm head radius whatever the length
    g.GlyphType.TipRadius = builtins.min(0.45, rel); g.GlyphType.ShaftRadius = builtins.min(0.2, rel * 0.4); g.GlyphType.TipLength = 0.3
    gd = Show(g, v4)  # type: ignore #noqa: F405
    ColorBy(gd, ('POINTS', 'K'))  # type: ignore #noqa: F405
    glyph_disps.append((gd, v4))
t4 = Text(registrationName='title 3d'); t4.Text = f'stack In2 / In1 / F.Cu + via current  {FREQ / 1e9:g} GHz  (z x{Z3:g})'  # type: ignore #noqa: F405
d4 = Show(t4, v4); d4.WindowLocation = 'Upper Left Corner'; d4.FontSize = 12  # type: ignore #noqa: F405

# ---- shared colour scale ----
lut = GetColorTransferFunction('K')  # type: ignore #noqa: F405
for preset in ('Viridis (matplotlib)', 'Viridis', 'Turbo', 'Jet'):
    try:
        if lut.ApplyPreset(preset, True): break
    except RuntimeError:
        pass
try:
    lut.AutomaticRescaleRangeMode = 'Never'
except AttributeError:
    pass
lut.RescaleTransferFunction(peak * 10 ** -DECADES, peak)
lut.UseLogScale = 1
for th, kind in thresholds:
    # arrows: top layers above ARROW_MIN x colour top; In2 above ARROW_MIN x its own top; vias above the colour floor
    lo = peak * 10 ** -DECADES if kind == 'via' else ARROW_MIN * (pcts['h_in2'] if kind == 'h_in2' else peak)
    try:
        th.LowerThreshold, th.UpperThreshold = lo, 1e30
    except AttributeError:
        th.ThresholdRange = [lo, 1e30]
for gd, view in glyph_disps:
    gd.SetScalarBarVisibility(view, view is not v4)
    sb = GetScalarBar(lut, view)  # type: ignore #noqa: F405
    sb.Title = '|K| (rel.)'; sb.ComponentTitle = ''
    sb.WindowLocation = 'Any Location'; sb.Position = [0.88, 0.45]; sb.ScalarBarLength = 0.4
    sb.TitleFontSize = sb.LabelFontSize = 9

GetAnimationScene().UpdateAnimationUsingDataTimeSteps()  # type: ignore #noqa: F405
for v in (v1, v2, v3):
    v.CameraParallelProjection = 0
    v.CameraFocalPoint = [17, 12, 0]; v.CameraPosition = [17, -2, 40]; v.CameraViewUp = [0, 1, 0]
    v.ResetCamera(False)
AddCameraLink(v1, v2, 'planes12')  # type: ignore #noqa: F405
AddCameraLink(v1, v3, 'planes13')  # type: ignore #noqa: F405
v4.CameraFocalPoint = [8, 4, -2.5]; v4.CameraPosition = [-4, -12, 10]; v4.CameraViewUp = [0, 0, 1]
SetActiveView(v1)  # type: ignore #noqa: F405
for v in (v1, v2, v3, v4): Render(v)  # type: ignore #noqa: F405
