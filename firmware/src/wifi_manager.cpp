// AirBox V2 — WiFi connection manager with captive-portal provisioning.
// See include/wifi_manager.h for the module overview and the connect/portal
// flow. This translation unit compiles standalone: it depends only on the
// ESP32 Arduino core (WiFi/WebServer/DNSServer/Preferences), no third-party
// libraries, so the same source builds under the Arduino IDE.

#include "wifi_manager.h"

#include <Arduino.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

static constexpr uint32_t CONNECT_TIMEOUT_MS = 20000;  // STA join budget
static constexpr uint16_t DNS_PORT = 53;
static constexpr uint16_t HTTP_PORT = 80;

// Time held after sending the "saved" page so it reaches the browser before
// the reboot tears the TCP connection down.
static constexpr uint32_t RESTART_FLUSH_MS = 1500;

static const char* AP_SSID = "AirBox-Setup";  // open network (no password)

// NVS namespace and keys. The namespace name is capped at 15 chars by NVS.
static const char* NVS_NAMESPACE = "wificfg";
static const char* KEY_SSID = "ssid";
static const char* KEY_PASS = "pass";
static const char* KEY_GEOHASH = "geohash";

// ---------------------------------------------------------------------------
// Portal state
// ---------------------------------------------------------------------------
// The WebServer callbacks take no arguments, so the objects and the state they
// touch are file-local. storedSsid/storedGeohash hold the current values for
// prefilling the form; portalDone is raised by the /retry handler to break the
// portal loop.

static WebServer server(HTTP_PORT);
static DNSServer dnsServer;
static bool portalDone = false;
static String storedSsid;
static String storedGeohash;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static String htmlEscape(const String& in) {
  String out;
  out.reserve(in.length() + 8);
  for (unsigned int i = 0; i < in.length(); ++i) {
    char c = in[i];
    switch (c) {
      case '&': out += "&amp;"; break;
      case '<': out += "&lt;"; break;
      case '>': out += "&gt;"; break;
      case '"': out += "&quot;"; break;
      case '\'': out += "&#39;"; break;
      default: out += c; break;
    }
  }
  return out;
}

static String buildConfigPage() {
  String ssid = htmlEscape(storedSsid);
  String geohash = htmlEscape(storedGeohash);

  String page;
  page.reserve(1024);
  page += "<!DOCTYPE html><html><head><meta charset=\"utf-8\">";
  page += "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">";
  page += "<title>AirBox Setup</title></head><body>";
  page += "<h2>AirBox WiFi Setup</h2>";
  page += "<form method=\"POST\" action=\"/save\">";
  page += "<p>WiFi SSID:<br><input type=\"text\" name=\"ssid\" value=\"";
  page += ssid;
  page += "\"></p>";
  // Password is never echoed back to the client.
  page += "<p>WiFi password:<br><input type=\"password\" name=\"pass\" value=\"\"></p>";
  page += "<p>Geohash:<br><input type=\"text\" name=\"geohash\" value=\"";
  page += geohash;
  page += "\"></p>";
  page += "<p><button type=\"submit\">Save &amp; restart</button> ";
  // formnovalidate lets "Try again" post even when SSID is empty.
  page += "<button type=\"submit\" formaction=\"/retry\" formnovalidate>";
  page += "Try again without saving</button></p>";
  page += "</form></body></html>";
  return page;
}

// ---------------------------------------------------------------------------
// STA connect
// ---------------------------------------------------------------------------

static bool tryConnect(const String& ssid, const String& pass) {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), pass.c_str());

  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED &&
         millis() - start < CONNECT_TIMEOUT_MS) {
    delay(50);
  }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[wifi] connect timeout");
    WiFi.disconnect(true);  // tear down STA before the portal takes over
    WiFi.mode(WIFI_OFF);
    return false;
  }
  Serial.printf("[wifi] connected, ip=%s rssi=%d\n",
                WiFi.localIP().toString().c_str(), WiFi.RSSI());
  return true;
}

// ---------------------------------------------------------------------------
// Portal HTTP handlers
// ---------------------------------------------------------------------------

static void handleRoot() {
  server.send(200, "text/html", buildConfigPage());
}

static void handleSave() {
  String ssid = server.arg("ssid");
  String pass = server.arg("pass");
  String geohash = server.arg("geohash");

  if (ssid.length() == 0) {
    Serial.println("[wifi] save rejected: empty SSID");
    server.send(400, "text/html",
                "<p>SSID must not be empty. <a href=\"/\">Back</a></p>");
    return;
  }

  Preferences prefs;
  prefs.begin(NVS_NAMESPACE, false);
  prefs.putString(KEY_SSID, ssid);
  prefs.putString(KEY_PASS, pass);
  prefs.putString(KEY_GEOHASH, geohash);
  prefs.end();

  Serial.printf("[wifi] saved credentials for '%s', restarting\n",
                ssid.c_str());
  server.send(200, "text/html", "<p>Saved - rebooting...</p>");
  // Let the response reach the browser before the reboot drops the socket.
  delay(RESTART_FLUSH_MS);
  ESP.restart();
}

static void handleRetry() {
  Serial.println("[wifi] retry requested");
  server.send(200, "text/html", "<p>Retrying...</p>");
  portalDone = true;
}

static void handleNotFound() {
  // Redirect everything to the portal root so the OS captive-portal probe
  // (which requests an unknown URL) pops the setup page.
  String url = "http://";
  url += WiFi.softAPIP().toString();
  url += "/";
  server.sendHeader("Location", url, true);
  server.send(302, "text/plain", "");
}

// ---------------------------------------------------------------------------
// Captive portal
// ---------------------------------------------------------------------------

static void runPortal() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID);  // open AP: single argument means no password
  delay(100);            // let the AP interface come up before reading its IP
  IPAddress apIp = WiFi.softAPIP();
  Serial.printf("[wifi] setup portal up: SSID '%s' at %s\n", AP_SSID,
                apIp.toString().c_str());

  dnsServer.start(DNS_PORT, "*", apIp);  // wildcard: resolve everything to us

  server.on("/", HTTP_GET, handleRoot);
  server.on("/save", HTTP_POST, handleSave);
  server.on("/retry", HTTP_POST, handleRetry);
  server.onNotFound(handleNotFound);
  server.begin();

  portalDone = false;
  while (!portalDone) {
    dnsServer.processNextRequest();
    server.handleClient();
    delay(5);
  }

  server.stop();
  dnsServer.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
  Serial.println("[wifi] portal closed, retrying stored credentials");
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

void wifiConnectBlocking() {
  for (;;) {
    Preferences prefs;
    prefs.begin(NVS_NAMESPACE, true);  // read-only
    String ssid = prefs.getString(KEY_SSID, "");
    String pass = prefs.getString(KEY_PASS, "");
    storedSsid = ssid;
    storedGeohash = prefs.getString(KEY_GEOHASH, "");
    prefs.end();

    if (ssid.length() > 0) {
      Serial.printf("[wifi] connecting to '%s'\n", ssid.c_str());
      if (tryConnect(ssid, pass)) return;
    } else {
      Serial.println("[wifi] no stored SSID, opening setup portal");
    }

    runPortal();  // returns only when the user picks "Try again"
  }
}

String wifiGetGeohash() {
  Preferences prefs;
  prefs.begin(NVS_NAMESPACE, true);  // read-only
  String geohash = prefs.getString(KEY_GEOHASH, "");
  prefs.end();
  return geohash;
}
