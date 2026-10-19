#!/bin/bash
# usage: [FREQS=0.75] tools/py.sh <script.py> [args...]   -- runs a feedline python script with the container's python
# (numpy + vtk + openEMS), e.g. sheet_current.py, via_current.py. Paths are relative to the feedline folder.
D="$(cd "$(dirname "$0")/.." && pwd)"
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e FREQS -v "$D:/work" -w /work \
    --entrypoint /home/docker/opt/openEMS/venv/bin/python gerber2ems "$@"
