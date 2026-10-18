#!/usr/bin/env python3
"""Open the E-field dumps of a sim_pi.py run (--dump=1) in ParaView, one time series per dump plane.

    DUMP_DIR=pi_neck/nport_dump/run_0 paraview --script paraview_nport.py
"""
import os
from pathlib import Path

from paraview.simple import *  # type: ignore #noqa: F403

path = Path(os.environ.get("DUMP_DIR", ".")).resolve()
groups = {}
for f in sorted(path.glob("*.vtr")):
    groups.setdefault(f.stem.rstrip("0123456789").rstrip("_"), []).append(str(f))
if not groups:
    raise SystemExit(f"no .vtr dumps in {path}")

paraview.simple._DisableFirstRenderCameraReset()  # type: ignore #noqa: F405
view = GetActiveViewOrCreate("RenderView")  # type: ignore #noqa: F405
display = None
for name, files in sorted(groups.items()):
    reader = XMLRectilinearGridReader(registrationName=name, FileName=files)  # type: ignore #noqa: F405
    reader.PointArrayStatus = ["E-Field"]
    scene = GetAnimationScene()  # type: ignore #noqa: F405
    scene.UpdateAnimationUsingDataTimeSteps()
    display = Show(reader, view, "UniformGridRepresentation")  # type: ignore #noqa: F405
    ColorBy(display, ("POINTS", "E-Field", "Magnitude"))  # type: ignore #noqa: F405
    Hide()  # type: ignore #noqa: F405
Show()  # type: ignore #noqa: F405
display.RescaleTransferFunctionToDataRangeOverTime()
display.SetScalarBarVisibility(view, True)
view.Update()
view.ResetCamera(False)
scene.Play()
