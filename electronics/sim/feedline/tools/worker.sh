#!/bin/bash
# usage: [PORTS="0 1 2 3 4 5 6 7"] [DRY=1] tools/worker.sh <variant> <outbase> <host|local> <cpus> <threads> [sim_pi args...]
#   e.g. tools/worker.sh board20n nport_gap atmega 0-11 6 $COMMON
#        tools/worker.sh board20n nport_gap local  0-7  8 $COMMON
# Dynamic work queue for the N-port runs: one worker per machine (start each DETACHED with setsid nohup). A worker
# claims the next unclaimed excitation (atomic mkdir in logs/queue/<variant>_<outbase>/<port>), runs that ONE port
# into <variant>/<outbase>_p<port>/ (local: S.npz; remote: S_<host>.npz), writes 'done' into the claim, then claims
# the next. Faster machines therefore take more ports and a slow machine only ever holds one. A failed run releases its
# claim so another worker retries it. Merge afterwards:
#   mkdir -p <variant>/<outbase> && tools/py.sh merge_nport.py <variant>/<outbase> <variant>/<outbase>_p*/S*.npz
# Status: ls logs/queue/<variant>_<outbase>/*  (each claim holds host, start time, and 'done' when finished).
D="$(cd "$(dirname "$0")/.." && pwd)"; V=$1; OB=$2; H=$3; C=$4; T=$5; shift 5
Q="$D/logs/queue/${V}_$OB"; mkdir -p "$Q"
for k in ${PORTS:-0 1 2 3 4 5 6 7}; do
  mkdir "$Q/$k" 2>/dev/null || continue                      # someone else has (or had) this port
  echo "$H $(date '+%F %T')" > "$Q/$k/claim"
  if [ -n "$DRY" ]; then echo "[dry] $H would run port $k into $V/${OB}_p$k"; echo done >> "$Q/$k/claim"; continue; fi
  if [ "$H" = local ]; then
    "$D/tools/sim.sh" -c "$C" -t "${OB}_p$k" "$V" "$k" "$@" --threads="$T" --out="${OB}_p$k"
  else
    RHOST="$H" RCPUS="$C" "$D/tools/remote.sh" "$V" "${OB}_p$k" "$k" "$@" --threads="$T"
  fi
  if [ $? -eq 0 ]; then echo "done $(date '+%F %T')" >> "$Q/$k/claim"; else rm -rf "$Q/$k"; fi   # failure: release
done
