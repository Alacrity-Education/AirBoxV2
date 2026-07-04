// AirBox V2 user configuration.
// Fill in the credentials below before flashing. Avoid committing real
// credentials: `git update-index --skip-worktree include/config.h`
#pragma once

#include <stdint.h>

// ---------------------------------------------------------------------------
// Credentials / identity
// ---------------------------------------------------------------------------
#define WIFI_SSID "CHANGE_ME"
#define WIFI_PASS "CHANGE_ME"

// Sent as "Authorization: ApiKey <API_KEY>".
#define API_KEY "XXXXXXXXXX"

// Station location, hardcoded per deployment.
#define GEOHASH "CHANGE_ME"

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
// Target interval between measurement cycles. Time spent awake is subtracted
// from the deep-sleep duration to keep an approximately fixed cadence.
constexpr uint32_t WAKE_INTERVAL_S = 600;
constexpr uint32_t MIN_SLEEP_S     = 30;

constexpr uint32_t WIFI_TIMEOUT_MS       = 8000;   // per README: give up after 8 s
constexpr uint32_t PING_TIMEOUT_MS       = 1000;   // per README: 1 s, result ignored
constexpr uint32_t HTTP_CONNECT_TIMEOUT_MS = 5000;
constexpr uint32_t HTTP_IO_TIMEOUT_MS      = 8000;

// SEN66 warm-up before reading (per README: wait 10 seconds, then measure).
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
// Debug helpers
// ---------------------------------------------------------------------------
// Keep the LED on while the board is awake (disable for production to save
// a little power).
#define STATUS_LED 1

// Wait 3 s after boot so the USB CDC console can enumerate and capture the
// full log. Only for bench bring-up; keep 0 in the field.
#define DEBUG_WAIT_SERIAL 0
