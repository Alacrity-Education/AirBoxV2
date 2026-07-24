// AirBox V2 user configuration.
// WiFi SSID / password / geohash are provisioned at runtime via setup mode
// (hold the GPIO9 button, or just flash a fresh board) and stored in NVS. The
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
// Deep-sleep cadence. Awake time is subtracted from WAKE_INTERVAL_S to keep an
// approximately fixed wake-to-wake cadence, floored at MIN_SLEEP_S. Production:
// 900 s = a reading roughly every 15 minutes.
constexpr uint32_t WAKE_INTERVAL_S = 900;
constexpr uint32_t MIN_SLEEP_S     = 30;

// Connect + warm-up window. On each wake the WiFi association is kicked off and
// the SEN66 starts warming up together, then the node waits this long. If the
// link is up at the deadline it samples and uploads; otherwise it skips the
// sample/upload and just deep-sleeps the regular cadence, retrying next wake.
// This window doubles as the SEN66 warm-up, so keep it comfortably above the
// sensor's ~10 s warm-up.
constexpr uint32_t CONNECT_WINDOW_MS  = 60000;  // 60 s

constexpr uint32_t WIFI_TIMEOUT_MS       = 8000;   // legacy; unused since CONNECT_WINDOW_MS replaced connectWifi()
constexpr uint32_t PING_TIMEOUT_MS       = 1000;   // per README: 1 s, result ignored
constexpr uint32_t HTTP_CONNECT_TIMEOUT_MS = 5000;
constexpr uint32_t HTTP_IO_TIMEOUT_MS      = 8000;

// SEN66 warm-up. Now provided by the connect window (CONNECT_WINDOW_MS runs the
// sensor's continuous measurement in the background); kept for reference/tuning.
constexpr uint32_t SEN66_WARMUP_MS   = 10000;
constexpr uint8_t  SEN66_READ_RETRIES = 5;  // extra 1 s attempts if data not ready

// Sensor rail settle time before first I2C/1-Wire access. SEN66 needs up to
// 100 ms from power-up until it accepts I2C traffic.
constexpr uint32_t SENSOR_RAIL_SETTLE_MS = 250;

// Charger is disabled this long before sampling battery/solar voltages, so
// the battery voltage is measured unloaded (per README: 200 ms).
constexpr uint32_t CHARGE_SETTLE_MS = 200;

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
// Setup mode (GPIO9 button + SoftAP config page)
// ---------------------------------------------------------------------------
// Hold the GPIO9 button (shared with I2C SCL, wired to GND) this long, from any
// state, to enter setup mode. Runtime SSID/pass/geohash live in NVS; the
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

// Wait up to 3 s after boot so the USB CDC console can re-enumerate and capture
// the full log. Only for bench/bring-up; 0 for a real low-power deployment (no
// 3 s wake penalty). Flip to 1 if you want to watch a unit provision over USB.
#define DEBUG_WAIT_SERIAL 0
