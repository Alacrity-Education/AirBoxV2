#!/bin/bash
# usage: tools/g2e.sh <variant> <gerber2ems flags...>     e.g.  tools/g2e.sh pi_void_neck -g   (geometry + mesh only)
# Runs gerber2ems (Docker image 'gerber2ems') in <variant>/; log: logs/g2e_<variant>.log
D="$(cd "$(dirname "$0")/.." && pwd)"; V=${1%/}; shift
mkdir -p "$D/logs"; LOG="$D/logs/g2e_$V.log"
echo "=== $(date) gerber2ems $* in $V" >> "$LOG"
docker run --rm --user "$(id -u):$(id -g)" -e MPLCONFIGDIR=/tmp/mpl -e HOME=/tmp -v "$D/$V:/work" -w /work gerber2ems "$@" >> "$LOG" 2>&1
rc=$?; echo "=== exit $rc $(date)" >> "$LOG"; exit $rc
