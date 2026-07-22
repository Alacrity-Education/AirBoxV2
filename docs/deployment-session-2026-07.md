# AirBox V2 — Deployment Session Changelog (2026-07-09 → 2026-07-21)

This document records every change made to the AirBox V2 deployment on this host during the July 2026 deployment sessions, in chronological order. The deployment lives in `infrastructure/` (compose project `infrastructure`); middleware source in `new-middleware/`; branch `cezar`.

---

## 1. Initial V2 deployment (2026-07-09)

**Old stack decommissioned.** The legacy MQTT pipeline (`mosquitto` + `telegraf` + `prometheus`, deployed from `/home/eros/airbox`) was torn down: containers, locally built images, and all data under `/var/alacrity/airbox-*` removed. Only the secrets from the old `.env` were carried over. `/home/eros/airbox` and `/home/eros/airbox-git` were left in place as inert leftovers.

**New stack deployed** from `AirBoxV2/infrastructure`, keeping the established format (ansible-created mounts, Jinja2 templates, flat `.env`):

- Services: `airbox-grafana` (host port 3009), `airbox-timescaledb` (pg17), `airbox-middleware` (Spring Boot, host port 8080, built from `../new-middleware`), `airbox-mock` (built from `../new-middleware/mock-firmware`). The middleware replaces both the MQTT broker and the MQTT→DB bridge; Flyway owns the DB schema.
- Playbooks rewritten: `create-mounts.yml` (unchanged, derives bind mounts from compose), `create-config.yml` (trimmed to Grafana datasource + dashboards + landing page), `configure-caddy.yml` (now uses `INGEST_DOMAIN`).
- Caddy (`/etc/caddy/conf.d/airbox`, rendered from `templates/caddy-airbox.conf.j2`):
  - `airbox.alacrity.ro` — static landing page at the root, Grafana under the `/g/` subpath (per the project wiki), via `GF_SERVER_ROOT_URL=…/g/` + `GF_SERVER_SERVE_FROM_SUB_PATH=true`.
  - `ingest.airbox.alacrity.ro` → middleware (`POST /api/v2/submit`, ApiKey auth via `Authorization: ApiKey`, `ApiKey`, or `X-ApiKey` headers).
- The old beta domains (`beta.grafana.airbox.alacrity.ro`, `beta.mqtt.airbox.alacrity.ro`) were removed.
- A simple hand-written landing page was templated to `/var/alacrity/airbox-landing/`.
- The legacy dashboard was migrated from the old `airbox` table/`id` column to `airbox_readings`/`device`.

## 2. Middleware update — Grafana API sync (2026-07-21, commit `57897ca`)

The middleware gained Grafana-API capabilities (dashboard sync + public dashboard sharing). Redeployed with a full DB + Grafana wipe:

- New middleware env: `MDW_GRAFANA_SYNC_ENABLED=true`, `MDW_GRAFANA_URL=http://grafana:3000`, `MDW_GRAFANA_PUBLIC_URL=${GRAFANA_DOMAIN}/g`, `MDW_GRAFANA_USER=admin`, `MDW_GRAFANA_PASSWORD=${GRAFANA_ADMIN_PASSWORD}` (basic auth per requirements), `MDW_PUBLIC_TOKEN_SECRET` (new random secret in `.env`; **required** — app refuses to boot without it when sync is enabled), `MDW_GRAFANA_MAP_UID`.
- Datasource template pinned to `uid: airbox-postgres` (all dashboards reference the datasource by that uid).
- Old single dashboard replaced by the four repo dashboards (geomap, overview, station, station-v2).
- Grafana image pinned to `grafana/grafana:13.1.0` (was `latest`).
- Business Forms plugin preinstalled: `GF_INSTALL_PLUGINS=${GRAFANA_PLUGINS}` with `GRAFANA_PLUGINS=volkovlabs-form-panel` in `.env`.
- Authentik OIDC redirect updated server-side (by admin) — SSO functional on the new domain.
- **Live host fix:** a stale hand-added `ingest.airbox.alacrity.ro → localhost:9001` block in the main `/etc/caddy/Caddyfile` collided with the managed conf.d block (`ambiguous site definition`, caddy refused to start) and pointed at a dead port. It was removed (backup kept); a comment in the Caddyfile now points to the managed definition.

## 3. Determinism audit + hardening

A multi-agent audit verified whether all Grafana ids/links reproduce bit-identically across wipe+redeploy. Result: everything was already deterministic (pinned uids, `/d/<uid>` links, uid-based datasource refs, explicit folder uids, HMAC public tokens) **except** one latent path. Changes:

- `GrafanaClient.createPublicDashboard` made **fail-hard**: removed the HTTP-400 fallback that let Grafana mint a random public token; added an echo assertion that Grafana honored the supplied token. Two regression tests added.
- `GF_PUBLIC_DASHBOARDS_ENABLED=true` pinned explicitly in compose.
- Invariant comments added at the two commentable geomap-uid sites (compose env + `application.properties`); the uid `9f08aae7-…` is duplicated across env, properties default, the geomap JSON, and every nav link — edit all together.
- (A dashboards drift guard was also added at this stage; later removed — see §11.)
- Verified in place: token diff before/after empty; deterministic tokens later survived multiple full wipes unchanged.

## 4. Mock deployment switch

Single knob `AIRBOX_MOCKS_ENABLED` in `.env` (lowercase `true`/`false`, currently `true`):

- `false` ⇒ **no mock container** (compose profile gate `profiles: ["${AIRBOX_MOCKS_ENABLED:-true}"]`; `COMPOSE_PROFILES=true` in `.env` is fixed machinery — never edit) **and no mock-installation seed** (V3 migration rewritten to be conditional on the Flyway placeholder `seed_mock_installations`, wired via `MDW_SEED_MOCK_INSTALLATIONS`).
- The seed decision applies when migrations first run on a **fresh** DB; flipping later requires a DB re-init. Flipping to `false` on a live system also needs `docker compose rm -sf mock`.
- Editing V3 changed its checksum ⇒ one-time DB re-init was performed (public URLs survived — determinism).

## 5. Human-readable public dashboard URLs

Grafana 13.1.0 **rejects non-32-hex access tokens** (verified empirically), so readable URLs are implemented as a slug layer:

- Slugs: `/g/public-dashboards/overview`, `/g/public-dashboards/geomap`, `/g/public-dashboards/abx-overview-<deviceId>` (per-device "view"), `/g/public-dashboards/abx-details-<deviceId>` (per-device "details").
- New middleware endpoint `GET /public-dashboards/{slug}` → 302 to the real token URL (404 unknown). Caddy intercepts exactly the slug patterns ahead of the generic `/g/*` proxy; real token URLs and `/g/api/public/*` pass through to Grafana. Singular `/g/public-dashboard/*` redirects to the plural.
- Migration **V6**: `slug` column on `airbox_grafana_artifacts` + backfill; view labels renamed (`statie_v1`→`vedere`, `statie_v2`→`detalii` — later anglicized again, see §7).
- Generated dashboard uids renamed to equal the slugs (`st1-*`→`abx-overview-*`, `st2-*`→`abx-details-*`); old dashboards/artifacts cleaned up (no automatic orphan cleanup exists in the sync — manual step).
- All SQL-generated inter-dashboard links now build `'/public-dashboards/' || slug`; `public_url` in the artifacts table stores the pretty URL. Internal tokens remain HMAC(secret, uid) — invisible to users.

## 6. English translation, root-URL fix, new landing page

- **Translation:** all user-visible Romanian text translated to English across the four dashboards, sync templates, SQL display aliases, value mappings (Bun/Moderat/Nesanatos → Good/Moderate/Unhealthy; DA/NU → YES/NO), recommendation sentences, and Java-rendered twin titles (`– vedere/– detalii` → `– overview/– details`).
- **404 fix:** bare `https://airbox.alacrity.ro/public-dashboards/*` (and singular) fell through to the landing file server; Caddy now 302-redirects root-level paths into `/g/public-dashboards/*`.
- **Landing page replaced** with the "Wind Map" design export (`infrastructure/templates/landing-index.html`, ~913 KB). Deployed via ansible `copy`, **not** `template` (the export's JS contains `{{…}}` sequences that break Jinja). Its `static LINKS = {…}` object is patched: See the data → `/g/public-dashboards/geomap`, Administration area → `/g/login`, read the docs → the wiki airbox-v2 page.
- Shipped via a full wipe of all container volumes + redeploy + caddy restart (all public URLs reproduced identically).

## 7. View identifiers anglicized (V7)

Migration **V7** + coordinated renames: artifact `view` values are now `map`, `overview`, `station_overview`, `station_details` (were `harta`/`vedere`/`detalii`). Java constants and all dashboard SQL updated together — these values are the code↔SQL contract.

## 8. Grafana UX changes

- `GF_USERS_DEFAULT_THEME=light`.
- Source dashboards moved into a **"Templates"** folder. (At the time, via provider.yaml — Grafana's file provisioner only assigns folders at create time, so relocation required a remove-files→restart→restore→restart cycle. Superseded by §11.)
- Dashboard title `AirBox Station` → `AirBox Station – overview` (en-dash, matching `– details`), nav labels updated: Map / Station – overview / Station – details / Overview.

## 9. Geomap public twin

`syncMap` redesigned: instead of attaching a public share to the file-provisioned source, it fetches the **live** source by uid and upserts a twin `airbox-public-geomap` into the "AirBox Public" folder (hash-gated refresh, fixing the old create-only quirk). The folder now holds **both** public twins (`airbox-public-overview`, `airbox-public-geomap`); source dashboards carry no public shares. The `geomap` slug transparently absorbed the token change.

## 10. All twins follow the live-source flow

The overview and 8 station twins previously rendered from templates baked into the middleware jar. Now **all twins** are generated by fetching the live Templates sources and transforming them (public dashboards cannot have variables):

- Transform rules: remove the `device` template variable and links; resolve `${device:sqlstring}` → `'<deviceId>'` for station twins and an all-devices subquery for the global overview; **strip `owner_email` (PII) from public SQL** (with a hard-fail guard); inject `nav_panels.json` at the top; set id/version/uid/title; tag `airbox-generated`. Hash of the transformed JSON gates re-upserts.
- Source uids configurable: `MDW_GRAFANA_OVERVIEW_UID`, `MDW_GRAFANA_STATION_OVERVIEW_UID`, `MDW_GRAFANA_STATION_DETAILS_UID` (defaults `airbox-overview`/`airbox-station`/`airbox-station-v2`), mirroring `MDW_GRAFANA_MAP_UID`.
- The jar templates `overview.json`/`station_overview.json`/`station_details.json` were **deleted**; only the `nav_panels.json` chrome fragment remains (also the only thing `MDW_GRAFANA_TEMPLATES_DIR` now overrides).
- Editing a source now propagates to its twins on the next sync (hourly cron, or `docker restart airbox-middleware`).

## 11. API-based dashboard provisioning (replaces file provisioning)

Grafana 13 file-provisioned dashboards are "managed": UI edits were reverted by the ~30 s provisioner reconcile even with `allowUiUpdates`. Replaced entirely:

- **New:** `infrastructure/scripts/provision-dashboards.py` (stdlib-only Python) + `infrastructure/provision-dashboards.yml`. The playbook waits for Grafana health, seeds the four sources from the **single canonical dir** `new-middleware/grafana/dashboards/` into the pinned-uid `templates` folder, sets the org home dashboard (geomap) via API, then restarts the middleware to trigger the twin sync.
- **Semantics:** create-if-absent; existing dashboards are **skipped** (UI edits preserved); `--force` overwrites (used to push repo edits).
- **Removed:** `provider.yaml`, the dashboards volume mount, `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH`, the mirrored `infrastructure/templates/grafana-dashboards/` copy, and the drift guard (single-sourcing made them obsolete). The **datasource stays file-provisioned** (readOnly, pinned uid).
- **Deploy order is now:** `create-config.yml` → `docker compose up -d` → `provision-dashboards.yml`.
- Result: dashboards are unmanaged; UI edits are durable (verified surviving well past the old reconcile window) and flow to the public twins on the next sync.

## 12. Mock: fixed geohashes + DB purge

- `AIRBOX_GEOHASHES=sxft17ek,sxfscg0b,sxfsd565,sxfs8jdh` (`.env`, compose, Dockerfile default): each API key/device is paired with one geohash by index (`001`→`sxft17ek`, `002`→`sxfscg0b`, `003`→`sxfsd565`, `004`→`sxfs8jdh`); unset ⇒ old random behavior.
- All pre-existing readings with non-conforming geohashes purged from `airbox_readings` (730 rows deleted); the geomap now shows exactly four stable station points.

## 13. Dashboard units

- Every µg/m³ unit (`conµgm3`) changed to `ppm` across all PM panels (PM1/PM2.5/PM4/PM10 incl. history variants) in overview, station, and station-v2 — 15 changes total.
- VOC index / NOx index panels confirmed unitless (`none`); CO₂ already `ppm`; temperature/humidity untouched.
- Pushed with `provision-dashboards.py --force` + middleware restart (twins refreshed).

## 14. Station dashboards merged (V8)

The per-device "overview" and "details" dashboards were combined into a single **"AirBox Station"** (uid `airbox-station` kept; `airbox-station-v2` deleted): 13 exact-duplicate panels dropped, 11 unique panels folded in. One public twin per device now — the slug/uid stays **`abx-details-<deviceId>`** so previously distributed URLs survive; the `abx-overview-*` twins/slugs are retired (404). Migration **V8** collapsed the view values to `station`; middleware env simplified to a single `MDW_GRAFANA_STATION_UID`. Nav links everywhere reduced to Map / Station / Overview.

## 15. Public navigation: tab-style buttons (and the SPA token bug)

Station twins no longer carry the injected Navigation/Stations panels (they open directly with station content); the global views got tab-style navigation. Three iterations, documented because the failure mode is subtle:

1. **HTML text-panel tabs** — clicking produced *"Invalid access token"*: on a public dashboard (served at `/g/public-dashboards/:accessToken`) Grafana's SPA router intercepts same-origin anchors and parses the target slug as a token. `GF_PANELS_DISABLE_SANITIZE_HTML` was added for this attempt and reverted afterward.
2. **Native stat-panel buttons** with absolute `/g/...` URLs — still intercepted: the router claims *any* path under its `appSubUrl`, absolute or not. Also, the backing query must be **numeric** (`SELECT 1`) — a string frame renders "No data", which kills both the label and the data link.
3. **Shipped fix**: stat-panel buttons (active blue-gray `#5a6b8c`, unlinked; inactive sharp blue `#2563eb`, one-click data link) whose links use the **root-level path** (`https://airbox.alacrity.ro/public-dashboards/<slug>`, no `/g`) — outside the router base, forcing a full navigation through the Caddy root redirect. Verified with a headless-browser (Playwright) click test in both directions. (Harness note: the Playwright container needs `locale="en-US"` on the browser context or Grafana's bootstrap throws.)

## 16. UI-edit → repo export helper

`infrastructure/scripts/export-dashboards.py` pulls the live Templates dashboards back into the repo by uid, stripping exactly the Grafana-added noise (`id`; the save-bumped `version` is ignored in favor of the repo's). A semantic-equality guard leaves unchanged files byte-untouched, so exports never produce formatting churn. Round-trip verified (clean export right after provisioning; positive control detects a deliberate API-side edit as exactly one diff). Workflow: edit in the Grafana UI → run the export → `git diff` → commit. Twins pick the edits up automatically on the next sync.

## 17. AQI enrichment (V9)

The middleware computes an EPA-style AQI at ingest into new nullable `airbox_readings.aqi` + `aqi_pollutant` columns: per-pollutant truncation → linear interpolation over breakpoint tables → max sub-index, rounded. Eligibility per the project rule: ≥3 computable sub-indices with at least one PM, else NULL. Implementation: pure `AqiCalculator` + table-driven `Pollutant` registry covering all six EPA pollutants; fed today by **PM2.5 / PM10 (24 h trailing means)** and **NOx as an NO2 1 h-mean ppb proxy** (documented approximation — the SEN66 does not measure NO2 specifically). Consequence: only full-profile readings qualify; SEN66-without-raw-NOx readings land NULL by design. May-2024 PM2.5 breakpoints; over-scale clamps to 500; the two EPA "Hazardous" rows are merged into one 301–500 band per the spec. Verified with an exact hand-check against a live row. AQI is not yet surfaced on the dashboards.

## 18. Mock and data lifecycle

- **SCD30 profile retired** (obsolete hardware): the mock's roster is now `full` + `sen66_no_raw_voc_nox`. The V3 seed's "SCD30" device *note* is deliberately unchanged (editing an applied migration breaks Flyway checksums).
- Readings purges during testing: non-conforming-geohash rows, then null-AQI rows, then a full truncate — the table rebuilds from live mock traffic; all data on this deployment is disposable.

## 19. Renames

- Module directory **`new-middleware/` → `middleware/`** (git history preserved). All active references updated: compose build contexts, provision/export script paths, Maven `artifactId`/`name` (jar is `middleware-*.jar`), `spring.application.name`. Historical sections of this document keep the old name intentionally.
- Java package **`com.cezar.newmiddleware` → `ro.alacrity.airbox.middleware`** (35 files, `git mv`); Maven `groupId` → `ro.alacrity.airbox`; `NewMiddlewareApplication` → `MiddlewareApplication`. Flyway migrations untouched and validated clean.

---

## Current deployment procedure (fresh host / full redeploy)

```bash
cd /home/eros/AirBoxV2/infrastructure
ansible-playbook create-config.yml        # mounts, datasource, landing page
docker compose build && docker compose up -d
ansible-playbook provision-dashboards.yml # seeds dashboards via API, restarts middleware
ansible-playbook configure-caddy.yml      # renders conf.d/airbox, restarts caddy
```

Secrets and switches live in `infrastructure/.env` (never committed; the committed copy holds `CHANGE-ME` placeholders): `GRAFANA_ADMIN_PASSWORD`, `TSDB_*`, `GRAFANA_OIDC_*`, `MDW_PUBLIC_TOKEN_SECRET` (changing it changes every public URL token — the slugs hide this from users), `AIRBOX_MOCKS_ENABLED`, `AIRBOX_API_KEYS`/`AIRBOX_GEOHASHES`, `GRAFANA_PLUGINS`. Canonical dashboards live only in `middleware/grafana/dashboards/`; the UI-edit → repo loop is `scripts/export-dashboards.py` (§16).

Git: pushes go over the SSH deploy key `~/.ssh/airbox_deploy_key` (repo-locally pinned via `core.sshCommand`); the working-tree `.env` holds real secrets and must never be staged.

## Known follow-ups / notes (each re-verified 2026-07-22)

- `GET https://ingest.airbox.alacrity.ro/api/v2/submit` returns 405 (verified); the wiki specifies an HTML how-to page — unimplemented in the middleware.
- Twin title inconsistency (verified live): the overview twin is titled "AirBox Overview (public)" while the geomap twin kept its source title "AirBox Geomap View" — cosmetic.
- AQI (§17) is computed and stored but not yet shown on any dashboard; the NOx→NO2 proxy assumption should be revisited if real NO2-capable hardware arrives.
- SEN66-without-raw-NOx readings intentionally carry `aqi = NULL` (only 2 of the required ≥3 pollutants).
- Optional determinism items deliberately not shipped: public-dashboard object-uid pin, `ensureShare` stray-token reconcile.
- All current DB data is mock/disposable; per-device public surfaces are deterministic *given the same device-id set* (`airbox_installations`).
