#!/usr/bin/env python3
"""Multi-port openEMS run of the feed + matching-network copper (runs inside the gerber2ems container).

    docker run --rm --user $(id -u):$(id -g) -e HOME=/tmp -v "$PWD:/work" -w /work \
        --entrypoint /home/docker/opt/openEMS/venv/bin/python gerber2ems sim_pi.py pi

Input : <variant>/ems/geometry/geometry.xml from `gerber2ems -g` (copper polygons, substrates, vias, mesh) and
        <variant>_pads.json from build_pi.py.
Ports : 1 modem end of the feed (trace to GND1), 2 antenna (between ANT1 pad 1 and pad 2), then one port per
        0402 land of the pi network (land to GND1): C5.1 C5.2 L6.1 L6.2 L5.1 C8.1 (C6/C7 are DNI: floating lands).
        gerber2ems' own port/probes/excitation are stripped from the XML.
Output: <variant>/nport/feed_pi.s10p (50 ohm) + ports.json; analyze_pi.py connects the L/C values and the antenna.
"""
import os, sys, re, json, shutil
import numpy as np
from CSXCAD import ContinuousStructure
from openEMS import openEMS

variant = sys.argv[1].rstrip('/')
here = os.path.dirname(os.path.abspath(__file__))
geom = os.path.join(here, variant, 'ems', 'geometry', 'geometry.xml')
meta = json.load(open(os.path.join(here, f'{variant}_pads.json')))
args = [a for a in sys.argv[2:] if not a.startswith('--')]
ONLY = [int(a) for a in args] if args else None
ONLY_FIRST = ONLY[0] if ONLY else 0
opts = dict(a[2:].split('=') for a in sys.argv[2:] if a.startswith('--') and '=' in a)
OUT = opts.get('out', 'nport')            # --out=<folder>: write under <variant>/<folder> (e.g. a field-dump run next to the main results)
DUMP3D = [float(v) for v in opts['dump3d'].split(',')] if 'dump3d' in opts else None   # --dump3d=x0,x1,y0,y1 (mm, sim frame): 3-D E-field volume
FDUMP = [float(v) * 1e9 for v in opts.get('fdump', '0.75,1.8,2.6').split(',')]          # frequencies (GHz) of the 3-D frequency-domain dump
HDUMP = [float(v) for v in opts['hdump'].split(',')] if 'hdump' in opts else None   # --hdump=x0,x1,y0,y1 (mm): H-field planes
# for surface (return) current K = n x H: first mesh line above GND1 (in the prepreg), above F.Cu (air) and above In2.Cu
# (core); plus mid-prepreg and mid-core planes for the via currents (Ampere loop around each via),
# frequency domain at the --fdump frequencies (abs + phase sweep)
# ---- realistic materials (2026-10-20) ----
TAND = opts.get('tand', '0') == '1'      # --tand=1: dielectric loss from stackup.json lossTangent, as conductivity at --fref
FREF = float(opts.get('fref', 1.8)) * 1e9  # --fref=GHz: frequency where the dielectric loss is exact (kappa = 2 pi f eps0 epsr tand)
CU = float(opts['cu']) if 'cu' in opts else None   # --cu=5.8e7: copper as lossy conducting sheets (S/m), thickness from stackup.json
MASK = [float(v) for v in opts['mask'].split(',')] if 'mask' in opts else None   # --mask=t_um,epsr,tand: solder mask top + bottom
if MASK and 'prio' not in opts: opts['prio'] = '5'   # ports/parts must sit above the mask (2) and openings (3), below metal (50)
KDUMP = opts.get('kdump')                # --kdump=all | x0,x1,y0,y1 (mm): curl-H (total current density) planes on each copper
KDUMP = None if KDUMP is None else ('all' if KDUMP == 'all' else [float(v) for v in KDUMP.split(',')])   # sheet + mid-dielectric, at --fdump
DUMP = opts.get('dump', '0') == '1'       # --dump=1: E-field dumps (top copper + middle of each dielectric) for ParaView, in run_<k>/
out = os.path.join(here, variant, OUT); os.makedirs(out, exist_ok=True)
UNIT = 1e-7                      # geometry.xml DeltaUnit
MM = 1e-3 / UNIT                 # mm -> drawing units
Z_GND1 = -764                    # In1.Cu top surface (prepreg 76.4 um below F.Cu, z=0)
F0, FC = 2.35e9, 1.85e9          # Gaussian pulse 0.5 .. 4.2 GHz
FREQ = np.linspace(0.5e9, 4.2e9, 371)
NRTS = int(opts.get('nrts', 150000)); TSF = float(opts.get('tsf', 0.9)); USE_PORTS = opts.get('ports')   # --ports=0,1 keeps only those ports
PRIO = int(opts.get('prio', 0))      # lumped-port priority: below the metal sheets (1) so the pad copper stays PEC at the box faces
LINE = opts.get('line', '0') == '1'   # --line=1: z-ports as zero-length sheets (single mesh line in y), like the MSL feed resistor
THREADS = int(opts.get('threads', 0))   # --threads=N: force the openEMS thread count (0 = openEMS picks by benchmark)
GAPZ = opts.get('gapz', '0') == '1'     # --gapz=1: gap ports as vertical sheets (z from the copper plane up to the first air mesh line) instead of lines
GAP = opts.get('gap', '0') == '1'       # --gap=1: one line port per part ACROSS its land gap (series parts) or to its GND land (shunt parts),
                                        # on the copper plane; the antenna port between ANT1 pad 2 (GND) and pad 1. analyze_pi.py then
                                        # terminates each part port with the part's 1-port impedance.

# ---- strip gerber2ems ports (they are rebuilt here as lumped ports) ----
xml = open(geom).read()
for tag in ('ProbeBox', 'Excitation', 'LumpedElement'):      # gerber2ems MSL port probes, excitation and feed resistor
    xml = re.sub(r'<%s\b.*?</%s>' % (tag, tag), '', xml, flags=re.S)
xml = re.sub(r'<Metal\b[^>]*Name="Port_\d+".*?</Metal>', '', xml, flags=re.S)
xml = re.sub(r'<Metal\b[^>]*Name="Plane"\s*>\s*<Primitives>\s*</Primitives>\s*</Metal>', '', xml, flags=re.S)
EPS0 = 8.8541878128e-12
stack = json.load(open(os.path.join(here, variant, 'fab', 'stackup.json')))['layers']
diel = [l for l in stack if l['type'] in ('prepreg', 'core')]       # top to bottom = Substrate_0, _1, _2
cu_layers = [l for l in stack if l['type'] == 'copper']             # top to bottom = Gerber_0 .. Gerber_3
if TAND:
    for k, l in enumerate(diel):
        kappa = 2 * np.pi * FREF * EPS0 * l['epsilon'] * (l['lossTangent'] or 0)
        pat = r'(<Material\b[^>]*Name="Substrate_%d".*?<Property\b[^>]*?Kappa=")[^"]*(")' % k
        xml, n = re.subn(pat, lambda m: m.group(1) + f'{kappa:e},{kappa:e},{kappa:e}' + m.group(2), xml, count=1, flags=re.S)
        if n != 1: raise RuntimeError(f'Substrate_{k} not found for loss')
        print(f'    dielectric {l["name"]}: epsr {l["epsilon"]} tand {l["lossTangent"]} -> kappa {kappa:.3e} S/m @ {FREF/1e9:g} GHz', flush=True)
if CU:
    for k, l in enumerate(cu_layers):
        th = l['thickness'] * 1e-3      # mm -> m
        pat = r'<Metal (ID="\d+" Name="Gerber_%d")>(.*?)</Metal>' % k
        xml, n = re.subn(pat, lambda m: f'<ConductingSheet {m.group(1)} Conductivity="{CU:e}" Thickness="{th:e}">' + m.group(2) + '</ConductingSheet>', xml, count=1, flags=re.S)
        if n != 1: raise RuntimeError(f'Gerber_{k} not found for conducting sheet')
        print(f'    copper {l["name"]}: conducting sheet sigma {CU:.3g} S/m, t {th*1e6:.1f} um', flush=True)
if MASK:   # metal (sheets, vias) above everything else so the mask box cannot override copper at the surface
    def raise_prio(m): return re.sub(r'Priority="\d+"', 'Priority="50"', m.group(0))
    xml = re.sub(r'<(Metal|ConductingSheet)\b[^>]*Name="(Gerber_\d|Via)".*?</(Metal|ConductingSheet)>', raise_prio, xml, flags=re.S)
ZB = -round(sum(l['thickness'] for l in diel) * 1e4)   # B.Cu sheet elevation (drawing units): gerber2ems stacks dielectrics only (copper = zero-thickness sheets)
stripped = os.path.join(out, 'geometry_stripped.xml'); open(stripped, 'w').write(xml)

# ---- ports ----
pads = meta['pads']; p1 = meta['port1']
def box(d, shrink=0.0): return (d['x0'] + shrink, d['x1'] - shrink, d['y0'] + shrink, d['y1'] - shrink)
ports = [('modem', box(p1), 'z')]
if 'ANT1.1' in pads:                               # coplanar port between pad 1 (feed, larger y) and pad 2 (GND)
    a1, a2 = pads['ANT1.1'], pads['ANT1.2']; xc = (a1['x0'] + a1['x1']) / 2
    ports.append(('antenna', (xc - 0.5, xc + 0.5, a2['y1'], a1['y0']), 'y'))   # z extent set in build(): 0 .. first air mesh line
elif 'neckport' in meta:                            # neck variant: port on the feed neck over GND1, feed cut there
    ports.append(('antenna', box(meta['neckport']), 'z'))
if GAP:
    def gap_x(a, b): return (a['x1'], b['x0'], (a['y0'] + a['y1']) / 2, (a['y0'] + a['y1']) / 2)   # a left of b
    def gap_y(a, b): return ((a['x0'] + a['x1']) / 2, (a['x0'] + a['x1']) / 2, a['y1'], b['y0'])   # a below b
    ports = [ports[0]]
    if 'ANT1.1' in pads: ports.append(('antenna', gap_y(pads['ANT1.2'], pads['ANT1.1']), 'y'))
    elif 'neckport' in meta: ports.append(('antenna', box(meta['neckport']), 'z'))
    for part, d in (('C5', 'x'), ('L6', 'x'), ('L5', 'y'), ('C8', 'y'), ('C6', 'y'), ('C7', 'y')):
        if part + '.1' in pads: ports.append((part, (gap_x if d == 'x' else gap_y)(pads[part + '.1'], pads[part + '.2']), d))
else:
    for key in ('C5.1', 'C5.2', 'L6.1', 'L6.2', 'L5.1', 'C8.1'):      # C6/C7 (DNI) lands stay as floating copper
        if key in pads: ports.append((key, box(pads[key], 0.05), 'z'))
json.dump([dict(index=i, name=n, box_mm=b, dir=d, net=pads.get(n, pads.get(n + '.1', {})).get('net', ''), gap=GAP and i > 0) for i, (n, b, d) in enumerate(ports)], open(os.path.join(out, 'ports.json'), 'w'), indent=1)
if USE_PORTS: ports = [ports[int(i)] for i in USE_PORTS.split(',')]
# --parts=C5:3.6p,L6:2.2n,...  (with --gap=1): put those parts into the FDTD model as lumped L/C elements across their gap
# instead of ports; gap ports of parts not listed are dropped (open lands). Used for field pictures with the real network.
def _val(v):
    m = {'p': 1e-12, 'n': 1e-9, 'u': 1e-6}; return float(v[:-1]) * m[v[-1]] if v[-1] in m else float(v)
PARTS = {}
if 'parts' in opts:
    for kv in opts['parts'].split(','):
        k, v = kv.split(':'); PARTS[k] = _val(v)
ELEMS = [p for p in ports if p[0] in PARTS] if PARTS else []
if PARTS: ports = [p for p in ports if p[0] in ('modem', 'antenna')]
N = len(ports)
print('ports:', [(i, n) for i, (n, _, _) in enumerate(ports)], flush=True)

def build(excite_idx):
    CSX = ContinuousStructure(); CSX.ReadFromXML(stripped)
    FDTD = openEMS(NrTS=NRTS, EndCriteria=1e-6, TimeStepFactor=TSF); FDTD.SetCSX(CSX)
    FDTD.SetGaussExcite(F0, FC); FDTD.SetBoundaryCond(['MUR'] * 6)
    mesh = CSX.GetGrid()
    if MASK:   # mesh lines at the mask surfaces (before any port snaps to the grid): move a close line or add one
        t_m = MASK[0] * 10
        zl = list(mesh.GetLines('z'))
        for zt in (t_m, ZB - t_m):
            near_lines = [z for z in zl if abs(z - zt) < t_m and abs(z) > 1 and abs(z - ZB) > 1]
            if near_lines: zl.remove(min(near_lines, key=lambda z: abs(z - zt)))
            zl.append(zt)
        mesh.SetLines('z', sorted(zl))
    plist = []
    for i, (name, (x0, x1, y0, y1), d) in enumerate(ports):
        zl = np.array(mesh.GetLines('z')); z_air = float(zl[zl > 0].min())
        z0, z1 = (Z_GND1, 0) if d == 'z' else (0, z_air if GAPZ else 0) if GAP else (0, z_air)
        # snap the port box to existing mesh lines (adding lines would create tiny cells and a tiny timestep);
        # gerber2ems already meshes every land at ~40 um, so the snapped box differs from the pad by < 20 um
        def snap(ax, v):
            lines = np.array(mesh.GetLines(ax)); return float(lines[np.argmin(np.abs(lines - v))])
        sx0, sx1 = snap('x', x0 * MM), snap('x', x1 * MM); sy0, sy1 = snap('y', y0 * MM), snap('y', y1 * MM)
        if LINE and d == 'z': sy0 = sy1 = snap('y', 0.5 * (y0 + y1) * MM)
        ext = {'x': sx1 - sx0, 'y': sy1 - sy0, 'z': z1 - z0}[d]
        if ext <= 0 or sx1 < sx0 or sy1 < sy0 or (not GAP and not LINE and (sx1 == sx0 or sy1 == sy0)): raise RuntimeError(f'port {name} collapsed after snapping: {sx0},{sx1},{sy0},{sy1}')
        print(f'    port {i} {name}: {d} box x {sx0/MM:.3f}-{sx1/MM:.3f} y {sy0/MM:.3f}-{sy1/MM:.3f} z {z0/MM:.3f}-{z1/MM:.3f} mm', flush=True)
        start = [sx0, sy0, z0]; stop = [sx1, sy1, z1]
        plist.append(FDTD.AddLumpedPort(i + 1, 50, start, stop, d, excite=1.0 if i == excite_idx else 0, priority=PRIO))
    for name, (x0, x1, y0, y1), d in ELEMS:   # real parts: parallel-type lumped element with only L or only C set
        def snap(ax, v):
            lines = np.array(mesh.GetLines(ax)); return float(lines[np.argmin(np.abs(lines - v))])
        zl = np.array(mesh.GetLines('z')); z_air = float(zl[zl > 0].min())
        start = [snap('x', x0 * MM), snap('y', y0 * MM), 0]; stop = [snap('x', x1 * MM), snap('y', y1 * MM), z_air if GAPZ else 0]
        kw = {'L': PARTS[name]} if name.startswith('L') else {'C': PARTS[name]}
        le = CSX.AddLumpedElement(name, ny=d, caps=True, LEtype=0, **kw); le.AddBox(start, stop, priority=PRIO)
        print(f'    part {name} = {PARTS[name]:.3g} across {d}: x {start[0]/MM:.3f}-{stop[0]/MM:.3f} y {start[1]/MM:.3f}-{stop[1]/MM:.3f}', flush=True)
    if DUMP:   # same planes as gerber2ems --export-field cu-outer substrate: top of F.Cu, middle of prepreg 1, core, prepreg 2
        xl, yl = mesh.GetLines('x'), mesh.GetLines('y')
        for name, z in (('e_field_F_Cu', 350), ('e_field_dielectric_1', Z_GND1 // 2), ('e_field_dielectric_2', Z_GND1 - 152 - 6325), ('e_field_dielectric_3', Z_GND1 - 152 - 12650 - 152 - 382)):
            dmp = CSX.AddDump(name, sub_sampling=[1, 1, 1]); dmp.AddBox([min(xl), min(yl), z], [max(xl), max(yl), z])
    if MASK:   # solder mask: top over F.Cu (z 0..t), bottom under B.Cu; openings over the pads of that side (air)
        t_m, eps_m, tand_m = MASK[0] * 10, MASK[1], MASK[2]          # um -> drawing units (0.1 um)
        kap_m = 2 * np.pi * FREF * EPS0 * eps_m * tand_m
        zb = ZB
        xl, yl = mesh.GetLines('x'), mesh.GetLines('y')
        sm = CSX.AddMaterial('SolderMask', epsilon=eps_m, kappa=kap_m)
        sm.AddBox([min(xl), min(yl), 0], [max(xl), max(yl), t_m], priority=2)
        sm.AddBox([min(xl), min(yl), zb - t_m], [max(xl), max(yl), zb], priority=2)
        op = CSX.AddMaterial('MaskOpening', epsilon=1.0)
        n_op = 0
        for key, pd in pads.items():
            side = pd.get('side', 'F')
            box = [pd['x0'] * MM, pd['y0'] * MM, pd['x1'] * MM, pd['y1'] * MM]
            if side in ('F', 'FB'): op.AddBox([box[0], box[1], 0], [box[2], box[3], t_m], priority=3); n_op += 1
            if side in ('B', 'FB'): op.AddBox([box[0], box[1], zb - t_m], [box[2], box[3], zb], priority=3); n_op += 1
        if excite_idx == 0 or excite_idx == ONLY_FIRST:
            print(f'    solder mask {MASK[0]:g} um epsr {eps_m} tand {tand_m} (kappa {kap_m:.3e}) top + bottom, {n_op} pad openings', flush=True)
    if HDUMP:
        zl = np.array(mesh.GetLines('z'))
        x0, x1, y0, y1 = (v * MM for v in HDUMP)
        Z_IN2 = Z_GND1 - 12650   # In2.Cu (core 1.265 mm below GND1)
        near = lambda v: float(zl[np.argmin(np.abs(zl - v))])
        for name, z in (('h_inner', float(zl[zl > Z_GND1].min())), ('h_top', float(zl[zl > 0].min())),
                        ('h_in2', float(zl[zl > Z_IN2].min())),          # just above In2.Cu (in the core)
                        ('h_pp', near(Z_GND1 / 2)),                       # middle of the top prepreg (via current F.Cu-In1)
                        ('h_core', near((Z_GND1 + Z_IN2) / 2)),           # middle of the core (via current In1-In2)
                        ('h_top_below', float(zl[zl < 0].max())),         # just below each copper layer: sheet current
                        ('h_inner_below', float(zl[zl < Z_GND1].max())),  # K = z x (H_above - H_below) counts both faces
                        ('h_in2_below', float(zl[zl < Z_IN2].max()))):
            CSX.AddDump(name, dump_type=11, file_type=0, frequency=FDUMP).AddBox([x0, y0, z], [x1, y1, z])
            print(f'    H dump {name} at z = {z / MM * 1000:.1f} um', flush=True)
    if KDUMP:    # conductor current: total current density curl H (frequency domain) ON each copper sheet's mesh plane;
        # x the dual-cell height in z it is the sheet current K (A/m) through the conductor's whole depth. Mid-dielectric
        # planes give Jz in the via barrels (via current = integral of Jz over the barrel cells).
        zl = np.array(mesh.GetLines('z'))
        x0, x1, y0, y1 = (v * MM for v in KDUMP) if KDUMP != 'all' else (min(mesh.GetLines('x')), max(mesh.GetLines('x')), min(mesh.GetLines('y')), max(mesh.GetLines('y')))
        near = lambda v: float(zl[np.argmin(np.abs(zl - v))])
        Z_IN2 = Z_GND1 - 12650
        for name, z in (('j_F_Cu', 0.0), ('j_In1_Cu', float(Z_GND1)), ('j_In2_Cu', float(Z_IN2)), ('j_B_Cu', float(ZB)),
                        ('j_pp1', near(Z_GND1 / 2)), ('j_core', near((Z_GND1 + Z_IN2) / 2)), ('j_pp2', near((Z_IN2 + ZB) / 2))):
            CSX.AddDump(name, dump_type=13, file_type=0, frequency=FDUMP).AddBox([x0, y0, z], [x1, y1, z])
            if excite_idx == ONLY_FIRST: print(f'    J dump {name} at z = {z / MM * 1000:.1f} um', flush=True)
    if DUMP3D:   # volume from inside the core (below GND1) to 1 mm above the board: time series + frequency-domain snapshots
        x0, x1, y0, y1 = (v * MM for v in DUMP3D); z0, z1 = Z_GND1 - 2000, 10000
        CSX.AddDump('e3d', dump_type=0, file_type=0).AddBox([x0, y0, z0], [x1, y1, z1])
        CSX.AddDump('e3d_fd', dump_type=10, file_type=0, frequency=FDUMP).AddBox([x0, y0, z0], [x1, y1, z1])
    return FDTD, plist, mesh

S = np.full((N, N, len(FREQ)), np.nan + 0j)
prev = os.path.join(out, 'S.npz')
if os.path.exists(prev):                       # resume: keep columns of ports already simulated with the same port list
    d = np.load(prev, allow_pickle=True)
    if d['S'].shape == S.shape and list(d['names']) == [n for n, _, _ in ports]:
        S = d['S']; print('resuming; finished ports:', [k for k in range(N) if np.all(np.isfinite(S[:, k, :]))], flush=True)
for k in range(N):
    if ONLY is not None and k not in ONLY: continue
    if ONLY is None and np.all(np.isfinite(S[:, k, :])): print(f'--- port {k} already done, skipping', flush=True); continue
    sim_path = os.path.join(out, f'run_{k}'); shutil.rmtree(sim_path, ignore_errors=True)
    FDTD, plist, mesh = build(k)
    nx, ny, nz = (mesh.GetQtyLines(a) for a in 'xyz')
    print(f'--- excite port {k} ({ports[k][0]}): mesh {nx}x{ny}x{nz} = {nx*ny*nz/1e6:.2f} Mcells', flush=True)
    FDTD.Run(sim_path, cleanup=True, **({'numThreads': THREADS} if THREADS else {}), **({'debug_PEC': True} if DUMP3D else {}))
    for j, p in enumerate(plist):
        p.CalcPort(sim_path, FREQ, ref_impedance=50)
    inc = plist[k].uf_inc
    for j, p in enumerate(plist):
        S[j, k, :] = p.uf_ref / inc
    if KDUMP or HDUMP or DUMP3D:   # port waves at the dump frequencies: normalise the fields to a unit incident wave and superpose runs
        for p in plist: p.CalcPort(sim_path, np.array(FDUMP), ref_impedance=50)
        np.savez(os.path.join(sim_path, 'waves.npz'), f=np.array(FDUMP), names=[n for n, _, _ in ports], excite=k,
                 uf_inc=np.array([p.uf_inc for p in plist]), uf_ref=np.array([p.uf_ref for p in plist]),
                 uf_tot=np.array([p.uf_tot for p in plist]), if_tot=np.array([p.if_tot for p in plist]))
    if KDUMP:      # the per-phase snapshots of the J planes are ~10x the data; abs + arg hold the complex field
        import glob
        for fn in glob.glob(os.path.join(sim_path, 'j_*_p=*.vtr')): os.remove(fn)
    np.savez(os.path.join(out, 'S.npz'), f=FREQ, S=S, names=[n for n, _, _ in ports])
    print(f'    done; |S{k}{k}| at 0.9/1.8/3.5 GHz: ' + ' '.join(f'{20*np.log10(abs(S[k,k,np.argmin(np.abs(FREQ-f))])):.1f}' for f in (0.9e9, 1.8e9, 3.5e9)) + ' dB', flush=True)

if not USE_PORTS and np.all(np.isfinite(S)):
    import skrf as rf
    ntw = rf.Network(frequency=rf.Frequency.from_f(FREQ, unit='Hz'), s=S.transpose(2, 0, 1), z0=50, name='feed_pi')
    ntw.write_touchstone(os.path.join(out, 'feed_pi'))
    print('wrote', os.path.join(out, 'feed_pi.s%dp' % N))
