#!/usr/bin/env python3
"""Recompute the EPA-style AQI for historical airbox_readings rows.

Backfills the `aqi` / `aqi_pollutant` columns for rows that predate (or were
skipped by) the middleware's ingest-time enrichment — most importantly the
real SEN66 stations whose readings were all NULL under the old 3-sub-index
gate. For each target device it pulls the device's FULL reading history
(time-ordered), recomputes every row with EXACTLY the middleware's semantics
— per-device trailing windows (3h for pm25/pm10, 1h for the NO2 proxy and
CO2, each INCLUDING the current row), the same truncation, linear
interpolation, breakpoint tables and 2-sub-index / at-least-one-PM
eligibility gate — and UPDATEs only the rows whose recomputed value differs
from what is stored.

Stdlib only, like the sibling infrastructure scripts. Run on the docker host:
    python3 infrastructure/scripts/backfill-aqi.py --device airbox-005
    python3 infrastructure/scripts/backfill-aqi.py --all-null --dry-run

============================  !! DRIFT WARNING !!  ============================
The breakpoint tables (INCLUDING the custom, non-EPA CO2 table below), the
concentration precisions, the trailing-window sizes, the truncation /
interpolation / clamping rules, the tie-breaking (pollutant declaration
order) and the MIN_SUBINDICES eligibility gate are ALL a second copy of the
Java implementation in
    middleware/.../aqi/AqiCalculator.java
    middleware/.../aqi/Pollutant.java
    middleware/.../service/SensorReadingService.java
    middleware/.../repository/SensorReadingRepository.java
They MUST be changed together — any divergence makes backfilled rows drift
from freshly-ingested ones. See the matching DRIFT WARNING in AqiCalculator's
javadoc. Cross-check with a couple of freshly middleware-enriched rows
(--dry-run) after ANY change on either side.
==============================================================================
"""

import argparse
import math
import subprocess
import sys
from decimal import Decimal, ROUND_FLOOR

# --------------------------------------------------------------------------
# AQI model — a faithful port of AqiCalculator / Pollutant (see DRIFT WARNING)
# --------------------------------------------------------------------------

# Minimum number of computable sub-indices for an AQI to be reported. Lowered
# from 3 to 2 so real SEN66 devices (pm25 + pm10, no raw NOx) qualify.
MIN_SUBINDICES = 2

SECONDS_PM = 3 * 3600
SECONDS_1H = 3600


class Pollutant:
    """One pollutant: its stored key, whether it is particulate (PM2.5/PM10),
    the number of decimals its concentration is TRUNCATED to, and its
    [cLo, cHi] -> [iLo, iHi] breakpoint bands."""

    __slots__ = ("key", "particulate", "decimals", "bands")

    def __init__(self, key, particulate, decimals, bands):
        self.key = key
        self.particulate = particulate
        self.decimals = decimals
        self.bands = bands  # list of (cLo, cHi, iLo, iHi)


# Declaration order == middleware Pollutant enum order for the fed pollutants
# (PM2.5 < PM10 < NO2 < CO2); this drives the exact-tie dominant choice.
# NO2 is fed from the raw `nox` field as a ppb proxy (1h window). CO2 uses the
# CUSTOM, NON-EPA indoor-air-quality proxy table (the EPA publishes no CO2 AQI):
#   0-600 -> 0-50 | 601-1000 -> 51-100 | 1001-1500 -> 101-150 |
#   1501-2000 -> 151-200 | 2001-5000 -> 201-300 | 5001-40000 -> 301-500;
#   above 40000 clamps to 500.
POLLUTANTS = [
    Pollutant("pm25", True, 1, [
        (0.0, 9.0, 0, 50), (9.1, 35.4, 51, 100), (35.5, 55.4, 101, 150),
        (55.5, 125.4, 151, 200), (125.5, 225.4, 201, 300), (225.5, 325.4, 301, 500)]),
    Pollutant("pm10", True, 0, [
        (0, 54, 0, 50), (55, 154, 51, 100), (155, 254, 101, 150),
        (255, 354, 151, 200), (355, 424, 201, 300), (425, 604, 301, 500)]),
    Pollutant("no2", False, 0, [
        (0, 53, 0, 50), (54, 100, 51, 100), (101, 360, 101, 150),
        (361, 649, 151, 200), (650, 1249, 201, 300), (1250, 2049, 301, 500)]),
    Pollutant("co2", False, 0, [
        (0, 600, 0, 50), (601, 1000, 51, 100), (1001, 1500, 101, 150),
        (1501, 2000, 151, 200), (2001, 5000, 201, 300), (5001, 40000, 301, 500)]),
]
BY_KEY = {p.key: p for p in POLLUTANTS}


def truncate(value, decimals):
    """FLOOR to `decimals` places via Decimal, mirroring Java
    BigDecimal.valueOf(value).setScale(decimals, RoundingMode.FLOOR)."""
    quant = Decimal(1).scaleb(-decimals)  # 10 ** -decimals
    return float(Decimal(repr(value)).quantize(quant, rounding=ROUND_FLOOR))


def sub_index(pollutant, concentration):
    """Truncate then interpolate within (or clamp to) the breakpoint table."""
    c = truncate(concentration, pollutant.decimals)
    bands = pollutant.bands
    if c <= bands[0][0]:            # at/below the scale -> iLo of first band
        return float(bands[0][2])
    for c_lo, c_hi, i_lo, i_hi in bands:
        if c <= c_hi:
            return (i_hi - i_lo) / (c_hi - c_lo) * (c - c_lo) + i_lo
    return float(bands[-1][3])      # above the top band -> clamp to iHi (500)


def compute_aqi(concentrations):
    """concentrations: dict pollutant-key -> trailing-mean concentration.
    Returns (aqi:int, dominant_key:str) or None when not eligible."""
    computable = 0
    has_particulate = False
    max_sub = float("-inf")
    dominant = None
    for p in POLLUTANTS:                       # declaration order for tie-break
        conc = concentrations.get(p.key)
        if conc is None:
            continue
        computable += 1
        has_particulate = has_particulate or p.particulate
        si = sub_index(p, conc)
        if si > max_sub:                       # strict > : earlier pollutant wins ties
            max_sub = si
            dominant = p
    if computable < MIN_SUBINDICES or not has_particulate:
        return None
    aqi = math.floor(max_sub + 0.5)            # Java Math.round: floor(x + 0.5)
    return aqi, dominant.key


# --------------------------------------------------------------------------
# Trailing means over a device's own time-ordered rows
# --------------------------------------------------------------------------

class Reading:
    __slots__ = ("id", "t", "pm25", "pm10", "nox", "co2", "aqi", "pollutant")

    def __init__(self, rid, t, pm25, pm10, nox, co2, aqi, pollutant):
        self.id = rid
        self.t = t              # epoch seconds (float)
        self.pm25 = pm25
        self.pm10 = pm10
        self.nox = nox
        self.co2 = co2
        self.aqi = aqi          # stored aqi (int or None)
        self.pollutant = pollutant


class _Window:
    """Running non-null sum/count of one field over a sliding trailing window."""

    __slots__ = ("field", "span", "lo", "s", "c")

    def __init__(self, field, span):
        self.field = field
        self.span = span
        self.lo = 0
        self.s = 0.0
        self.c = 0

    def add(self, reading):
        v = getattr(reading, self.field)
        if v is not None:
            self.s += v
            self.c += 1

    def evict(self, rows, i, now):
        # Drop historical rows older than `now - span`; keep [lo, i).
        while self.lo < i and rows[self.lo].t < now - self.span:
            v = getattr(rows[self.lo], self.field)
            if v is not None:
                self.s -= v
                self.c -= 1
            self.lo += 1

    def mean(self, current):
        """Trailing mean including the current value, or None if current is None."""
        if current is None:
            return None
        return (self.s + current) / (self.c + 1)


def recompute(rows):
    """Yield (reading, result) for every row, result = (aqi, key) or None.
    rows must be sorted by (time, id) ascending — matching insert order, so a
    row's historical window is exactly the rows the middleware would have seen."""
    w_pm25 = _Window("pm25", SECONDS_PM)
    w_pm10 = _Window("pm10", SECONDS_PM)
    w_nox = _Window("nox", SECONDS_1H)
    w_co2 = _Window("co2", SECONDS_1H)
    windows = (w_pm25, w_pm10, w_nox, w_co2)

    for i, r in enumerate(rows):
        if i > 0:
            for w in windows:            # the previous row is now historical
                w.add(rows[i - 1])
        for w in windows:
            w.evict(rows, i, r.t)

        conc = {}
        m = w_pm25.mean(r.pm25)
        if m is not None:
            conc["pm25"] = m
        m = w_pm10.mean(r.pm10)
        if m is not None:
            conc["pm10"] = m
        m = w_nox.mean(r.nox)            # raw nox -> NO2 proxy
        if m is not None:
            conc["no2"] = m
        m = w_co2.mean(r.co2)
        if m is not None:
            conc["co2"] = m

        yield r, compute_aqi(conc)


# --------------------------------------------------------------------------
# DB access via `docker exec ... psql` (same pattern as register-airbox.py)
# --------------------------------------------------------------------------

def psql(container, sql, variables=None, capture=True):
    """Run SQL via docker exec psql. Values travel as `-v name=value` and are
    referenced as :'name' in the statement (psql does NOT interpolate those in
    -c, so the SQL is piped on stdin). COPY data may also ride on stdin."""
    cmd = ["docker", "exec", "-i", container, "psql", "-U", "airbox", "-d", "airbox",
           "-X", "-q", "-t", "-A", "-F", "\t", "-v", "ON_ERROR_STOP=1"]
    for name, value in (variables or {}).items():
        cmd += ["-v", f"{name}={value}"]
    result = subprocess.run(cmd, input=sql, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"error: psql failed: {result.stderr.strip()}")
    return result.stdout if capture else ""


def _num(s):
    return float(s) if s != "" else None


def _int(s):
    return int(s) if s != "" else None


def load_devices(container, devices, all_null):
    if all_null:
        out = psql(container,
                   "SELECT DISTINCT device FROM airbox_readings "
                   "WHERE aqi IS NULL ORDER BY device;")
        return [line for line in out.splitlines() if line]
    return devices


def load_history(container, device):
    """Full time-ordered history for one device."""
    out = psql(container,
               "SELECT id, EXTRACT(EPOCH FROM time), pm25, pm10, nox, co2, aqi, aqi_pollutant "
               "FROM airbox_readings WHERE device = :'dev' ORDER BY time, id;",
               {"dev": device})
    rows = []
    for line in out.splitlines():
        if not line:
            continue
        f = line.split("\t")
        rows.append(Reading(int(f[0]), float(f[1]), _num(f[2]), _num(f[3]),
                            _num(f[4]), _num(f[5]), _int(f[6]),
                            f[7] if f[7] != "" else None))
    return rows


def apply_updates(container, updates):
    """Batch-apply (id, aqi, aqi_pollutant) via a temp table + COPY from stdin."""
    lines = ["BEGIN;",
             "CREATE TEMP TABLE _aqi_bf (id bigint PRIMARY KEY, aqi integer, aqi_pollutant text);",
             "COPY _aqi_bf (id, aqi, aqi_pollutant) FROM STDIN;"]
    for rid, aqi, pollutant in updates:
        aqi_s = r"\N" if aqi is None else str(aqi)
        pol_s = r"\N" if pollutant is None else pollutant
        lines.append(f"{rid}\t{aqi_s}\t{pol_s}")
    lines += [r"\.",
              "UPDATE airbox_readings r SET aqi = b.aqi, aqi_pollutant = b.aqi_pollutant "
              "FROM _aqi_bf b WHERE r.id = b.id;",
              "COMMIT;"]
    psql(container, "\n".join(lines) + "\n", capture=False)


# --------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------

def process_device(container, device, all_null, dry_run):
    rows = load_history(container, device)
    if not rows:
        print(f"  {device}: no rows")
        return 0

    updates = []            # (id, aqi, pollutant) actually changing
    computed_aqis = []      # non-null recomputed aqis among candidates
    dominant_counts = {}
    examined = 0

    for r, result in recompute(rows):
        # In --all-null mode only null rows are candidates; in --device mode all are.
        if all_null and r.aqi is not None:
            continue
        examined += 1
        new_aqi = result[0] if result else None
        new_pol = result[1] if result else None
        if new_aqi is not None:
            computed_aqis.append(new_aqi)
            dominant_counts[new_pol] = dominant_counts.get(new_pol, 0) + 1
        if (new_aqi, new_pol) != (r.aqi, r.pollutant):
            updates.append((r.id, new_aqi, new_pol))

    if updates and not dry_run:
        apply_updates(container, updates)

    verb = "would update" if dry_run else "updated"
    print(f"  {device}: examined {examined}, {verb} {len(updates)}", end="")
    if computed_aqis:
        dist = ", ".join(f"{k}:{v}" for k, v in sorted(dominant_counts.items()))
        print(f"; aqi min {min(computed_aqis)} max {max(computed_aqis)}; dominant {dist}")
    else:
        print("; no eligible rows")
    return len(updates)


def main():
    ap = argparse.ArgumentParser(
        description="Recompute historical AQI values with the middleware's exact semantics.")
    ap.add_argument("--device", action="append", default=[], metavar="ID",
                    help="device id to backfill (repeatable)")
    ap.add_argument("--all-null", action="store_true",
                    help="process every row whose aqi IS NULL, across all devices")
    ap.add_argument("--db-container", default="airbox-timescaledb",
                    help="TimescaleDB container name (default: airbox-timescaledb)")
    ap.add_argument("--dry-run", action="store_true",
                    help="compute and report, but do not write any UPDATE")
    args = ap.parse_args()

    if not args.device and not args.all_null:
        ap.error("give --device <id> (repeatable) and/or --all-null")

    devices = load_devices(args.db_container, args.device, args.all_null)
    if not devices:
        print("no matching devices")
        return

    mode = "DRY-RUN — no writes" if args.dry_run else "LIVE"
    print(f"backfill-aqi [{mode}] over {len(devices)} device(s):")
    total = 0
    for device in devices:
        total += process_device(args.db_container, device, args.all_null, args.dry_run)
    verb = "would update" if args.dry_run else "updated"
    print(f"done: {verb} {total} row(s) total.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
