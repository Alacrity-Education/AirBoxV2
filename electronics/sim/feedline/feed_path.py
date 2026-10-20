#!/usr/bin/env python3
"""Oriented centreline of the RF feed (modem port -> antenna), for the signed-current colouring in paraview_conductor.py.

    python3 feed_path.py <variant>          (KiCad python; reads the built board and <variant>_pads.json)

Board: feed_<variant>.kicad_pcb (build_pi.py) or board_<variant>.kicad_pcb (build_board.py); its aux origin is the
simulation frame origin (bottom-left corner, y up). The path follows the RF-net tracks (segments and arcs) and jumps the
series parts (L6, C5) pad to pad; it runs from the modem port (port1 box) to the antenna end (neckport box, else ANT1
pad 1). Writes <variant>/feed_path.json: {"points": [[x, y], ...]} in mm, sim frame, ordered modem -> antenna.
"""
import heapq, json, math, os, sys
import pcbnew

HERE = os.path.dirname(os.path.abspath(__file__))
var = sys.argv[1].rstrip('/')
board = next(p for p in (f'{HERE}/feed_{var}.kicad_pcb', f'{HERE}/board_{var}.kicad_pcb') if os.path.exists(p))
meta = json.load(open(f'{HERE}/{var}_pads.json'))
RF_NETS = {'/RF_ANT', '/Antenna/ANT_FEED', '/Antenna/ANT_MID'}
B = pcbnew.LoadBoard(board)
ao = B.GetDesignSettings().GetAuxOrigin()
X0, Y1 = ao.x / 1e6, ao.y / 1e6
def sim(x, y): return (round(x - X0, 4), round(Y1 - y, 4))           # board mm -> sim frame (y up)
def P(v): return sim(v.x / 1e6, v.y / 1e6)

nodes, adj = {}, {}
def node(p):
    key = (round(p[0], 3), round(p[1], 3))
    nodes.setdefault(key, p); adj.setdefault(key, []); return key
def edge(a, b):
    ka, kb = node(a), node(b); d = math.dist(a, b)
    adj[ka].append((kb, d)); adj[kb].append((ka, d))

for t in B.Tracks():
    if t.GetNetname() not in RF_NETS or t.Type() == pcbnew.PCB_VIA_T: continue
    if t.Type() == pcbnew.PCB_ARC_T:                                   # sample the arc
        s, m, e = P(t.GetStart()), P(t.GetMid()), P(t.GetEnd())
        ax, ay, bx, by, cx, cy = *s, *m, *e
        dd = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        ux = ((ax**2 + ay**2) * (by - cy) + (bx**2 + by**2) * (cy - ay) + (cx**2 + cy**2) * (ay - by)) / dd
        uy = ((ax**2 + ay**2) * (cx - bx) + (bx**2 + by**2) * (ax - cx) + (cx**2 + cy**2) * (bx - ax)) / dd
        r = math.dist((ux, uy), s); a0, a1, am = (math.atan2(p[1] - uy, p[0] - ux) for p in (s, e, m))
        def norm(a): return (a - a0) % (2 * math.pi)
        sweep = norm(a1) if norm(am) < norm(a1) else norm(a1) - 2 * math.pi
        n = max(2, int(abs(sweep) * r / 0.1) + 1)
        pts = [(ux + r * math.cos(a0 + sweep * i / n), uy + r * math.sin(a0 + sweep * i / n)) for i in range(n + 1)]
        pts[0], pts[-1] = s, e
        for a, b in zip(pts, pts[1:]): edge(a, b)
    else:
        edge(P(t.GetStart()), P(t.GetEnd()))

pads = meta['pads']
def centre(pd): return ((pd['x0'] + pd['x1']) / 2, (pd['y0'] + pd['y1']) / 2)
def inside(p, pd, m=0.02): return pd['x0'] - m <= p[0] <= pd['x1'] + m and pd['y0'] - m <= p[1] <= pd['y1'] + m
for key, pd in pads.items():                                            # tie track ends inside an RF pad to its centre
    if pd.get('net') not in RF_NETS: continue
    c = centre(pd)
    for k in list(nodes):
        if inside(nodes[k], pd): edge(nodes[k], c)
for part in ('L6', 'C5'):                                               # series parts: jump pad to pad
    if f'{part}.1' in pads and f'{part}.2' in pads: edge(centre(pads[f'{part}.1']), centre(pads[f'{part}.2']))

def box_centre(b): return ((b['x0'] + b['x1']) / 2, (b['y0'] + b['y1']) / 2)
start_p = box_centre(meta['port1'])
end_p = box_centre(meta['neckport']) if 'neckport' in meta else centre(pads['ANT1.1'])
def nearest(p): return min(nodes, key=lambda k: math.dist(nodes[k], p))
s, e = nearest(start_p), nearest(end_p)
dist, prev, pq = {s: 0.0}, {}, [(0.0, s)]
while pq:
    d, k = heapq.heappop(pq)
    if k == e: break
    if d > dist[k]: continue
    for k2, w in adj[k]:
        if d + w < dist.get(k2, 1e30): dist[k2] = d + w; prev[k2] = k; heapq.heappush(pq, (d + w, k2))
if e not in dist: sys.exit('no RF path between the modem port and the antenna end')
path = [e]
while path[-1] != s: path.append(prev[path[-1]])
pts = [list(nodes[k]) for k in reversed(path)]
os.makedirs(f'{HERE}/{var}', exist_ok=True)
json.dump(dict(points=pts, board=os.path.basename(board), note='sim frame mm, modem port -> antenna'), open(f'{HERE}/{var}/feed_path.json', 'w'))
print(f'{var}: {len(pts)} points, length {dist[e]:.2f} mm, from {pts[0]} to {pts[-1]}')
