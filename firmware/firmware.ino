// AirBox V2 solar powered air quality station firmware.
// Target: WeMos/LOLIN S2 mini (ESP32-S2), Arduino framework.
//
// This sketch holds the SENSOR + PAYLOAD code and the cycle skeleton. All of the
// networking, deep sleep, NVS credential storage and the setup-mode hotspot live
// in "airbox_net.h" behind a handful of airbox* functions (airboxBegin /
// airboxBeginConnect / airboxWaitConnected / airboxUpload / airboxSleep) -
// you rarely need to open that file.
//
// Cycle: a small state machine driven by the sleep reason that airboxBegin()
// reports (see the flow diagram in the docs). The SEN66 gas signals need
// minutes of continuous measurement to settle after power-on (datasheet:
// VOC raw < 60 s, NOx raw < 300 s), so a cycle spans two wakes:
//   * Fresh boot or REASON_DEEP_SLEEP: read the DS18B20 guard probe. Temps
//     inside the SEN66 operating range -> sensor rail on, start continuous
//     measurement, airboxSleep(SLEEP_PREHEAT_MS, REASON_PREHEAT_SLEEP) - the
//     rail stays powered through the nap, so the sensor preheats. Temps bad
//     or probe missing -> airboxSleep(SLEEP_SKIP_MS, REASON_DEEP_SLEEP) and
//     retry next wake.
//   * REASON_PREHEAT_SLEEP: the SEN66 has been measuring ~5 min. Measure
//     battery/solar (charging paused CHARGE_SETTLE_MS, radio still off),
//     start WiFi in the background, sample the SEN66, cut the sensor rail,
//     build the JSON, wait for the link (CONNECT_WINDOW_MS cap) and upload,
//     then airboxSleep(SLEEP_MEASURED_MS, REASON_DEEP_SLEEP).
//
// setup() runs runCycle(sleepReason) once; every path inside ends in
// airboxSleep(), which never returns - loop() is never reached.
//
// SETUP MODE: holding the GPIO21 button (wired to GND) for SETUP_HOLD_MS from
// any state starts a SoftAP config page (see airbox_net.h) where SSID /
// password / geohash are edited and saved to NVS.
//
// IDE setup note: this uses the USB CDC serial port. In Arduino IDE set
//   Tools -> USB CDC On Boot -> Enabled
// so the "Serial" object below is the USB console you see in the monitor.

#include <Arduino.h>
#include <DallasTemperature.h>
#include <OneWire.h>
#include <SensirionI2cSen66.h>
#include <Wire.h>

#include "driver/gpio.h"
#include "esp_sleep.h"

#include "config.h"
#include "pins.h"
#include "debug.h"

#include "airbox_net.h"  // WiFi / upload / sleep / NVS creds / setup-mode hotspot

// Raw sentinels reported by the SEN66 while a signal is not (yet) available.
static constexpr uint16_t SEN66_INVALID_U16 = 0xFFFF;
static constexpr int16_t SEN66_INVALID_I16 = 0x7FFF;

// Sleep-reason codes handed to airboxSleep() and reported back by
// airboxBegin() on the next wake (-1 = fresh boot). Must be positive. They
// drive the cycle state machine in runCycle() - see the flow diagram in the
// docs.
constexpr int REASON_DEEP_SLEEP    = 1;  // regular sleep between cycles
constexpr int REASON_PREHEAT_SLEEP = 2;  // SEN66 preheat nap, sensor rail on

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

// GPIO

static void initPins() {
  // The sensor rail and the setup button are configured by airboxBegin(),
  // which must run before this (the rail has to be restored before anything
  // else can glitch it). Everything else is set up here.
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

// DS18B20 guard probe

static bool readGuardTemp(float& outC) {
  OneWire oneWire(PIN_DS18B20);
  DallasTemperature probe(&oneWire);
  probe.begin();

  uint8_t count = probe.getDS18Count();
  LOGD("guard", "DS18B20 devices found on bus: %u", (unsigned)count);
  if (count == 0) {
    LOGE("guard", "no DS18B20 on GPIO%d (check data wire and 4.7k pull-up)",
         (int)PIN_DS18B20);
    return false;
  }

  probe.requestTemperatures();  // blocking conversion (app 750 ms at 12 bit)
  float t = probe.getTempCByIndex(0);
  LOGD("guard", "raw reading: %.4f C", t);

  if (t <= DEVICE_DISCONNECTED_C) {
    LOGE("guard", "read failed (got %.1f C, sensor disconnected?)", t);
    return false;
  }
  // 85.00 C is the DS18B20 power-on / conversion-not-ready default value; it
  // passes this validity check as if real, so it is called out here to make it
  // obvious on the bench. )
  if (t > 84.9f && t < 85.1f) {
    LOGW("guard", "reading is app. 85.00 C, the DS18B20 power-on default (suspect)");
  }

  outC = t;
  LOGI("guard", "temperature: %.2f C", t);
  return true;
}

// SEN66
// Split into start (preheat wake: begin continuous measurement, then nap) and
// read (measurement wake: take the sample). The two run on DIFFERENT wakes,
// so each attaches to the sensor over I2C itself.

static SensirionI2cSen66 g_sen;

// Attach to the SEN66 on the I2C bus. Safe to call more than once.
static void sen66Attach() {
  Wire.begin((int)PIN_I2C_SDA, (int)PIN_I2C_SCL, 100000u);
  g_sen.begin(Wire, SEN66_I2C_ADDR_6B);
}

// Begin continuous measurement so the sensor starts preheating. Returns true
// if the sensor accepted the command; the sample itself is taken by
// readSen66() on the next wake, after the preheat nap.
static bool startSen66() {
  sen66Attach();
  int16_t err = g_sen.startContinuousMeasurement();
  if (err != 0) {
    LOGE("sen66", "startContinuousMeasurement failed: %d", err);
    return false;
  }
  LOGI("sen66", "preheat started (%lu s nap ahead, rail stays on)",
       (unsigned long)(SLEEP_PREHEAT_MS / 1000));
  return true;
}

// Read one sample. Assumes the SEN66 has been in continuous measurement since
// the preheat wake, so data is normally ready on the first attempt; the retry
// loop only covers the rare case where a headline signal still lags.
static bool readSen66(Measurements& m) {
  sen66Attach();  // fresh boot on the measurement wake: reattach I2C first
  uint16_t pm1 = SEN66_INVALID_U16, pm25 = SEN66_INVALID_U16;
  uint16_t pm4 = SEN66_INVALID_U16, pm10 = SEN66_INVALID_U16;
  uint16_t co2 = SEN66_INVALID_U16;
  int16_t hum = SEN66_INVALID_I16, temp = SEN66_INVALID_I16;
  int16_t voc = SEN66_INVALID_I16, nox = SEN66_INVALID_I16;

  // The headline signals (PM, CO2) can still lag by a sample or two; retry
  // briefly before settling for whatever is available. A read error on a later
  // attempt must not discard data already in hand, so success is tracked across
  // attempts (the library leaves the out-params untouched on error).
  bool gotData = false;
  int16_t err;
  for (uint8_t attempt = 0;; ++attempt) {
    err = g_sen.readMeasuredValuesAsIntegers(pm1, pm25, pm4, pm10, hum, temp,
                                             voc, nox, co2);
    LOGD("sen66", "attempt %u: err=%d pm1=%u co2=%u", (unsigned)attempt, err,
         (unsigned)pm1, (unsigned)co2);
    if (err != 0) {
      LOGW("sen66", "read failed on attempt %u: %d", (unsigned)attempt, err);
    } else {
      gotData = true;
      if (pm1 != SEN66_INVALID_U16 && co2 != SEN66_INVALID_U16) break;
    }
    if (attempt >= SEN66_READ_RETRIES) break;
    delay(1000);
  }
  g_sen.stopMeasurement();  // rail power-off follows; stop is best effort

  if (!gotData) {
    LOGE("sen66", "no valid frame after %u attempts",
         (unsigned)(SEN66_READ_RETRIES + 1));
    return false;
  }

  LOGD("sen66", "raw u16: pm1=%u pm25=%u pm4=%u pm10=%u co2=%u", (unsigned)pm1,
       (unsigned)pm25, (unsigned)pm4, (unsigned)pm10, (unsigned)co2);
  LOGD("sen66", "raw i16: hum=%d temp=%d voc=%d nox=%d", hum, temp, voc, nox);

  // Scale factors per datasheet "read measured values"; sentinel -> omitted.
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

  LOGI("sen66", "pm1=%.1f pm2.5=%.1f pm4=%.1f pm10=%.1f ug/m3", m.pm1, m.pm25,
       m.pm4, m.pm10);
  LOGI("sen66", "t=%.2f C rh=%.1f %% voc=%.1f nox=%.1f co2=%ld ppm", m.temp,
       m.hum, m.voc, m.nox, (long)m.co2);
  return true;
}

// Battery / solar

static uint32_t readAdcMv(uint8_t pin) {
  analogReadMilliVolts(pin);  // discard first sample after (re)configuration
  uint32_t sum = 0;
  for (int i = 0; i < 16; ++i) sum += analogReadMilliVolts(pin);
  return sum / 16;
}

static void logChargerStatus() {
  bool s1 = digitalRead(PIN_CHR_STAT1);
  bool s2 = digitalRead(PIN_CHR_STAT2);
  const char* state = s1 ? (s2 ? "idle/done/disabled" : "charging")
                         : (s2 ? "recoverable fault" : "latch-off fault");
  LOGI("power", "charger STAT1=%d STAT2=%d (%s)", s1, s2, state);
}

static void measurePower(Measurements& m) {
  logChargerStatus();

  // Measure with the charger disabled so the battery is unloaded and the
  // solar input is at open-circuit voltage. This stage runs before WiFi boots,
  // so the radio is not loading the system either; the CHARGE_SETTLE_MS (5 s)
  // rest lets the battery voltage relax toward true open-circuit. Charging
  // resumes right after the samples are taken.
  digitalWrite(PIN_CHR_DISABLE, HIGH);
  delay(CHARGE_SETTLE_MS);
  uint32_t batMv = readAdcMv(PIN_SENSE_BAT);
  uint32_t solarMv = readAdcMv(PIN_SENSE_SOLAR);
  digitalWrite(PIN_CHR_DISABLE, LOW);

  LOGD("power", "adc at pin (before divider): bat=%lu mV solar=%lu mV",
       (unsigned long)batMv, (unsigned long)solarMv);
  LOGD("power", "divider ratios: bat x%.2f  solar x%.2f", BAT_DIVIDER_RATIO,
       SOLAR_DIVIDER_RATIO);

  m.vbat = batMv * BAT_DIVIDER_RATIO * VBAT_CAL / 1000.0f;
  m.vsolar = solarMv * SOLAR_DIVIDER_RATIO * VSOLAR_CAL / 1000.0f;
  m.chargePct = socFromVoltage(m.vbat);
  m.sun = m.vsolar >= SUN_SOLAR_THRESHOLD_V;

  LOGI("power", "vbat=%.3f V (%.1f%%)  vsolar=%.2f V  sun=%s (threshold %.1f V)",
       m.vbat, m.chargePct, m.vsolar, m.sun ? "yes" : "no",
       SUN_SOLAR_THRESHOLD_V);
}

// Payload

// appends `,"key":value` for finite values; invalid readings are omitted so
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
  // Station geohash from airbox_net.h (NVS-backed, set via setup mode). The
  // setup portal caps it at 100 chars, so this buffer always fits it whole.
  char geohash[101];
  airboxGetGeohash(geohash, sizeof(geohash));

  String out;
  out.reserve(320);
  out += "{\"geohash\":\"";
  out += geohash;
  out += "\"";
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

// Cycle

//  one glance status table printed at the end of every cycle. Useful on
// the bench to confirm which subsystems produced a reading this pass.
static void logCycleSummary(const Measurements& m) {
#if LOG_LEVEL >= LOG_INFO
  Serial.println();
  Serial.println("----- cycle summary ------");
  Serial.printf("  guard : %s\n", m.guardOk ? "OK" : "-- ");
  Serial.printf("  sen66 : %s\n", m.senOk ? "OK" : "-- ");
  Serial.printf("  vbat  : %.3f V (%.0f%%)\n", m.vbat, m.chargePct);
  Serial.printf("  vsolar: %.2f V (sun=%s)\n", m.vsolar, m.sun ? "yes" : "no");
  // Heap health: on the bench this is the fastest way to spot a per-cycle leak
  // or fragmentation. free = current, min = low-water since boot, max-block =
  // largest single allocation still possible (a TLS handshake needs a big one).
  // If free/max-block trend down every cycle, something is not being released.
  Serial.printf("  heap  : free=%u min=%u max-block=%u\n",
                (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMinFreeHeap(),
                (unsigned)ESP.getMaxAllocHeap());
  Serial.println("-------------------------");
#endif
}

// One pass of the cycle state machine (see the flow diagram in the docs).
// sleepReason comes from airboxBegin(): REASON_PREHEAT_SLEEP means the SEN66
// has been preheating through the nap and it is time to measure; anything
// else (fresh boot, or the regular deep sleep) starts a new cycle with the
// guard check. Every path ends inside airboxSleep() and never returns.
static void runCycle(int sleepReason) {
  if (sleepReason != REASON_PREHEAT_SLEEP) {
    // --- Fresh cycle: guard check, then preheat the SEN66 -------------------
    // The DS18B20 hangs off the always-on rail, so it reads fine with the
    // sensor rail still off.
    LOG_STAGE("GUARD PROBE");
    bool tempsOk;
    float guardC = NAN;
    if (!readGuardTemp(guardC)) {
      tempsOk = false;
      LOGW("sen66", "guard probe unavailable, cannot verify temperature");
    } else if (guardC < SEN66_TEMP_MIN_C || guardC > SEN66_TEMP_MAX_C) {
      tempsOk = false;
      LOGW("sen66", "%.2f C outside operating range %.0f..%.0f C", guardC,
           SEN66_TEMP_MIN_C, SEN66_TEMP_MAX_C);
    } else {
      tempsOk = true;
    }
    if (!tempsOk) {
      // Conservative: the guard exists to protect the sensor. Retry next wake.
      airboxSleep(SLEEP_SKIP_MS, REASON_DEEP_SLEEP);  // never returns
    }

    LOG_STAGE("SEN66 PREHEAT");
    sensorRail(true);
    delay(SENSOR_RAIL_SETTLE_MS);
    startSen66();
    // The rail is ON right now, so airboxSleep() holds it on through the nap:
    // the SEN66 keeps measuring and its gas signals settle. Never returns;
    // the next wake runs the measurement pass below.
    airboxSleep(SLEEP_PREHEAT_MS, REASON_PREHEAT_SLEEP);
  }

  // --- Measurement pass: the SEN66 has been preheating since the last wake --
  Measurements m;

  // Charging is paused for CHARGE_SETTLE_MS so the battery rests unloaded,
  // then sampled, then charging resumes - all before the radio boots.
  LOG_STAGE("BATTERY / SOLAR");
  measurePower(m);

  LOG_STAGE("CONNECT");
  airboxBeginConnect();  // associates in the background while we sample

  m.guardOk = readGuardTemp(m.guardTempC);  // fresh temp for the payload fallback

  LOG_STAGE("SEN66 SAMPLE");
  m.senOk = readSen66(m);
  sensorRail(false);  // measurement done: cut sensor power

  // --- Payload stage -----------
  LOG_STAGE("PAYLOAD");
  String payload = buildPayload(m);
  LOGI("payload", "%s", payload.c_str());

  // --- Upload stage -----------
  LOG_STAGE("POST");
  if (airboxWaitConnected(CONNECT_WINDOW_MS)) {
    airboxUpload(payload.c_str());
  } else {
    LOGW("wifi", "no link: this measurement is not uploaded");
  }

  logCycleSummary(m);
  airboxSleep(SLEEP_MEASURED_MS, REASON_DEEP_SLEEP);  // never returns
}

// Main

void setup() {
  // Boot housekeeping - MUST be the first call, before anything else touches
  // a pin: restores the sensor rail without a glitch, makes the setup button
  // readable, brings up the serial console, loads credentials (NVS), and
  // enters setup mode if the button is held. Configures its own pins.
  // Returns the reason code the board went to sleep with (-1 = fresh boot).
  int sleepReason = airboxBegin();

  initPins();

  LOGI("boot", "previous sleep reason: %d%s", sleepReason,
       sleepReason < 0 ? " (fresh boot)" : "");

  runCycle(sleepReason);  // every path ends in airboxSleep(): never returns
}

void loop() {
  // Never reached: setup() ends in deep sleep.
}
