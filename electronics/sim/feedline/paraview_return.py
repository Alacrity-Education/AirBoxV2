#!/usr/bin/env python3
"""Return current on the ground planes from a sim_pi.py --hdump run, at one frequency.

    DUMP_DIR=pi_void_neck/nport_h/run_0 FREQ=0.75 paraview --script paraview_return.py

Two linked views: left = GND1 (In1.Cu), right = top layer (F.Cu: ground pour + trace). Each shows that layer's
3-D copper (KiCad export, export_copper3d.py) with arrows of the surface current K = z x H just above it, coloured
by |K| (log scale over DECADES decades, same scale in both views). The arrows are the current at one phase of the
cycle; play the animation to sweep the phase. 'h_inner |K|' / 'h_top |K|' (steady-state |K| as a coloured plane) are
in the Pipeline Browser, hidden. The H planes are the first mesh planes above GND1 (19 um, in the prepreg) and
above F.Cu (30 um, in air). Units are relative (frequency-domain amplitudes of the excitation pulse).
Options (environment): FREQ (GHz), DECADES=3, PCT=99.9 (colour top = percentile of |K|), STRIDE=3 (arrow
spacing in mesh cells), ARROW_MIN=0.05 (arrows only where |K| > ARROW_MIN x colour top), ARROW=0.3 (arrow length, mm),
ZSCALE=1 (height exaggeration).
"""
import json, os, re
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
ZSCALE = float(os.environ.get('ZSCALE', '1'))
LIFT = 0.01   # mm: arrows drawn this far above their H plane
cdir = Path(os.environ.get('COPPER_DIR', str(path.parent.parent / 'copper3d'))).resolve()
paraview.simple._DisableFirstRenderCameraReset()  # type: ignore #noqa: F405
COPPER = [0.80, 0.52, 0.30]
LAYERS = {'h_inner': (-0.105, -0.07), 'h_top': (-0.01, 0.045)}   # mm, sim frame: In1.Cu and F.Cu copper slabs

def fd_files(plane):
    files = sorted(path.glob(f'{plane}_f={FREQ}_*.vtr'))
    if not files:
        have = sorted({re.search(r'f=(\d+)', f.name).group(1) for f in path.glob(f'{plane}_f=*.vtr')})
        raise SystemExit(f'no {plane} dump at {FREQ} Hz in {path}; available: {have}')
    return [str(f) for f in files if f.stem.endswith('_abs')], [str(f) for f in files if '_p=' in f.stem]

def mm(src, name):
    t = Transform(registrationName=name, Input=src)  # type: ignore #noqa: F405
    t.Transform.Scale = [1000, 1000, 1000]          # openEMS dumps are in metres
    return t

def zs(disp):
    disp.Scale = [1, 1, ZSCALE]

# ---- views: left In1.Cu, right top layer, linked cameras ----
v1 = GetActiveViewOrCreate('RenderView')  # type: ignore #noqa: F405
layout = GetLayout(v1)  # type: ignore #noqa: F405
if layout is None:
    layout = CreateLayout('return current')  # type: ignore #noqa: F405
    AssignViewToLayout(view=v1, layout=layout, hint=0)  # type: ignore #noqa: F405
layout.SplitHorizontal(0, 0.5)
v2 = CreateView('RenderView')  # type: ignore #noqa: F405
AssignViewToLayout(view=v2, layout=layout, hint=2)  # type: ignore #noqa: F405
VIEWS = {'h_inner': v1, 'h_top': v2}
TITLES = {'h_inner': 'In1.Cu (GND1)', 'h_top': 'F.Cu (pour + trace)'}
for v in (v1, v2):
    v.Background = [0.1, 0.1, 0.12]
    try:
        v.UseColorPaletteForBackground = 0
    except AttributeError:
        pass
    v.OrientationAxesVisibility = 0

# ---- 3-D copper of each layer (KiCad), z aligned to the openEMS frame ----
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
for plane, (z0, z1) in LAYERS.items():
    for name in ('copper_gnd', 'copper_rf'):
        th = Threshold(registrationName=f'{name} {plane} layer', Input=cu[name])  # type: ignore #noqa: F405
        th.Scalars = ['POINTS', 'zmm']
        try:
            th.LowerThreshold, th.UpperThreshold = z0, z1
        except AttributeError:
            th.ThresholdRange = [z0, z1]
        try:
            th.AllScalars = 1
        except AttributeError:
            pass
        th.UpdatePipeline()
        if th.GetDataInformation().GetNumberOfPoints() == 0: continue
        d = Show(th, VIEWS[plane])  # type: ignore #noqa: F405
        d.ColorArrayName = ['POINTS', '']
        d.AmbientColor = d.DiffuseColor = COPPER if name == 'copper_gnd' else [0.95, 0.72, 0.35]
        d.Specular = 0.4
        zs(d)
parts = PLYReader(registrationName='parts', FileNames=[str(cdir / 'parts.ply')]) if (cdir / 'parts.ply').exists() else None  # type: ignore #noqa: F405
if parts is not None:
    pt = Transform(registrationName='parts aligned', Input=parts)  # type: ignore #noqa: F405
    pt.Transform.Translate = [0, 0, dz]
    d = Show(pt, v2); d.ColorArrayName = ['POINTS', '']; d.AmbientColor = d.DiffuseColor = [0.55, 0.55, 0.6]; d.Opacity = 0.3; zs(d)  # type: ignore #noqa: F405

# ---- |K| planes (hidden) and K arrows (shown), coloured by |K| ----
peak = 0.0
arrows, planes, thresholds = [], [], []
for plane in ('h_inner', 'h_top'):
    view = VIEWS[plane]
    absf, phase = fd_files(plane)
    a = mm(XMLRectilinearGridReader(registrationName=f'raw {plane} abs', FileName=absf), f'{plane} abs mm')  # type: ignore #noqa: F405
    k = Calculator(registrationName=f'{plane} |K|', Input=a)  # type: ignore #noqa: F405
    k.ResultArrayName = 'K'
    k.Function = 'sqrt("H-Field_X"^2+"H-Field_Y"^2)'
    k.UpdatePipeline()
    peak = max(peak, float(np.percentile(np.array(dsa.WrapDataObject(sm.Fetch(k)).PointData['K']), PCT)))
    d = Show(k, view); ColorBy(d, ('POINTS', 'K')); zs(d); planes.append((k, d, view))  # type: ignore #noqa: F405
    p = mm(XMLRectilinearGridReader(registrationName=f'raw {plane} phase', FileName=phase), f'{plane} phase mm')  # type: ignore #noqa: F405
    kv = Calculator(registrationName=f'{plane} K vector', Input=p)  # type: ignore #noqa: F405
    kv.ResultArrayName = 'Kvec'
    kv.Function = '-"H-Field_Y"*iHat+"H-Field_X"*jHat'
    kv.UpdatePipeline()
    ex = ExtractSubset(registrationName=f'{plane} K thinned', Input=kv)  # type: ignore #noqa: F405
    ext = kv.GetDataInformation().GetExtent()
    ex.VOI = list(ext)
    ex.SampleRateI = ex.SampleRateJ = STRIDE
    ex.SampleRateK = 1
    km = Calculator(registrationName=f'{plane} K now', Input=ex)  # type: ignore #noqa: F405
    km.ResultArrayName = 'K'
    km.Function = 'mag(Kvec)'
    th = Threshold(registrationName=f'{plane} K flowing', Input=km)  # type: ignore #noqa: F405
    th.Scalars = ['POINTS', 'K']
    thresholds.append(th)
    g = Glyph(registrationName=f'{plane} K arrows', Input=th, GlyphType='Arrow')  # type: ignore #noqa: F405
    g.OrientationArray = ['POINTS', 'Kvec']
    g.ScaleArray = ['POINTS', 'No scale array']
    g.ScaleFactor = ARROW
    g.GlyphMode = 'All Points'
    gd = Show(g, view)  # type: ignore #noqa: F405
    ColorBy(gd, ('POINTS', 'K'))  # type: ignore #noqa: F405
    gd.Translation = [0, 0, LIFT * ZSCALE]
    zs(gd)
    arrows.append((gd, view))
    txt = Text(registrationName=f'title {plane}')  # type: ignore #noqa: F405
    txt.Text = f'{TITLES[plane]}  {FREQ / 1e9:g} GHz'
    td = Show(txt, view); td.WindowLocation = 'Upper Left Corner'; td.FontSize = 12  # type: ignore #noqa: F405

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
for th in thresholds:
    try:
        th.LowerThreshold, th.UpperThreshold = ARROW_MIN * peak, 1e30
    except AttributeError:
        th.ThresholdRange = [ARROW_MIN * peak, 1e30]
for k, d, view in planes:
    Hide(k, view)  # type: ignore #noqa: F405
for gd, view in arrows:
    gd.SetScalarBarVisibility(view, True)
    sb = GetScalarBar(lut, view)  # type: ignore #noqa: F405
    sb.Title = '|K| (rel.)'
    sb.ComponentTitle = ''
    sb.WindowLocation = 'Any Location'; sb.Position = [0.88, 0.5]; sb.ScalarBarLength = 0.35
    sb.TitleFontSize = sb.LabelFontSize = 10

GetAnimationScene().UpdateAnimationUsingDataTimeSteps()  # type: ignore #noqa: F405
for v in (v1, v2):
    v.CameraParallelProjection = 0
    v.CameraFocalPoint = [17, 12, 0]
    v.CameraPosition = [17, -2, 40]
    v.CameraViewUp = [0, 1, 0]
    v.ResetCamera(False)
AddCameraLink(v1, v2, 'planes')  # type: ignore #noqa: F405
SetActiveView(v1)  # type: ignore #noqa: F405
Render(v1); Render(v2)  # type: ignore #noqa: F405
