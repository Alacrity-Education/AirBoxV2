// AirBox V2 user configuration.
// WiFi SSID / password / geohash are provisioned at runtime via setup mode
// (hold the GPIO21 button, or just flash a fresh board) and stored in NVS. The
// defaults below are intentionally EMPTY, so an unprovisioned board boots
// straight into setup mode. Only API_KEY and the tuning constants are baked in.
#pragma once

#include <stdint.h>

// ---------------------------------------------------------------------------
// Credentials / identity
// ---------------------------------------------------------------------------
// First-boot defaults for the NVS-stored credentials. Keep these EMPTY for
// production: with no saved SSID, a freshly flashed board boots into setup mode
// so WiFi + geohash are entered via the hotspot and never baked into firmware.
#define WIFI_SSID ""
#define WIFI_PASS ""

// Station location (geohash). Also provisioned via setup mode; empty default.
#define GEOHASH ""

// API key, sent as "Authorization: ApiKey <API_KEY>". This one stays a
// compile-time credential (per decision); set it before building.
#define API_KEY "abxkey-147b00650ac7fa80"

// ---------------------------------------------------------------------------
// Ingest endpoint
// ---------------------------------------------------------------------------
#define INGEST_HOST "ingest.airbox.alacrity.ro"
#define INGEST_URL "https://" INGEST_HOST "/api/v2/submit"

// Optional: pin the server's root CA (PEM string). When left undefined the
// TLS connection is encrypted but the certificate is NOT verified.
// #define INGEST_ROOT_CA "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----\n"

// ---------------------------------------------------------------------------
// Timing
// ---------------------------------------------------------------------------
// Sleep durations for the cycle state machine (see the flow diagram in
// AIRBOX_NET.md). A good cycle is: preheat nap (sensor rail kept on so the
// SEN66 gas signals settle - the NOx raw signal needs < 300 s per datasheet
// section 1.4, table 4) + the measurement wake + the post-measurement deep
// sleep -> a reading roughly every 15-16 minutes. When the guard probe blocks
// the SEN66, the node just deep-sleeps SLEEP_SKIP_MS and retries.
constexpr uint32_t SLEEP_PREHEAT_MS  = 5UL * 60UL * 1000UL;   // 5 min, rail on
constexpr uint32_t SLEEP_MEASURED_MS = 10UL * 60UL * 1000UL;  // 10 min
constexpr uint32_t SLEEP_SKIP_MS     = 15UL * 60UL * 1000UL;  // 15 min

// Connect timeout. airboxWaitConnected() blocks until the link is up, at most
// this long (it returns as soon as the connection succeeds). The association
// runs in the background from airboxBeginConnect() on, overlapping the SEN66
// sampling, so most of this budget is rarely used.
constexpr uint32_t CONNECT_WINDOW_MS  = 60000;  // 60 s

constexpr uint32_t WIFI_TIMEOUT_MS       = 8000;   // legacy; unused since CONNECT_WINDOW_MS replaced connectWifi()
constexpr uint32_t PING_TIMEOUT_MS       = 1000;   // per README: 1 s, result ignored
constexpr uint32_t HTTP_CONNECT_TIMEOUT_MS = 5000;
constexpr uint32_t HTTP_IO_TIMEOUT_MS      = 8000;

// SEN66 first-data warm-up. Superseded by the preheat nap above
// (SLEEP_PREHEAT_MS); kept for reference/tuning.
constexpr uint32_t SEN66_WARMUP_MS   = 10000;
constexpr uint8_t  SEN66_READ_RETRIES = 5;  // extra 1 s attempts if data not ready

// Sensor rail settle time before first I2C/1-Wire access. SEN66 needs up to
// 100 ms from power-up until it accepts I2C traffic.
constexpr uint32_t SENSOR_RAIL_SETTLE_MS = 250;

// Charger is disabled this long before sampling battery/solar voltages, so
// the battery rests unloaded and its voltage relaxes toward open-circuit.
// The power stage runs before WiFi boots, so the radio is not loading the
// system during the measurement either.
constexpr uint32_t CHARGE_SETTLE_MS = 2000;  // 2 s

// ---------------------------------------------------------------------------
// Limits & thresholds
// ---------------------------------------------------------------------------
// SEN66 absolute operating range (datasheet table 9). If the DS18B20 guard
// probe reads outside this window the SEN66 is not powered up for measurement.
constexpr float SEN66_TEMP_MIN_C = -10.0f;
constexpr float SEN66_TEMP_MAX_C = 50.0f;

// Solar voltage (unloaded, charger disabled) above which "sun" is reported.
constexpr float SUN_SOLAR_THRESHOLD_V = 3.0f;

// Per-board ADC divider trim, applied on top of the nominal divider ratios
// (calibrate against a multimeter if needed).
constexpr float VBAT_CAL   = 1.0f;
constexpr float VSOLAR_CAL = 1.0f;

// ---------------------------------------------------------------------------
// Setup mode (GPIO21 button + SoftAP config page)
// ---------------------------------------------------------------------------
// Hold the GPIO21 setup button (wired to GND) this long, from any state, to
// enter setup mode. Runtime SSID/pass/geohash live in NVS; the
// credentials above are only the first-boot defaults (used when NVS is empty).
constexpr uint32_t SETUP_HOLD_MS = 15000;   // 15 s

// SoftAP the config page is served on while in setup mode. Password must be >= 8
// characters for WPA2 (or use an empty string "" for an open AP).
#define SETUP_AP_SSID "AirBox-Setup"
#define SETUP_AP_PASS "airbox-setup"

// ---------------------------------------------------------------------------
// Debug helpers
// ---------------------------------------------------------------------------
// Keep the LED on while the board is awake (disable for production to save
// a little power).
#define STATUS_LED 1
