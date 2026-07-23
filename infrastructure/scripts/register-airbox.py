#!/usr/bin/env python3
"""Interactive TUI for registering a new AirBox installation.

Walks through the fields of AIRBOX_INSTALLATIONS (device id, API key,
owner/co-owner emails, installation type, notes), validates them, inserts
the row through `docker exec <db-container> psql` using psql variable
binding (no SQL string interpolation), and optionally restarts the
middleware so the Grafana sync creates the station's dashboards and
public URL immediately.

Stdlib only, like the other infrastructure scripts. Run on the docker
host:  python3 infrastructure/scripts/register-airbox.py
"""

import argparse
import re
import secrets
import subprocess
import sys
import time

# Grafana dashboard uids are capped at 40 chars; the per-device twin uid is
# "abx-details-" + device_id (12 + n), and the middleware allowlist matches.
DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,27}$")
APIKEY_RE = re.compile(r"^[A-Za-z0-9_-]{8,100}$")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

BOLD, DIM, GREEN, YELLOW, RED, CYAN, RESET = (
    "\033[1m", "\033[2m", "\033[32m", "\033[33m", "\033[31m", "\033[36m", "\033[0m")


def say(msg=""):
    print(msg, flush=True)


def die(msg, code=1):
    say(f"{RED}error:{RESET} {msg}")
    sys.exit(code)


def psql(container, sql, variables=None, capture=True):
    """Run one SQL statement via docker exec psql.

    Values are passed with `-v name=value` and referenced as :'name' inside
    the statement, so user input never touches the SQL text itself.
    """
    cmd = ["docker", "exec", "-i", container, "psql", "-U", "airbox", "-d", "airbox",
           "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1"]
    for name, value in (variables or {}).items():
        cmd += ["-v", f"{name}={value}"]
    # The SQL travels on stdin (input=...): psql does NOT interpolate
    # :'name' variables inside -c commands, and an explicit stdin pipe also
    # keeps the child from swallowing the wizard's own pending input.
    result = subprocess.run(cmd, input=sql, capture_output=True, text=True)
    if result.returncode != 0:
        die(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip() if capture else ""


def ask(label, *, default=None, required=False, pattern=None, hint=None):
    """Prompt until the answer validates. Empty answer takes the default."""
    suffix = f" {DIM}[{default}]{RESET}" if default else ""
    while True:
        raw = input(f"{BOLD}{label}{RESET}{suffix}: ").strip()
        if not raw and default is not None:
            return default
        if not raw:
            if required:
                say(f"  {YELLOW}this field is required{RESET}")
                continue
            return None
        if len(raw) > 100:
            say(f"  {YELLOW}must be at most 100 characters{RESET}")
            continue
        if pattern and not pattern.match(raw):
            say(f"  {YELLOW}{hint or 'invalid value'}{RESET}")
            continue
        return raw


def ask_choice(label, choices, default_index=0):
    say(f"{BOLD}{label}{RESET}")
    for i, choice in enumerate(choices, 1):
        marker = f"{DIM}(default){RESET}" if i - 1 == default_index else ""
        say(f"  {CYAN}{i}{RESET}) {choice} {marker}")
    while True:
        raw = input(f"  choose 1-{len(choices)}: ").strip()
        if not raw:
            return choices[default_index]
        if raw.isdigit() and 1 <= int(raw) <= len(choices):
            return choices[int(raw) - 1]
        say(f"  {YELLOW}enter a number between 1 and {len(choices)}{RESET}")


def main():
    parser = argparse.ArgumentParser(description="Register a new AirBox installation.")
    parser.add_argument("--db-container", default="airbox-timescaledb",
                        help="TimescaleDB container name (default: airbox-timescaledb)")
    parser.add_argument("--middleware-container", default="airbox-middleware",
                        help="Middleware container name (default: airbox-middleware)")
    args = parser.parse_args()

    say(f"\n{BOLD}AirBox — register a new installation{RESET}")
    say(f"{DIM}Inserts a row into airbox_installations; the Grafana sync then creates "
        f"the station dashboard and its public URL automatically.{RESET}\n")

    existing = psql(args.db_container,
                    "SELECT device_id || '  (' || installation || ')' "
                    "FROM airbox_installations ORDER BY created_at;")
    if existing:
        say(f"{DIM}existing devices:{RESET}")
        for line in existing.splitlines():
            say(f"  {DIM}- {line}{RESET}")
        say("")

    device_id = ask("Device id", required=True, pattern=DEVICE_ID_RE,
                    hint="letters, digits, - and _ only, at most 27 characters "
                         "(Grafana uid limit for the abx-details- twin)")
    apikey = ask("API key", default=f"abxkey-{secrets.token_hex(8)}",
                 pattern=APIKEY_RE,
                 hint="letters, digits, - and _ only, 8-100 characters")
    owner = ask("Owner email", required=True, pattern=EMAIL_RE, hint="not a valid email")
    co1 = ask("Co-owner 1 email (optional)", pattern=EMAIL_RE, hint="not a valid email")
    co2 = ask("Co-owner 2 email (optional)", pattern=EMAIL_RE, hint="not a valid email") if co1 else None
    installation = ask_choice("Installation type", ["outdoor", "indoor"])
    notes = ask("Notes (optional)")

    clash = psql(args.db_container,
                 "SELECT device_id FROM airbox_installations "
                 "WHERE device_id = :'dev' OR apikey = :'key';",
                 {"dev": device_id, "key": apikey})
    if clash:
        die(f"device id or API key already in use (clashes with '{clash}')")

    say(f"\n{BOLD}about to insert:{RESET}")
    say(f"  device_id     {GREEN}{device_id}{RESET}")
    say(f"  apikey        {GREEN}{apikey}{RESET}")
    say(f"  owner         {owner}")
    say(f"  co-owners     {co1 or '-'} / {co2 or '-'}")
    say(f"  installation  {installation}")
    say(f"  notes         {notes or '-'}")
    if input(f"\n{BOLD}confirm insert? (y/N):{RESET} ").strip().lower() != "y":
        die("aborted, nothing inserted", code=0)

    psql(args.db_container,
         "INSERT INTO airbox_installations "
         "(device_id, apikey, owner_email, co_owner1_email, co_owner2_email, installation, notes) "
         "VALUES (:'dev', :'key', :'owner', "
         + (":'co1'" if co1 else "NULL") + ", "
         + (":'co2'" if co2 else "NULL") + ", :'inst', "
         + (":'notes'" if notes else "NULL") + ");",
         {k: v for k, v in {"dev": device_id, "key": apikey, "owner": owner,
                            "co1": co1, "co2": co2, "inst": installation,
                            "notes": notes}.items() if v is not None})
    say(f"\n{GREEN}inserted.{RESET} devices can now POST with header "
        f"{CYAN}ApiKey: {apikey}{RESET}")

    if input(f"\n{BOLD}restart the middleware now to create the Grafana dashboards? "
             f"(y/N):{RESET} ").strip().lower() == "y":
        subprocess.run(["docker", "restart", args.middleware_container],
                       check=True, capture_output=True)
        say(f"{DIM}waiting for the sync...{RESET}")
        deadline = time.time() + 120
        while time.time() < deadline:
            logs = subprocess.run(
                ["docker", "logs", "--since", "2m", args.middleware_container],
                capture_output=True, text=True).stdout
            done = [l for l in logs.splitlines() if "Grafana sync:" in l and "na-sync-startup" in l]
            if done:
                say(f"  {done[-1].split(':', 3)[-1].strip()}")
                break
            time.sleep(3)
        else:
            say(f"{YELLOW}sync did not report within 2 minutes — check "
                f"docker logs {args.middleware_container}{RESET}")
        say(f"\npublic dashboard: {CYAN}https://airbox.alacrity.ro/g/public-dashboards/"
            f"abx-details-{device_id}{RESET}")
    else:
        say(f"{DIM}the hourly sync will create the dashboards; or restart "
            f"{args.middleware_container} manually. Public URL will be "
            f"https://airbox.alacrity.ro/g/public-dashboards/abx-details-{device_id}{RESET}")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        say(f"\n{DIM}aborted{RESET}")
        sys.exit(130)
