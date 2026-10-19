#!/bin/bash
# usage: tools/watch.sh <log name without .log, e.g. sim_pi_void_neck>
# Streams the lines worth acting on: per-port completion, errors, exit, and energy blow-ups (> 1e-13 or non-finite).
# Meant for a Monitor / tail session; a run whose energy keeps rising past ~1e-13 is diverging: stop it.
D="$(cd "$(dirname "$0")/.." && pwd)"
tail -n 0 -F "$D/logs/$1.log" | grep --line-buffered -E 'done;|wrote|Traceback|Error|RuntimeError|=== exit|Energy: ~[0-9.]+e-(0[0-9]|1[0-3]) |Energy: ~.*(e\+|inf|nan)'
