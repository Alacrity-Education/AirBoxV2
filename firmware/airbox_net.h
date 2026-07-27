// AirBox V2 - networking / config / sleep / setup-mode module.
//
// This is the "hard stuff" pupils do NOT need to read or edit: WiFi, the HTTPS
// upload, deep sleep, NVS credential storage, and the button-triggered setup-mode
// hotspot. Keep this file next to main.ino and `#include "airbox_net.h"` once;
// then call the five airbox* functions from your sketch. Everything else here is
// internal plumbing.
//
// The "boxes", in the order the sketch calls them:
//   airboxBegin() -> int          - boot housekeeping; MUST be the FIRST call
//                                   in setup(), before any other pin is
//                                   touched. Restores the sensor rail without
//                                   a glitch (kept ON when waking out of the
//                                   preheat nap), makes the setup
//                                   button readable, brings up the serial
//                                   console, loads saved credentials, and if
//                                   the GPIO21 button is held, enters setup
//                                   mode. Configures its own pins. Returns
//                                   the sleep reason passed to airboxSleep()
//                                   before this wake, or -1 on a fresh boot.
//   airboxBeginConnect()          - start associating to WiFi (returns at once;
//                                   the radio connects in the background).
//   airboxWaitConnected(windowMs) - block until the link is up, at most
//                                   windowMs (timed from the call itself);
//                                   true as soon as connected, false at the
//                                   timeout.
//   airboxUpload(json)            - POST the JSON C string over HTTPS, radio
//                                   off.
//   airboxSleep(durationMs, reason) - deep sleep durationMs with the sensor
//                                   rail held at its CURRENT level (a
//                                   preheating SEN66 stays powered). reason
//                                   (positive) comes back from airboxBegin()
//                                   on the next wake. Never returns in FIELD;
//                                   in BENCH it pauses briefly and returns.
//   airboxGetGeohash(out, size)   - helper: copy the station geohash (NVS-
//                                   backed, set via setup mode) into a caller
//                                   buffer, always NUL-terminated.
//   socFromVoltage(v)             - helper: Li-ion open-circuit voltage ->
//                                   approximate charge percentage.
//
// THE SLEEP-REASON MECHANISM: the sketch drives its cycle as a small state
// machine (see the flow diagram in AIRBOX_NET.md). Every airboxSleep() call
// tags the sleep with a positive reason code, stored in RTC memory; the next
// wake's airboxBegin() hands the code back, telling the sketch where to
// resume - e.g. "the SEN66 has been preheating, go measure". This module only
// provides the mechanism (reason storage + rail hold through sleep); the flow
// itself lives in firmware.ino.
//
// Credentials (SSID / password / geohash) live in NVS and are changed via setup
// mode; config.h holds only the first-boot defaults. Your buildPayload() gets
// the geohash via airboxGetGeohash().
//
// Include this file exactly once (from main.ino): it *defines* its functions, so
// including it in a second .cpp would duplicate them.

#pragma once

#include <Arduino.h>
#include <DNSServer.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>

#include "driver/gpio.h"
#include "driver/rtc_io.h"
#include "esp_sleep.h"

#include "config.h"
#include "pins.h"
#include "debug.h"

#if __has_include("ping/ping_sock.h")
#include "lwip/ip_addr.h"
#include "ping/ping_sock.h"
#define HAVE_PING_SOCK 1
#endif

// Runtime configuration (NVS-backed; config.h supplies the first-boot defaults).
static Preferences g_prefs;
static String g_ssid;
static String g_pass;
static String g_geohash;  // handed to the sketch via airboxGetGeohash()

// Sleep bookkeeping, kept in RTC memory - the only RAM that survives deep
// sleep. A crash, reset or power loss clears/invalidates it, which correctly
// reads as a fresh boot (sensor rail off, airboxBegin() returns -1).
static RTC_DATA_ATTR int g_sleepReason;   // code passed to airboxSleep()
static RTC_DATA_ATTR bool g_railKeptOn;   // sensor-rail level held through sleep

// True when this boot is a genuine deep-sleep wake (timer, or the setup
// button via ext0). After a crash/reset/reflash the wake cause is undefined.
static bool wokeFromDeepSleep() {
  esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
  return cause == ESP_SLEEP_WAKEUP_TIMER || cause == ESP_SLEEP_WAKEUP_EXT0;
}

// Setup mode never returns (it reboots); forward-declared because the connect
// wait can trigger it mid-cycle.
static void enterSetupMode();

// ---- NVS load / save -------------------------------------------------------
static void loadConfig() {
  g_prefs.begin("airbox", true);  // read-only; missing keys fall back to args
  g_ssid = g_prefs.getString("ssid", WIFI_SSID);
  g_pass = g_prefs.getString("pass", WIFI_PASS);
  g_geohash = g_prefs.getString("geohash", GEOHASH);
  g_prefs.end();
  LOGI("cfg", "ssid=\"%s\" geohash=\"%s\" (pass: %u chars)", g_ssid.c_str(),
       g_geohash.c_str(), (unsigned)g_pass.length());
}

static void saveConfig(const String& ssid, const String& pass,
                       const String& geohash) {
  g_prefs.begin("airbox", false);  // read-write
  g_prefs.putString("ssid", ssid);
  g_prefs.putString("pass", pass);
  g_prefs.putString("geohash", geohash);
  g_prefs.end();
  LOGI("cfg", "saved ssid=\"%s\" geohash=\"%s\"", ssid.c_str(),
       geohash.c_str());
}

// ---- Setup button (GPIO21, momentary to GND -> active low) ------------------
static bool buttonDown() {
  return digitalRead(PIN_SETUP_BUTTON) == LOW;
}

// Block while the button stays down, up to SETUP_HOLD_MS. Returns true only if it
// was held the whole time; false if not pressed or released early.
static bool setupButtonHeld() {
  if (!buttonDown()) return false;
  LOGW("setup", "button down: hold %lu s to enter setup mode",
       (unsigned long)(SETUP_HOLD_MS / 1000));
  uint32_t start = millis();
  while (millis() - start < SETUP_HOLD_MS) {
    if (!buttonDown()) {
      LOGI("setup", "released after %lu ms; staying in normal mode",
           (unsigned long)(millis() - start));
      return false;
    }
    delay(100);
  }
  LOGW("setup", "hold complete -> entering setup mode");
  return true;
}

// ---- WiFi ------------------------------------------------------------------
// WiFi.begin() is non-blocking: the driver associates on its own task while the
// caller does other work (here, warming up the SEN66 and waiting the window).
static void wifiBegin() {
  LOGI("wifi", "association started for SSID \"%s\"", g_ssid.c_str());
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.begin(g_ssid.c_str(), g_pass.c_str());
}

// Release all WiFi/TLS/socket state and power the radio down.
static void wifiOff() {
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
}

// Block until the link is up, at most windowMs (timed from entry into this
// function); returns as soon as the connection succeeds. delay() yields, so
// the WiFi task keeps associating underneath. A held setup button during the
// wait drops into setup mode (never returns).
static bool waitConnectWindow(uint32_t windowMs) {
  uint32_t waitStart = millis();
  uint32_t pressStart = 0;
  while (millis() - waitStart < windowMs) {
    if (buttonDown()) {
      if (pressStart == 0) pressStart = millis();
      else if (millis() - pressStart >= SETUP_HOLD_MS) enterSetupMode();
    } else {
      pressStart = 0;
    }
    if (WiFi.status() == WL_CONNECTED) {
      LOGI("wifi", "connected after %lu ms  ip=%s  rssi=%d dBm",
           (unsigned long)(millis() - waitStart),
           WiFi.localIP().toString().c_str(), WiFi.RSSI());
      return true;
    }
    delay(200);
  }
  LOGW("wifi", "not connected within %lu ms (status=%d)",
       (unsigned long)windowMs, (int)WiFi.status());
  return false;
}

// ---- Upload ----------------------------------------------------------------
// One ICMP echo to the ingest host; the result is deliberately ignored (it just
// pre-warms DNS/ARP/NAT along the path).
static void pingIngest() {
#ifdef HAVE_PING_SOCK
  IPAddress ip;
  if (WiFi.hostByName(INGEST_HOST, ip) != 1) {
    LOGW("ping", "DNS lookup failed for %s", INGEST_HOST);
    return;
  }
  LOGD("ping", "%s resolved to %s", INGEST_HOST, ip.toString().c_str());

  ip_addr_t target;
  if (!ipaddr_aton(ip.toString().c_str(), &target)) return;

  esp_ping_config_t cfg = ESP_PING_DEFAULT_CONFIG();
  cfg.target_addr = target;
  cfg.count = 1;
  cfg.timeout_ms = PING_TIMEOUT_MS;

  esp_ping_handle_t ping;
  if (esp_ping_new_session(&cfg, nullptr, &ping) != ESP_OK) return;
  esp_ping_start(ping);
  delay(PING_TIMEOUT_MS + 200);
  esp_ping_stop(ping);
  esp_ping_delete_session(ping);
  LOGI("ping", "sent 1 echo to %s", ip.toString().c_str());
#else
  LOGW("ping", "ping_sock unavailable, skipped");
#endif
}

static bool postData(const char* payload) {
  WiFiClientSecure client;
#ifdef INGEST_ROOT_CA
  client.setCACert(INGEST_ROOT_CA);
  LOGI("post", "TLS: verifying server certificate against pinned root CA");
#else
  client.setInsecure();  // TLS without certificate verification
  LOGW("post", "TLS: server certificate NOT verified (INGEST_ROOT_CA undefined)");
#endif
  // Bound the TLS handshake (argument in seconds); the library default of 120 s
  // is not covered by the connect/IO timeouts below and would keep the node
  // awake if a server accepts TCP but stalls mid-handshake.
  client.setHandshakeTimeout(HTTP_IO_TIMEOUT_MS / 1000);

  HTTPClient http;
  http.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
  http.setTimeout(HTTP_IO_TIMEOUT_MS);
  if (!http.begin(client, INGEST_URL)) {
    LOGE("post", "http.begin failed for %s", INGEST_URL);
    return false;
  }
  http.addHeader("Authorization", "ApiKey " API_KEY);
  http.addHeader("Content-Type", "application/json");

  size_t payloadLen = strlen(payload);
  LOGI("post", "POST %s (%u bytes)", INGEST_URL, (unsigned)payloadLen);
  int code = http.POST((uint8_t*)payload, payloadLen);
#if LOG_LEVEL >= LOG_DEBUG
  String body = http.getString();
  LOGD("post", "response body: %s", body.c_str());
#endif
  http.end();

  if (code >= 200 && code < 300) {
    LOGI("post", "HTTP %d (accepted)", code);
    return true;
  }
  LOGE("post", "HTTP %d (rejected)", code);
  return false;
}

// ---- Setup mode (SoftAP + web config page) ---------------------------------
// Escape a value for safe inclusion inside a double-quoted HTML attribute.
static String htmlAttr(const String& s) {
  String o;
  o.reserve(s.length() + 8);
  for (size_t i = 0; i < s.length(); ++i) {
    char c = s[i];
    switch (c) {
      case '&': o += "&amp;"; break;
      case '<': o += "&lt;"; break;
      case '>': o += "&gt;"; break;
      case '"': o += "&quot;"; break;
      case '\'': o += "&#39;"; break;
      default: o += c;
    }
  }
  return o;
}

// Host a SoftAP + config page to edit SSID / password / geohash, save to NVS,
// and reboot. Never returns.
static void enterSetupMode() {
  LOG_STAGE("SETUP MODE");
  wifiOff();
  // Can be entered straight from airboxBegin(), before the sketch's initPins()
  // has run, so configure the pins written here (the rail is already an
  // output; airboxBegin() sets it up first thing).
  pinMode(PIN_CHR_DISABLE, OUTPUT);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_CHR_DISABLE, LOW);    // charge normally during setup mode
  digitalWrite(PIN_SENSOR_POWER, HIGH);  // rail off (sensorRail() lives in the
                                         // sketch; drive the pin directly here)
  digitalWrite(PIN_LED, HIGH);           // solid LED = in setup mode

  WiFi.persistent(false);
  WiFi.mode(WIFI_AP);
  WiFi.softAP(SETUP_AP_SSID, SETUP_AP_PASS);
  IPAddress ip = WiFi.softAPIP();
  LOGW("setup", "AP \"%s\" (pass \"%s\") up -> http://%s/", SETUP_AP_SSID,
       SETUP_AP_PASS, ip.toString().c_str());

  static WebServer server(80);
  static DNSServer dns;
  dns.start(53, "*", ip);  // captive portal: every lookup resolves to the AP

  server.on("/", HTTP_GET, [&]() {
    String h;
    h.reserve(1200);
    h += "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>";
    h += "<title>AirBox Setup</title>";
    h += "<body style='font-family:sans-serif;max-width:440px;margin:24px auto;padding:0 14px'>";
    h += "<h2>AirBox Setup</h2><form method='POST' action='/save'>";
    h += "<p>WiFi SSID<br><input name=ssid maxlength=100 style='width:100%' value=\"";
    h += htmlAttr(g_ssid);
    h += "\"></p><p>WiFi password<br><input name=pass maxlength=100 style='width:100%' value=\"";
    h += htmlAttr(g_pass);
    h += "\"></p><p>Geohash<br><input name=geohash maxlength=100 style='width:100%' value=\"";
    h += htmlAttr(g_geohash);
    h += "\"></p><p><button style='padding:9px 18px'>Save &amp; reboot</button></p></form>";
    h += "<hr><form method=GET action='/exit'>";
    h += "<button style='padding:9px 18px'>Leave setup mode</button></form>";
    server.send(200, "text/html", h);
  });

  server.on("/save", HTTP_POST, [&]() {
    String ssid = server.arg("ssid");
    String pass = server.arg("pass");
    String geo = server.arg("geohash");
    if (ssid.length() == 0 || ssid.length() > 100 || geo.length() == 0 ||
        geo.length() > 100 || pass.length() > 100) {
      server.send(400, "text/html",
                  "<meta name=viewport content='width=device-width'>"
                  "Invalid: SSID and geohash are required, max 100 chars each. "
                  "<a href='/'>back</a>");
      return;
    }
    saveConfig(ssid, pass, geo);
    server.send(200, "text/html",
                "<meta name=viewport content='width=device-width'>"
                "<h3>Saved.</h3><p>Rebooting into normal mode&hellip;</p>");
    delay(600);
    ESP.restart();
  });

  server.on("/exit", HTTP_GET, [&]() {
    server.send(200, "text/html",
                "<meta name=viewport content='width=device-width'>"
                "<h3>Leaving setup mode.</h3><p>Rebooting&hellip;</p>");
    delay(600);
    ESP.restart();
  });

  server.onNotFound([&]() {  // push captive-portal probes to the form
    server.sendHeader("Location",
                      String("http://") + WiFi.softAPIP().toString() + "/");
    server.send(302, "text/plain", "");
  });

  server.begin();
  LOGI("setup", "config server up; edit values or press \"Leave setup mode\"");

  for (;;) {  // <-- the setup-mode blocking loop; exits only via ESP.restart()
    dns.processNextRequest();
    server.handleClient();
    delay(2);
  }
}

// ---- Deep sleep ------------------------------------------------------------
// Common deep-sleep entry. The sensor rail is held at whatever level the
// sketch left it: ON through the preheat nap (the SEN66 keeps measuring on
// its own, no I2C needed), OFF through the between-cycles sleeps. Either way
// the pin is actively held, otherwise R12 pulls the P-FET gate low and the
// pull-down would decide the rail state. Never returns.
static void deepSleep(uint32_t seconds) {
  wifiOff();

  // Can run before the sketch's initPins() (e.g. a cycle that goes back to
  // sleep straight from boot), so configure these two before writing them.
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_CHR_DISABLE, OUTPUT);
  digitalWrite(PIN_LED, LOW);
  digitalWrite(PIN_CHR_DISABLE, LOW);  // floats low in sleep: charging enabled

  // On arduino-esp32 an OUTPUT pin keeps its input buffer enabled, so the
  // rail's current level can be read back and remembered for the wake-up
  // restore in airboxBegin().
  g_railKeptOn = (digitalRead(PIN_SENSOR_POWER) == LOW);
  gpio_hold_en((gpio_num_t)PIN_SENSOR_POWER);
  gpio_deep_sleep_hold_en();

  Serial.flush();

  esp_sleep_enable_timer_wakeup((uint64_t)seconds * 1000000ULL);
  // Also wake if the setup button is pressed. GPIO21 is an RTC pad on the S2,
  // so ext0 can watch it through deep sleep; on wake, setup()'s hold check
  // decides whether it was a real 15 s hold or a stray press. The button pin
  // has no external pull-up and the normal (digital) pull-ups are powered down
  // in deep sleep, so enable the RTC-domain pull-up to keep the line high (the
  // ext0 wake source keeps the RTC domain alive).
  rtc_gpio_pullup_en((gpio_num_t)PIN_SETUP_BUTTON);
  rtc_gpio_pulldown_dis((gpio_num_t)PIN_SETUP_BUTTON);
  esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_SETUP_BUTTON, 0);  // 0 = wake on low
  esp_deep_sleep_start();
}

// ============================================================================
// Public API - the functions the sketch calls.
// ============================================================================

// 1) Boot housekeeping - MUST be the very first call in setup(), before any
//    other code touches a pin. It configures every pin it uses itself:
//      - restores the sensor rail BEFORE the deep-sleep hold is released, so
//        the rail can never glitch: kept ON when waking out of the warm-up
//        nap (the SEN66 is mid warm-up and must not lose power), OFF on any
//        other boot;
//      - makes the setup button readable (internal pull-up);
//      - brings up the USB serial console (bench/debug builds wait briefly
//        for the host to attach so the boot log is not lost);
//      - loads saved credentials from NVS, entering setup mode if none are
//        stored or if the button is held;
//    Returns the sleep-reason code that was passed to airboxSleep() when the
//    board went to sleep, or -1 when this boot is not a deep-sleep wake
//    (power-on, reset, crash, reflash). The sketch branches its cycle state
//    machine on that value (see the flow diagram in AIRBOX_NET.md). The
//    setup-mode path never returns at all.
static int airboxBegin() {
  bool fromSleep = wokeFromDeepSleep();

  // Sensor rail first, before anything can glitch it: restore the level it
  // was held at through the sleep (kept ON through the preheat nap), or force
  // it OFF on a fresh boot. The level is written while the deep-sleep hold
  // still freezes the pad, then the hold is released - a seamless handover.
  pinMode(PIN_SENSOR_POWER, OUTPUT);
  digitalWrite(PIN_SENSOR_POWER, (fromSleep && g_railKeptOn) ? LOW : HIGH);
  gpio_hold_dis((gpio_num_t)PIN_SENSOR_POWER);
  gpio_deep_sleep_hold_dis();

  // Setup button: momentary to GND, no external pull-up -> internal one. Has
  // to be configured here (the sketch's initPins() runs later) so the hold
  // check below never reads a floating pin.
  pinMode(PIN_SETUP_BUTTON, INPUT_PULLUP);

  // Serial console. On the ESP32-S2 the USB CDC port re-enumerates on every
  // reset; on the bench (or with DEBUG_WAIT_SERIAL) wait briefly for the host
  // to attach so the first prints are not lost.
  Serial.begin(115200);
#if defined(BENCH_MODE) || DEBUG_WAIT_SERIAL
  uint32_t serialT0 = millis();
  while (!Serial && millis() - serialT0 < 3000) delay(10);
  delay(200);
#endif
  LOG_STAGE("BOOT");
  LOGI("boot", "AirBox V2, wakeup cause %d", (int)esp_sleep_get_wakeup_cause());

  loadConfig();
#if RUN_WIFI
  // A freshly flashed board has empty NVS and empty config.h defaults, so no
  // SSID -> boot straight into setup mode to be provisioned over the hotspot.
  // (Gated on RUN_WIFI so an offline bench build doesn't force setup.)
  if (g_ssid.length() == 0) {
    LOGW("setup", "no WiFi credentials configured -> entering setup mode");
    enterSetupMode();  // never returns (reboots)
  }
#endif
  if (setupButtonHeld()) enterSetupMode();  // never returns (reboots)

  // Waking from deep sleep -> report the reason code stored by airboxSleep();
  // anything else is a fresh boot.
  return (fromSleep && g_sleepReason > 0) ? g_sleepReason : -1;
}

// 2) Kick off the WiFi association (non-blocking; the radio associates on its
//    own task while the sketch does other work). Call after the power stage -
//    the battery must be measured while the radio is still off.
static void airboxBeginConnect() {
  wifiBegin();
}

// 3) Block until the link is up, at most windowMs (timed from this call
//    itself); returns true as soon as the connection succeeds, false at the
//    timeout. On failure the radio is powered down for you. A held setup
//    button during the wait jumps to setup mode.
static bool airboxWaitConnected(uint32_t windowMs) {
  bool connected = waitConnectWindow(windowMs);
  if (!connected) wifiOff();  // no link -> drop the radio; caller just sleeps
  return connected;
}

// 4) Send the JSON payload (plain C string): ping the host, POST over HTTPS,
//    radio off. Returns true on a 2xx. Honors RUN_POST (debug.h).
static bool airboxUpload(const char* json) {
  pingIngest();
#if RUN_POST
  bool ok = postData(json);
#else
  LOGW("post", "RUN_POST = 0: payload built and shown above but NOT sent");
  bool ok = false;
#endif
  wifiOff();
  return ok;
}

// 5) Deep sleep for sleepDurationMs, holding the sensor rail at its CURRENT
//    level (so a preheating SEN66 stays powered through the nap, and an idle
//    rail stays off). sleepReason must be positive; it is stored in RTC
//    memory and handed back by airboxBegin() on the next wake, which is how
//    the sketch knows where to resume its cycle. Also arms the setup-button
//    wake. Never returns in FIELD mode; in BENCH mode there is no deep sleep,
//    so it logs, pauses BENCH_LOOP_DELAY_S and RETURNS - the bench build runs
//    the same state machine with pretend sleeps.
static void airboxSleep(uint32_t sleepDurationMs, int sleepReason) {
  if (sleepReason <= 0) {
    LOGE("sleep", "sleepReason must be positive, got %d; using 1", sleepReason);
    sleepReason = 1;
  }
  g_sleepReason = sleepReason;
#ifdef BENCH_MODE
  LOGW("sleep", "BENCH: would deep-sleep %lu s (reason %d); pausing %lu s instead",
       (unsigned long)(sleepDurationMs / 1000), sleepReason,
       (unsigned long)BENCH_LOOP_DELAY_S);
  delay(BENCH_LOOP_DELAY_S * 1000UL);
#else
  uint32_t sleepS = sleepDurationMs / 1000;
  if (sleepS == 0) sleepS = 1;
  LOGI("sleep", "deep sleeping %lu s (reason %d)", (unsigned long)sleepS,
       sleepReason);
  deepSleep(sleepS);
#endif
}

// Helper: copy the station geohash (NVS-backed, edited via setup mode) into
// the caller-supplied buffer. Always NUL-terminated; truncated if the buffer
// is too small. outSize is the FULL buffer size in bytes (the setup portal
// caps the geohash at 100 chars, so 101 bytes always fits it whole).
static void airboxGetGeohash(char* out, size_t outSize) {
  if (out == nullptr || outSize == 0) return;
  size_t n = g_geohash.length();
  if (n >= outSize) n = outSize - 1;
  memcpy(out, g_geohash.c_str(), n);
  out[n] = '\0';
}

// Helper: open-circuit battery voltage -> state of charge (%) for a single
// Li-ion cell, linearly interpolated over a typical discharge curve. Used by
// the sketch's measurePower(). Approximate by nature: the curve is generic
// and the cell only rests CHARGE_SETTLE_MS before being sampled.
static float socFromVoltage(float v) {
  static const struct {
    float v, soc;
  } curve[] = {{3.00f, 0}, {3.30f, 2},  {3.45f, 5},  {3.60f, 15}, {3.70f, 30},
               {3.75f, 40}, {3.80f, 50}, {3.85f, 60}, {3.90f, 70}, {3.95f, 78},
               {4.00f, 85}, {4.10f, 94}, {4.20f, 100}};
  constexpr size_t n = sizeof(curve) / sizeof(curve[0]);
  if (v <= curve[0].v) return 0.0f;
  if (v >= curve[n - 1].v) return 100.0f;
  for (size_t i = 1; i < n; ++i) {
    if (v < curve[i].v) {
      float f = (v - curve[i - 1].v) / (curve[i].v - curve[i - 1].v);
      return curve[i - 1].soc + f * (curve[i].soc - curve[i - 1].soc);
    }
  }
  return 100.0f;
}
