#!/bin/bash
# usage: [RHOST=atmega RCPUS=0-11] tools/remote.sh <variant> <out> [sim_pi.py args...]
# Copies sim_pi.py, <variant>_pads.json and <variant>/ems/geometry to RHOST:~/feedline-sim, runs sim_pi.py there in
# Docker (image 'gerber2ems' must exist on RHOST), copies <variant>/<out>/S.npz back as S_<RHOST>.npz (merge with
# merge_nport.py). With --dump/--hdump/--dump3d the field files stay on RHOST (that is where ParaView shows them).
# Log: logs/sim_<variant>_<RHOST>.log
D="$(cd "$(dirname "$0")/.." && pwd)"; H=${RHOST:-atmega}; C=${RCPUS:-0-11}; R=feedline-sim
V=${1%/}; OUT=$2; shift 2
mkdir -p "$D/logs"; LOG="$D/logs/sim_${V}_$H.log"
echo "=== $(date) $H: sim_pi.py $V $* --out=$OUT (cpuset $C)" >> "$LOG"
ssh -o BatchMode=yes "$H" "mkdir -p $R/$V/ems" >> "$LOG" 2>&1
rsync -a "$D/sim_pi.py" "$D/${V}_pads.json" "$H:$R/" >> "$LOG" 2>&1
rsync -a --delete "$D/$V/ems/geometry" "$H:$R/$V/ems/" >> "$LOG" 2>&1
ssh -o BatchMode=yes "$H" "cd $R && rm -rf $V/$OUT && docker run --rm --user \$(id -u):\$(id -g) --cpuset-cpus=$C -e HOME=/tmp -v \$PWD:/work -w /work --entrypoint /home/docker/opt/openEMS/venv/bin/python gerber2ems sim_pi.py $V $* --out=$OUT" >> "$LOG" 2>&1
rc=$?
mkdir -p "$D/$V/$OUT"
rsync -a "$H:$R/$V/$OUT/S.npz" "$D/$V/$OUT/S_$H.npz" >> "$LOG" 2>&1
echo "=== exit $rc $(date)" >> "$LOG"; exit $rc
