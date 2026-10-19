#!/usr/bin/env python3
"""Whole-board gerber2ems project (all copper, the full board outline) with a scaled mesh.

    python3 build_board.py <name> <optimal_um> <inter_layers> [--board=PATH]
    e.g. python3 build_board.py board20 20 4 ; python3 build_board.py board13 13 6

Copies the board, refills the zones, sets the aux origin to the outline's bottom-left corner and exports gerbers (X2),
drill, stackup and a one-port pos file into <name>/fab. simulation.json takes the coarse study grid (optimal 40,
diagonal 40, perpendicular 150, max 400 um) scaled by optimal/40, with <inter_layers> mesh cells per dielectric; the
mesh is refined along the RF nets. Run with KiCad's python, then tools/g2e.sh <name> -g to build geometry + mesh.
"""
import json, os, shutil, subprocess, sys
import pcbnew

HERE = os.path.dirname(os.path.abspath(__file__)); WT = os.path.dirname(os.path.dirname(HERE))
args = [a for a in sys.argv[1:] if not a.startswith('--')]
NAME, OPT, NZ = args[0], float(args[1]), int(args[2])
BOARD = next((a.split('=', 1)[1] for a in sys.argv if a.startswith('--board=')), f'{WT}/airbox.kicad_pcb')
FromMM = pcbnew.FromMM

B = pcbnew.LoadBoard(BOARD)
pcbnew.ZONE_FILLER(B).Fill(B.Zones())
bb = B.GetBoardEdgesBoundingBox()
X0, X1, Y0, Y1 = bb.GetLeft() / 1e6, bb.GetRight() / 1e6, bb.GetTop() / 1e6, bb.GetBottom() / 1e6
B.GetDesignSettings().SetAuxOrigin(pcbnew.VECTOR2I(FromMM(X0), FromMM(Y1)))
out = f'{HERE}/board_{NAME}.kicad_pcb'; pcbnew.SaveBoard(out, B)
print(f'{NAME}: outline ({X0:.2f},{Y0:.2f})-({X1:.2f},{Y1:.2f}) = {X1-X0:.2f} x {Y1-Y0:.2f} mm, source {BOARD}')

fab = f'{HERE}/{NAME}/fab'; os.makedirs(fab, exist_ok=True)
subprocess.run(['kicad-cli', 'pcb', 'export', 'gerbers', '--layers', 'F.Cu,In1.Cu,In2.Cu,B.Cu,Edge.Cuts', '--use-drill-file-origin',
                '--disable-aperture-macros', '--no-protel-ext', '-o', fab + '/', out], check=True, capture_output=True)
subprocess.run(['kicad-cli', 'pcb', 'export', 'drill', '--drill-origin', 'plot', '--excellon-separate-th',
                '--excellon-zeros-format', 'decimal', '-o', fab + '/', out], check=True, capture_output=True)
for fn in os.listdir(fab):
    if fn.endswith('-GND1.gbr'): os.rename(f'{fab}/{fn}', f'{fab}/{fn.replace("-GND1.gbr", "-In1_Cu.gbr")}')
    if fn.endswith('-GND2.gbr'): os.rename(f'{fab}/{fn}', f'{fab}/{fn.replace("-GND2.gbr", "-In2_Cu.gbr")}')
shutil.copy(f'{HERE}/asis/fab/stackup.json', fab)
# one MSL port on the modem stub (x 194.25, y 129..131.25 on the routed board), pointing down the stub (rot 180 = -y)
open(f'{fab}/board-top-pos.csv', 'w').write(
    f'Ref,Val,Package,PosX,PosY,Rot,Side\n"SP1","Simulation_Port","Simulation_Port",{194.25 - X0:.6f},{Y1 - 129.6:.6f},180.000000,top\n')

cfg = json.load(open(f'{HERE}/pi_void_neck/simulation.json'))
s = OPT / 40.0
g = cfg['grid']
g.update(optimal=OPT, diagonal=OPT, perpendicular=round(150 * s, 2), max=round(400 * s, 2), inter_layers=NZ)
g['margin']['from_trace'] = False   # size the simulation box to the whole board outline, not the RF traces + margin
cfg['ports'] = [dict(cfg['ports'][0], width=140, length=600)]
cfg['traces'] = [{"start": 0, "stop": 0, "name": "RF_FEED", "nets": ["/RF_ANT", "/Antenna/ANT_FEED", "/Antenna/ANT_MID"]}]
json.dump(cfg, open(f'{HERE}/{NAME}/simulation.json', 'w'), indent=4)
print('grid', {k: g[k] for k in ('optimal', 'diagonal', 'perpendicular', 'max', 'inter_layers')}, 'pixel', cfg.get('pixel_size'))
print(sorted(os.listdir(fab)))
