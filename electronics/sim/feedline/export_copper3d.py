#!/usr/bin/env python3
"""Export the copper of a derived sim board as PLY meshes for ParaView, in the simulation frame.

    python3 export_copper3d.py pi_void_neck

Writes <variant>/copper3d/copper_rf.ply (nets *ANT*), copper_gnd.ply (GND, all four layers), parts.ply (0402 parts,
no copper) and align.json with the z offset (mm) that puts the top of F.Cu at the simulation's z = +35 um
(openEMS: F.Cu bottom at z = 0). Origin = drill/aux origin = the bottom-left corner of the window, y up, mm.
"""
import json, os, struct, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
var = sys.argv[1].rstrip('/')
board = os.path.join(HERE, f'feed_{var}.kicad_pcb')
if not os.path.exists(board): board = os.path.join(HERE, f'board_{var}.kicad_pcb')   # whole-board builds (build_board.py)
out = os.path.join(HERE, var, 'copper3d'); os.makedirs(out, exist_ok=True)
base = ['kicad-cli', 'pcb', 'export', 'ply', '-f', '--drill-origin', '--no-board-body']
cu = ['--no-components', '--include-tracks', '--include-pads', '--include-zones', '--include-inner-copper']
jobs = {'copper_rf.ply': cu + ['--net-filter', '*ANT*'], 'copper_gnd.ply': cu + ['--net-filter', 'GND'], 'parts.ply': []}
for name, extra in jobs.items():
    r = subprocess.run(base + extra + ['-o', os.path.join(out, name), board], capture_output=True, text=True)
    print(name, 'rc', r.returncode, r.stderr.strip()[-200:])

def zrange(path):
    """min/max z of a PLY (ascii or binary little endian, float vertex x y z first)."""
    with open(path, 'rb') as f:
        hdr = []
        while True:
            line = f.readline().decode('ascii', 'replace').strip(); hdr.append(line)
            if line == 'end_header': break
        fmt = next(l.split()[1] for l in hdr if l.startswith('format'))
        nv = int(next(l.split()[2] for l in hdr if l.startswith('element vertex')))
        props = []
        inv = False
        for l in hdr:
            if l.startswith('element'): inv = l.startswith('element vertex')
            elif l.startswith('property') and inv: props.append(l.split()[1])
        zs = []
        if fmt == 'ascii':
            for _ in range(nv): zs.append(float(f.readline().split()[2]))
        else:
            code = {'float': 'f', 'double': 'd', 'uchar': 'B', 'int': 'i', 'uint': 'I', 'short': 'h', 'ushort': 'H', 'char': 'b', 'float32': 'f', 'float64': 'd', 'uint8': 'B'}
            st = struct.Struct('<' + ''.join(code[p] for p in props))
            data = f.read(st.size * nv)
            for i in range(nv): zs.append(st.unpack_from(data, i * st.size)[2])
    return min(zs), max(zs)

lo, hi = zrange(os.path.join(out, 'copper_rf.ply'))
dz = 0.035 - hi   # mm: top of F.Cu copper -> sim z = +35 um
json.dump(dict(dz_mm=dz, rf_z=[lo, hi]), open(os.path.join(out, 'align.json'), 'w'))
print(f'RF copper z {lo:.4f}..{hi:.4f} mm -> dz {dz:.4f} mm')
