#!/bin/bash
# usage: tools/rejoin.sh <pid of running remote.sh/sim.sh> <port k> <worker.sh args...>
# Takes over from a worker whose worker.sh was killed while its port k was still running: waits for that run to end,
# marks port k done in the queue (or releases it if its S file is missing), then execs worker.sh with new args
# (e.g. other CPUs/threads). Start detached with setsid nohup.
D="$(cd "$(dirname "$0")/.." && pwd)"; P=$1; K=$2; shift 2; V=$1; OB=$2; H=$3
while kill -0 "$P" 2>/dev/null; do sleep 30; done
Q="$D/logs/queue/${V}_$OB/$K"
if ls "$D/$V/${OB}_p$K"/S*.npz >/dev/null 2>&1; then echo "done $(date '+%F %T') (rejoin)" >> "$Q/claim"; else rm -rf "$Q"; fi
exec "$D/tools/worker.sh" "$@"
