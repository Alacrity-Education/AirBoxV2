#!/bin/bash
# usage: [RHOST=atmega] tools/paraview_remote.sh <script.py> VAR=value ...
#   e.g. tools/paraview_remote.sh paraview_return.py DUMP_DIR=pi_void_neck_fine/nport_h3/run_0 FREQ=0.75
# Opens ParaView with <script.py> on RHOST's desktop (KDE Plasma, Wayland + Xwayland), in ~/feedline-sim. Copies the
# script first. Display variables are read from the running plasmashell, so this works whatever the X auth file is.
# Closes any ParaView already open there.
D="$(cd "$(dirname "$0")/.." && pwd)"; H=${RHOST:-atmega}; S=$1; shift
rsync -a "$D/$S" "$H:feedline-sim/" || exit 1
ssh -o BatchMode=yes "$H" bash -s -- "$S" "$@" <<'REMOTE'
S=$1; shift
pkill -x paraview; sleep 2
PID=$(pgrep -u "$(id -u)" -x plasmashell | head -1)
[ -z "$PID" ] && { echo "no plasmashell session on $(hostname)"; exit 1; }
eval "$(tr '\0' '\n' < /proc/$PID/environ | grep -E '^(DISPLAY|XAUTHORITY|WAYLAND_DISPLAY|XDG_RUNTIME_DIR|DBUS_SESSION_BUS_ADDRESS)=' | sed 's/^/export /')"
export QT_QPA_PLATFORM=xcb
for kv in "$@"; do export "$kv"; done
cd ~/feedline-sim && setsid nohup paraview --script "$S" > "${S%.py}.log" 2>&1 < /dev/null & disown
sleep 20; pgrep -a paraview || { echo "ParaView did not start:"; tail -n 20 ~/feedline-sim/"${S%.py}.log"; }
REMOTE
