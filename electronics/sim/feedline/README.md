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
