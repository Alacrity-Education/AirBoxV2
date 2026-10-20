# Visualising the antenna feed: current approach

How the simulated currents of the nRF9151 → P822601 feed are turned into pictures, what each part of the picture
means, and how to produce it. Status as of 2026-10-20 (main tree, branch `v3-ltem`). Numbers and validation details
live in `HANDOFF.md` sections 6d–6e; this document is the method and the reading guide.

## 1. What we want to see

The question behind every view is **where the RF current flows**: along the feed trace, back through the ground
(return current), around the J11 U.FL pad and its ground void, through the vias between the layers, and through the
matching network into the antenna. The view must therefore show:

- **Real current, only in the copper:** not fields in the dielectric next to it.
- **The direction of flow at every point,** so closed return-current loops can be followed by eye.
- **Its strength:** over a wide range, about 1:1000, since the GND2 return is ~100× weaker than the trace current.
- **All copper layers and the vias between them, together.**
- **Optionally the real operating case:** the matching network fitted and the measured antenna on the end, not just
  50 Ω terminations.

## 2. Pipeline

```
KiCad board ──build_board.py / build_pi.py──▶ gerbers + stackup ──gerber2ems -g──▶ openEMS geometry + mesh
     │                                                                                    │
     └──export_copper3d.py──▶ copper3d/*.ply (3-D copper for the views)                   ▼
                                        sim_pi.py <variant> <port> --kdump=all [materials] (one run per port)
                                                                                          │  curl-H planes, port waves
                                                                                          ▼
                     conductor_current.py single <run>   or   conductor_current.py super <runs…> L5=… C5=…
                                                                                          │  K_<layer>_f=…vtr/.pvd,
                                                                                          ▼  via_f=…vtp/.pvd, meta.json
                                     paraview_conductor.py  (2 × 2 view, locally or on atmega)
```

### 2.1 Simulation output (`sim_pi.py --kdump=all`)

openEMS models each copper layer as a ConductingSheet: a zero-thickness sheet on one z mesh line, with the copper's
conductivity and thickness in the update (skin effect included). `--kdump=all` dumps **curl H** (openEMS dump type 13,
frequency domain, no interpolation) on two kinds of plane:

- **each copper sheet's own mesh plane** (F.Cu, In1.Cu, In2.Cu, B.Cu);
- **the middle of each dielectric** (F.Cu–In1, In1–In2, In2–B.Cu), for the via currents.

It also saves the port waves, so the results can be normalised and superimposed.

Materials: always with the production settings `--tand=1 --cu=5.8e7 --mask=20,3.8,0.02` (lossy prepreg/core, lossy
copper, 20 µm solder mask; HANDOFF 6c).

### 2.2 Conductor current (`conductor_current.py`)

- **Sheet current.** openEMS evaluates curl H per E edge as the H loop around the edge's dual face divided by the
  face area. For an edge lying in a copper sheet, that dual face spans the whole copper depth. So

  `K = J_tangential(z_sheet) × dz_dual`  (A/m)

  is the **current of the whole conductor depth, both faces together, per unit width**. It is exact on the FDTD
  grid, with no half-cell offset and no smearing next to the copper. `dz_dual` is taken from the solver's own z mesh,
  including the line `--mask` adds; `sim_zlines()` must stay in step with `sim_pi.py`.
- **Via current.** Jz on the mid-dielectric planes, summed over the dual faces nearest each drill hole, is the
  current in that via segment. This is a discrete Ampère loop. There is one value per via and segment: F.Cu–In1,
  In1–In2 and In2–B.Cu.
- **Copper mask.** Off the copper, curl H is displacement current. Each node therefore carries its copper coverage,
  0–1, read from gerber2ems' layer image, and the views hide current outside copper.
- **Normalisation.** Fields are divided by the incident wave at the driven port, so every value is for **1 V incident
  at the modem port**: A/m for sheets, A for vias. Values are comparable across frequencies and runs.
- **Phase.** Results are stored as complex amplitude (`Kre`, `Kim`). The instantaneous current at phase φ is
  Re((Kre + j·Kim)·e^{jφ}), formed in ParaView on the fly. A `.pvd` series with one step per phase (18 by default)
  drives the animation without storing per-phase copies.

Two modes:

| Mode | Command | What the picture is |
|---|---|---|
| `single` | `tools/py.sh conductor_current.py single board20n/nport_gap_p0/run_0` | one excitation (modem driven), every other port terminated in 50 Ω: the raw simulation case, labelled as such |
| `super` | `tools/py.sh conductor_current.py super board20n/nport_gap_p*/run_* L5=22n C5=3.6p L6=2.2n C8=0.5p C6=- C7=-` | **the real operating case**: the 8 per-port runs superposed with the weights of the circuit solution (parts as 1-ports, measured antenna `.s1p` on the antenna port, 50 Ω source at the modem). Any part values, no new EM run. |

For `super`, fields are linear, so the weights come from solving the port network with the parts and the antenna.
The weights also cancel the small residual incident waves at the runs' "terminated" ports. It prints, per frequency,
|S11| at the modem and the power delivered to the antenna port. These match `analyze_pi.py` to 0.1 dB.

Options: `--freqs=0.725,1.75` (GHz, default all dumped), `--phases=18`, `--crop=x0,x1,y0,y1` (mm), `--mask=20`
(must match the run; 0 for runs without mask), `--via_r=0.35`, `--snapshots=1` (explicit per-phase files, ~8 MB each
on the whole board), `--out=…`.

### 2.3 3-D copper (`export_copper3d.py`)

`python3 export_copper3d.py <variant>` (KiCad python) exports the board's copper as PLY meshes, aligned to the
simulation frame: RF nets, GND on all four layers, and the parts. The views draw the actual copper, pads, vias and
parts from these, not the solver's mesh.

## 3. The view (`paraview_conductor.py`)

### 3.1 Layout: the approved 2 × 2

| Top left | Top right |
|---|---|
| **In1.Cu (GND1)**, top-down | **F.Cu** (trace + ground pour), top-down |
| **In2.Cu (GND2)**, top-down | **3-D stack** In2 / In1 / F.Cu with via barrels |

- **Linked cameras:** the three top-down views share one camera, so zooming one zooms all.
- **Starting window:** the views open on the ports' bounding box + 3 mm (`VIEW=x0,x1,y0,y1` to change it). On
  `board20n` that is the RF area from the modem port and J11 to the matching network.

### 3.2 Visual encoding (top views)

| Element | Encodes | How |
|---|---|---|
| Copper surface colour | **strength**: amplitude \|K\| of the whole-depth sheet current | Viridis, **log scale over 3 decades**; the top is the 99.9th percentile of \|K\| on F.Cu copper, the same scale in every panel; no colour outside copper |
| Surface LIC texture (line-integral convolution) | **current lines** of the instantaneous current Re(K·e^{jφ}) | streaks follow the flow at every pixel of the copper; masked below the colour floor |
| Sparse white arrows | **direction** (LIC has no sign) | uniform 0.3 mm grid (`ARROW_DX`), 0.35 mm long (`ARROW`), only where \|K\| > 3 % of the top (`ARROW_MIN`) |
| Animation | **phase** over one RF cycle | 18 steps; starts at `PHASE=auto` = the instant of maximum modem-port current |

**Why LIC rather than arrows or streamlines:**
- **Arrows fall short.** On the very non-uniform FDTD mesh, arrows either miss the 0.14 mm trace or clutter the
  ground pour.
- **Streamlines fall short.** They need seed points, and they stop where the instantaneous current is not
  divergence-free (charge build-up at line ends and pads).
- **LIC works everywhere.** It shows the flow everywhere, whatever the 1:100 range of |K|. A return-current loop,
  such as trace → port → GND1 return → vias → GND2, reads as one continuous, closed streak pattern.

### 3.3 3-D view

- **Copper:** translucent copper of In2, In1 and F.Cu with the via barrels between them. Height is exaggerated ×4
  (`Z3`), so the 76 µm prepreg is visible next to the 1.27 mm core.
- **Current surfaces:** shown only where |K| > 3 % of the top (`STRONG`), so the main loops stand out.
- **Via-current arrows:** vertical arrows on each via, per segment, coloured by |I|, shown above 2 % of the largest
  via current (`VIA_MIN`). They flip with the phase animation.

### 3.4 Reading it

- **Feed and its return.** The return current on GND1 runs directly under the trace, opposite to it.
  Coplanar return flows along the edges of the F.Cu ground pour on both sides of the trace.
- **Through the J11 void.** At the void under J11 pad 1 the GND1 current cannot cross. Look for where it goes: around
  the rim, or down the J11 ground vias to GND2, under the void, and back up. In the H-slice study at 0.75 GHz most of
  it took the via route. Re-check this with the conductor view on the current board, whose void changed (4bd46ea).
- **Vias.** The via arrows show which vias carry the layer change, and in which direction at that phase. The strongest
  are normally the J11 ground vias and those next to the matching network.
- **Frequencies.** At low frequency (B28/B12, ~0.7 GHz) the feed is electrically short. At 2.6 GHz it is about half a
  wavelength, so `single` runs show a standing-wave minimum along it. `super` runs show the real match instead.
- **Absolute values.** They are meaningful (A/m and A for 1 V incident), but strength depends on mismatch. Compare
  patterns between `single` and `super` with that in mind.

### 3.5 Options (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `DUMP_DIR` | — | run dir (uses `<run>/cc`) or a `conductor_current.py` output dir (`meta.json`) |
| `FREQ` | 0.75 | GHz, nearest dumped frequency |
| `PHASE` | auto | starting phase in degrees, or the instant of maximum feed current |
| `MODE` | — | `single` / `superposed`: only checked against `meta.json` |
| `STYLE` | lic | `lic` (texture + arrows) or `arrows` (colour + arrows) |
| `VIEW` | ports + 3 mm | top-view window x0,x1,y0,y1 in mm |
| `DECADES`, `PCT` | 3, 99.9 | log range and colour top |
| `ARROW_DX`, `ARROW`, `ARROW_MIN` | 0.3, 0.35, 0.03 | arrow spacing, length, threshold |
| `Z3`, `STRONG`, `VIA_MIN` | 4, 0.03, 0.02 | 3-D height factor, current-surface and via-arrow thresholds |
| `SHOT` | — | write `<prefix>_2x2.png` (2400 × 1600) for headless checks |

### 3.6 Running it

```
cd electronics/sim/feedline
tools/py.sh conductor_current.py single board20n/nport_gap_p0/run_0 --freqs=0.725,1.75     # or: super …
DUMP_DIR=board20n/nport_gap_p0/run_0 FREQ=0.725 paraview --script paraview_conductor.py    # locally
```

Viewing on **atmega** (the desktop ParaView host):
1. **Copy the inputs** to `~/feedline-sim/` with the same layout: the `cc` or `cc_super_*` folder and
   `<variant>/copper3d/`.
2. **Launch:** `tools/paraview_remote.sh paraview_conductor.py DUMP_DIR=… FREQ=… STYLE=arrows`.
3. **Use `STYLE=arrows` there.** The LIC texture segfaults in the ParaView 6.2 GUI under Xwayland on atmega. LIC
   works locally and in `pvpython` offscreen.

Headless check: `SHOT=/tmp/v pvpython --force-offscreen-rendering paraview_conductor.py` with the same environment.

## 4. Validation (summary; numbers in HANDOFF 6e)

- **Trace current vs port current.** The current through cuts across the trace equals the port current from the
  port waves to 0.4–1.4 %, at all distances. The small drift is the standing wave. Neighbouring cuts change by ≤
  0.06 %, so there are no half-cell artefacts.
- **Return-current balance.** The return through GND1, the F.Cu pour and GND2 balances the trace current to about 4
  % at the cut next to the port.
- **Superposition.** |S11| at the modem matches the circuit analysis (`analyze_pi.py`) to 0.1 dB. The superposed
  trace current matches the circuit's port current to 0.995–0.998 within 2°.
- **Against the old method.** The old H-slice method agrees within ~6 % on the planes. It is wrong at the trace
  edges: it puts current beside the trace and misses 7 % of it. The curl-H method is exact there.

## 5. Limits

- **Top and bottom face.** Each sheet's current is the sum of its two faces. The split is not resolved, and copper
  roughness is not modelled.
- **Edge crowding.** It is resolved only to the mesh. The 0.14–0.158 mm trace spans 4–8 cells across, depending on
  the model.
- **Displacement current near pads.** On copper, curl H includes a negligible displacement term. Off copper it is
  masked out.
- **Real operating case.** `single` pictures have 50 Ω on every other port. Only `super` shows the real case, the
  network and the measured antenna. The antenna file was measured on AVX's board, not on this ground plane.
- **No E-field or radiation.** This view shows conductor current only. For fields use the views in section 6.

## 6. Older views (still in the tree, superseded or for other questions)

| Script | Shows | Status |
|---|---|---|
| `paraview_return.py` + `sheet_current.py` / `via_current.py` | same 2 × 2, current from H sampled on dielectric planes just above and below each copper layer (K = z × (H_above − H_below)), arrows coloured by \|K\|, copper-masked | **superseded** by the conductor view: misses part of the current at conductor edges and puts some beside the copper; relative units only |
| `paraview_3d.py` (`--dump3d`) | 3-D E-field volume: iso-surfaces, vertical slice across the feed, time series and per-frequency snapshots | for fields between trace and ground, not currents |
| `paraview_nport.py` (`--dump=1`) | time-domain E-field on the top copper and mid-dielectric planes | first-look field animation |
| `paraview_preview.py` | gerber2ems' own E-field dumps | gerber2ems built-in runs |
