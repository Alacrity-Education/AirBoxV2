// AirBox V2 solar powered air quality station firmware.
// Target: WeMos/LOLIN S2 mini (ESP32-S2), Arduino framework.
//
// This sketch holds the SENSOR + PAYLOAD code and the cycle skeleton. All of the
// networking, deep sleep, NVS credential storage and the setup-mode hotspot live
// in "airbox_net.h" behind five functions (airboxBegin / airboxBeginConnect /
// airboxWaitConnected / airboxUpload / airboxSleep) - you rarely need to open
// that file.
//
// Cycle (one pass through runCycle()):
//   1. At t=0 start the WiFi association (airboxBeginConnect) AND the SEN66
//      warm-up together, so the radio connects while the sensor stabilises. The
//      DS18B20 guard probe is read first and gates the SEN66 (only warmed up if
//      the ambient temperature is inside its operating range).
//   2. airboxWaitConnected() waits a fixed window (CONNECT_WINDOW_MS, 60 s) -
//      WiFi and the SEN66 warm-up overlap during it.
//   3. At the end of the window: connected -> read the SEN66, sample
//      battery/solar, build JSON, airboxUpload(); not connected -> skip it.
//   4. airboxSleep() deep-sleeps the regular cadence either way.
//
// BENCH vs FIELD (see debug.h):
//   * FIELD  (BENCH_MODE commented out): setup() runs runCycle() once, then
//     airboxSleep(). Every stage runs.
//   * BENCH  (BENCH_MODE defined): the board LOOPS runCycle() forever with a
//     short pause and never deep-sleeps, so the USB serial console stays up.
//     Individual stages are toggled with the RUN_* switches in debug.h.
//
// SETUP MODE: holding the GPIO9 button (shared with I2C SCL, wired to GND) for
// SETUP_HOLD_MS from any state starts a SoftAP config page (see airbox_net.h)
// where SSID / password / geohash are edited and saved to NVS.
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
  // Drive SENSOR-POWER high (rail off) before releasing the deep-sleep hold
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

  // GPIO9 is shared: I2C SCL + the setup button (to GND, active low). Configure
  // it as an input up front - the external SCL pull-up holds it high - so the
  // button is readable before I2C is brought up. Wire.begin() re-muxes it later.
  pinMode(PIN_SETUP_BUTTON, INPUT);
}

static void sensorRail(bool on) {
  digitalWrite(PIN_SENSOR_POWER, on ? LOW : HIGH);  // P-FET, active low
}

// Serial wake-up

// On the ESP32-S2 the USB CDC console re-enumerates on every reset. In bench
// mode (or when DEBUG_WAIT_SERIAL is set) wait briefly for the host to attach
// so the first prints are not lost.
static void bringUpSerial() {
  Serial.begin(115200);
#if defined(BENCH_MODE) || DEBUG_WAIT_SERIAL
  uint32_t t0 = millis();
  while (!Serial && millis() - t0 < 3000) delay(10);
  delay(200);
#endif
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
// Split into start (begin the warm-up) and read (take the sample) so the warm-up
// can overlap the WiFi-connect window. One shared instance spans the two calls.

static SensirionI2cSen66 g_sen;

// Begin continuous measurement so the sensor starts warming up. Returns true if
// the sensor accepted the command; the sample itself is taken later by
// readSen66(), after the connect window has provided the warm-up time.
static bool startSen66() {
  Wire.begin((int)PIN_I2C_SDA, (int)PIN_I2C_SCL, 100000u);
  g_sen.begin(Wire, SEN66_I2C_ADDR_6B);

  int16_t err = g_sen.startContinuousMeasurement();
  if (err != 0) {
    LOGE("sen66", "startContinuousMeasurement failed: %d", err);
    return false;
  }
  LOGI("sen66", "warm-up started, overlapping the %lu ms connect window",
       (unsigned long)CONNECT_WINDOW_MS);
  return true;
}

// Read one sample. Assumes startSen66() ran and the sensor warmed up during the
// connect window, so data is normally ready on the first attempt; the retry
// loop only covers the rare case where a headline signal still lags.
static bool readSen66(Measurements& m) {
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

// ppen-circuit voltage to state-of-charge for li-ion cell.
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
  LOGI("power", "charger STAT1=%d STAT2=%d (%s)", s1, s2, state);
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
  String out;
  out.reserve(320);
  out += "{\"geohash\":\"";
  out += g_geohash;  // from airbox_net.h (NVS-backed, config.h default)
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

// One full measurement + report pass. Which stages run is decided at compile
// time by the RUN_* switches in debug.h. The networky steps are the airbox*
// calls (see airbox_net.h); everything else here is the sensor / payload code.
static void runCycle() {
  Measurements m;

  // --- t=0: start WiFi (non-blocking) and the SEN66 warm-up together --------
#if RUN_WIFI
  LOG_STAGE("CONNECT WINDOW");
  airboxBeginConnect();  // WiFi begins associating in the background
#endif

  bool sen66Started = false;
#if RUN_GUARD || RUN_SEN66
  LOG_STAGE("SENSOR RAIL + GUARD PROBE");
  sensorRail(true);
  delay(SENSOR_RAIL_SETTLE_MS);

#if RUN_GUARD
  m.guardOk = readGuardTemp(m.guardTempC);
#else
  LOGW("guard", "stage disabled (RUN_GUARD = 0)");
#endif

#if RUN_SEN66
  bool gateOk;
#if RUN_GUARD
  if (!m.guardOk) {
    gateOk = false;
    LOGW("sen66", "skipped: guard probe unavailable, cannot verify temperature");
  } else if (m.guardTempC < SEN66_TEMP_MIN_C || m.guardTempC > SEN66_TEMP_MAX_C) {
    gateOk = false;
    LOGW("sen66", "skipped: %.2f C outside operating range %.0f..%.0f C",
         m.guardTempC, SEN66_TEMP_MIN_C, SEN66_TEMP_MAX_C);
  } else {
    gateOk = true;
  }
#else
  gateOk = true;
  LOGW("sen66", "RUN_GUARD = 0, temperature gate NOT enforced (bench only)");
#endif  // RUN_GUARD
  if (gateOk) sen66Started = startSen66();  // begins warm-up, no blocking delay
#else
  LOGW("sen66", "stage disabled (RUN_SEN66 = 0)");
#endif  // RUN_SEN66
#endif  // RUN_GUARD || RUN_SEN66

  // --- Wait out the connect + warm-up window --------------------------------
  // WiFi associates in the background while the SEN66 warms up concurrently.
#if RUN_WIFI
  if (!airboxWaitConnected(CONNECT_WINDOW_MS)) {
    // No link this wake: don't sample data we cannot send. Power the sensor
    // down and return; setup() deep-sleeps the regular cadence as usual.
    if (sen66Started) g_sen.stopMeasurement();
#if RUN_GUARD || RUN_SEN66
    sensorRail(false);
#endif
    LOGW("wifi", "no link this wake: skipping sample + upload");
    logCycleSummary(m);
    return;
  }
#else
  // No WiFi in this build: still hold the rail on for the warm-up window so the
  // offline sensor read below is valid.
  uint32_t warmupStart = millis();
  while (millis() - warmupStart < CONNECT_WINDOW_MS) delay(200);
  LOGW("wifi", "stage disabled (RUN_WIFI = 0): running fully offline");
#endif

  // --- Connected: read the warmed-up sensor, then cut the rail --------------
#if RUN_SEN66
  if (sen66Started) {
    LOG_STAGE("SEN66 SAMPLE");
    m.senOk = readSen66(m);
  }
#endif
#if RUN_GUARD || RUN_SEN66
  sensorRail(false);
#endif

  // --- Battery / solar stage -----------
#if RUN_POWER
  LOG_STAGE("BATTERY / SOLAR");
  measurePower(m);
#else
  LOGW("power", "stage disabled (RUN_POWER = 0)");
#endif

  // --- Payload stage -----------
  LOG_STAGE("PAYLOAD");
  String payload = buildPayload(m);
  LOGI("payload", "%s", payload.c_str());

  // --- Upload stage -----------
#if RUN_WIFI
  LOG_STAGE("POST");
  airboxUpload(payload);
#endif

  logCycleSummary(m);
}

// Main

void setup() {
  initPins();
  bringUpSerial();

  LOG_STAGE("BOOT");
  LOGI("boot", "AirBox V2, wakeup cause %d",
       (int)esp_sleep_get_wakeup_cause());

  // Load saved credentials (NVS), and enter setup mode if the GPIO9 button is
  // held. Done before any I2C so a hold-through-reset never touches the bus.
  airboxBegin();

#ifdef BENCH_MODE
  LOGW("boot", "BENCH MODE: looping, no deep sleep, USB console stays up");
  LOGI("boot", "stages -> guard=%d sen66=%d power=%d wifi=%d post=%d", RUN_GUARD,
       RUN_SEN66, RUN_POWER, RUN_WIFI, RUN_POST);
#endif

#ifndef BENCH_MODE
  runCycle();
  airboxSleep();  // never returns
#endif
  // In bench mode setup() falls through to loop().
}

void loop() {
#ifdef BENCH_MODE
  runCycle();
  LOGI("bench", "cycle complete, pausing %lu s (BENCH_LOOP_DELAY_S in debug.h)",
       (unsigned long)BENCH_LOOP_DELAY_S);
  delay(BENCH_LOOP_DELAY_S * 1000UL);
#endif
  // FIELD mode never reaches here: setup() ends in deep sleep.
}
