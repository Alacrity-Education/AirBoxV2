#!/usr/bin/env python3
"""Whole-board gerber2ems project (all copper, the full board outline) with a scaled mesh.

    python3 build_board.py <name> <optimal_um> <inter_layers> [--neck] [--board=PATH]
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
NECK = '--neck' in sys.argv     # cut the feed at the antenna neck and drop ANT1 (antenna = measured S1P on the neck port)
NECK_X, NECK_Y = 218.3, 113.9   # board mm: feed cut point (end of the ground bump at 218.4), trace centre
P1X, P1Y, P1W = 194.25, 129.25, 0.14   # modem port: stub x, cut y, trace width (routed board: 0.14 mm)
RF_NETS = {'/RF_ANT', '/Antenna/ANT_FEED', '/Antenna/ANT_MID'}
V = lambda x, y: pcbnew.VECTOR2I(FromMM(x), FromMM(y))
if NECK:
    for fp in [f for f in B.Footprints()]:
        if fp.GetReference() == 'ANT1': B.Delete(fp)
        elif fp.GetReference() == 'U1':          # the modem's ANT pad: the port replaces it
            for pd in [q for q in fp.Pads() if q.GetNetname() == '/RF_ANT']: fp.Remove(pd)
    for t in [t for t in B.Tracks()]:
        if t.GetNetname() not in RF_NETS or t.Type() == pcbnew.PCB_VIA_T: continue
        s, e = t.GetStart(), t.GetEnd()
        if min(s.x, e.x) >= FromMM(NECK_X): B.Delete(t); continue           # antenna side of the neck cut
        if max(s.x, e.x) > FromMM(NECK_X) and t.Type() == pcbnew.PCB_TRACE_T:
            if s.x > e.x: t.SetStart(V(NECK_X, s.y / 1e6))
            else: t.SetEnd(V(NECK_X, e.y / 1e6))
        if t.Type() == pcbnew.PCB_TRACE_T and abs(s.x - FromMM(P1X)) < 1000 and abs(e.x - FromMM(P1X)) < 1000:   # modem stub
            if s.y < e.y: t.SetStart(V(P1X, P1Y))
            else: t.SetEnd(V(P1X, P1Y))
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

# pads json for sim_pi.py: every pad (sim frame: mm from the outline's bottom-left corner, y up; side F/B/FB) + ports
b2 = pcbnew.LoadBoard(out); pads = {}
for fp in b2.Footprints():
    for q in fp.Pads():
        key = f'{fp.GetReference()}.{q.GetNumber()}'
        if key in pads or not q.IsOnCopperLayer(): continue
        bbq = q.GetBoundingBox(); fl, bl = q.IsOnLayer(pcbnew.F_Cu), q.IsOnLayer(pcbnew.B_Cu)
        pads[key] = dict(net=q.GetNetname(), side='FB' if fl and bl else 'F' if fl else 'B',
                         x0=round(bbq.GetLeft() / 1e6 - X0, 4), x1=round(bbq.GetRight() / 1e6 - X0, 4),
                         y0=round(Y1 - bbq.GetBottom() / 1e6, 4), y1=round(Y1 - bbq.GetTop() / 1e6, 4))
meta = dict(window=[X0, Y0, X1, Y1], pads=pads,
            port1=dict(x0=P1X - P1W / 2 - X0, x1=P1X + P1W / 2 - X0, y0=Y1 - (P1Y + 0.2), y1=Y1 - P1Y))
if NECK: meta['neckport'] = dict(x0=NECK_X - 0.4 - X0, x1=NECK_X - X0, y0=Y1 - (NECK_Y + 0.1), y1=Y1 - (NECK_Y - 0.1))
json.dump(meta, open(f'{HERE}/{NAME}_pads.json', 'w'), indent=1)
print(f'{len(pads)} pads; RF parts:', {k: (v['x0'], v['y0']) for k, v in pads.items() if k.split('.')[0] in ('L5', 'C5', 'L6', 'C8', 'J11')})
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
