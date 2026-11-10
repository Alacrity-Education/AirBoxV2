// AirBox V2 - DEMO SETUP / reliability test firmware.
//
// Implements the "Demo setup" section of the wiki
// (https://wiki.alacrity.ro/projects/airbox-v2#demo-setup):
//   - Measures and sends data every 10 seconds.
//   - The SEN66 is kept on permanently and measures continuously.
//   - WiFi is kept connected permanently (reconnects on its own if it drops).
//   - Button: short press (<3 s) toggles airbox power,
//             long press (>3 s) enters setup mode (WiFi credentials).
//   - LED: powered on  -> one flash every 4 s, 25% duty (1 s on, 3 s off)
//          powered off -> off
//          setup mode  -> flashes every 500 ms, 50% duty (in airbox_net.h)
//          after each successful send -> 3 quick 300 ms flashes, then back
//          to the powered-on blink.
//
// On top of the spec:
//   - No sleep of any kind; the ESP32 stays awake.
//   - The charger is NEVER disabled (runs from USB, no solar panel needed).
//     The battery is read while charging, so the reported charge reads high.
//   - The SEN66 is never switched off, not even by the power toggle (power
//     "off" is a soft off: no measuring, no sending, WiFi off, LED off - the
//     sensor stays warm so readings resume instantly). The DS18B20 guard
//     temperature is only logged.
//   - Upload counters, WiFi drops and the reset reason are printed on Serial,
//     so every hole in the server data can be traced to a cause. The JSON
//     payload is identical to production.
//
// The .h files in this folder are copies of the ones in firmware/ (Arduino
// only builds headers inside the sketch folder), with two demo changes:
// SETUP_HOLD_MS is 3 s (config.h) and setup mode blinks (airbox_net.h).

#include <Arduino.h>
#include <DallasTemperature.h>
#include <OneWire.h>
#include <SensirionI2cSen66.h>
#include <Wire.h>
#include "esp_system.h"

#include "config.h"
#include "pins.h"
#include "airbox_net.h"

// ---------------------------------------------------------------------------
// Demo timing
// ---------------------------------------------------------------------------
constexpr uint32_t SEND_INTERVAL_MS = 10UL * 1000UL;  // spec: every 10 s
// Readings are held back until the SEN66 has run this long after power-on.
constexpr uint32_t SEN66_WARMUP_AFTER_POWER_MS = SLEEP_PREHEAT_MS;  // 15 s
// If WiFi stays down this long, tear the radio down and associate again.
constexpr uint32_t WIFI_RETRY_MS = 10UL * 1000UL;

// LED patterns (spec)
constexpr uint32_t LED_ON_PERIOD_MS = 4000;  // flashes every 4 s ...
constexpr uint32_t LED_ON_TIME_MS = 1000;    // ... at 25% duty cycle
constexpr uint32_t LED_SENT_FLASH_MS = 300;  // 3 quick 300 ms flashes
constexpr uint8_t LED_SENT_FLASHES = 3;

// Button
constexpr uint32_t BUTTON_DEBOUNCE_MS = 30;
// Long-press threshold is SETUP_HOLD_MS (3 s, config.h).

// ---------------------------------------------------------------------------
OneWire oneWire(PIN_DS18B20);
DallasTemperature probe(&oneWire);
SensirionI2cSen66 sen66;

float batteryVolts, solarVolts, chargePercent;
bool sunOut;
float pm1, pm25, pm4, pm10, temperature, humidity;
uint16_t co2;

char json[1000];

uint32_t sen66OnSince = 0;
uint32_t nextSendAt = 0;

// Shared between the UI task (button + LED) and loop().
volatile bool powered = true;           // boots powered on
volatile bool setupRequested = false;   // long press seen
volatile bool inSetupMode = false;      // UI task leaves the LED alone
volatile uint32_t sentFlashStart = 0;
volatile bool sentFlashActive = false;

// WiFi state (loop() only)
bool wifiWasUp = false;
uint32_t lastWifiBegin = 0;

// Test statistics (Serial only)
uint32_t cycleCount = 0;
uint32_t uploadOk = 0;
uint32_t uploadFail = 0;   // HTTP error while connected
uint32_t wifiFail = 0;     // reading dropped because WiFi was down
uint32_t wifiDrops = 0;    // times an established link was lost
uint32_t sensorFail = 0;   // SEN66 gave no valid data

const char* resetReasonName(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON:   return "power-on";
    case ESP_RST_EXT:       return "external pin";
    case ESP_RST_SW:        return "software (ESP.restart)";
    case ESP_RST_PANIC:     return "PANIC / exception";
    case ESP_RST_INT_WDT:   return "interrupt watchdog";
    case ESP_RST_TASK_WDT:  return "task watchdog";
    case ESP_RST_WDT:       return "other watchdog";
    case ESP_RST_DEEPSLEEP: return "deep sleep wake";
    case ESP_RST_BROWNOUT:  return "BROWNOUT";
    case ESP_RST_SDIO:      return "SDIO";
    default:                return "unknown";
  }
}

// ---------------------------------------------------------------------------
// UI task: button + LED, every 10 ms. Runs separately from loop() so the
// blink timing and button presses stay correct while an HTTPS POST blocks.
// ---------------------------------------------------------------------------
void uiTask(void*) {
  bool lastRaw = buttonDown();
  bool stable = lastRaw;
  uint32_t lastChange = millis();
  uint32_t pressStart = millis();
  // A press already in progress when the task starts (e.g. left over from
  // the boot-time setup check) is ignored until it is released.
  bool pressConsumed = stable;

  for (;;) {
    uint32_t now = millis();

    // --- button (active low, debounced) ---
    bool raw = buttonDown();
    if (raw != lastRaw) {
      lastRaw = raw;
      lastChange = now;
    }
    if (now - lastChange >= BUTTON_DEBOUNCE_MS && raw != stable) {
      stable = raw;
      if (stable) {  // pressed
        pressStart = now;
        pressConsumed = false;
      } else if (!pressConsumed && !inSetupMode) {  // released before 3 s
        powered = !powered;
        Serial.printf("Button: short press -> power %s\n", powered ? "ON" : "OFF");
      }
    }
    if (stable && !pressConsumed && !inSetupMode &&
        now - pressStart >= SETUP_HOLD_MS) {
      pressConsumed = true;  // so the release does not also toggle power
      Serial.println("Button: long press -> setup mode");
      setupRequested = true;
    }

    // --- LED ---
    if (!inSetupMode) {
      bool on;
      if (!powered) {
        on = false;
        sentFlashActive = false;
      } else if (sentFlashActive &&
                 now - sentFlashStart < 2UL * LED_SENT_FLASH_MS * LED_SENT_FLASHES) {
        on = ((now - sentFlashStart) % (2UL * LED_SENT_FLASH_MS)) < LED_SENT_FLASH_MS;
      } else {
        sentFlashActive = false;
        on = (now % LED_ON_PERIOD_MS) < LED_ON_TIME_MS;
      }
      digitalWrite(PIN_LED, on ? HIGH : LOW);
    }

    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

// ---------------------------------------------------------------------------
// Hardware
// ---------------------------------------------------------------------------
void hardwareInit() {
  // Charging always enabled; nothing in this sketch ever drives it HIGH.
  pinMode(PIN_CHR_DISABLE, OUTPUT);
  digitalWrite(PIN_CHR_DISABLE, LOW);

  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, LOW);

  analogSetPinAttenuation(PIN_SENSE_BAT, ADC_11db);
  analogSetPinAttenuation(PIN_SENSE_SOLAR, ADC_11db);

  Wire.begin((int)PIN_I2C_SDA, (int)PIN_I2C_SCL);
  sen66.begin(Wire, SEN66_I2C_ADDR_6B);

  probe.begin();
  // Non-blocking conversions: each cycle reads the result of the request
  // made by the previous cycle (10 s ago), instead of waiting ~750 ms.
  probe.setWaitForConversion(false);
}

// Called once from setup(); the sensor rail is never driven HIGH again.
void sen66PowerOn() {
  digitalWrite(PIN_SENSOR_POWER, LOW);  // Q1 on, sensors get 3.3V
  delay(SENSOR_RAIL_SETTLE_MS);
  sen66.begin(Wire, SEN66_I2C_ADDR_6B);
  sen66.startContinuousMeasurement();
  sen66OnSince = millis();
  Serial.println("SEN66 powered on, continuous measurement started");
}

// Logged only - the sensor is never switched off.
void logGuardTemperature() {
  float t = probe.getTempCByIndex(0);
  bool ok = (t == -127 || (t >= SEN66_TEMP_MIN_C && t <= SEN66_TEMP_MAX_C));
  Serial.printf("Guard temperature: %.2f C%s\n", t,
                ok ? "" : "  (OUT OF SEN66 RANGE, measuring anyway)");
  probe.requestTemperatures();  // start the next conversion
}

float readVoltageAverage(uint8_t pin) {
  analogReadMilliVolts(pin);  // discard
  uint32_t sum = 0;
  for (int i = 0; i < 16; i++) {
    sum += analogReadMilliVolts(pin);
  }
  return (sum / 16.0) / 1000.0;
}

// Same readings as production, but WITHOUT pausing the charger.
void measureBatteryAndSolar() {
  batteryVolts = readVoltageAverage(PIN_SENSE_BAT) * BAT_DIVIDER_RATIO;
  chargePercent = socFromVoltage(batteryVolts);

  solarVolts = readVoltageAverage(PIN_SENSE_SOLAR) * SOLAR_DIVIDER_RATIO;
  sunOut = solarVolts > SUN_SOLAR_THRESHOLD_V;

  Serial.printf("Battery (while charging): %.2f V (%.0f%%), solar %.2f V\n",
                batteryVolts, chargePercent, solarVolts);
}

// One read; the SEN66 updates every second in continuous mode, so a bad read
// is simply retried by the next cycle 10 s later.
bool readSen66Values() {
  uint16_t rawPm1, rawPm25, rawPm4, rawPm10, rawCo2;
  int16_t rawHum, rawTemp, rawVoc, rawNox;
  int16_t err = sen66.readMeasuredValuesAsIntegers(
      rawPm1, rawPm25, rawPm4, rawPm10, rawHum, rawTemp, rawVoc, rawNox, rawCo2);

  if (err != 0 || rawPm1 == 0xFFFF || rawPm25 == 0xFFFF || rawPm4 == 0xFFFF ||
      rawPm10 == 0xFFFF || rawCo2 == 0xFFFF || rawHum == 0x7FFF ||
      rawTemp == 0x7FFF) {
    Serial.printf("SEN66: no valid data (err %d)\n", err);
    return false;
  }

  pm1 = rawPm1 / 10.0;
  pm25 = rawPm25 / 10.0;
  pm4 = rawPm4 / 10.0;
  pm10 = rawPm10 / 10.0;
  humidity = rawHum / 100.0;
  temperature = rawTemp / 200.0;
  co2 = rawCo2;
  return true;
}

// Identical payload to production firmware.
void buildJson() {
  char geohash[101];
  airboxGetGeohash(geohash, sizeof(geohash));

  snprintf(json, sizeof(json),
           "{\"geohash\":\"%s\",\"charge\":%.1f,\"sun\":%s,\"co2\":%u,"
           "\"pm1\":%.1f,\"pm25\":%.1f,\"pm4\":%.1f,\"pm10\":%.1f,"
           "\"temp\":%.2f,\"hum\":%.1f,\"voc_raw\":%u,\"nox_raw\":%u}",
           geohash, chargePercent, sunOut ? "true" : "false", co2,
           pm1, pm25, pm4, pm10, temperature, humidity, 0, 0);

  Serial.printf("Payload: %s\n", json);
}

// ---------------------------------------------------------------------------
// WiFi: kept connected permanently while powered on.
// ---------------------------------------------------------------------------
void startWifi() {
  wifiBegin();  // from airbox_net.h (sets country, STA mode, WiFi.begin)
  lastWifiBegin = millis();
}

void maintainWifi() {
  if (WiFi.status() == WL_CONNECTED) {
    if (!wifiWasUp) {
      wifiWasUp = true;
      LOGI("wifi", "connected  ip=%s  rssi=%d dBm",
           WiFi.localIP().toString().c_str(), WiFi.RSSI());
    }
    return;
  }
  if (wifiWasUp) {
    wifiWasUp = false;
    wifiDrops++;
    lastWifiBegin = millis();  // give the driver's auto-reconnect a chance
    LOGW("wifi", "link lost (status=%d), reconnecting", (int)WiFi.status());
  }
  if (millis() - lastWifiBegin >= WIFI_RETRY_MS) {
    LOGW("wifi", "still not connected, restarting association");
    wifiOff();
    delay(100);
    startWifi();
  }
}

// ---------------------------------------------------------------------------
void printStats() {
  Serial.printf(
      "[stats] cycle=%lu uptime=%lus ok=%lu http_fail=%lu wifi_fail=%lu "
      "wifi_drops=%lu sensor_fail=%lu rssi=%d heap=%lu\n",
      (unsigned long)cycleCount, (unsigned long)(millis() / 1000),
      (unsigned long)uploadOk, (unsigned long)uploadFail,
      (unsigned long)wifiFail, (unsigned long)wifiDrops,
      (unsigned long)sensorFail, wifiWasUp ? WiFi.RSSI() : 0,
      (unsigned long)ESP.getFreeHeap());
}

void doCycle() {
  cycleCount++;
  LOG_STAGE("CYCLE");
  Serial.printf("Cycle %lu, uptime %lu s\n", (unsigned long)cycleCount,
                (unsigned long)(millis() / 1000));

  logGuardTemperature();
  measureBatteryAndSolar();

  if (!readSen66Values()) {
    sensorFail++;
    return;
  }
  buildJson();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi down, reading dropped");
    wifiFail++;
    return;
  }

  // postData() directly instead of airboxUpload(): that one turns the radio
  // off afterwards, and the demo keeps WiFi connected permanently.
  if (postData(json)) {
    uploadOk++;
    sentFlashStart = millis();
    sentFlashActive = true;  // UI task plays the 3 quick flashes
  } else {
    uploadFail++;
  }
}

void setup() {
  airboxBegin();  // must stay the very first line (serial, NVS, setup mode)

  esp_reset_reason_t rr = esp_reset_reason();
  LOG_STAGE("DEMO / RELIABILITY TEST");
  Serial.printf("Reset reason: %s (%d)\n", resetReasonName(rr), (int)rr);
  Serial.printf("Sending every %lu s, no sleep, charger always on\n",
                (unsigned long)(SEND_INTERVAL_MS / 1000));

  hardwareInit();
  delay(SENSOR_RAIL_SETTLE_MS);  // let the guard probe start up
  probe.requestTemperatures();   // first conversion, read by the first cycle

  sen66PowerOn();  // on for good from here
  startWifi();

  // First reading once the SEN66 is warm, then every 10 s.
  nextSendAt = sen66OnSince + SEN66_WARMUP_AFTER_POWER_MS;

  // Priority above loop() (1) so the LED/button keep running during uploads.
  xTaskCreate(uiTask, "ui", 4096, nullptr, 2, nullptr);
}

void loop() {
  static bool appliedPowered = true;

  if (setupRequested) {
    inSetupMode = true;  // the setup loop in airbox_net.h now drives the LED
    enterSetupMode();    // never returns (reboots)
  }

  // Apply power toggles from the button.
  if (powered != appliedPowered) {
    appliedPowered = powered;
    if (appliedPowered) {
      Serial.println("Power ON: WiFi up, measuring resumes");
      startWifi();
      nextSendAt = millis();  // SEN66 stayed on and warm, send right away
    } else {
      Serial.println("Power OFF: not measuring, WiFi off (SEN66 stays on)");
      wifiOff();
      wifiWasUp = false;
    }
  }

  if (!appliedPowered) {
    delay(50);
    return;
  }

  maintainWifi();

  if ((int32_t)(millis() - nextSendAt) >= 0) {
    doCycle();
    printStats();

    // Fixed cadence from the planned time, so cycle length does not cause
    // drift. If a slow upload put us a whole interval behind, resync.
    nextSendAt += SEND_INTERVAL_MS;
    if ((int32_t)(millis() - nextSendAt) >= 0) nextSendAt = millis() + SEND_INTERVAL_MS;
  }

  delay(50);
}
