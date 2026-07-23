// AirBox V2 — WiFi connection manager with captive-portal provisioning.
//
// Credentials live in NVS (Preferences namespace "wificfg"), not in config.h,
// so a field unit can be re-pointed at a new network without reflashing.
//
// wifiConnectBlocking() runs the whole provisioning loop and does not return
// until the station is associated:
//   1. Load SSID/pass/geohash from flash.
//   2. Try to join in STA mode for CONNECT_TIMEOUT_MS. On success -> return.
//      An empty stored SSID skips straight to the portal.
//   3. On failure, bring up an open softAP ("AirBox-Setup") with a wildcard
//      DNS server (captive portal) and a config web page on port 80.
//   4. "Save & restart" writes NVS and reboots; "Try again" closes the portal
//      and the loop retries the stored credentials.

#pragma once

#include <Arduino.h>

// Blocks until WiFi is connected. Runs the captive-portal provisioning loop
// (see file header). Only returns on a successful STA connection; the save
// path never returns (the device reboots).
void wifiConnectBlocking();

// Returns the geohash currently stored in flash (empty string if unset).
String wifiGetGeohash();
