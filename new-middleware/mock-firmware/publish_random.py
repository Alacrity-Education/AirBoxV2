#!/usr/bin/env python3
"""Mock AirBox firmware: publishes random sensor readings to the ingest API."""

import json
import os
import random
import string
import sys
import time
import urllib.error
import urllib.request

ENDPOINT = os.environ.get("AIRBOX_ENDPOINT", "https://ingest.airbox.alacrity.ro/api/v2/submit")
INTERVAL_SECONDS = float(os.environ.get("AIRBOX_INTERVAL_SECONDS", "10"))
REQUEST_COUNT = int(os.environ.get("AIRBOX_REQUEST_COUNT", "0"))  # 0 = run forever
AUTH_STYLE = os.environ.get("AIRBOX_AUTH_STYLE", "random")  # authorization | apikey | x-apikey | random
INSECURE = os.environ.get("AIRBOX_INSECURE_TLS", "false").lower() == "true"

API_KEYS = [k.strip() for k in os.environ.get("AIRBOX_API_KEYS", "").split(",") if k.strip()]
GEOHASHES = [g.strip() for g in os.environ.get("AIRBOX_GEOHASHES", "").split(",") if g.strip()]

# Pair each API key with a fixed geohash by index so a device always reports from
# the same location. Keys without a matching geohash (unset/empty list, or fewer
# geohashes than keys) fall back to random_geohash() at send time.
KEY_GEOHASHES = {key: GEOHASHES[i] for i, key in enumerate(API_KEYS) if i < len(GEOHASHES)}

if GEOHASHES and len(GEOHASHES) != len(API_KEYS):
    print(
        f"[warn] AIRBOX_GEOHASHES count ({len(GEOHASHES)}) != AIRBOX_API_KEYS count "
        f"({len(API_KEYS)}): mapping by index, unmatched keys use random geohashes.",
        flush=True,
    )

# Simulated sensor profiles, mirroring real AirBox hardware combinations.
# "full": SEN5x/SEN66-class module reporting particulates, gas index values and climate.
# "sen66_no_raw_voc_nox": SEN66 without raw (non-index) VOC/NOX support.
# "scd30_only": SCD30 module, CO2 only besides the three mandatory fields.
SENSOR_PROFILES = ["full", "sen66_no_raw_voc_nox", "scd30_only"]

_GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"


def random_geohash(length=9):
    return "".join(random.choice(_GEOHASH_ALPHABET) for _ in range(length))


def geohash_for_key(api_key):
    """Fixed geohash paired with this key, or a random one if unpaired."""
    return KEY_GEOHASHES.get(api_key) or random_geohash()


def random_climate_fields():
    return {
        "pm1": round(random.uniform(0, 50), 2),
        "pm25": round(random.uniform(0, 75), 2),
        "pm4": round(random.uniform(0, 90), 2),
        "pm10": round(random.uniform(0, 120), 2),
        "temp": round(random.uniform(-10, 45), 2),
        "hum": round(random.uniform(0, 100), 2),
    }


def build_payload(profile, geohash):
    payload = {
        "geohash": geohash,
        "charge": round(random.uniform(0, 100), 2),
        "sun": random.choice([True, False]),
    }

    if profile == "full":
        payload.update(random_climate_fields())
        payload["voc_index"] = round(random.uniform(0, 500), 2)
        payload["nox_index"] = round(random.uniform(0, 500), 2)
        payload["voc"] = round(random.uniform(0, 1000), 3)
        payload["nox"] = round(random.uniform(0, 1000), 3)
        payload["co2"] = round(random.uniform(350, 2000), 1)
    elif profile == "sen66_no_raw_voc_nox":
        payload.update(random_climate_fields())
        payload["voc_index"] = round(random.uniform(0, 500), 2)
        payload["nox_index"] = round(random.uniform(0, 500), 2)
        # No raw voc/nox: SEN66 doesn't measure them, so the fields are omitted entirely.
    elif profile == "scd30_only":
        payload["co2"] = round(random.uniform(350, 2000), 1)
    else:
        raise ValueError(f"Unknown sensor profile: {profile}")

    return payload


def pick_auth_headers(api_key):
    style = AUTH_STYLE if AUTH_STYLE != "random" else random.choice(
        ["authorization", "apikey", "x-apikey"]
    )
    if style == "authorization":
        return {"Authorization": f"ApiKey {api_key}"}
    if style == "apikey":
        return {"ApiKey": api_key}
    if style == "x-apikey":
        return {"X-ApiKey": api_key}
    raise ValueError(f"Unknown AIRBOX_AUTH_STYLE: {AUTH_STYLE}")


def random_api_key():
    if API_KEYS:
        return random.choice(API_KEYS)
    # No configured keys: fabricate a plausible-looking one so the script
    # still runs standalone (the server will just reject it).
    return "".join(random.choices(string.ascii_letters + string.digits, k=32))


def send_reading():
    profile = random.choice(SENSOR_PROFILES)
    api_key = random_api_key()
    payload = build_payload(profile, geohash_for_key(api_key))

    headers = {"Content-Type": "application/json"}
    headers.update(pick_auth_headers(api_key))

    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, headers=headers, method="POST")

    context = None
    if INSECURE:
        import ssl

        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE

    try:
        with urllib.request.urlopen(request, timeout=10, context=context) as response:
            print(f"[{profile}] {response.status} <- {payload}", flush=True)
    except urllib.error.HTTPError as e:
        print(f"[{profile}] HTTP {e.code} <- {payload} ({e.read().decode(errors='replace')})", flush=True)
    except urllib.error.URLError as e:
        print(f"[{profile}] request failed: {e.reason}", flush=True)


def main():
    sent = 0
    while REQUEST_COUNT <= 0 or sent < REQUEST_COUNT:
        send_reading()
        sent += 1
        if REQUEST_COUNT > 0 and sent >= REQUEST_COUNT:
            break
        time.sleep(INTERVAL_SECONDS)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
