#!/usr/bin/env python3
"""Derive the feed + matching-network simulation board (variant `pi`) from ../../airbox.kicad_pcb.

Keeps: RF tracks, J11, the pi-network lands (L5 C5 C6 C7 L6 C8), ANT1 pads, GND tracks/vias/pours and the antenna
keep-out rule areas; adds a solid GND zone on all copper layers (carved by the keep-outs), the window outline and
the aux origin. Exports gerbers (X2 net attributes), drill, stackup, a one-port pos file for gerber2ems, and
pi_pads.json with every pad box in the simulation frame (mm from the bottom-left corner, y up) for sim_pi.py.
Run with KiCad's python: python3 build_pi.py [--neck] [--void] [--val] [--board=PATH]
(--void adds the OLD board's GND1 void under J11 pad 1 at fixed coordinates; --board defaults to ../../airbox.kicad_pcb)
"""
import pcbnew, os, subprocess, shutil, json, sys
HERE = os.path.dirname(os.path.abspath(__file__)); WT = os.path.dirname(os.path.dirname(HERE))
VOID = '--void' in sys.argv
NECK = '--neck' in sys.argv       # antenna port on the feed neck over GND1 instead of between the antenna pads
VAL = '--val' in sys.argv         # validation of the sheet ports: the asis window (feed + J11 only), feed cut at board x 213 where
                                  # gerber2ems' MSL port 2 sits, so sim_pi.py's 2-port result can be compared with asis/ems/results
if VAL: NECK = True
NAME = 'val' if VAL else ('pi_void' if VOID else 'pi') + ('_neck' if NECK else '')
NECK_X = 213.0 if VAL else 218.3  # board x where the feed is cut in neck mode (end of the ground bump is 218.4)
NECK_Y = 113.75 if VAL else 113.9 # board y of the trace centre at the cut
FromMM = pcbnew.FromMM
def V(x, y): return pcbnew.VECTOR2I(FromMM(x), FromMM(y))
X0, Y0, X1, Y1 = (189.0, 107.0, 215.2, 137.0) if VAL else (189.0, 106.0, 225.0, 134.0)      # window: feed + pi network + antenna pads 1/2
P1Y = 129.25                                      # feed cut on the stub at the nRF9151 pad (port 1)
KEEP_FP = {'J11'} if VAL else {'J11', 'L5', 'C5', 'C6', 'C7', 'L6', 'C8', 'ANT1'}
RF_NETS = {'/RF_ANT', '/Antenna/ANT_FEED', '/Antenna/ANT_MID'}
BOARD = next((a.split('=', 1)[1] for a in sys.argv if a.startswith('--board=')), f'{WT}/airbox.kicad_pcb')   # --board=PATH: source board
print('source board:', BOARD)
B = pcbnew.LoadBoard(BOARD)
gnd = B.FindNet('GND').GetNetCode(); rfcodes = {B.FindNet(n).GetNetCode() for n in RF_NETS}
def inside(p): return FromMM(X0) <= p.x <= FromMM(X1) and FromMM(Y0) <= p.y <= FromMM(Y1)
for fp in [f for f in B.Footprints()]:
    if fp.GetReference() not in KEEP_FP or (NECK and fp.GetReference() == 'ANT1'): B.Delete(fp)
for t in [t for t in B.Tracks()]:
    nc = t.GetNetCode()
    if nc in rfcodes:
        if NECK and t.Type() == pcbnew.PCB_TRACE_T:
            s, e = t.GetStart(), t.GetEnd()
            if min(s.x, e.x) >= FromMM(NECK_X): B.Delete(t); continue          # stub and pad side of the cut
            if max(s.x, e.x) > FromMM(NECK_X):
                if s.x > e.x: t.SetStart(V(NECK_X, s.y/1e6))
                else: t.SetEnd(V(NECK_X, e.y/1e6))
        if t.Type() == pcbnew.PCB_TRACE_T and abs(t.GetStart().x - FromMM(194.25)) < 1000 and abs(t.GetEnd().x - FromMM(194.25)) < 1000:
            s, e = t.GetStart(), t.GetEnd()
            if s.y < e.y: t.SetStart(V(194.25, P1Y))
            else: t.SetEnd(V(194.25, P1Y))
    elif nc == gnd:
        if not (inside(t.GetStart()) and inside(t.GetEnd())): B.Delete(t)
    else: B.Delete(t)
for z in [z for z in B.Zones()]:
    if (not z.GetIsRuleArea()) and z.GetNetCode() != gnd: B.Delete(z)
    elif VAL and z.GetIsRuleArea(): B.Delete(z)        # asis was built before the pour keep-out around the lands existed
for d in [d for d in B.Drawings()]: B.Delete(d)
r = pcbnew.PCB_SHAPE(B); r.SetShape(pcbnew.SHAPE_T_RECT); r.SetStart(V(X0, Y0)); r.SetEnd(V(X1, Y1)); r.SetLayer(pcbnew.Edge_Cuts); r.SetWidth(FromMM(0.05)); B.Add(r)
z = pcbnew.ZONE(B); z.SetNet(B.FindNet('GND')); ls = pcbnew.LSET()
for l in (pcbnew.F_Cu, pcbnew.In1_Cu, pcbnew.In2_Cu, pcbnew.B_Cu): ls.AddLayer(l)
z.SetLayerSet(ls); o = z.Outline(); o.NewOutline()
for p in ((X0, Y0), (X1, Y0), (X1, Y1), (X0, Y1)): o.Append(FromMM(p[0]), FromMM(p[1]))
z.SetPadConnection(pcbnew.ZONE_CONNECTION_FULL); z.SetAssignedPriority(2); z.SetMinThickness(FromMM(0.2)); z.SetZoneName('SIM_GND'); B.Add(z)
if VOID:   # GND1 void under J11 pad 1 (the proposed fix): pad + 0.25 mm
    v = pcbnew.ZONE(B); v.SetIsRuleArea(True); v.SetDoNotAllowZoneFills(True)
    for a in ('SetDoNotAllowTracks', 'SetDoNotAllowVias', 'SetDoNotAllowPads', 'SetDoNotAllowFootprints'):
        if hasattr(v, a): getattr(v, a)(False)
    vl = pcbnew.LSET(); vl.AddLayer(pcbnew.In1_Cu); v.SetLayerSet(vl); v.SetZoneName('J11_PLANE_VOID')
    vo = v.Outline(); vo.NewOutline()
    for p in ((195.725, 130.225), (197.275, 130.225), (197.275, 131.725), (195.725, 131.725)): vo.Append(FromMM(p[0]), FromMM(p[1]))
    B.Add(v)
B.GetDesignSettings().SetAuxOrigin(V(X0, Y1))
pcbnew.ZONE_FILLER(B).Fill(B.Zones())
out = f'{HERE}/feed_{NAME}.kicad_pcb'; pcbnew.SaveBoard(out, B)
b2 = pcbnew.LoadBoard(out)
pads = {}
for fp in b2.Footprints():
    for p in fp.Pads():
        bb = p.GetBoundingBox(); key = f'{fp.GetReference()}.{p.GetNumber()}'
        if key in pads: continue
        pads[key] = dict(net=p.GetNetname(), x0=round(bb.GetLeft()/1e6 - X0, 4), x1=round(bb.GetRight()/1e6 - X0, 4), y0=round(Y1 - bb.GetBottom()/1e6, 4), y1=round(Y1 - bb.GetTop()/1e6, 4))
json.dump(dict(window=[X0, Y0, X1, Y1], port1=dict(x0=194.25-0.079-X0, x1=194.25+0.079-X0, y0=Y1-129.45, y1=Y1-129.25),
               neckport=dict(x0=NECK_X-0.4-X0, x1=NECK_X-X0, y0=Y1-(NECK_Y+0.1), y1=Y1-(NECK_Y-0.1)), pads=pads), open(f'{HERE}/{NAME}_pads.json', 'w'), indent=1)
print(NAME, "footprints:", [f.GetReference() for f in b2.Footprints()], "| tracks", sum(1 for t in b2.Tracks() if t.Type()!=pcbnew.PCB_VIA_T), "vias", sum(1 for t in b2.Tracks() if t.Type()==pcbnew.PCB_VIA_T), "zones", len(b2.Zones()), "rule areas", sum(1 for z in b2.Zones() if z.GetIsRuleArea()))
for k in (('J11.1',) if VAL else ('L5.1',) if NECK else ('ANT1.1', 'ANT1.2', 'L5.1', 'L5.2', 'C5.1', 'C5.2', 'C6.1', 'C7.1', 'L6.1', 'L6.2', 'C8.1', 'J11.1')): print("  ", k, pads[k])
fab = f'{HERE}/{NAME}/fab'; os.makedirs(fab, exist_ok=True)
subprocess.run(['kicad-cli','pcb','export','gerbers','--layers','F.Cu,In1.Cu,In2.Cu,B.Cu,Edge.Cuts','--use-drill-file-origin','--no-protel-ext','-o',fab+'/',out], check=True, capture_output=True)
subprocess.run(['kicad-cli','pcb','export','drill','--drill-origin','plot','--excellon-separate-th','--excellon-zeros-format','decimal','-o',fab+'/',out], check=True, capture_output=True)
for fn in os.listdir(fab):
    if fn.endswith('-GND1.gbr'): os.rename(f'{fab}/{fn}', f'{fab}/{fn.replace("-GND1.gbr","-In1_Cu.gbr")}')
    if fn.endswith('-GND2.gbr'): os.rename(f'{fab}/{fn}', f'{fab}/{fn.replace("-GND2.gbr","-In2_Cu.gbr")}')
shutil.copy(f'{HERE}/asis/fab/stackup.json', fab)
open(f'{fab}/feed-top-pos.csv', 'w').write(f'Ref,Val,Package,PosX,PosY,Rot,Side\n"SP1","Simulation_Port","Simulation_Port",5.250000,{Y1-129.9:.6f},180.000000,top\n')
cfg = json.load(open(f'{HERE}/asis/simulation.json')); cfg['ports'] = [cfg['ports'][0]]
cfg['traces'] = [{"start": 0, "stop": 0, "name": "RF_FEED", "nets": ["/RF_ANT", "/Antenna/ANT_FEED", "/Antenna/ANT_MID"]}]
json.dump(cfg, open(f'{HERE}/{NAME}/simulation.json', 'w'), indent=4)
print(sorted(os.listdir(fab)))
