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
| `worker.sh <variant> <outbase> <host\|local> <cpus> <threads> <sim_pi args>` | dynamic work queue: one worker per machine claims the next free excitation (section 6d) |
| `chain_local.sh <logname> <command \| sim.sh args>` | wait for a local run's `=== exit`, then exec the command (or `sim.sh` with the args); detach with `setsid nohup` |
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
- **J11 void, updated by "Fix ufl strip" (4bd46ea, pulled 2026-10-20).** One rule area on GND1 + GND2 under J11 pad 1,
  (201.95, 132.70)–(203.05, 133.88).
  - **Previous version:** two halves leaving a 0.14 mm GND strip under the trace through the void. The strip is
    now gone.
  - **Still pad-sized,** about pad + 0.05 mm, while the old study's fix was pad + 0.25 mm on GND1 only. Simulate it
    as drawn; `build_pi.py` / `build_board.py` keep rule areas. Confirm in `<variant>/ems/geometry/In1_Cu.png` and
    `In2_Cu.png`.
- **RF via removed.** The via on `/RF_ANT` next to C8 at (213.765, 113.0) is removed by the same commit.
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

## 6c. Material model and the 20 µm whole-board model (decided by the user, 2026-10-20)

**Decision:** bake in the prepreg, core and solder mask, model copper as a lossy conductor ("as in real life"), and
use the 20 µm whole-board model (`board20n`). Every run from now on should use these options unless the user says
otherwise:

```
--tand=1 --cu=5.8e7 --mask=20,3.8,0.02          (sim_pi.py; --fref=1.8 GHz default)
```

| Item | Model | Value / source |
|---|---|---|
| Prepreg (2×, 76.4 µm) | lossy dielectric | εr 3.91, tan δ 0.016 (stackup.json, NP-155F 1080); loss as κ = 2π f ε0 εr tan δ, exact at `--fref` 1.8 GHz |
| Core (1.265 mm) | lossy dielectric | εr 4.43, tan δ 0.016 (NP-155F) |
| Solder mask, top + bottom | lossy dielectric layer 20 µm, openings over every pad of that side (air) | εr 3.8 (JLCPCB), tan δ 0.02 (common assumption, not published by JLCPCB), 20 µm = JLCPCB's 0.8 mil over laminate (they quote 0.5 mil over copper) |
| Copper F.Cu / B.Cu | openEMS conducting sheet (lossy, skin effect) | σ 5.8e7 S/m, 35 µm |
| Copper In1 / In2 | conducting sheet | σ 5.8e7 S/m, 15.2 µm |
| Vias | PEC (unchanged) | |

Implementation notes (`sim_pi.py`):
- **Copper:** `--cu` rewrites gerber2ems' `Metal Gerber_k` into `ConductingSheet` with `Conductivity`, and
  `Thickness` in metres.
- **Dielectric loss:** `--tand` sets `Kappa` on `Substrate_0..2`.
- **Solder mask and priorities:** `--mask` adds `SolderMask` boxes (priority 2) and `MaskOpening` air boxes from
  `<variant>_pads.json` (priority 3, `side` F/B/FB). It raises metal and vias to priority 50 and the ports and parts
  to 5 (`--prio` overrides). It also moves or adds a z mesh line at each mask surface.
- **B.Cu height:** gerber2ems stacks dielectrics only, so B.Cu sits at −1.4178 mm in the model.
- **Copper roughness** is not modelled.
- **Cost:** the conducting sheets make openEMS about 2.8× slower (~225 MC/s instead of ~650 on the local P-cores);
  the dielectric loss and the mask are free.
- **Effect** on the small coarse model, 50 Ω-loaded, 0.9/1.8/3.5 GHz: −7.0/−10.8/−7.3 dB lossless became
  −4.7/−13.0/−9.2 dB with all of the above. These are not small changes, so redo the matching search with them.

`board20n` = `python3 build_board.py board20n 20 4 --neck`:
- **What it is:** the whole routed board, with the feed cut at the antenna neck (x 218.3) and ANT1 removed.
- **Modem port:** U1's ANT pad is removed and the modem stub cut at y 129.25, with port 1 there (0.14 mm wide).
- **Port file:** `board20n_pads.json` has all pads plus the port and neck boxes.
- **Size:** 925 × 712 × 44 lines = 29.0 Mcells with the mask line; building the operator takes several minutes and
  about 5+ GB.

Machines added: **ilinca** (`ssh ilinca`, user ilinca): i7-12700H, CPUs 0–11 = P-cores with HT, only **15 GB RAM**
(~7 GB free), 800 GB disk, Docker with the `gerber2ems` image (loaded 2026-10-20), no ParaView. Use it for solver
runs that fit in memory: `RHOST=ilinca RCPUS=0-11 tools/remote.sh …`.

Branches: `antenna-sim` was fast-forwarded into `v3-ltem` in the main checkout on 2026-10-20 (not pushed). Keep the
two in step: commit on `antenna-sim`, then `git merge --ff-only antenna-sim` in the main checkout.

Long runs: start them detached (`setsid nohup tools/sim.sh … &`), not as a tool background job; the agent's
background jobs are killed after 2 hours, which stops the container (docker run forwards the signal).

## 6d. State at handoff and the three-machine run plan (2026-10-20, ~03:00)

**Nothing is running.** The board20n runs below were started, then stopped on the user's request before producing
results (they were still in the first excitation). Partial outputs were deleted on all three machines. The logs of
those attempts are in `logs/` (gitignored, local to the worktree):

| Run | What it measured before the stop |
|---|---|
| local field run | 28.98 Mcells, timestep 2.197e-14 s, excitation 70,471 steps, **210 MC/s = 0.138 s/step**, operator build ≈ 10 min (single-threaded), memory 3.4 GB steady (4.5 GB peak while building) |
| atmega / ilinca 8-port | stopped during the operator build: no speed figure; expect roughly 130–150 MC/s (≈ 6.5–7.5 h per excitation) |

**Inputs the next agent needs.** `fab/`, `ems/` and `copper3d/` of board builds are gitignored, so regenerate them
in the main tree (≈ 3 min):

```
cd electronics/sim/feedline
python3 build_board.py board20n 20 4 --neck        # KiCad python: board_board20n.kicad_pcb, board20n/fab, board20n_pads.json, board20n/simulation.json
tools/g2e.sh board20n -g                            # geometry + mesh (925 x 712 x 44 lines; 28.98 Mcells after the mask line)
python3 export_copper3d.py board20n                 # KiCad python: PLY copper for the ParaView views
```

`board20n_pads.json` and `board20n/simulation.json` are also committed, for reference. They predate the 4bd46ea board
fix: rebuild before running.

**Run settings (all runs):**

```
COMMON="--line=1 --gap=1 --tand=1 --cu=5.8e7 --mask=20,3.8,0.02 --nrts=120000 --tsf=0.9"
```

`--nrts=120000` is only 1.7× the excitation length (openEMS warns below 3×), so check the energy at the end of
each run. With lossy materials it should be below −20 dB. If it is not, raise `--nrts` (time scales linearly).

**Compute pool:**

| Host | CPU | RAM | Notes |
|---|---|---|---|
| local | Core Ultra 9 285K, 8 P-cores (0–7) | 30 GB | 210 MC/s on board20n with materials (measured) |
| atmega | i9-12900H, P-cores 0–11 (HT) | 31 GB | user `ux`, also the ParaView host; speed on board20n not measured |
| ilinca | i7-12700H, P-cores 0–11 (HT) | 15 GB (~7 free) | user `ilinca`; board20n needs ~3.5–4.5 GB, fits; speed not measured |
| khazadum-compute | Threadripper 1950X, 16 cores / 32 threads (cores 0–15, HT siblings 16–31) | 31 GB | user `root`, Proxmox container; root disk resized to 26 GB on 2026-10-20, `gerber2ems` image loaded (6.5 GB, 17 GB left); no `zstd` (use gzip); speed not measured |

- **khazadum-compute was prepared but never run.** On 2026-10-20 the user said not to run simulations there: the
  image was loaded but no simulation ran. Use it only if the user OKs it. If so, start with
  `RCPUS=0-15 --threads=16` (one thread per physical core, since openEMS is memory-bound) and compare
  against `0-31`.
- **Copying the image:** `docker save gerber2ems:latest | gzip -1 | ssh <host> 'gzip -d | docker load'`. This
  machine has no `pigz`; atmega and ilinca have `zstd`.

**Distribute dynamically, not with a fixed split.** Each excitation is one sequential run that cannot be split
across machines (no MPI). So:

1. **Field run:** start the transmit field run on the fastest machine (local), detached:

```
setsid nohup tools/sim.sh -t field board20n 0 $COMMON --parts=C5:3.6p,L6:2.2n,L5:22n,C8:0.5p --hdump=30,60,10,40 \
    --fdump=0.75,1.8,2.6 --threads=8 --out=nport_h >/dev/null 2>&1 </dev/null &
```
2. **Workers:** start one queue worker per machine for the 8-port model. Each claims the next free port, runs it,
   then claims another:

```
for h in atmega ilinca; do
  setsid nohup tools/worker.sh board20n nport_gap $h 0-11 6 $COMMON >/dev/null 2>&1 </dev/null &
done
setsid nohup tools/chain_local.sh sim_board20n_field tools/worker.sh board20n nport_gap local 0-7 8 $COMMON \
    >/dev/null 2>&1 </dev/null &      # local joins the queue after the field run
```


3. **Watch for a machine falling behind (user's instruction).** Check progress regularly: `ls logs/queue/board20n_nport_gap/*`,
   `cat logs/queue/board20n_nport_gap/*/claim`, and the step rate in `logs/sim_board20n_<host>.log`. A machine is behind
   when it is still on its port while all others have finished and the queue is empty, or when its step rate projects
   finishing much later than the rest. Then:
   - **Speculative duplicate:** run the same port on an idle faster machine
     (`PORTS=<k> tools/worker.sh …` after removing `logs/queue/…/<k>`, or run `tools/sim.sh` / `tools/remote.sh` for
     that port into a different `--out`). Keep whichever finishes first and stop the other with `docker stop` on its
     host.
   - **Lighten a slow host:** stop its worker after its current port (kill the `worker.sh` process; the running
     container finishes) so it takes no more ports.
   - **Reclaim a dead run:** if a run dies or a host drops out, `rm -rf logs/queue/…/<k>` and any idle worker re-takes
     it.
4. **Results:** they land in `board20n/nport_gap_p<k>/` (local `S.npz`, remote `S_<host>.npz`). Merge with
   `mkdir -p board20n/nport_gap && tools/py.sh merge_nport.py board20n/nport_gap board20n/nport_gap_p*/S*.npz`.

Rough expectations: one excitation takes ~4.7 h locally. The others are unmeasured, guess 6–8 h for atmega/ilinca. All
9 excitations (field + 8 ports) on three machines: ~15–20 h; adding khazadum-compute (if allowed) cuts that further.

- **8-port port order:** 0 modem, 1 antenna (neck), 2 C5, 3 L6, 4 L5, 5 C8, 6 C6, 7 C7 (gap ports; see `ports.json`).
- **Merging and analysis.** After all three finish:
  `tools/py.sh merge_nport.py board20n/nport_gap board20n/nport_gap/S_atmega.npz board20n/nport_gap/S_ilinca.npz`
  (writes `feed_pi.s8p`), then `~/.venvs/rf/bin/python analyze_pi.py board20n out=nport_gap`. Recreate the venv if
  needed (section 3), and redo the refined E24 search around the best grid point.
- **Field post-processing and view.** `FREQS=0.75 tools/py.sh sheet_current.py board20n/nport_h/run_0`, then
  `FREQS=0.75 tools/py.sh via_current.py board20n/nport_h/run_0 board20n/fab/board_board20n-PTH.drl`. Copy to
  atmega the `*f=750000000_*` files of `run_0`, `board20n/copper3d/` and `board20n/ems/geometry/{F_Cu,In1_Cu,In2_Cu}.png`
  (same layout under `~/feedline-sim/`). Then run
  `tools/paraview_remote.sh paraview_return.py DUMP_DIR=board20n/nport_h/run_0 FREQ=0.75`.
  The whole-board frame differs from the old window: the RF area is around sim x 30–60, y 10–40 mm. The script's
  default camera (focal point 17,12) is off-centre; press "reset camera" or update the camera lines.

**Stopping runs safely:**
- **Remote containers outlive their wrapper.** Killing `remote.sh` locally does NOT stop the container on the
  remote host; `docker stop` it there.
- **Never `pkill -f <pattern>`** from a shell whose own command line contains the pattern: it kills itself (this
  happened twice). List PIDs with `ps -eo pid,args` and kill those, or `docker stop` by container id.

## 6e. Conductor-current workflow (replaces the H-slice view, 2026-10-20)

**Why.** The old view (`sheet_current.py` / `paraview_return.py`) took H on dielectric slices half a cell above
and below each copper sheet. That misses part of the current and smears it beside the copper. The new workflow
integrates the conductor over its whole depth and shows that sum on the copper surface.

**Method.**
- **Sheet current.** `sim_pi.py --kdump=all` dumps curl H (openEMS dump type 13, frequency domain) on each copper
  sheet's own mesh plane. openEMS computes it per E edge as (H loop around the edge's dual face) / (dual area).
  Multiplying by the dual height `dz_dual = (z[k+1] - z[k-1]) / 2` gives the exact current per unit width through
  the whole sheet, both faces included: K in A/m.
- **Via current.** Jz on the mid-dielectric planes (`j_pp1` F.Cu–In1, `j_core` In1–In2, `j_pp2` In2–B.Cu),
  summed over the dual faces nearest to each drill hole, is the via current. This is an exact discrete Ampère loop.
- **Mesh.** `dz_dual` must use the solver's z mesh, which `--mask` changes. `conductor_current.py sim_zlines()`
  repeats the logic of `sim_pi.py build()`; keep the two in sync. Use `--mask=0` for runs without `--mask`.
  - **Effect on F.Cu:** `dz_dual` is 19.55 µm with the mask line and 24.55 µm without it.
  - **Check:** the H-plane z values recorded by openEMS sit exactly on the reconstructed dual lines.
- **Dumps are raw Yee-edge data.** `sim_pi.py` leaves openEMS dump mode 0 (no interpolation): Kx at index i is
  the edge x_i..x_i+1.
  - **Integrals** use these raw edge values.
  - **Display** values are node-interpolated, each component along its own axis, as openEMS node mode does.

### Commands

```
# one excitation (port 0 = modem): 1 V incident at port 0, ALL OTHER PORTS 50 ohm terminated  -> <run>/cc/
tools/py.sh conductor_current.py single board20n/nport_gap_p0/run_0 [--freqs=0.725,1.75] [--crop=25,62,12,40]
# matching network fitted (superposition of the 8 per-port runs; any part values, no new EM run)
tools/py.sh conductor_current.py super board20n/nport_gap_p*/run_* L5=22n C5=3.6p L6=2.2n C8=0.5p C6=- C7=- \
    [--out=board20n/cc_super_best]           # default out: board20n/cc_super_<parts>/
# validation numbers (needs a run with --kdump AND --hdump)
tools/py.sh conductor_current.py check pi_void_neck/ktest/run_0
# view (local or via tools/paraview_remote.sh on atmega; copy the cc dir + <variant>/copper3d)
DUMP_DIR=board20n/nport_gap_p0/run_0 FREQ=0.725 paraview --script paraview_conductor.py
DUMP_DIR=board20n/cc_super_best FREQ=0.725 MODE=superposed paraview --script paraview_conductor.py
```

### Outputs

Outputs are in mm, sim frame, A/m and A, for a 1 V incident wave at the modem:

| File | Contents |
|---|---|
| `K_<layer>_f=<Hz>.vtr` | `K` (amplitude), `Kre`/`Kim`, `Kvec` (phase 0), `copper` (copper coverage of the node's dual cell, from the layer PNG) |
| `K_<layer>_f=<Hz>.pvd` | phase animation: 18 steps, time = phase in degrees, all pointing at the same `.vtr` |
| `via_f=<Hz>.vtp/.pvd` | per hole and segment: `I`, `Ire`/`Iim`, `segment` |
| `meta.json` | label (single: "all other ports 50 ohm terminated"; super: the part values), port waves and currents, port boxes |

- **Phase in ParaView.** The view computes Re((Kre + j Kim) e^{jφ}) with a Python Calculator (`t_value`), so no
  per-phase copies are stored. Add `--snapshots=1` for explicit `_p=<deg>` files (about 8 MB each on the whole board).
- **Superposition** works at each dump frequency:
  - **S-matrix:** built from the runs' `waves.npz` as S = B A⁻¹, with A/B the incident/reflected waves of all ports
    in every run.
  - **Port terminations:** parts as 1-ports Γ = (Z − 50)/(Z + 50), `-` = open, as in `analyze_pi.py`. The antenna
    is the measured `.s1p`, and port 0 is a 50 Ω source with a = 1 V.
  - **Solve:** (I − ΓS) a = e0.
  - **Run weights:** c = A⁻¹ a, which also cancels the small non-zero incident waves at the "terminated" ports.
  - **Printed per frequency:** |S11| at the modem with the network, the power into the antenna port, |a_k|, and the
    difference to each run's own `S.npz`.

### Validation

Test run `pi_void_neck/ktest`: `--kdump=all --hdump=0,36,0,28 --fdump=0.75,1.8 --nrts=60000`, all materials, E-cores.

| Check | 0.75 GHz | 1.8 GHz |
|---|---|---|
| port-0 current `if_tot` (1 V incident) | 18.079 mA ∠−13.5° | 22.085 mA ∠+7.7° |
| trace current through cuts 0.1/0.2/0.4/0.8 mm from the port ÷ `if_tot` | 1.004/1.006/1.009/1.014, ≤ 2° | 0.995/0.993/0.989/0.982, ≤ 3.3° |
| return at the 0.1 mm cut: In1 / F.Cu pour / In2 (±3 mm) | 16.41 / 0.89 / 0.16 mA, opposite phase (+166°); sum of all 0.70 mA (3.9 %) | 19.99 / 1.08 / 0.17 mA; sum 0.75 mA |
| along the straight 0.9 mm of feed, cuts every 25 µm | 18.14–18.36 mA, max step 0.011 mA; node-interp. vs raw ≤ 0.014 mA | 21.65–21.99 mA, step ≤ 0.017 mA |
| curl-H vs H-jump K, In1 / In2 (relative L2, median ratio) | 0.058 / 0.066, 1.001 / 1.000 | 0.068 / 0.066, 1.001 / 1.000 |
| curl-H vs H-jump K, F.Cu | L2 0.40, median 1.008, 99.9th pct 228 vs 137 A/m | L2 0.41, 212 vs 133 A/m |
| trace cut 0.4 mm: curl-H / H-jump / `if_tot` | 18.24 / 16.86 / 18.08 mA | |

- **The two methods agree on the planes.** Away from conductor edges they match within about 6 % L2 and their
  medians agree.
- **They differ at the trace edges.** The H-jump drops the Hz part of the Ampère loop, which wraps around the trace
  edge.
  - **H-jump error:** it puts about 21 A/m on the nodes BESIDE the 0.158 mm trace, where there is no copper. It
    reads the edges at 93 instead of 128 A/m and misses 7 % of the trace current.
  - **curl-H:** zero off copper, edge crowding 128 vs 86 A/m in the middle, and the integral matches the port
    current to 0.4–1.4 %. The small change with distance is the standing wave.
- **No half-cell artefacts.** Neighbouring cuts change by 0.06 % at most.
- **Superposition** (`super` on all 8 test ports, L5=22n C5=3.6p L6=2.2n C8=0.5p, C6/C7 open, measured antenna):
  - **|S11| at the modem:** −11.5 dB at 0.75 GHz and −16.8 dB at 1.8 GHz. `analyze_pi.py` (skrf circuit on
    `feed_pi.s8p`) gives −11.50 / −16.72 dB.
  - **S-matrix:** S from `waves.npz` matches each run's `S.npz` to ≤ 1.1e-3.
  - **Trace current:** in the superposed field it equals the circuit's port-0 current (a − b)/50 to 0.995–0.998,
    within 2°.
  - **Antenna power:** the antenna port receives 81 % (0.75 GHz) and 87 % (1.8 GHz) of the incident power.

### View (`paraview_conductor.py`)

- **Layout:** the approved 2 × 2.
- **Top views:**
  - **Colour:** copper coloured by |K| (log, Viridis, 3 decades below the 99.9th percentile on F.Cu, the same scale in
    every panel).
  - **Texture:** a surface LIC of the instantaneous current Re(K e^{jφ}). Streaks are masked below the colour floor.
  - **Arrows:** sparse white arrows on a uniform 0.3 mm grid give the direction.
- **3-D view:** current surfaces where |K| > 3 % of the top, via barrels, and via-current arrows coloured by |I|.
- **Why LIC:** it shows the direction of the current at every pixel of the copper. Return-current loops (the
  GND1 path around the J11 void, the coplanar F.Cu pour return, via drops to GND2) therefore show as continuous
  closed streak patterns, whatever the arrow density or the 1:100 range of |K|. Arrows on the very non-uniform FDTD
  grid either miss the 0.14 mm trace or clutter the pour. Streamlines need seeds and stop at charge accumulations
  (the instantaneous K is not divergence-free).
- **Animation:** play it to sweep the phase. The start is `PHASE=auto`, the instant of maximum modem-port current.
- **Options:** `VIEW=x0,x1,y0,y1`, which defaults to the ports' bounding box + 3 mm (board20n: about x 29–59,
  y 16–38), plus `STYLE=arrows`, `DECADES`, `PCT`, `ARROW_DX`, `ARROW`, `ARROW_MIN`, `Z3`, `VIA_MIN`, `STRONG`, and
  `SHOT=<prefix>` (writes a 2400 × 1600 PNG, for headless tests).

### Caveats

- **Displacement current.** curl H is the TOTAL current: conduction plus displacement through the dual face. Off
  copper it is pure displacement current, hence the copper mask. On copper the dielectric half-cells add a
  negligible jωεE term.
- **Skin effect.** The conducting sheet models it in the E-edge update (σ, thickness). The sheet carries the sum of
  both faces' currents. The split between top and bottom face is not resolved, and roughness is not modelled.
- **Mesh resolution.** The trace edges sit where gerber2ems' mesh puts them. The 0.158 mm test trace carries
  current on 4 nodes (±0.066 mm, effective width ≈ 0.17 mm), so the edge crowding is resolved only to the mesh.
- **Units.** Values are per 1 V incident wave, so they are comparable across frequencies. The H-slice "relative"
  numbers were not.

## 7. Open items / likely next tasks

- **Rerun on the new board** (section 4): the feed impedance at 0.14 mm, the effect of inline J11 with its pad-sized
  void plus GND strip on GND1+GND2, and the RF via stub at C8.
- **Refresh the matching search.** Redo the 8-port model on the new board, ideally on the fine mesh: halve
  `optimal/diagonal/perpendicular/max` in `simulation.json`, as in `pi_void_neck_fine/simulation.json`. Then re-run the
  value search and update the Antenna sheet note.
- **Real part models.** Use vendor S-parameter files for the actual 0402 parts (inductor SRF at 20+ nH matters)
  instead of ideal L/C in `analyze_pi.py`.
- **Real antenna load in the field view.** Done in post-processing: `conductor_current.py super` (section 6e)
  superposes the 8 per-port `--kdump` runs for any part values.
- **Main tree is the place to work.** The main checkout (`~/Projects/AirBoxV2`, branch `v3-ltem`) contains all of
  this. `antenna-sim` and the worktree were only the study's sandbox. Generated data (`fab/` of board builds, `ems/`,
  `nport*/run_*`, logs) is gitignored and must be regenerated (section 6d).
  `antenna-sim`.
