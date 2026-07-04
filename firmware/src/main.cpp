// AirBox V2 — solar powered air quality station firmware.
// Target: WeMos/LOLIN S2 mini (ESP32-S2), Arduino framework.
//
// Cycle (one pass through setup(), then deep sleep):
//   1. Power the sensor rail and read the DS18B20 guard probe. The SEN66 is
//      only operated if the ambient temperature is inside its absolute
//      operating range, otherwise the measurement is skipped.
//   2. SEN66: start continuous measurement, wait 10 s, read one sample, cut
//      sensor power.
//   3. Battery/solar: disable charging, wait 200 ms, sample both ADC dividers
//      (unloaded battery voltage), re-enable charging.
//   4. Post: connect WiFi (8 s budget), ICMP-ping the ingest host once
//      (result ignored), POST the JSON payload over HTTPS, disconnect.
//   5. Deep sleep. SENSOR-POWER (GPIO7) is driven HIGH and RTC-held through
//      sleep — its gate pull-down would otherwise turn the sensor rail back on.

#include <Arduino.h>
#include <DallasTemperature.h>
#include <HTTPClient.h>
#include <OneWire.h>
#include <SensirionI2cSen66.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <Wire.h>

#include "driver/gpio.h"
#include "esp_sleep.h"

#include "config.h"
#include "pins.h"

#if __has_include("ping/ping_sock.h")
#include "lwip/ip_addr.h"
#include "ping/ping_sock.h"
#define HAVE_PING_SOCK 1
#endif

// Raw sentinels reported by the SEN66 while a signal is not (yet) available.
static constexpr uint16_t SEN66_INVALID_U16 = 0xFFFF;
static constexpr int16_t SEN66_INVALID_I16 = 0x7FFF;

struct Measurements {
  // Power stage — always measured.
  float vbat = NAN;
  float vsolar = NAN;
  float chargePct = 0.0f;
  bool sun = false;

  // DS18B20 guard probe.
  bool guardOk = false;
  float guardTempC = NAN;

  // SEN66 — NAN / -1 marks "not measured / not available"; such fields are
  // omitted from the JSON payload.
  bool senOk = false;
  float pm1 = NAN, pm25 = NAN, pm4 = NAN, pm10 = NAN;
  float temp = NAN, hum = NAN, voc = NAN, nox = NAN;
  int32_t co2 = -1;
};

// ---------------------------------------------------------------------------
// GPIO
// ---------------------------------------------------------------------------

static void initPins() {
  // Drive SENSOR-POWER high (rail off) *before* releasing the deep-sleep hold
  // so the rail cannot glitch on between hold release and reconfiguration.
  pinMode(PIN_SENSOR_POWER, OUTPUT);
  digitalWrite(PIN_SENSOR_POWER, HIGH);
  gpio_hold_dis((gpio_num_t)PIN_SENSOR_POWER);
  gpio_deep_sleep_hold_dis();

  pinMode(PIN_CHR_DISABLE, OUTPUT);
  digitalWrite(PIN_CHR_DISABLE, LOW);  // charging enabled

  pinMode(PIN_CHR_STAT1, INPUT_PULLUP);  // open-drain, no external pull-ups
  pinMode(PIN_CHR_STAT2, INPUT_PULLUP);

  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, STATUS_LED ? HIGH : LOW);

  analogSetPinAttenuation(PIN_SENSE_BAT, ADC_11db);
  analogSetPinAttenuation(PIN_SENSE_SOLAR, ADC_11db);
}

static void sensorRail(bool on) {
  digitalWrite(PIN_SENSOR_POWER, on ? LOW : HIGH);  // P-FET, active low
}

// ---------------------------------------------------------------------------
// DS18B20 guard probe
// ---------------------------------------------------------------------------

static bool readGuardTemp(float& outC) {
  OneWire oneWire(PIN_DS18B20);
  DallasTemperature probe(&oneWire);
  probe.begin();
  if (probe.getDS18Count() == 0) {
    Serial.println("[guard] no DS18B20 found");
    return false;
  }
  probe.requestTemperatures();  // blocking conversion (~750 ms at 12 bit)
  float t = probe.getTempCByIndex(0);
  if (t <= DEVICE_DISCONNECTED_C) {
    Serial.println("[guard] DS18B20 read failed");
    return false;
  }
  outC = t;
  Serial.printf("[guard] DS18B20: %.2f C\n", t);
  return true;
}

// ---------------------------------------------------------------------------
// SEN66
// ---------------------------------------------------------------------------

static bool measureSen66(Measurements& m) {
  Wire.begin((int)PIN_I2C_SDA, (int)PIN_I2C_SCL, 100000u);

  SensirionI2cSen66 sen;
  sen.begin(Wire, SEN66_I2C_ADDR_6B);

  int16_t err = sen.startContinuousMeasurement();
  if (err != 0) {
    Serial.printf("[sen66] startContinuousMeasurement failed: %d\n", err);
    return false;
  }

  delay(SEN66_WARMUP_MS);

  uint16_t pm1 = SEN66_INVALID_U16, pm25 = SEN66_INVALID_U16;
  uint16_t pm4 = SEN66_INVALID_U16, pm10 = SEN66_INVALID_U16;
  uint16_t co2 = SEN66_INVALID_U16;
  int16_t hum = SEN66_INVALID_I16, temp = SEN66_INVALID_I16;
  int16_t voc = SEN66_INVALID_I16, nox = SEN66_INVALID_I16;

  // The headline signals (PM, CO2) can lag the 10 s warm-up by a sample or
  // two; retry briefly before settling for whatever is available. A read
  // error on a later attempt must not discard data already in hand, so
  // success is tracked across attempts (the library leaves the out-params
  // untouched on error).
  bool gotData = false;
  for (uint8_t attempt = 0;; ++attempt) {
    err = sen.readMeasuredValuesAsIntegers(pm1, pm25, pm4, pm10, hum, temp,
                                           voc, nox, co2);
    if (err != 0) {
      Serial.printf("[sen66] read failed: %d\n", err);
    } else {
      gotData = true;
      if (pm1 != SEN66_INVALID_U16 && co2 != SEN66_INVALID_U16) break;
    }
    if (attempt >= SEN66_READ_RETRIES) break;
    delay(1000);
  }
  sen.stopMeasurement();  // rail power-off follows; stop is best effort

  if (!gotData) return false;

  // Scale factors per datasheet "Read Measured Values"; sentinel -> omitted.
  if (pm1 != SEN66_INVALID_U16) m.pm1 = pm1 / 10.0f;
  if (pm25 != SEN66_INVALID_U16) m.pm25 = pm25 / 10.0f;
  if (pm4 != SEN66_INVALID_U16) m.pm4 = pm4 / 10.0f;
  if (pm10 != SEN66_INVALID_U16) m.pm10 = pm10 / 10.0f;
  if (hum != SEN66_INVALID_I16) m.hum = hum / 100.0f;
  if (temp != SEN66_INVALID_I16) m.temp = temp / 200.0f;
  // A genuine gas index is 1..500; 0 means "algorithm still initializing".
  if (voc != SEN66_INVALID_I16 && voc > 0) m.voc = voc / 10.0f;
  if (nox != SEN66_INVALID_I16 && nox > 0) m.nox = nox / 10.0f;
  if (co2 != SEN66_INVALID_U16 && co2 != 0) m.co2 = co2;

  Serial.printf(
      "[sen66] pm1=%.1f pm2.5=%.1f pm4=%.1f pm10=%.1f t=%.2f rh=%.1f "
      "voc=%.1f nox=%.1f co2=%ld\n",
      m.pm1, m.pm25, m.pm4, m.pm10, m.temp, m.hum, m.voc, m.nox, (long)m.co2);
  return true;
}

// ---------------------------------------------------------------------------
// Battery / solar
// ---------------------------------------------------------------------------

static uint32_t readAdcMv(uint8_t pin) {
  analogReadMilliVolts(pin);  // discard first sample after (re)configuration
  uint32_t sum = 0;
  for (int i = 0; i < 16; ++i) sum += analogReadMilliVolts(pin);
  return sum / 16;
}

// Open-circuit voltage to state-of-charge for a 1S Li-ion cell.
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

static void logChargerStatus() {
  bool s1 = digitalRead(PIN_CHR_STAT1);
  bool s2 = digitalRead(PIN_CHR_STAT2);
  const char* state = s1 ? (s2 ? "idle/done/disabled" : "charging")
                         : (s2 ? "recoverable fault" : "latch-off fault");
  Serial.printf("[power] charger STAT1=%d STAT2=%d (%s)\n", s1, s2, state);
}

static void measurePower(Measurements& m) {
  logChargerStatus();

  // Measure with the charger disabled so the battery is unloaded and the
  // solar input is at open-circuit voltage.
  digitalWrite(PIN_CHR_DISABLE, HIGH);
  delay(CHARGE_SETTLE_MS);
  uint32_t batMv = readAdcMv(PIN_SENSE_BAT);
  uint32_t solarMv = readAdcMv(PIN_SENSE_SOLAR);
  digitalWrite(PIN_CHR_DISABLE, LOW);

  m.vbat = batMv * BAT_DIVIDER_RATIO * VBAT_CAL / 1000.0f;
  m.vsolar = solarMv * SOLAR_DIVIDER_RATIO * VSOLAR_CAL / 1000.0f;
  m.chargePct = socFromVoltage(m.vbat);
  m.sun = m.vsolar >= SUN_SOLAR_THRESHOLD_V;

  Serial.printf("[power] vbat=%.3f V (%.1f%%) vsolar=%.2f V sun=%d\n", m.vbat,
                m.chargePct, m.vsolar, m.sun);
}

// ---------------------------------------------------------------------------
// Network
// ---------------------------------------------------------------------------

static bool connectWifi() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_TIMEOUT_MS) {
    delay(50);
  }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[wifi] connect timeout");
    return false;
  }
  Serial.printf("[wifi] connected, ip=%s rssi=%d\n",
                WiFi.localIP().toString().c_str(), WiFi.RSSI());
  return true;
}

// One ICMP echo to the ingest host; the result is deliberately ignored (it
// just pre-warms DNS/ARP/NAT along the path).
static void pingIngest() {
#ifdef HAVE_PING_SOCK
  IPAddress ip;
  if (WiFi.hostByName(INGEST_HOST, ip) != 1) {
    Serial.println("[ping] DNS lookup failed");
    return;
  }
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
  Serial.printf("[ping] sent 1 echo to %s\n", ip.toString().c_str());
#else
  Serial.println("[ping] ping_sock unavailable, skipped");
#endif
}

// Appends `,"key":value` for finite values; invalid readings are omitted so
// the backend never receives fabricated zeros.
static void jsonAddFloat(String& out, const char* key, float value,
                         uint8_t decimals) {
  if (isnan(value)) return;
  out += ",\"";
  out += key;
  out += "\":";
  out += String(value, (unsigned int)decimals);
}

static String buildPayload(const Measurements& m) {
  String out;
  out.reserve(320);
  out += "{\"geohash\":\"" GEOHASH "\"";
  jsonAddFloat(out, "charge", m.chargePct, 1);
  out += ",\"sun\":";
  out += m.sun ? "true" : "false";
  if (m.co2 >= 0) {
    out += ",\"co2\":";
    out += (int)m.co2;
  }
  jsonAddFloat(out, "pm1", m.pm1, 1);
  jsonAddFloat(out, "pm25", m.pm25, 1);
  jsonAddFloat(out, "pm4", m.pm4, 1);
  jsonAddFloat(out, "pm10", m.pm10, 1);
  // Prefer the SEN66 temperature; fall back to the guard probe when the
  // SEN66 was skipped (e.g. out of operating range) so "temp" still reports.
  float temp = !isnan(m.temp) ? m.temp : (m.guardOk ? m.guardTempC : NAN);
  jsonAddFloat(out, "temp", temp, 2);
  jsonAddFloat(out, "hum", m.hum, 1);
  jsonAddFloat(out, "voc_index", m.voc, 1);
  jsonAddFloat(out, "nox_index", m.nox, 1);
  out += "}";
  return out;
}

static bool postData(const String& payload) {
  WiFiClientSecure client;
#ifdef INGEST_ROOT_CA
  client.setCACert(INGEST_ROOT_CA);
#else
  client.setInsecure();  // TLS without certificate verification
#endif
  // Bound the TLS handshake (argument in seconds); the library default of
  // 120 s is not covered by the connect/IO timeouts below and would keep the
  // node awake if a server accepts TCP but stalls mid-handshake.
  client.setHandshakeTimeout(HTTP_IO_TIMEOUT_MS / 1000);

  HTTPClient http;
  http.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
  http.setTimeout(HTTP_IO_TIMEOUT_MS);
  if (!http.begin(client, INGEST_URL)) {
    Serial.println("[post] http.begin failed");
    return false;
  }
  http.addHeader("Authorization", "ApiKey " API_KEY);
  http.addHeader("Content-Type", "application/json");

  int code = http.POST((uint8_t*)payload.c_str(), payload.length());
  http.end();

  Serial.printf("[post] HTTP %d\n", code);
  return code >= 200 && code < 300;
}

// ---------------------------------------------------------------------------
// Sleep
// ---------------------------------------------------------------------------

[[noreturn]] static void goToSleep() {
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);

  digitalWrite(PIN_LED, LOW);
  digitalWrite(PIN_CHR_DISABLE, LOW);  // floats low in sleep: charging enabled

  // Keep the sensor rail off while sleeping: hold GPIO7 high through deep
  // sleep, otherwise R12 pulls the P-FET gate low and repowers the sensors.
  digitalWrite(PIN_SENSOR_POWER, HIGH);
  gpio_hold_en((gpio_num_t)PIN_SENSOR_POWER);
  gpio_deep_sleep_hold_en();

  uint32_t elapsedS = millis() / 1000;
  uint32_t sleepS = (elapsedS + MIN_SLEEP_S < WAKE_INTERVAL_S)
                        ? WAKE_INTERVAL_S - elapsedS
                        : MIN_SLEEP_S;
  Serial.printf("[sleep] cycle took %lu s, sleeping %lu s\n",
                (unsigned long)elapsedS, (unsigned long)sleepS);
  Serial.flush();

  esp_sleep_enable_timer_wakeup((uint64_t)sleepS * 1000000ULL);
  esp_deep_sleep_start();
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

void setup() {
  initPins();
  Serial.begin(115200);
#if DEBUG_WAIT_SERIAL
  delay(3000);
#endif
  Serial.printf("\nAirBox V2 boot, wakeup cause %d\n",
                (int)esp_sleep_get_wakeup_cause());

  Measurements m;

  // --- Sensor stage ---
  sensorRail(true);
  delay(SENSOR_RAIL_SETTLE_MS);
  m.guardOk = readGuardTemp(m.guardTempC);
  if (m.guardOk && m.guardTempC >= SEN66_TEMP_MIN_C &&
      m.guardTempC <= SEN66_TEMP_MAX_C) {
    m.senOk = measureSen66(m);
  } else {
    Serial.println(m.guardOk ? "[sen66] skipped: outside operating range"
                             : "[sen66] skipped: guard probe unavailable");
  }
  sensorRail(false);

  // --- Battery / solar stage ---
  measurePower(m);

  // --- Post stage ---
  if (connectWifi()) {
    pingIngest();
    String payload = buildPayload(m);
    Serial.printf("[post] %s\n", payload.c_str());
    postData(payload);
  }

  goToSleep();
}

void loop() {
  // Never reached: setup() ends in deep sleep.
}
