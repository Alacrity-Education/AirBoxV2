# AirBox V2 — Deployment Changelog (2026-07-09 → 2026-07-22)

This document records the changes made to the AirBox V2 deployment on this host during the July 2026 deployment sessions: the initial V2 cutover as the baseline (§1), then a consolidated final-state changelist for everything from the `cezar` middleware integration onward (§2). The deployment lives in `infrastructure/` (compose project `infrastructure`); middleware source in `middleware/`; work now lands on branch `main`.

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

## 2. Consolidated changelist — cezar middleware integration → 2026-07-22

Everything from the `cezar` branch pickup (commit `57897ca`, Grafana-sync middleware) through today, deduplicated to the **final state**. Iteration history (file-provisioning → API provisioning, HTML tabs → button panels, two station views → one, Romanian → English identifiers) is collapsed; only the shipped design and its load-bearing gotchas remain.

### Middleware (`middleware/`, Java package `ro.alacrity.airbox.middleware`)

- **Grafana sync** (`DashboardSyncJob` + `DashboardTemplateService` + `GrafanaClient`): fetches each *live* source dashboard by uid and generates public twins — `airbox-public-overview`, `airbox-public-geomap` (folder "AirBox Public"), and one `abx-details-<deviceId>` per installation (per-device folders). Transform executes all variable-ing away (public dashboards cannot have variables): strips the `device` template variable and links, bakes `${device:sqlstring}` → `'<dev>'` (station) or an all-devices subquery (overview), strips `owner_email` PII from public SQL (hard-fail guard), injects the nav fragment (global overview only), sets uid/title/tags. Re-upserts are hash-gated (SHA-256 of the transformed JSON); runs at startup + hourly cron; per-view failure isolation.
- **Deterministic public tokens**: HMAC-SHA256(`MDW_PUBLIC_TOKEN_SECRET`, dashboard-uid)[:32]. `createPublicDashboard` is fail-hard — no random-token fallback, echo assertion that Grafana honored the supplied token. All ids/links are pure functions of repo + `.env` + device-id set.
- **Slug resolution endpoints**: `GET /internal/slug-token/{slug}` → 200 + `X-Abx-Token` header (404 unknown) — consumed by Caddy's request-time proxy; legacy `GET /public-dashboards/{slug}` → 302 kept for back-compat. Both backed by `airbox_grafana_artifacts.slug`.
- **Ingest help page**: `GET /api/v2/submit` returns a small self-contained HTML guide (auth headers, JSON schema, curl example, wiki link) per the wiki spec.
- **AQI enrichment** (V9): EPA-style AQI computed at ingest into nullable `aqi` + `aqi_pollutant` — truncation → interpolation over breakpoint tables (registry covers all six EPA pollutants) → max sub-index. Fed by PM2.5/PM10 24 h trailing means and NOx as an NO2 1 h-mean ppb proxy (documented approximation). NULL unless ≥3 sub-indices incl. one PM — only full-profile readings qualify.
- **Migrations V4→V9**: artifacts table (V4), view-label renames (V5, V7), slugs (V6), station-view collapse (V8), AQI columns (V9). V3 seed is conditional on the `seed_mock_installations` Flyway placeholder.
- **Env contract** (compose → app): `MDW_DB_*`, `MDW_SERVER_PORT`, `MDW_GRAFANA_SYNC_ENABLED`, `MDW_GRAFANA_URL` (`http://grafana:3000`), `MDW_GRAFANA_PUBLIC_URL` (`https://airbox.alacrity.ro/g`), `MDW_GRAFANA_USER`/`MDW_GRAFANA_PASSWORD` (admin basic auth), `MDW_PUBLIC_TOKEN_SECRET` (**required** when sync on — app refuses to boot), `MDW_GRAFANA_MAP_UID`/`MDW_GRAFANA_OVERVIEW_UID`/`MDW_GRAFANA_STATION_UID`, `MDW_SEED_MOCK_INSTALLATIONS`.
- **Renames**: module dir `new-middleware/` → `middleware/`; package `com.cezar.newmiddleware` → `ro.alacrity.airbox.middleware`; groupId `ro.alacrity.airbox`; `MiddlewareApplication`. Test suite ~66 green in a `maven:3-eclipse-temurin-21` container.

### Grafana content

- **Three source dashboards** in the "Templates" folder (pinned folder uid `templates`): geomap (`9f08aae7-…`), `airbox-overview`, `airbox-station` (the former overview + details station dashboards merged in V8; one twin per device, slug kept `abx-details-<dev>` so distributed URLs survived).
- **All display text English**; internal view identifiers English too (`map`/`overview`/`station`, matched between Java constants and dashboard SQL). PM panels use **ppm** units; VOC/NOx index panels unitless; CO₂ ppm.
- **Datasource** pinned uid `airbox-postgres` (file-provisioned, readOnly — the only file-provisioned piece left). Grafana image pinned `13.1.0`, light theme default, Business Forms plugin preinstalled (`GF_INSTALL_PLUGINS`), org home dashboard = geomap (set via API).
- **Navigation**: station twins carry no nav; the two global views have two stat-panel tab buttons (active `#5a6b8c` unlinked, inactive `#2563eb` one-click data link) plus the SQL-driven "Stations" table. Twin titles use the clean source titles (no "(public)" suffix). **Gotchas encoded here**: button links must use root-level paths (`https://airbox.alacrity.ro/public-dashboards/<slug>`, no `/g`) or Grafana's SPA router parses the slug as an access token; stat buttons need a numeric backing query (`SELECT 1`) or they render "No data" with a dead link. Browser-verified via Playwright (container needs `locale="en-US"`).

### Provisioning & repo round-trip

- **API-based provisioning** (file provisioning removed entirely): `infrastructure/scripts/provision-dashboards.py` seeds the sources from the single canonical dir `middleware/grafana/dashboards/` after `docker compose up` (create-if-absent — reruns never clobber UI edits; `--force` pushes repo changes), sets the home dashboard, restarts the middleware to trigger twin sync. API-created dashboards are **unmanaged** → UI edits are durable and flow to twins on the next sync.
- **Export back to repo**: `infrastructure/scripts/export-dashboards.py` (strips `id`, keeps repo `version`; semantic-equality guard → zero formatting churn; round-trip and positive-control verified). Workflow: edit in UI → export → `git diff` → commit.

### Public URL layer / Caddy

- Hardcoded slugs, zero hash dependence in anything user-visible: `/g/public-dashboards/{overview,geomap}` and `/g/public-dashboards/abx-details-<dev>`. Caddy REVERSE-PROXIES the pretty paths (no redirect — the browser stays on the slug URL permanently, so bookmarks are secret-independent): one regex matches both the page path and Grafana's `/g/api/public/dashboards/…` calls, a `forward_auth` subrequest to the middleware's `/internal/slug-token/{slug}` resolves the token per request, the path is rewritten and proxied to Grafana. The whole site block lives in one outer `route{}` — Caddy's directive sort would otherwise rank `handle` above `route` and shadow the proxy. Real 32-hex token URLs still pass to Grafana untouched (old token bookmarks keep working; Grafana rejects non-hex tokens as native slugs — verified, hence this design). Singular `/g/public-dashboard/*` and root-level forms redirect into the canonical pretty path. Browser-verified via Playwright: URL bar keeps the slug through rendering and tab navigation.
- **Landing page**: "Wind Map" design export served at the apex (`templates/landing-index.html`, ansible `copy` not `template` — its JS contains `{{…}}`), links: See the data → geomap slug, Administration area → `/g/login`, read the docs → wiki.
- Stale hand-maintained `ingest.airbox.alacrity.ro` block removed from the main Caddyfile (dead port 9001, conflicted with the managed conf.d entry).

### Mock & data

- Mock profiles: `full` + `sen66_no_raw_voc_nox` (SCD30 retired). Fixed per-device geohashes via `AIRBOX_GEOHASHES` (paired with `AIRBOX_API_KEYS` by index): 001→sxft17ek, 002→sxfscg0b, 003→sxfsd565, 004→sxfs8jdh.
- Deployment switch `AIRBOX_MOCKS_ENABLED` (one `.env` knob): `false` = no mock container (compose profile gate; `COMPOSE_PROFILES=true` is fixed machinery) *and* no V3 seed — a fresh-DB decision.
- All data on this deployment is disposable mock data; several purges/truncates exercised the determinism guarantee (public URLs reproduce bit-identically across wipes).

### Ops / repo

- Git pushes via the write-enabled deploy key `~/.ssh/airbox_deploy_key` (repo-local `core.sshCommand`). The committed `infrastructure/.env` holds `CHANGE-ME` placeholders; the working-tree copy holds real secrets and must never be staged.

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

- ~~Ingest GET help page~~ RESOLVED 2026-07-22: `GET /api/v2/submit` serves the wiki-specified HTML guide.
- ~~Twin title inconsistency~~ RESOLVED 2026-07-22: all twins now inherit their source titles verbatim (overview twin's "(public)" suffix dropped).
- AQI (§17) is computed and stored but not yet shown on any dashboard; the NOx→NO2 proxy assumption should be revisited if real NO2-capable hardware arrives.
- SEN66-without-raw-NOx readings intentionally carry `aqi = NULL` (only 2 of the required ≥3 pollutants).
- Optional determinism items deliberately not shipped (what they mean):
  - **Public-dashboard object-uid pin.** When a dashboard is shared publicly, Grafana creates an internal *public-dashboard object* with its own random ~13-char uid (distinct from both the dashboard uid and the access token). That uid differs after every wipe+redeploy — but it appears in no URL, link, or stored artifact, so the non-determinism is invisible. Pinning it would mean supplying our own uid on the create call, gated on Grafana honoring client-supplied object uids; skipped as zero-benefit risk.
  - **`ensureShare` stray-token reconcile.** When the sync finds an *existing* public share on a twin, it adopts whatever access token that share already has instead of verifying it equals the HMAC-derived token. On any clean deployment this never matters (post-wipe there is no pre-existing share, and shares the sync itself created always carry derived tokens) — but a share created manually, or left by a partial run under a different secret, would be silently kept with its non-derived token. Full reconciliation (compare adopted token to the derivation; delete + recreate on mismatch) needs a `deletePublicDashboard` client method and was judged not worth the extra write path. Consequence to remember: if `MDW_PUBLIC_TOKEN_SECRET` is ever rotated *without* wiping, existing shares keep their old tokens until manually removed.
- All current DB data is mock/disposable; per-device public surfaces are deterministic *given the same device-id set* (`airbox_installations`).
