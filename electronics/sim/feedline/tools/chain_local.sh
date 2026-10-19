#!/bin/bash
# usage: tools/chain_local.sh <logname-to-wait-for> <tools/sim.sh args...>
# Waits until the named run's log shows '=== exit', then starts tools/sim.sh with the given arguments (detach this with setsid nohup).
D="$(cd "$(dirname "$0")/.." && pwd)"; W=$1; shift
until grep -q '=== exit' "$D/logs/$W.log" 2>/dev/null; do sleep 30; done
exec "$D/tools/sim.sh" "$@"
