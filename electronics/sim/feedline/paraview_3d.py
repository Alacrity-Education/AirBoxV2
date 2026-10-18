#!/usr/bin/env python3
"""3-D view of a sim_pi.py --dump3d run: simulated copper + E-field volume (time series) + frequency snapshots.

    DUMP_DIR=pi_void_neck/nport_3d/run_0 paraview --script paraview_3d.py

Loaded (Pipeline Browser): 'copper_rf' / 'copper_gnd' / 'parts' (KiCad copper and 0402 parts from
export_copper3d.py, aligned to the field grid), 'copper' (PEC_dump, the metal exactly as meshed, hidden), 'E(t)' (time series) with '|E|' shown as a volume, 'slice' (vertical cut across the feed, shown), 'contour' (|E| iso-surfaces, shown), '|E|' volume (hidden), and one 'E @ f' source per frequency of
the FD dump (hidden; use the real/imag arrays). Everything is in mm (openEMS dumps are metres, scaled x1000);
height is exaggerated ZSCALE times on every display so the 76 um prepreg is visible.
"""
import os
from pathlib import Path
from paraview.simple import *  # type: ignore #noqa: F403

ZSCALE = float(os.environ.get('ZSCALE', '5'))
CLIP = float(os.environ.get('CLIP', '0.08'))     # colour/opacity range = CLIP x the global |E| maximum (the port hotspot)
FRAME = float(os.environ.get('FRAME', '0.3'))    # starting frame as a fraction of the time series
path = Path(os.environ.get('DUMP_DIR', '.')).resolve()
paraview.simple._DisableFirstRenderCameraReset()  # type: ignore #noqa: F405
view = GetActiveViewOrCreate('RenderView')  # type: ignore #noqa: F405
view.Background = [1, 1, 1]
try:
    view.UseColorPaletteForBackground = 0
except AttributeError:
    pass

def scale(disp):
    disp.Scale = [1, 1, ZSCALE]

pec = sorted(path.glob('PEC*.vtp')) + sorted(path.glob('PEC*.vtk'))
if pec:
    cu0 = OpenDataFile(str(pec[0]))  # type: ignore #noqa: F405
    cu = Transform(registrationName='copper', Input=cu0)  # type: ignore #noqa: F405
    cu.Transform.Scale = [1000, 1000, 1000]
    d = Show(cu, view)  # type: ignore #noqa: F405
    d.ColorArrayName = ['POINTS', '']
    d.AmbientColor = d.DiffuseColor = [0.85, 0.5, 0.2]
    d.LineWidth = 1.5
    scale(d)

# KiCad copper (export_copper3d.py): mm, origin at the window's bottom-left corner; z shifted onto the openEMS frame
import json
cdir = Path(os.environ.get('COPPER_DIR', str(path.parent.parent / 'copper3d'))).resolve()
if (cdir / 'align.json').exists():
    dz = json.load(open(cdir / 'align.json'))['dz_mm']
    for name, color, opacity in (('copper_rf', [1.0, 0.78, 0.25], 1.0), ('copper_gnd', [0.72, 0.42, 0.2], 0.2), ('parts', [0.55, 0.55, 0.6], 0.45)):
        f = cdir / (name + '.ply')
        if not f.exists(): continue
        r = PLYReader(registrationName=name + '_mm', FileNames=[str(f)])  # type: ignore #noqa: F405
        tr = Transform(registrationName=name, Input=r)  # type: ignore #noqa: F405
        tr.Transform.Scale = [1, 1, ZSCALE]
        tr.Transform.Translate = [0, 0, dz * ZSCALE]
        d = Show(tr, view)  # type: ignore #noqa: F405
        d.ColorArrayName = ['POINTS', '']
        d.AmbientColor = d.DiffuseColor = color
        d.Opacity = opacity
    cu_src = FindSource('copper')  # type: ignore #noqa: F405
    if cu_src is not None: Hide(cu_src, view)  # type: ignore #noqa: F405   (meshed-copper grid stays available)

td = sorted(f for f in path.glob('e3d_[0-9]*.vtr'))
src = None
if td:
    src = XMLRectilinearGridReader(registrationName='E(t)', FileName=[str(f) for f in td])  # type: ignore #noqa: F405
    src.PointArrayStatus = ['E-Field']
    raw = src
    src = Transform(registrationName='E(t) mm', Input=raw)  # type: ignore #noqa: F405   (openEMS dumps are in metres)
    src.Transform.Scale = [1000, 1000, 1000]
    Hide(src, view)  # type: ignore #noqa: F405
    GetAnimationScene().UpdateAnimationUsingDataTimeSteps()  # type: ignore #noqa: F405
    calc = Calculator(registrationName='|E|', Input=src)  # type: ignore #noqa: F405
    calc.ResultArrayName = 'Emag'
    calc.Function = 'mag("E-Field")'
    d = Show(calc, view)  # type: ignore #noqa: F405
    d.SetRepresentationType('Volume')
    ColorBy(d, ('POINTS', 'Emag'))  # type: ignore #noqa: F405
    lut = GetColorTransferFunction('Emag')  # type: ignore #noqa: F405
    for preset in ('Inferno', 'Inferno (matplotlib)', 'Black-Body Radiation'):
        try:
            if lut.ApplyPreset(preset, True): break
        except RuntimeError:
            pass
    hi = 0.0   # peak |E| over the time series (every 4th frame; the port hotspot sets it)
    for tv in raw.TimestepValues[::4]:
        calc.UpdatePipeline(tv)
        hi = max(hi, calc.PointData['Emag'].GetRange()[1])
    lut.RescaleTransferFunction(0.0, CLIP * hi)
    GetOpacityTransferFunction('Emag').RescaleTransferFunction(0.0, CLIP * hi)  # type: ignore #noqa: F405
    try:
        lut.AutomaticRescaleRangeMode = 'Never'
    except AttributeError:
        pass
    d.SetScalarBarVisibility(view, True)
    scale(d)
    Hide(calc, view)  # type: ignore #noqa: F405   (volume of |E|: switch on with the eye icon of '|E|')
    sl = Slice(registrationName='slice', Input=calc)  # type: ignore #noqa: F405
    sl.SliceType = 'Plane'
    sl.SliceType.Origin = [0, float(os.environ.get('SLICE_Y', '12')), 0]   # across the long vertical feed segment
    sl.SliceType.Normal = [0, 1, 0]
    ds = Show(sl, view); ColorBy(ds, ('POINTS', 'Emag')); scale(ds)  # type: ignore #noqa: F405
    ct = Contour(registrationName='contour', Input=calc)  # type: ignore #noqa: F405
    ct.ContourBy = ['POINTS', 'Emag']
    ct.Isosurfaces = [f * CLIP * hi for f in (0.15, 0.4)]
    dc = Show(ct, view); ColorBy(dc, ('POINTS', 'Emag')); dc.Opacity = 0.6; scale(dc)  # type: ignore #noqa: F405

# frequency-domain dump: per frequency an |E| snapshot (_abs) and a phase sweep (_p=*, a time series that animates the
# steady-state wave at that single frequency); all hidden, switch on in the Pipeline Browser
import re
fds = {}
for f in sorted(path.glob('e3d_fd_f=*.vtr')):
    m = re.match(r'e3d_fd_f=(\d+)_(abs|arg|p=\d+)', f.stem)
    if m: fds.setdefault(int(m.group(1)), {}).setdefault('p' if m.group(2).startswith('p=') else m.group(2), []).append(str(f))
for fr, kinds in sorted(fds.items()):
    for kind, files in sorted(kinds.items()):
        if kind == 'arg': continue
        label = f'{fr / 1e9:g} GHz ' + ('|E| (steady state)' if kind == 'abs' else 'E phase sweep')
        s0 = XMLRectilinearGridReader(registrationName='raw ' + label, FileName=sorted(files))  # type: ignore #noqa: F405
        s = Transform(registrationName=label, Input=s0)  # type: ignore #noqa: F405
        s.Transform.Scale = [1000, 1000, 1000]
        Hide(s0, view)  # type: ignore #noqa: F405
        d = Show(s, view); scale(d); Hide(s, view)  # type: ignore #noqa: F405

if src is not None:
    SetActiveSource(src)  # type: ignore #noqa: F405
    ts = raw.TimestepValues
    if len(ts) > 1: GetAnimationScene().AnimationTime = ts[int(FRAME * (len(ts) - 1))]  # type: ignore #noqa: F405
if src is not None:   # lock the colour/opacity range last (earlier ColorBy calls may auto-rescale it)
    lut = GetColorTransferFunction('Emag')  # type: ignore #noqa: F405
    try:
        lut.AutomaticRescaleRangeMode = 'Never'
    except AttributeError:
        pass
    LOG = os.environ.get('LOG', '1') == '1'   # log colour scale over 3 decades shows the weak fringe field
    lut.RescaleTransferFunction(CLIP * hi * (1e-3 if LOG else 0.0), CLIP * hi)
    if LOG:
        lut.UseLogScale = 1
    GetOpacityTransferFunction('Emag').RescaleTransferFunction(0.0, CLIP * hi)  # type: ignore #noqa: F405
    for n in ('slice', 'contour'):
        GetDisplayProperties(FindSource(n), view).SetScalarBarVisibility(view, True)  # type: ignore #noqa: F405
view.ResetCamera(False)
view.CameraPosition = [view.CameraFocalPoint[0] - 12, view.CameraFocalPoint[1] - 16, view.CameraFocalPoint[2] + 15]
view.CameraViewUp = [0, 0, 1]
view.ResetCamera(False)
Render()  # type: ignore #noqa: F405
