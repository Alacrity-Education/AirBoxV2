#!/usr/bin/env python3
"""First-boot Grafana dashboard provisioning via the HTTP API.

Replaces file-based dashboard provisioning. Dashboards created through the API
are *unmanaged* resources in Grafana 13's unified storage, so admin UI edits are
durable (unlike file-provisioned "managed" resources, which the provisioner
reconcile loop reverts). This script is idempotent: it seeds dashboards only
when absent, preserving any later UI edits unless --force is given.

Datasource provisioning stays file-based and is intentionally NOT touched here.

Stdlib only (Python 3.12 on host). Admin password comes from the environment
variable GRAFANA_ADMIN_PASSWORD (never argv). Basic auth as 'admin'.
"""

import argparse
import base64
import glob
import json
import os
import sys
import time
import urllib.error
import urllib.request

# Pinned identifiers — determinism requirement. These must never drift.
TEMPLATES_FOLDER_UID = "templates"
TEMPLATES_FOLDER_TITLE = "Templates"
HOME_DASHBOARD_UID = "9f08aae7-e794-402e-8a13-044578bfab39"  # AirBox Geomap View


class GrafanaClient:
    def __init__(self, base_url, password, user="admin"):
        self.base_url = base_url.rstrip("/")
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.auth_header = f"Basic {token}"

    def request(self, method, path, body=None):
        """Return (status_code, parsed_json_or_none). Raises on transport errors."""
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", self.auth_header)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                parsed = json.loads(raw) if raw else None
                return resp.status, parsed
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                parsed = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                parsed = {"raw": raw.decode(errors="replace")}
            return e.code, parsed


def wait_for_health(client, retries=30, delay=2):
    for attempt in range(1, retries + 1):
        try:
            status, body = client.request("GET", "/api/health")
            if status == 200:
                print(f"[health] Grafana healthy (attempt {attempt}): {body}")
                return True
            print(f"[health] attempt {attempt}/{retries}: status {status}")
        except (urllib.error.URLError, OSError) as e:
            print(f"[health] attempt {attempt}/{retries}: not reachable ({e})")
        time.sleep(delay)
    return False


def ensure_templates_folder(client):
    status, body = client.request(
        "POST", "/api/folders",
        {"uid": TEMPLATES_FOLDER_UID, "title": TEMPLATES_FOLDER_TITLE},
    )
    if status in (200, 201):
        print(f"[folder] created '{TEMPLATES_FOLDER_TITLE}' (uid={TEMPLATES_FOLDER_UID})")
        return True
    if status in (409, 412):
        print(f"[folder] '{TEMPLATES_FOLDER_TITLE}' already exists (uid={TEMPLATES_FOLDER_UID}) — ok")
        return True
    print(f"[folder] ERROR creating folder: status {status}: {body}", file=sys.stderr)
    return False


def provision_dashboard(client, path, force):
    """Return one of 'created', 'skipped', 'forced', or raises RuntimeError."""
    with open(path) as f:
        raw = json.load(f)

    # Files may be a bare dashboard object or wrapped as {"dashboard": {...}}.
    dashboard = raw.get("dashboard", raw)
    uid = dashboard.get("uid")
    title = dashboard.get("title")
    if not uid:
        raise RuntimeError(f"{path}: dashboard has no uid")

    # New/unmanaged resource semantics: let Grafana assign the internal id.
    dashboard["id"] = None

    status, _ = client.request("GET", f"/api/dashboards/uid/{uid}")
    present = status == 200

    if present and not force:
        print(f"[dashboard] SKIP  {uid} ('{title}') — already present, preserving UI edits")
        return "skipped"

    overwrite = present  # only overwrite an existing one (force path)
    payload = {"dashboard": dashboard, "folderUid": TEMPLATES_FOLDER_UID, "overwrite": overwrite}
    pstatus, pbody = client.request("POST", "/api/dashboards/db", payload)
    if pstatus in (200, 201):
        action = "forced" if present else "created"
        verb = "FORCE-OVERWRITE" if present else "CREATE"
        print(f"[dashboard] {verb} {uid} ('{title}') → folder '{TEMPLATES_FOLDER_UID}'")
        return action
    raise RuntimeError(f"{path}: POST /api/dashboards/db failed: status {pstatus}: {pbody}")


def set_home_dashboard(client):
    status, body = client.request(
        "PUT", "/api/org/preferences", {"homeDashboardUID": HOME_DASHBOARD_UID}
    )
    if status == 200:
        print(f"[prefs] org home dashboard set to geomap uid {HOME_DASHBOARD_UID}")
        return True
    print(f"[prefs] ERROR setting home dashboard: status {status}: {body}", file=sys.stderr)
    return False


def main():
    parser = argparse.ArgumentParser(description="Provision Grafana dashboards via API (first-boot seed).")
    parser.add_argument("--grafana-url", default="http://localhost:3009")
    parser.add_argument("--dashboards-dir", required=True,
                        help="Canonical repo dir containing dashboard *.json files")
    parser.add_argument("--force", action="store_true",
                        help="Overwrite existing dashboards (discards UI edits)")
    args = parser.parse_args()

    password = os.environ.get("GRAFANA_ADMIN_PASSWORD")
    if not password:
        print("ERROR: GRAFANA_ADMIN_PASSWORD not set in environment", file=sys.stderr)
        return 1

    client = GrafanaClient(args.grafana_url, password)

    if not wait_for_health(client):
        print("ERROR: Grafana did not become healthy", file=sys.stderr)
        return 1

    if not ensure_templates_folder(client):
        return 1

    files = sorted(glob.glob(os.path.join(args.dashboards_dir, "*.json")))
    if not files:
        print(f"ERROR: no *.json dashboards found in {args.dashboards_dir}", file=sys.stderr)
        return 1

    counts = {"created": 0, "skipped": 0, "forced": 0}
    failed = 0
    for path in files:
        try:
            counts[provision_dashboard(client, path, args.force)] += 1
        except (RuntimeError, OSError, json.JSONDecodeError) as e:
            print(f"[dashboard] ERROR {path}: {e}", file=sys.stderr)
            failed += 1

    home_ok = set_home_dashboard(client)

    print(
        f"\n=== summary === created={counts['created']} "
        f"skipped={counts['skipped']} forced={counts['forced']} "
        f"failed={failed} home_dashboard_set={home_ok}"
    )

    if failed or not home_ok:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
