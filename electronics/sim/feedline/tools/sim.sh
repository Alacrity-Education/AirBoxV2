#!/bin/bash
# usage: tools/sim.sh [-c CPUSET] [-t TAG] <variant> [sim_pi.py args...]
#   e.g. tools/sim.sh -c 0-7 pi_void_neck --line=1 --gap=1 --nrts=70000 --threads=8 --out=nport_gap
# Runs sim_pi.py in the gerber2ems container, pinned to CPUSET (default 0-7 = the 8 P-cores of the Core Ultra 9 285K).
# Log: logs/sim_<variant>[_TAG].log
D="$(cd "$(dirname "$0")/.." && pwd)"; CPUS=0-7; TAG=
while getopts "c:t:" o; do case $o in c) CPUS=$OPTARG;; t) TAG=_$OPTARG;; esac; done; shift $((OPTIND-1))
V=${1%/}; shift
mkdir -p "$D/logs"; LOG="$D/logs/sim_$V$TAG.log"
echo "=== $(date) sim_pi.py $V $* (cpuset $CPUS)" >> "$LOG"
docker run --rm --user "$(id -u):$(id -g)" --cpuset-cpus="$CPUS" -e HOME=/tmp -v "$D:/work" -w /work \
    --entrypoint /home/docker/opt/openEMS/venv/bin/python gerber2ems sim_pi.py "$V" "$@" >> "$LOG" 2>&1
rc=$?; echo "=== exit $rc $(date)" >> "$LOG"; exit $rc
