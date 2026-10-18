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
opts = dict(a[2:].split('=') for a in sys.argv[2:] if a.startswith('--') and '=' in a)
OUT = opts.get('out', 'nport')            # --out=<folder>: write under <variant>/<folder> (e.g. a field-dump run next to the main results)
DUMP3D = [float(v) for v in opts['dump3d'].split(',')] if 'dump3d' in opts else None   # --dump3d=x0,x1,y0,y1 (mm, sim frame): 3-D E-field volume
FDUMP = [float(v) * 1e9 for v in opts.get('fdump', '0.75,1.8,2.6').split(',')]          # frequencies (GHz) of the 3-D frequency-domain dump
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
    np.savez(os.path.join(out, 'S.npz'), f=FREQ, S=S, names=[n for n, _, _ in ports])
    print(f'    done; |S{k}{k}| at 0.9/1.8/3.5 GHz: ' + ' '.join(f'{20*np.log10(abs(S[k,k,np.argmin(np.abs(FREQ-f))])):.1f}' for f in (0.9e9, 1.8e9, 3.5e9)) + ' dB', flush=True)

if not USE_PORTS and np.all(np.isfinite(S)):
    import skrf as rf
    ntw = rf.Network(frequency=rf.Frequency.from_f(FREQ, unit='Hz'), s=S.transpose(2, 0, 1), z0=50, name='feed_pi')
    ntw.write_touchstone(os.path.join(out, 'feed_pi'))
    print('wrote', os.path.join(out, 'feed_pi.s%dp' % N))
