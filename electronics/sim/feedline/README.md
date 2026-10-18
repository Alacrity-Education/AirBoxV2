# nRF9151 -> P822601 feedline simulation (openEMS via gerber2ems)

Only the antenna feed is simulated: the 0.158 mm microstrip from the nRF9151 ANT pad to the input of the
pi matching network, with the U.FL test connector pads (J11) and the GND via fence, on the JLCPCB
JLC05161H-1080 4-layer stack with inner layer 1 (GND1) as the reference plane. The antenna itself is not an
openEMS model; its measured S-parameters (`sparam/`, from KYOCERA AVX) are cascaded with the simulated line
in `analyze.py`.

Variants (each folder is a gerber2ems project: `fab/` + `simulation.json`):

| folder    | board file               | what it is                                                     |
|-----------|--------------------------|----------------------------------------------------------------|
| `asis`    | `feed_asis.kicad_pcb`    | feed as drawn (0.158 mm, J11 pads present)                     |
| `noufl`   | `feed_noufl.kicad_pcb`   | same without the J11 pads                                      |
| `w135`    | `feed_w135.kicad_pcb`    | 0.135 mm trace, J11 pads present                               |
| `cut`     | `feed_cut.kicad_pcb`     | as drawn + GND1 void under J11 pad 1, pad + 0.25 mm (proposed) |
| `cut2`    | `feed_cut2.kicad_pcb`    | as drawn + GND1 void, pad + 0.5 mm                             |
| `cutboth` | `feed_cutboth.kicad_pcb` | as drawn + void in GND1 and GND2, pad + 0.25 mm                |

The `feed_*.kicad_pcb` files are derived from `../../airbox.kicad_pcb` (everything outside the feed removed,
feed cut at the two simulation ports, solid GND added on all copper layers, aux origin at the bottom-left
corner of the 26.2 x 30 mm window at board x 189..215.2, y 107..137). Ports: SP1 on the stub at the nRF9151
ANT pad, SP2 at the matching-network input (158 um wide, 600 um long, F.Cu over In1.Cu, 50 ohm), defined
in `fab/feed-top-pos.csv`. Gerbers must be exported with X2 net attributes (default) so the mesh is refined
along net `/RF_ANT`.

## Results (line alone, port 1 = modem side, port 2 terminated in 50 ohm)

| variant   | Zin 0.9 GHz | Zin 1.8 GHz | Zin 3.5 GHz | worst line S11 in LTE bands | worst in n78 |
|-----------|-------------|-------------|-------------|-----------------------------|--------------|
| `noufl`   | 49 ohm      | 49 ohm      | 48 ohm      | -38 dB                      | -35 dB       |
| `asis`    | 47 -6j      | 42 -10j     | 30 -9j      | -14 dB (B7)                 | -11 dB       |
| `w135`    | 51 -4j      | 40 -14j     | 32 -10j     | -13 dB (B7)                 | -11 dB       |
| `cut`     | 50          | 52 +4j      | 56 +5j      | -25 dB (B7)                 | -22 dB       |
| `cut2`    | 50          | 53 +7j      | 62 +10j     | -20 dB (B7)                 | -17 dB       |
| `cutboth` | 50          | 52 +4j      | 56 +5j      | -25 dB (B7)                 | -22 dB       |

Conclusions: the 0.158 mm trace is a 49 ohm line on this stack and needs no change. The J11 U.FL signal
pad on 76 um of prepreg is a 0.48 pF shunt capacitor (parallel-plate value, confirmed by `asis` vs `noufl`)
and is the only significant discontinuity; a GND1 void of pad + 0.25 mm under it removes it. See the note
on the Antenna schematic sheet.

## Running

Container built from https://github.com/antmicro/gerber2ems (`docker build -t gerber2ems .` in its
checkout; it compiles openEMS at the commit antmicro tested):

```
cd asis   # or any other variant folder
docker run --rm --user $(id -u):$(id -g) -e MPLCONFIGDIR=/tmp/mpl -e HOME=/tmp \
    -v "$PWD:/work" -w /work gerber2ems -a
```

Flags: `-g` geometry only, `-s` simulate, `-p` post-process, `-a` all. Results land in `ems/results/`
(`Port_0_data.csv`, S-parameter, Smith and impedance plots). Then, from this folder,
`python analyze.py sparam` (needs numpy and scikit-rf) prints the line parameters and the return loss seen
by the modem for every variant that has results.

## Feed + matching network as a multi-port model (`build_pi.py`, `sim_pi.py`, `analyze_pi.py`)

gerber2ems has no lumped elements, so the matching network is handled the standard way: the copper of the
feed, the 0402 lands of L5/C5/C6/C7/L6/C8 and the ground around them is simulated as an N-port, and the
part values plus the measured antenna are connected to those ports in scikit-rf (`analyze_pi.py`). Matching
values can then be swept in seconds without re-running the field solver.

Variants: `pi` (antenna port between ANT1 pad 1 and pad 2; diverges in every port shape tried, not usable),
`pi_void_neck` (`pi_neck` + the proposed GND1 void under J11 pad 1) and `pi_neck` (feed cut at the end
of the ground bump, board x = 218.3, antenna port on the 0.2 mm neck over GND1; the 1.0 mm stub and pad 1
are then outside the model, i.e. counted as part of the antenna). The DNI lands C6/C7 stay as floating
copper. Ports (see `<variant>/nport/ports.json`): 0 modem end, 1 antenna, 2-3 C5 lands (ANT_MID / ANT_FEED
side), 4-5 L6 lands (RF / ANT_MID side), 6 L5 land, 7 C8 land (feed side; the other land of each shunt part
is ground copper).

```
python3 build_pi.py [--neck] [--void]          # KiCad python: derived board, fab files, <variant>_pads.json
<run_g2e> <variant> -g                          # gerber2ems geometry + mesh (ports stripped again by sim_pi.py)
docker run --rm --user $(id -u):$(id -g) -e HOME=/tmp -v "$PWD:/work" -w /work \
    --entrypoint /home/docker/opt/openEMS/venv/bin/python gerber2ems sim_pi.py pi_neck --line=1 --tsf=0.9 --nrts=70000
python analyze_pi.py pi_neck [L5=24n C5=2.4p L6=1n C8=0.3p]
```

### Results (feed + matching lands + measured antenna, worst |S11| at the modem per band, dB)

Gap-port models (`sim_pi.py <variant> --line=1 --gap=1 --out=nport_gap`, then `analyze_pi.py <variant> out=nport_gap`),
ideal L/C parts, measured bare antenna (`sparam/Ant_P822601`) on the antenna port:

| model / values (L5 C5 L6 C8)                 | B28/12 | B5    | B8    | B3    | B2    | B1    | B7    | n78   |
|----------------------------------------------|--------|-------|-------|-------|-------|-------|-------|-------|
| bare antenna, no feed                        | -2.8   | -9.9  | -10.4 | -12.4 | -12.8 | -15.1 | -14.0 | -8.5  |
| as drawn, datasheet p.12 24n 2.4p 1n 0.3p    | -16.7  | -7.3  | -7.1  | -3.4  | -5.7  | -4.1  | -3.5  | -8.6  |
| as drawn, 22n 2.4p 3.3n -                    | -11.3  | -11.2 | -10.6 | -4.6  | -9.0  | -7.5  | -9.6  | -3.0  |
| as drawn, 0R jumpers only                    | -2.4   | -7.7  | -11.6 | -7.8  | -7.3  | -6.0  | -3.6  | -8.3  |
| J11 GND1 void, datasheet p.12 values         | -13.5  | -6.6  | -5.9  | -4.5  | -8.1  | -6.2  | -4.5  | -8.2  |
| J11 GND1 void, 0R jumpers only               | -2.7   | -7.6  | -11.8 | -11.2 | -9.1  | -8.3  | -5.3  | -4.6  |
| J11 GND1 void, 22n 3.6p 2.2n 0.5p (best)     | -9.1   | -11.2 | -11.8 | -9.1  | -14.7 | -12.1 | -8.8  | -9.8  |
| J11 GND1 void, 20n 3.3p 2.4n 0.4p            | -9.0   | -12.2 | -11.1 | -8.8  | -17.8 | -13.4 | -8.8  | -10.1 |

("as drawn" = `pi_neck`, "J11 GND1 void" = `pi_void_neck`.) The datasheet values are far off on this board
(B3/B7 around -4 dB); the void under the J11 pad is worth 2-4 dB in the high bands with the network fitted;
with the void a single value set reaches about -9 dB in every LTE band. The land-to-GND1 port model
(`nport/`, no `--gap`) gives the same cascade within about 1 dB. Caveats: ideal parts (real 0402 inductors near
SRF will shift this; repeat with vendor S-parameter models before ordering), the antenna file is the bare
antenna on AVX's board (not this ground plane), and the `pi_neck` antenna port puts the 1.0 mm stub and pad 1
on the antenna side. Treat the values as the starting point for VNA tuning at J11.

Port model validation (`val` variant = the `asis` feed cut at board x 213 where gerber2ems' MSL port 2 sits, run
with `sim_pi.py val --line=1`, two sheet ports): the single-mesh-line sheet ports reproduce gerber2ems' MSL-port
result on the same mesh to within 0.8 dB in |S11| and about 1.5 ohm in Zin from 0.7 to 4 GHz, e.g.

| GHz | MSL ports: S11 dB, Zin | sheet ports: S11 dB, Zin |
|-----|------------------------|--------------------------|
| 0.9 | -22.7, 46.9 -6.4j      | -23.2, 47.2 -6.1j        |
| 1.8 | -16.9, 41.7 -10.2j     | -17.1, 42.7 -10.8j       |
| 2.5 | -14.0, 36.5 -11.0j     | -13.4, 35.5 -11.5j       |
| 3.5 | -11.3, 29.8 -8.7j      | -11.8, 30.3 -6.5j        |

Lessons from getting this to run (all on the same gerber2ems mesh, which is stable with its own MSL port):
- openEMS lumped ports whose box has a 2-D cross-section (full pad area x substrate height) diverge here,
  slowly and from the first time step, whatever the priority or timestep factor. Lumped ports collapsed to a
  single mesh line along the trace (`--line=1`, the same shape as the MSL feed resistor) are stable.
- Any antenna port between ANT1 pad 1 and pad 2 diverged (2-D box, line, vertical sheet); hence the
  `pi_neck` variant with a conventional trace-to-GND1 port. Always pass `--line=1`: without it the modem port
  is a 2-D box and the run diverges whatever the other ports are.
- `--gap=1` puts one port across each part's own land gap on F.Cu (series parts) or from the land to its GND
  land (shunt parts); `analyze_pi.py` terminates it with the part impedance. Line (default) and vertical-sheet
  (`--gapz=1`) gap ports give the same result within 0.3 dB.
- Field dumps for ParaView: `--dump=1 --out=<folder>` (open with `DUMP_DIR=<variant>/<folder>/run_<k> paraview
  --script paraview_nport.py`). They show the characterisation run (all other ports 50 ohm, no parts), not the
  working circuit.
- Each excitation is an independent run: split ports across machines with `sim_pi.py <variant> <idx...>` and
  combine the `S.npz` files with `merge_nport.py <variant>/<out> S_other.npz`.
- gerber2ems leaves its own 50 ohm feed resistor (`LumpedElement`) in `geometry.xml`; `sim_pi.py` strips it
  together with the MSL port metal, probes and excitation.
- With floating copper in the model the field energy stops decaying at about -26 dB (static charge), so the
  energy stop criterion is never met; cap the run (`--nrts`), the S-parameters are not affected.

## 3-D fields with the real parts (`--parts`, `--dump3d`, `paraview_3d.py`)

`sim_pi.py <variant> 0 --line=1 --gap=1 --parts=C5:3.6p,L6:2.2n,L5:22n,C8:0.5p --dump3d=4,30.5,1,23.5 --out=nport_3d`
puts the parts into the FDTD model as lumped L/C elements (antenna end = 50 ohm port; the measured antenna can only
be used in the cascade) and records the E-field in a volume from inside the core to 1 mm above the board: a time
series (`e3d_*.vtr`, about 13 MB per frame, 2.5 GB for the run), steady-state |E| and a phase sweep at the `--fdump`
frequencies (`e3d_fd_f=*`), and the meshed metal (`PEC_dump.vtp`). `python3 export_copper3d.py <variant>` (KiCad
python) exports the board's copper and 0402 parts as PLY meshes aligned to the field grid. View with

```
DUMP_DIR=pi_void_neck/nport_3d/run_0 paraview --script paraview_3d.py     # ZSCALE=5 CLIP=0.08 FRAME=0.3 SLICE_Y=12 LOG=1
```

It shows the KiCad copper (RF nets gold, GND translucent), iso-surfaces of |E| (a tube around the trace) and a
vertical slice across the feed on a log colour scale; the |E| volume, the meshed metal and the steady-state /
phase-sweep sources are in the Pipeline Browser (eye icon). Heights are exaggerated ZSCALE times.

## Return current on the ground planes (`--hdump`, `paraview_return.py`)

`sim_pi.py pi_void_neck 0 --line=1 --gap=1 --parts=C5:3.6p,L6:2.2n,L5:22n,C8:0.5p --hdump=0,36,0,28 --fdump=0.75,1.8,2.6 --out=nport_h`
records the H-field (frequency domain) on the first mesh plane above GND1 (57 um, in the prepreg) and above F.Cu
(30 um, air). The surface current is K = z x H there. `DUMP_DIR=pi_void_neck/nport_h/run_0 FREQ=0.75 paraview --script
paraview_return.py` shows two linked views, In1.Cu (left) and F.Cu (right), each with that layer's 3-D copper and
arrows of K just above it coloured by |K| (log scale, top = 99.9th percentile); play the animation to sweep the phase
over one cycle. The flat |K| planes are in the Pipeline Browser (hidden).

## Viewing fields in ParaView

Add `--export-field cu-outer substrate` (planes at the top copper and in the middle of each dielectric) to
the run; openEMS then writes one `.vtr` file per sampled time step into `ems/simulation/<port>/`. Open them
on the host with ParaView:

```
cd asis
GERBER2EMS_PORT=0 paraview --script ../paraview_preview.py
```

(`paraview_preview.py` is antmicro's helper: it loads every dump plane as a time series, colours by
|E|, and starts the animation.) Or open the `e_field_*.vtr` groups manually with File > Open.
