#!/bin/bash
# usage: tools/chain_local.sh <logname-to-wait-for> <command...>
#        tools/chain_local.sh <logname-to-wait-for> <tools/sim.sh args...>     (if the first word is not an executable)
# Waits until logs/<logname>.log shows '=== exit', then execs the command (detach this with setsid nohup), e.g.
#   tools/chain_local.sh sim_board20n_field tools/worker.sh board20n nport_gap local 0-7 8 $COMMON
D="$(cd "$(dirname "$0")/.." && pwd)"; W=$1; shift
until grep -q '=== exit' "$D/logs/$W.log" 2>/dev/null; do sleep 30; done
cd "$D"
if [ -x "$1" ] || [ -x "$D/$1" ]; then exec "$@"; else exec "$D/tools/sim.sh" "$@"; fi
