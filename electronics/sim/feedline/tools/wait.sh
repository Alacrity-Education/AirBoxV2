#!/bin/bash
# usage: tools/wait.sh <log name> [timestep]   -- blocks until the run passes <timestep> (default: until it exits), then
# prints the last progress lines. Use as a background job to get one notification.
D="$(cd "$(dirname "$0")/.." && pwd)"; LOG="$D/logs/$1.log"; want=${2:-999999999}
while true; do
  last=$(grep -o 'Timestep: *[0-9]*' "$LOG" | tail -n 1 | grep -o '[0-9]*$')
  [ -n "$last" ] && [ "$last" -ge "$want" ] && break
  tail -n 3 "$LOG" | grep -q -E 'Traceback|=== exit' && break
  sleep 5
done
grep -E 'excite port|Energy|done;|=== exit' "$LOG" | tail -n 6 | cut -c1-120
