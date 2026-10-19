# Handoff: antenna feed + matching network EM simulation (AirBoxV2)

This document lets you redo the openEMS study of the nRF9151 → KYOCERA AVX P822601 antenna feed on an updated board.
Read it fully before running anything: the old board-specific coordinates in the scripts are WRONG for the new board
(section 4).

## 1. What this is and what was found

- **Board:** AirBoxV2 (`electronics/airbox.kicad_pcb`, KiCad 10.0.7), nRF9151 LTE modem (U1), P822601 antenna (ANT1)
  with a pi matching network (L6 series, C8 shunt at the modem side, C6/C7 DNI shunt, C5 series, L5 shunt at the antenna
  side; all 0402), U.FL test connector J11 on the feed. Schematic sheet `electronics/antenna.kicad_sch` carries a
  results note (bottom right).
- **Stack:** JLCPCB JLC05161H-1080, 4 layers. F.Cu 35 µm / prepreg 76.4 µm εr 3.91 / In1.Cu "GND1" 15.2 µm / core
  1.265 mm / In2.Cu "GND2" / prepreg 76.4 µm / B.Cu. The feed is a microstrip over GND1. Exact values:
  `asis/fab/stackup.json` (copied into every variant).
- **Tooling:** [antmicro/gerber2ems](https://github.com/antmicro/gerber2ems) turns gerbers into an openEMS geometry and
  mesh. Our own scripts then strip its ports and run custom lumped-port multi-port and field simulations. scikit-rf
  attaches the part values and the measured antenna S-parameters afterwards.

Results so far (old board, see README.md for all tables):

1. **Feed line:** 0.158 mm on this stack is a 49 Ω line, with |S11| < −35 dB when J11 is absent. A 0.135 mm trace is
   worse.
2. **J11 pad:** the J11 U.FL signal pad on 76 µm prepreg is a 0.48 pF shunt, costing up to 4 dB of return loss in the
   high bands.
   - **Fix:** a GND1 void of pad + 0.25 mm under it. Voiding GND2 as well changes nothing.
3. **Matching values:** with the feed and lands EM-modelled (8-port) and the measured bare antenna, the datasheet values
   (24n/2.4p/1n/0.3p) reach only about −3.5 dB in B3/B7.
   - **Best set with the J11 void:** L5 22 nH, C5 3.6 pF, L6 2.2 nH, C8 0.5 pF, giving −9 dB or better from B28 to n78.
   - **Void benefit:** with the network fitted, the void is worth 2-4 dB in B3/B7.
4. **Return current at 0.75 GHz:**
   - **Path:** the GND1 return current does not go around the J11 void. It drops through the 4 J11 ground vias to
     GND2, crosses under the void there, and comes back up.
   - **Feed length:** at 2.6 GHz the feed is about λ/2 long, so a standing-wave minimum shows along it.
5. **Mesh convergence:** halving the x/y mesh moved the 50 Ω-loaded |S11| by about 1 dB.
   - **Not yet done:** the 8-port matching search has not been redone on the fine mesh.

## 2. Where everything is

| What | Where |
|---|---|
| Simulation code + results | branch `antenna-sim`, folder `electronics/sim/feedline/` (worktree `.claude/worktrees/antenna-sim`) |
| Up-to-date board (source for the new run) | `electronics/airbox.kicad_pcb` on `antenna-sim` since merge 4476c8d; the user keeps editing it on `v3-ltem` in the main checkout `~/Projects/AirBoxV2` |
| Old board the results are from | `antenna-sim` commit 2cf48dc ("Snapshot of v3-ltem working tree"), `electronics/airbox.kicad_pcb` |
| Measured antenna S-parameters | `sim/feedline/sparam/Ant_P822601_S_parameters.s1p` (bare antenna, 650 MHz–4 GHz), `EVB_P822601-01_S_parameters.s1p` (AVX demo board incl. its matching) |
| Datasheet, demo-board gerbers | `electronics/AVX-E_P822601-P822602.pdf` (matching p.12), `P822601_GERBERS.zip`, `P822601_s-parameters.zip` (untracked, main checkout) |
| Narrative README | `sim/feedline/README.md` (variants, results tables, run commands, lessons) |
| Helper scripts | `sim/feedline/tools/*.sh` (section 3) |
| Remote results and viewer files | host `atmega`, user `ux`, `~/feedline-sim/` (mirrors the feedline layout; ~6 GB) |

`v3-ltem` ("Routing WIP", "Full route, TBD finish", "Add board outline") was merged into `antenna-sim` on 2026-10-20
(merge 4476c8d): `electronics/airbox.kicad_pcb` in the worktree IS the new routed board, so `build_pi.py` without
`--board` now reads it. The results in this folder are from the OLD board (commit 2cf48dc). If `v3-ltem` moves on
again, merge it again or pass `--board=PATH`.

## 3. Machines, tools and setup

| | local (this PC) | atmega |
|---|---|---|
| CPU | Core Ultra 9 285K: CPUs 0–7 = P-cores, 8–23 = E-cores | i9-12900H: CPUs 0–11 = P-cores (with HT), 12–19 = E-cores |
| openEMS speed | ~650 MC/s pinned to 0–7 with 8 threads (24 threads: 505, auto 6: 590 — memory bound) | ~400 MC/s on 0–11, 6 threads |
| Docker image | `gerber2ems:latest` | `gerber2ems:latest` (copied with `docker save \| zstd \| ssh docker load`) |
| ParaView | 6.2 (`paraview`, `pvpython`) | installed; KDE Plasma (Wayland + Xwayland), run with `QT_QPA_PLATFORM=xcb` |
| Disk | fine | was 94–97 % full during the study, 58 GB free now; field runs write 0.3–2.5 GB each |

- **Docker image:** build it from a checkout of antmicro/gerber2ems with `docker build -t gerber2ems .`. That compiles
  openEMS at the commit antmicro pinned. The host Python (3.14) cannot run openEMS, so everything that imports openEMS
  or CSXCAD runs in the container. The container Python is `/home/docker/opt/openEMS/venv/bin/python`, with numpy,
  vtk, openEMS and scikit-rf.
- **openEMS has no MPI engine** in this build. One excitation cannot be split across machines. Parallelise by port
  (one run per excited port), or run different cases on each machine.
- **Host-side scikit-rf** (for `analyze_pi.py`; the old venv is gone):
  `python3 -m venv ~/.venvs/rf && ~/.venvs/rf/bin/pip install numpy scikit-rf`. The study used skrf 2.2.0. Import
  `DefinedGammaZ0` from `skrf.media` and `Circuit` from `skrf.circuit`; the top-level names no longer exist.
- **KiCad Python:** `python3` on the host imports `pcbnew`, which `build_pi.py` and `export_copper3d.py` use.
  `kicad-cli` handles the gerber/drill/PLY exports.

### Helper scripts (`sim/feedline/tools/`, logs go to `sim/feedline/logs/`)

| Script | Does |
|---|---|
| `g2e.sh <variant> -g` | gerber2ems in `<variant>/`: geometry + mesh → `<variant>/ems/geometry/geometry.xml` + layer PNGs (`-a` = full gerber2ems MSL-port run) |
| `sim.sh [-c CPUSET] [-t TAG] <variant> <sim_pi args>` | `sim_pi.py` locally in Docker, default CPUs 0–7 |
| `remote.sh <variant> <out> <sim_pi args>` | same on `$RHOST` (default atmega, CPUs `$RCPUS`=0–11); copies `S.npz` back as `S_atmega.npz`; field dumps stay remote |
| `py.sh <script.py> args` | any feedline script in the container Python (`FREQS=0.75` env passes through) |
| `watch.sh <logname>` | stream completions/errors/energy blow-ups (use with a monitor) |
| `wait.sh <logname> [step]` | block until a step or the exit (background job → one notification) |
| `paraview_remote.sh <script.py> VAR=val …` | open a ParaView script on atmega's desktop (copies the script; closes an open ParaView) |

`g2e.sh`, `sim.sh`, `remote.sh` and `py.sh` were smoke-tested on 2026-10-20. `paraview_remote.sh` packages the exact
commands used during the study but was not run in this form.

## 4. Sourcing the new board: what must be re-derived

`build_pi.py` derives a small simulation board from the full board. It keeps the RF footprints (J11, L5, C5, C6, C7,
L6, C8 and ANT1, except in `--neck`), the RF tracks, the GND tracks/vias/zones and the rule areas inside a window. It
adds a solid GND zone on all four layers, an Edge.Cuts outline and an aux origin at the window's bottom-left corner. It
then exports gerbers (X2 attributes are required: the mesh refines along `traces[].nets`), drill, `stackup.json`, a
pos file and `<variant>_pads.json`.

**Hard-coded geometry in the scripts.** Re-check every item below against the new board:

| Item | File / constant | Old value | What it means |
|---|---|---|---|
| Window | `build_pi.py` `X0, Y0, X1, Y1` | 189, 106, 225, 134 (board mm) | sim area; must contain U1 ANT pad stub, J11, the network and (non-neck) ANT1 pads 1/2 with margin |
| Modem port cut | `build_pi.py` `P1Y`, x = 194.25 | trace cut at y 129.25 on the vertical stub x = 194.25 | the feed is cut here, port 1 sits on the stub end |
| Modem port box | `build_pi.py` → `pads.json port1` | x 194.25 ± 0.079 (half of 0.158), y 129.25–129.45 | must match the NEW trace width |
| gerber2ems MSL port | `build_pi.py` pos CSV `SP1` | (5.25, Y1 − 129.9), rot 180 | only used by `g2e.sh -a`; rotation points INTO the line (0 = +y, 90 = −x, 180 = −y, 270 = +x); width/length in `simulation.json` ports |
| Antenna "neck" port | `build_pi.py` `NECK_X`, `NECK_Y` | x 218.3, y 113.9 (end of the ground bump at 218.4) | `--neck` cuts the feed here and puts the antenna port on the 0.2 mm neck over GND1 |
| J11 void | `build_pi.py --void` | rule area x 195.725–197.275, y 130.225–131.725 on In1 | OLD J11 position: do NOT use `--void` on the new board (see below) |
| Validation variant | `build_pi.py --val` | window 189/107/215.2/137, cut x 213, y 113.75 | reproduces gerber2ems' own 2-port feed for port-model validation |
| GND1 height | `sim_pi.py` `Z_GND1 = -764` (0.1 µm units) | −76.4 µm | from the stack; In2 = Z_GND1 − 12650 |
| Port list | `sim_pi.py` | modem, antenna, C5.1 C5.2 L6.1 L6.2 L5.1 C8.1 (land ports) or gap ports C5 L6 L5 C8 C6 C7 (`--gap`) | taken from `pads.json` by reference/pad |
| Copper z slabs | `paraview_return.py` `PLANES` | In1 −0.105…−0.07, F.Cu −0.01…0.045, In2 −1.39…−1.35 mm | from `export_copper3d.py` output; recheck if the stack changes |
| View defaults | `paraview_3d.py` `SLICE_Y=12`, camera positions in both views | — | cosmetic |

**Changes found in the new board** (main checkout, 2026-10-20). Verify these yourself:

- **Trace width:** `/RF_ANT` is now 0.14 mm in most places, with 0.158 mm only at the network end (213.65–213.86,
  113.75–113.90).
  - **Impedance:** re-check it. The old run found 0.135 mm slightly off 50 Ω and 0.158 mm at 49 Ω.
  - **Port box:** update the modem port box width to ±0.07.
- **New route:** the vertical stub from U1 at x = 194.25 (y 129.0–131.25), an arc to y = 133.25, a horizontal run to
  x ≈ 207.5, an arc up x = 209.25 (y 131.5–117), an arc to y = 114.0 (x 212.25–213.77), then into the network at
  (213.8, 113.9).
- **J11 moved and is now inline.** It is at (202.5, 131.75), rot +90. Pad 1 (202.0–203.0, 132.75–133.80) sits directly
  on the trace at y = 133.25. GND pads are at x 200.5–201.55 and 203.45–204.5, y 130.65–132.85.
- **The window is too tight.** The old `Y1 = 134` leaves 0.2 mm past J11 pad 1. Extend it (e.g. `Y1 ≥ 137`) and recheck
  the bottom-left corner origin (all sim coordinates are relative to `(X0, Y1)`, y up).
- **New J11 void.** Two rule areas on GND1 + GND2 under J11 pad 1 ((201.95, 132.70)–(203.05, 133.18) and
  (201.95, 133.32)–(203.05, 133.90)). This is pad-sized (not pad + 0.25 mm), on both inner layers, and leaves a 0.14 mm
  GND strip under the trace through the void.
  - **Old fix:** the old study's fix had no strip.
  - **To confirm:** `build_pi.py` keeps rule areas (except `--val`), so the new void is simulated as drawn. Confirm in
    `<variant>/ems/geometry/In1_Cu.png` and `In2_Cu.png`.
- **A via on `/RF_ANT`** at (213.765, 113.0), 0.3 drill / 0.6 pad, next to C8 pad 1. No RF copper on the other layers,
  so it is an open via stub through the board. It will be in the model; flag it to the user.
- **More ground vias:** 96 in the old window area vs 46 before, and new GND zones `rf_gnd` / `ANT_GND` / `BASE_GND`.
  This is handled automatically.
- **Unchanged:** U1, the matching-network footprints and positions, ANT1, TUNE1/TUNE2 and the antenna keep-outs.
- **Layer names** are still `GND1` / `GND2`. `build_pi.py` renames their gerbers to `In1_Cu` / `In2_Cu`, which
  gerber2ems needs.

Suggested procedure:

1. The new board is already on `antenna-sim` (merge 4476c8d). Merge `v3-ltem` again if it has moved, or pass `--board=PATH`.
2. Edit the table items above in `build_pi.py`: window, port-1 box width, `SP1`, and neck point if the network moved.
   Rename `NAME` or add a flag so the new variants (e.g. `new_neck`) don't overwrite the old results.
3. `python3 build_pi.py --neck --board=…`, then open `feed_<variant>.kicad_pcb` in KiCad and LOOK at it: the cut
   points, the J11 void, the window edges, and that the GND fill reaches the trace.
4. `tools/g2e.sh <variant> -g`, then look at `<variant>/ems/geometry/*.png` (copper per layer; green = copper, row 0 =
   y max).
5. `python3 export_copper3d.py <variant>` with the host python, because it needs `pcbnew` (not available in the
   container). It writes PLY copper + `align.json` for the 3-D views.
6. Validate the ports on the new geometry if the trace width changed: build a `--val`-style 2-port cut of the feed, run
   `g2e.sh -a` (gerber2ems' MSL ports) and `sim_pi.py --line=1` on the same mesh, and compare. The old comparison
   script was lost with a scratchpad: compare |S11|/Zin from `<variant>/ems/results/Port_0_data.csv` with
   `<variant>/nport/S.npz`. The old agreement was within 0.8 dB / 1.5 Ω.

## 5. Running the simulations

All `sim_pi.py` options are documented at the top of `sim_pi.py`. The important ones:

| Option | Meaning |
|---|---|
| `<variant> [port idx …]` | which excitations to run (default all; finished columns in `S.npz` are skipped on resume) |
| `--line=1` | **always** — lumped ports as single-mesh-line sheets (the only stable shape, see section 6) |
| `--gap=1` | one port across each part's land gap (series) or land-to-GND-land (shunt) instead of land-to-GND1 ports |
| `--nrts=N --tsf=0.9 --threads=8` | step cap (70000 coarse, 120000 fine mesh), timestep factor, threads |
| `--out=<folder>` | results under `<variant>/<folder>` (default `nport`) |
| `--parts=C5:3.6p,L6:2.2n,L5:22n,C8:0.5p` | put real parts in as lumped L/C (field pictures); only modem + antenna ports remain, antenna = 50 Ω |
| `--hdump=x0,x1,y0,y1` | H planes (frequency domain at `--fdump`) above/below F.Cu, In1, In2 + mid-prepreg + mid-core |
| `--dump3d=x0,x1,y0,y1` | 3-D E-field volume (time + frequency domain) + meshed metal (`PEC_dump.vtp`) |
| `--dump=1` | E-field planes (time domain) |
| `--fdump=0.75,1.8,2.6` | GHz for the frequency-domain dumps (they cost almost nothing; drop them in post-processing instead) |

### a) 8-port matching model (the core result)

```
tools/sim.sh <variant> --line=1 --gap=1 --nrts=70000 --tsf=0.9 --threads=8 --out=nport_gap
~/.venvs/rf/bin/python analyze_pi.py <variant> out=nport_gap          # worst |S11| per band, datasheet sets, grid search
```

- **Split across machines** by port index: run e.g. `tools/sim.sh <variant> 0 1 2 3 …` locally and
  `tools/remote.sh <variant> nport_gap 4 5 6 7 --line=1 --gap=1 …`.
- **Merge** with `python merge_nport.py <variant>/nport_gap <variant>/nport_gap/S_atmega.npz`. Run it with the
  container (`tools/py.sh`) or the rf venv. It writes `feed_pi.s8p` once all columns exist.
- **Timing:** coarse mesh (~2.9 Mcells) takes about 6 min per port locally; fine mesh (12.9 Mcells) about 45 min per
  port.
- **Matching search:** `analyze_pi.py` has a coarse grid search built in. The refined E24 search used for the final
  values was an inline loop around the best grid point: L5 18–27n, C5 2.7–3.9p, L6 1.8–2.7n, C8 0.2–0.5p. Add it to the
  script if you rerun it.
- **Port order:** see `<variant>/<out>/ports.json`.

### b) Field / return-current pictures (what the user looked at last)

```
tools/sim.sh <variant> 0 --line=1 --gap=1 --parts=C5:3.6p,L6:2.2n,L5:22n,C8:0.5p --hdump=0,36,0,28 \
    --fdump=0.75,1.8,2.6 --out=nport_h --nrts=120000 --tsf=0.9 --threads=8
FREQS=0.75 tools/py.sh sheet_current.py <variant>/nport_h/run_0
FREQS=0.75 tools/py.sh via_current.py  <variant>/nport_h/run_0 <variant>/fab/<board>-PTH.drl
```

- **The `--hdump` box** is the window size in mm (sim frame). Adjust it to the new window.
- **Copy to atmega** only the files of the frequency to view, plus the inputs the views need: `rsync --include='*f=750000000_*' --exclude='*' <run_0>/ atmega:feedline-sim/<variant>/<out>/run_0/`,
  plus `<variant>/copper3d/` and `<variant>/ems/geometry/{F_Cu,In1_Cu,In2_Cu}.png` (the copper masks).
- **Open the view:**
  `tools/paraview_remote.sh paraview_return.py DUMP_DIR=<variant>/<out>/run_0 FREQ=0.75`.
- **The view the user approved (keep it):** a 2 × 2 layout.
  - **Top-down panels:** In1.Cu | F.Cu / In2.Cu.
  - **3-D panel:** the stack with via barrels and vertical via-current arrows.
  - **Copper:** real 3-D copper per layer (KiCad PLY).
  - **Arrows:** surface current K, masked to that layer's copper, colored by |K| (Viridis, log 3 decades, top =
    99.9th percentile of the top layers), animated over one cycle.
  - **Options (env):** `STRIDE`, `ARROW`, `ARROW_MIN`, `DECADES`, `PCT`, `Z3`, `MASK`.
- **3-D E-field view:** `--dump3d=4,30.5,1,23.5`, then `paraview_3d.py` with `DUMP_DIR`. This is 2.5 GB.

## 6. Pitfalls learned the hard way

- **Stable port shape.** Lumped ports must be single-mesh-line sheets (`--line=1`).
  - **Shapes that diverge:** 2-D box ports, from the first step. Forgetting `--line=1` makes the modem port a box and
    the run diverges whatever else is in the model.
  - **Also diverges:** any port between ANT1 pad 1 and pad 2 (box, line, vertical sheet), hence the `--neck`
    variants.
  - **Symptom:** energy rising past ~1e-13 around step 20–25k. Watch with `tools/watch.sh` and stop such runs early.
- **Don't add mesh lines.** `sim_pi.py` snaps port boxes to existing mesh lines; extra lines shrink the timestep.
- **Strip gerber2ems' own ports.** `sim_pi.py` removes its port metal, probes, excitation AND its `LumpedElement` feed
  resistor from `geometry.xml`.
- **Energy never decays fully.** It stalls at about −13 to −26 dB because of static charge on floating copper and the
  lumped capacitors (DNI lands, parts). It is a constant (DC) offset, harmless to the results. Cap the run with
  `--nrts`; the end criterion is never met.
- **Units in the field dumps.** openEMS dumps are in metres.
  - **Scaling:** the ParaView scripts scale geometry ×1000 to mm, and `TransformAllInputVectors=1` scales every
    vector by the same factor. So |K| is "relative ×1000"; compare patterns and ratios, not absolute numbers across
    frequencies (the excitation spectrum is not flat).
  - **Bug to avoid:** writing pre-scaled arrays and letting the transform scale them again (that bug produced
    saturated arrows).
- **H sits half a cell off.** H is sampled on the dual grid, so an H plane requested on mesh line z lands half a cell
  away, e.g. "above In1" = 10 µm above it. `via_current.py` and `sheet_current.py` use the actual z from the files.
- **Surface current needs both sides.** K = z × (H_above − H_below) across a zero-thickness copper sheet. Above-only
  misses the trace underside, where most microstrip current flows.
- **Arrow masks.** Arrows over voids and clearances are field, not current, hence the copper masks from gerber2ems'
  layer PNGs (5 µm/pixel, origin bottom-left in VTK).
- **ParaView quirks.**
  - **Shadowed builtins:** `from paraview.simple import *` shadows `max` and `min`; use `builtins.max`.
  - **Glyph translate:** the property is `GlyphTransform.Translate`.
  - **Render quirks:** headless test with `pvpython --force-offscreen-rendering`. ExtractSubset needs an explicit
    `VOI`. Color maps auto-rescale unless `AutomaticRescaleRangeMode='Never'`, set at the end.
- **atmega display.** The X auth file name changes per login; `tools/paraview_remote.sh` reads it from plasmashell's
  environment. Use `pkill -x paraview`, not `pkill -f …`: the latter matched the ssh command itself and killed it.
- **KiCad schematic edits.** KiCad 10 writes `(embedded_fonts no)` inside lib symbols too. Never insert by searching
  for that token; append before the file's final `)`. Notes: `justify left top`, lines ≤ 128 chars.
- **Worktree guard.** If you work from a worktree-isolated session, Bash commands with `$(...)`, `git -C`, docker
  `--format '{{…}}'` or complex pipelines get refused. Put them in a script file and run that.

## 6b. Whole-board meshes (2026-10-20)

`build_board.py <name> <optimal_um> <inter_layers>` exports the WHOLE routed board (all copper, all nets, zones
refilled) as a gerber2ems project. It needs `--disable-aperture-macros` in the gerber export: gerber2ems cannot parse
KiCad's pad aperture macros. The grid scales from the coarse study grid. Mesh only, built with `tools/g2e.sh <name> -g`:

| build | grid (optimal/diag, perpendicular, max, z cells per dielectric) | lines x × y × z | cells | smallest cell x / y / z |
|---|---|---|---|---|
| `board20` | 20, 75, 200 µm, 4 | 1091 × 727 × 44 | 34.0 M | 11.2 / 20.0 / 19.1 µm |
| `board13` | 13, 48.75, 130 µm, 6 | 1639 × 1055 × 64 | 108.8 M | 13.0 / 7.3 / 12.7 µm |

- **Box size:** both span 70.2 × 56.0 × 5.42 mm (outline + 1.5 mm, 2 mm air).
- **Port placement:** the pos file puts SP1 on the modem stub at board (194.25, 129.6), rot 180. Ports for
  `sim_pi.py` still need a pads json; `build_board.py` does not write one yet.
- **IMPORTANT, domain size.** gerber2ems' `grid.margin.from_trace: true` (the default in every earlier variant's
  `simulation.json`) sizes the simulation box to the RF traces + 1.5 mm, NOT to the board window.
  - **Earlier runs:** the feed-window simulations (36 × 28 mm windows) really ran on about 27 × 22 mm boxes, cutting
    the ground pour 1.5 mm beyond the outermost RF copper into the absorbing boundary.
  - **Whole board:** `build_board.py` sets `from_trace: false`.
  - **Feed windows:** decide deliberately whether the window variants should too; it changes the ground the feed sees.
- **The 7.3 µm y cell in `board13`** comes from two features close together. It sets the timestep: about 1.4× more
  steps than `board20`. Check `geometry.xml` for such pinch cells, or snap the offending feature, before long runs.

## 7. Open items / likely next tasks

- **Rerun on the new board** (section 4): the feed impedance at 0.14 mm, the effect of inline J11 with its pad-sized
  void plus GND strip on GND1+GND2, and the RF via stub at C8.
- **Refresh the matching search.** Redo the 8-port model on the new board, ideally on the fine mesh: halve
  `optimal/diagonal/perpendicular/max` in `simulation.json`, as in `pi_void_neck_fine/simulation.json`. Then re-run the
  value search and update the Antenna sheet note.
- **Real part models.** Use vendor S-parameter files for the actual 0402 parts (inductor SRF at 20+ nH matters)
  instead of ideal L/C in `analyze_pi.py`.
- **Real antenna load in the field view.** Possible via superposition of the 8 per-port field runs with the cascade
  weights (several hours of compute). Not done.
- **Uncommitted at handoff time:** `asis_pads.json`, `logs/` (gitignored helper logs). Everything else is committed on
  `antenna-sim`.
