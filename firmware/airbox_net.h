// AirBox V2 - networking / config / sleep / setup-mode module.
//
// This is the "hard stuff" pupils do NOT need to read or edit: WiFi, the HTTPS
// upload, deep sleep, NVS credential storage, and the button-triggered setup-mode
// hotspot. Keep this file next to main.ino and `#include "airbox_net.h"` once;
// then call the five airbox* functions from your sketch. Everything else here is
// internal plumbing.
//
// The five "boxes", in the order the sketch calls them:
//   airboxBegin()                 - boot: load saved credentials, and if the
//                                   GPIO9 button is held, enter setup mode.
//                                   Call once at the top of setup().
//   airboxBeginConnect()          - start associating to WiFi (returns at once).
//   airboxWaitConnected(windowMs) - wait windowMs; returns true if the link came
//                                   up. Start your sensor warm-up *between* these
//                                   two calls so WiFi connects while the sensor
//                                   stabilises (the two overlap for free).
//   airboxUpload(json)            - POST the JSON string over HTTPS, radio off.
//   airboxSleep()                 - deep sleep the regular cadence (+ arm the
//                                   setup-button wake). Never returns in FIELD.
//
// Credentials (SSID / password / geohash) live in NVS and are changed via setup
// mode; config.h holds only the first-boot defaults. Your buildPayload() reads
// the geohash from g_geohash, exposed below.
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
static String g_geohash;         // read by buildPayload() in the sketch
static uint32_t g_connectStart;  // millis() captured by airboxBeginConnect()

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

// ---- Setup button (GPIO9, shared with I2C SCL, wired to GND -> active low) --
// Read straight off the pad with gpio_get_level, which works whether or not the
// I2C driver owns the pin - as long as I2C is idle, which it is everywhere we
// poll. Safe to call at boot before any I2C traffic.
static bool buttonDown() {
  return gpio_get_level((gpio_num_t)PIN_SETUP_BUTTON) == 0;
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

// Hold until windowMs have elapsed since airboxBeginConnect(), then report the
// link state. Always burns the whole window (delay() yields, so the WiFi task
// keeps associating and any SEN66 warm-up runs in parallel). A held setup button
// during the wait drops into setup mode (never returns).
static bool waitConnectWindow(uint32_t windowMs) {
  bool announced = false;
  uint32_t pressStart = 0;
  while (millis() - g_connectStart < windowMs) {
    if (buttonDown()) {
      if (pressStart == 0) pressStart = millis();
      else if (millis() - pressStart >= SETUP_HOLD_MS) enterSetupMode();
    } else {
      pressStart = 0;
    }
    if (!announced && WiFi.status() == WL_CONNECTED) {
      LOGI("wifi", "connected after %lu ms  ip=%s  rssi=%d dBm",
           (unsigned long)(millis() - g_connectStart),
           WiFi.localIP().toString().c_str(), WiFi.RSSI());
      announced = true;
    }
    delay(200);
  }
  bool connected = (WiFi.status() == WL_CONNECTED);
  if (!connected) {
    LOGW("wifi", "not connected at end of %lu ms window (status=%d)",
         (unsigned long)windowMs, (int)WiFi.status());
  }
  return connected;
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

static bool postData(const String& payload) {
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

  LOGI("post", "POST %s (%u bytes)", INGEST_URL, (unsigned)payload.length());
  int code = http.POST((uint8_t*)payload.c_str(), payload.length());
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
// and reboot. Never returns. No I2C or sensor activity here, so GPIO9 sharing the
// SCL line is a non-issue in this mode.
static void enterSetupMode() {
  LOG_STAGE("SETUP MODE");
  wifiOff();
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
static void goToSleep() {
  wifiOff();

  digitalWrite(PIN_LED, LOW);
  digitalWrite(PIN_CHR_DISABLE, LOW);  // floats low in sleep: charging enabled

  // Keep the sensor rail off while sleeping: hold GPIO7 high through deep sleep,
  // otherwise R12 pulls the P-FET gate low and repowers the sensors.
  digitalWrite(PIN_SENSOR_POWER, HIGH);
  gpio_hold_en((gpio_num_t)PIN_SENSOR_POWER);
  gpio_deep_sleep_hold_en();

  uint32_t elapsedS = millis() / 1000;
  uint32_t sleepS = (elapsedS + MIN_SLEEP_S < WAKE_INTERVAL_S)
                        ? WAKE_INTERVAL_S - elapsedS
                        : MIN_SLEEP_S;
  LOGI("sleep", "cycle took %lu s, sleeping %lu s", (unsigned long)elapsedS,
       (unsigned long)sleepS);
  Serial.flush();

  esp_sleep_enable_timer_wakeup((uint64_t)sleepS * 1000000ULL);
  // Also wake if the setup button is pressed. GPIO9 is an RTC pad on the S2, so
  // ext0 can watch it through deep sleep; on wake, setup()'s hold check decides
  // whether it was a real 15 s hold or a stray press.
  esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_SETUP_BUTTON, 0);  // 0 = wake on low
  esp_deep_sleep_start();
}

// ============================================================================
// Public API - the five functions the sketch calls.
// ============================================================================

// 1) Boot: load saved credentials from NVS, and enter setup mode if the button
//    is held. Call once at the top of setup(). Returns immediately on a normal
//    boot; blocks forever (until reboot) if it enters setup mode.
static void airboxBegin() {
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
}

// 2) Kick off the WiFi association (non-blocking) and start the connect clock.
//    Call at t=0, then start your sensor warm-up so the two overlap.
static void airboxBeginConnect() {
  wifiBegin();
  g_connectStart = millis();
}

// 3) Wait out the connect + warm-up window; true if the link came up. On failure
//    the radio is powered down for you. A held setup button jumps to setup mode.
static bool airboxWaitConnected(uint32_t windowMs) {
  bool connected = waitConnectWindow(windowMs);
  if (!connected) wifiOff();  // no link -> drop the radio; caller just sleeps
  return connected;
}

// 4) Send the JSON payload: ping the host, POST over HTTPS, radio off. Returns
//    true on a 2xx. Honors RUN_POST (debug.h).
static bool airboxUpload(const String& json) {
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

// 5) Deep sleep the regular cadence (also arms the setup-button wake). Never
//    returns in FIELD mode; in BENCH mode you would not call this.
static void airboxSleep() {
  goToSleep();
}
