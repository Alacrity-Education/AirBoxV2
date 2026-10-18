#!/usr/bin/env python3
"""Return current on the ground planes from a sim_pi.py --hdump run, at one frequency.

    DUMP_DIR=pi_void_neck/nport_h/run_0 FREQ=0.75 paraview --script paraview_return.py

Two linked top-down views: left = just above GND1 (In1.Cu, 19 um up in the prepreg), right = just above F.Cu (30 um, in
air: the top ground pour and the trace). Colour = surface current density |K| = |H tangential| (steady state at FREQ,
log scale over 3 decades, same scale in both views); arrows = K = z x H at one phase of the cycle (play the animation
to sweep the phase); gold outline = the RF copper (trace, pads), grey = 0402 parts. Units are relative (openEMS
frequency-domain amplitudes of the excitation pulse); the pattern and the ratios are what matter.
"""
import os, re
from pathlib import Path
from paraview.simple import *  # type: ignore #noqa: F403

path = Path(os.environ.get('DUMP_DIR', '.')).resolve()
FREQ = int(round(float(os.environ.get('FREQ', '0.75')) * 1e9))
DECADES = float(os.environ.get('DECADES', '3'))
STRIDE = int(os.environ.get('STRIDE', '3'))
PCT = float(os.environ.get('PCT', '99.9'))       # colour scale top = this percentile of |K| over both planes
ARROW_MIN = float(os.environ.get('ARROW_MIN', '0.05'))   # arrows only where |K| > ARROW_MIN x scale top
cdir = Path(os.environ.get('COPPER_DIR', str(path.parent.parent / 'copper3d'))).resolve()
paraview.simple._DisableFirstRenderCameraReset()  # type: ignore #noqa: F405

def fd_files(plane):
    files = sorted(path.glob(f'{plane}_f={FREQ}_*.vtr'))
    if not files:
        have = sorted({re.search(r'f=(\d+)', f.name).group(1) for f in path.glob(f'{plane}_f=*.vtr')})
        raise SystemExit(f'no {plane} dump at {FREQ} Hz in {path}; available: {have}')
    absf = [str(f) for f in files if f.stem.endswith('_abs')]
    phase = [str(f) for f in files if '_p=' in f.stem]
    return absf, phase

def mm(src, name):
    t = Transform(registrationName=name, Input=src)  # type: ignore #noqa: F405
    t.Transform.Scale = [1000, 1000, 1000]          # openEMS dumps are in metres
    return t

v1 = GetActiveViewOrCreate('RenderView')  # type: ignore #noqa: F405
layout = GetLayout(v1)  # type: ignore #noqa: F405
if layout is None:
    layout = CreateLayout('return current')  # type: ignore #noqa: F405
    AssignViewToLayout(view=v1, layout=layout, hint=0)  # type: ignore #noqa: F405
layout.SplitHorizontal(0, 0.5)
v2 = CreateView('RenderView')  # type: ignore #noqa: F405
AssignViewToLayout(view=v2, layout=layout, hint=2)  # type: ignore #noqa: F405
for v in (v1, v2):
    v.Background = [0.08, 0.08, 0.1]
    try:
        v.UseColorPaletteForBackground = 0
    except AttributeError:
        pass
    v.OrientationAxesVisibility = 0

# |K| (steady state) and K arrows (phase sweep) per plane
peak = 0.0
kdisp = []
thresholds = []
for plane, view, title in (('h_inner', v1, 'GND1 (In1.Cu)'), ('h_top', v2, 'top: GND pour + trace')):
    absf, phase = fd_files(plane)
    a = mm(XMLRectilinearGridReader(registrationName=f'raw {plane} abs', FileName=absf), f'{plane} abs mm')  # type: ignore #noqa: F405
    k = Calculator(registrationName=f'{plane} |K|', Input=a)  # type: ignore #noqa: F405
    k.ResultArrayName = 'K'
    k.Function = 'sqrt("H-Field_X"^2+"H-Field_Y"^2)'
    k.UpdatePipeline()
    from paraview import servermanager as _sm
    from vtkmodules.numpy_interface import dataset_adapter as _dsa
    import numpy as _np
    kvals = _np.array(_dsa.WrapDataObject(_sm.Fetch(k)).PointData['K'])
    peak = max(peak, float(_np.percentile(kvals, PCT)))   # percentile, not max: one edge hotspot is ~10x the rest
    d = Show(k, view)  # type: ignore #noqa: F405
    ColorBy(d, ('POINTS', 'K'))  # type: ignore #noqa: F405
    kdisp.append((d, view))
    if phase:
        p = mm(XMLRectilinearGridReader(registrationName=f'raw {plane} phase', FileName=phase), f'{plane} phase mm')  # type: ignore #noqa: F405
        kv = Calculator(registrationName=f'{plane} K vector', Input=p)  # type: ignore #noqa: F405
        kv.ResultArrayName = 'Kvec'
        kv.Function = '-"H-Field_Y"*iHat+"H-Field_X"*jHat'
        ex = ExtractSubset(registrationName=f'{plane} K thinned', Input=kv)  # type: ignore #noqa: F405
        ex.SampleRateI = ex.SampleRateJ = STRIDE
        ex.SampleRateK = 1
        kv.UpdatePipeline()
        ext = kv.GetDataInformation().GetExtent()
        ex.VOI = [ext[0], ext[1], ext[2], ext[3], ext[4], ext[5]]
        km = Calculator(registrationName=f'{plane} K mag', Input=ex)  # type: ignore #noqa: F405
        km.ResultArrayName = 'Kmag'
        km.Function = 'mag(Kvec)'
        th = Threshold(registrationName=f'{plane} K flowing', Input=km)  # type: ignore #noqa: F405
        th.Scalars = ['POINTS', 'Kmag']
        thresholds.append(th)
        g = Glyph(registrationName=f'{plane} K arrows', Input=th, GlyphType='Arrow')  # type: ignore #noqa: F405
        g.OrientationArray = ['POINTS', 'Kvec']
        g.ScaleArray = ['POINTS', 'No scale array']
        g.ScaleFactor = 0.35
        g.GlyphMode = 'All Points'
        gd = Show(g, view)  # type: ignore #noqa: F405
        gd.ColorArrayName = ['POINTS', '']
        gd.AmbientColor = gd.DiffuseColor = [0.85, 0.95, 1.0]
        gd.Opacity = 0.7
        gd.Translation = [0, 0, 0.02]
    txt = Text(registrationName=f'title {plane}')  # type: ignore #noqa: F405
    txt.Text = f'{title}  |K| @ {FREQ / 1e9:g} GHz'
    td = Show(txt, view); td.WindowLocation = 'Upper Left Corner'; td.FontSize = 12  # type: ignore #noqa: F405

lut = GetColorTransferFunction('K')  # type: ignore #noqa: F405
for preset in ('Inferno', 'Inferno (matplotlib)', 'Black-Body Radiation'):
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
for th in thresholds:   # phase-sweep amplitudes are comparable to the steady-state |K|
    try:
        th.LowerThreshold, th.UpperThreshold = ARROW_MIN * peak, 1e30
    except AttributeError:
        th.ThresholdRange = [ARROW_MIN * peak, 1e30]
for d, view in kdisp:
    d.SetScalarBarVisibility(view, True)
    sb = GetScalarBar(lut, view)  # type: ignore #noqa: F405
    sb.WindowLocation = 'Any Location'; sb.Position = [0.86, 0.5]; sb.ScalarBarLength = 0.35
    sb.TitleFontSize = sb.LabelFontSize = 10

# RF copper outline (on top of the field) and the parts, from export_copper3d.py (mm, same x/y frame)
for name, color, opacity in (('copper_rf', [1.0, 0.8, 0.3], 1.0), ('parts', [0.6, 0.6, 0.65], 0.35)):
    f = cdir / (name + '.ply')
    if not f.exists(): continue
    r = PLYReader(registrationName=name, FileNames=[str(f)])  # type: ignore #noqa: F405
    fe = FeatureEdges(registrationName=name + ' outline', Input=r) if name == 'copper_rf' else r  # type: ignore #noqa: F405
    for view in (v1, v2):
        d = Show(fe, view)  # type: ignore #noqa: F405
        d.ColorArrayName = ['POINTS', '']
        d.AmbientColor = d.DiffuseColor = color
        d.Opacity = opacity
        d.Translation = [0, 0, 5]   # draw above the field planes in the top view
        if name == 'copper_rf':
            d.LineWidth = 1.0
            d.Opacity = 0.6

GetAnimationScene().UpdateAnimationUsingDataTimeSteps()  # type: ignore #noqa: F405
for v in (v1, v2):
    v.CameraParallelProjection = 1
    v.CameraFocalPoint = [17, 12, 0]
    v.CameraPosition = [17, 12, 100]
    v.CameraViewUp = [0, 1, 0]
    v.ResetCamera(False)
AddCameraLink(v1, v2, 'planes')  # type: ignore #noqa: F405
SetActiveView(v1)  # type: ignore #noqa: F405
Render(v1); Render(v2)  # type: ignore #noqa: F405
