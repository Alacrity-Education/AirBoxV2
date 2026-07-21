#!/usr/bin/env python3
"""Export live Grafana dashboards back into the canonical repo dir.

The inverse of provision-dashboards.py. For each dashboard *.json under the repo
dashboards dir this reads the root ``uid``, fetches the live dashboard from
Grafana (GET /api/dashboards/uid/<uid>, taking the ``.dashboard`` node),
normalizes away the noise Grafana injects on storage, and — only when the result
differs SEMANTICALLY from the repo file — rewrites the file. Exporting an
UNMODIFIED, freshly-provisioned dashboard reproduces the repo file byte-for-byte
(a clean git tree); a real UI/API edit shows up as exactly that edit.

NORMALIZATION — fields stripped/restored and why (derived by round-tripping the
force-provisioned sources; only these two differ between repo and a live GET):

  * ``id``  (top level) — Grafana assigns an auto-increment internal id to every
    stored dashboard. It is host/DB-local, not part of the portable definition,
    and the repo files never carry it. STRIPPED from the export.
  * ``version`` (top level) — Grafana bumps this monotonically on every save
    (provisioning included), so a live GET always reports a higher number than
    the repo file. It carries no authored intent. The repo file's own ``version``
    is PRESERVED in the output (the live value is ignored) so a mere save-bump is
    not mistaken for a content change.

No other Grafana mutations were observed for these dashboards (no injected empty
arrays, no schemaVersion/pluginVersion drift, no meta wrapper — ``.dashboard``
already excludes the ``meta`` envelope). Should new noise appear, extend
``normalize`` and this comment together.

FORMATTING — the repo files are not uniformly formatted (some hand-authored with
inline objects, some with a custom top-level key order, differing trailing
newlines). Rather than impose one canonical style and churn every file, this
tool:
  * decides "changed?" by SEMANTIC deep-equality, so a semantically-identical
    dashboard is left byte-untouched (its existing formatting is preserved); and
  * when a file genuinely changed, re-emits it ordered to follow the repo file's
    existing key/element order (``reorder_like``) with 2-space indentation,
    ``ensure_ascii=False`` and the file's existing trailing-newline convention,
    so the git diff shows only the real change, not reordering noise.

Stdlib only (Python 3.12 on host). Admin password comes from the environment
variable GRAFANA_ADMIN_PASSWORD (never argv). Basic auth as 'admin'.
"""

import argparse
import base64
import glob
import json
import os
import sys
import urllib.error
import urllib.request

# Top-level keys Grafana adds/mutates on storage that are not authored content.
# See the module docstring for the rationale on each.
STRIP_KEYS = ("id",)          # dropped from the export entirely
PRESERVE_FROM_REPO = ("version",)  # repo's value kept, live value ignored

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# scripts/ -> infrastructure/ -> repo root -> new-middleware/grafana/dashboards
_DEFAULT_DASHBOARDS_DIR = os.path.normpath(
    os.path.join(_SCRIPT_DIR, "..", "..", "new-middleware", "grafana", "dashboards")
)


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


def normalize(live, repo):
    """Return the live dashboard with Grafana storage-noise removed.

    ``id`` is dropped; ``version`` is taken from the repo file so a save-bump is
    not counted as a change. Operates on a shallow copy (the caller's live dict
    is left intact)."""
    out = dict(live)
    for k in STRIP_KEYS:
        out.pop(k, None)
    for k in PRESERVE_FROM_REPO:
        if k in repo:
            out[k] = repo[k]
        else:
            out.pop(k, None)
    return out


def reorder_like(new, ref):
    """Reorder ``new`` to follow ``ref``'s key/element order (recursively).

    Keys present in ``ref`` come first in ``ref``'s order; any keys only in
    ``new`` are appended. Lists are aligned by index. This keeps the emitted
    JSON diff minimal — unchanged content serializes identically to the repo
    file, so only genuinely changed values move."""
    if isinstance(new, dict) and isinstance(ref, dict):
        out = {}
        for k in ref:
            if k in new:
                out[k] = reorder_like(new[k], ref[k])
        for k in new:
            if k not in out:
                out[k] = new[k]
        return out
    if isinstance(new, list) and isinstance(ref, list):
        return [reorder_like(n, ref[i]) if i < len(ref) else n
                for i, n in enumerate(new)]
    return new


def top_level_diff_keys(a, b):
    """Top-level keys whose values differ between two dashboard objects."""
    return sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))


def export_dashboard(client, path):
    """Return 'unchanged' or 'updated'. Raises RuntimeError on API/data errors."""
    with open(path, encoding="utf-8") as f:
        repo_raw = f.read()
    repo = json.loads(repo_raw)

    uid = repo.get("uid")
    if not uid:
        raise RuntimeError(f"{path}: repo dashboard has no uid")

    status, body = client.request("GET", f"/api/dashboards/uid/{uid}")
    if status != 200:
        raise RuntimeError(
            f"{path}: GET /api/dashboards/uid/{uid} failed: status {status}: {body}")
    live = (body or {}).get("dashboard")
    if not isinstance(live, dict):
        raise RuntimeError(f"{path}: response had no .dashboard object: {body}")

    normalized = normalize(live, repo)

    if normalized == repo:
        print(f"[export] unchanged  {uid} ('{repo.get('title')}')")
        return "unchanged"

    ordered = reorder_like(normalized, repo)
    out = json.dumps(ordered, indent=2, ensure_ascii=False)
    if repo_raw.endswith("\n"):
        out += "\n"
    with open(path, "w", encoding="utf-8") as f:
        f.write(out)

    diff_keys = top_level_diff_keys(repo, normalized)
    print(f"[export] UPDATED    {uid} ('{repo.get('title')}') "
          f"— top-level keys differing: {diff_keys}")
    return "updated"


def main():
    parser = argparse.ArgumentParser(
        description="Export live Grafana dashboards back into the canonical repo dir.")
    parser.add_argument("--grafana-url", default="http://localhost:3009")
    parser.add_argument("--dashboards-dir", default=_DEFAULT_DASHBOARDS_DIR,
                        help="Repo dir of dashboard *.json files "
                             "(default: resolved relative to this script)")
    args = parser.parse_args()

    password = os.environ.get("GRAFANA_ADMIN_PASSWORD")
    if not password:
        print("ERROR: GRAFANA_ADMIN_PASSWORD not set in environment", file=sys.stderr)
        return 1

    client = GrafanaClient(args.grafana_url, password)

    files = sorted(glob.glob(os.path.join(args.dashboards_dir, "*.json")))
    if not files:
        print(f"ERROR: no *.json dashboards found in {args.dashboards_dir}",
              file=sys.stderr)
        return 1

    counts = {"unchanged": 0, "updated": 0}
    failed = 0
    for path in files:
        try:
            counts[export_dashboard(client, path)] += 1
        except (RuntimeError, OSError, json.JSONDecodeError) as e:
            print(f"[export] ERROR {path}: {e}", file=sys.stderr)
            failed += 1

    print(f"\n=== summary === unchanged={counts['unchanged']} "
          f"updated={counts['updated']} failed={failed}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
