#include <Arduino.h>
#include <DallasTemperature.h>
#include <OneWire.h>
#include <SensirionI2cSen66.h>
#include <Wire.h>

#include "config.h"
#include "pins.h"
#include "airbox_net.h"

#define REASON_DEEP 11
#define REASON_PREHEAT 22

// DS18B20 on its own 1-wire bus, SEN66 on the I2C bus.
OneWire oneWire(PIN_DS18B20);
DallasTemperature probe(&oneWire);
SensirionI2cSen66 sen66;

// everything we measure in one place, buildJson() reads it all at the end
float batteryVolts, solarVolts, chargePercent;
bool sunOut;  // bool cause it's either yes or no
float pm1, pm25, pm4, pm10, temperature, humidity;  // datatypes according to Sensirion's datasheet
uint16_t co2, vocRaw, noxRaw;

// The JSON we upload gets written into this buffer
char json[1000];

void hardwareInit() {
    // Sensor power pin and setup button are already handled by airboxBegin().
    // Charger control pin: LOW means the battery charges normally.
    pinMode(PIN_CHR_DISABLE, OUTPUT);
    digitalWrite(PIN_CHR_DISABLE, LOW);

    // The bare ADC only reads up to about 1V; 11db attenuation stretches that
    // to about 2.5V, enough for the divided battery voltage (2.1V at the pin).
    analogSetPinAttenuation(PIN_SENSE_BAT, ADC_11db);
    analogSetPinAttenuation(PIN_SENSE_SOLAR, ADC_11db);

    Wire.begin((int)PIN_I2C_SDA, (int)PIN_I2C_SCL);
    sen66.begin(Wire, SEN66_I2C_ADDR_6B);
    probe.begin();
}

bool doCheckSafetyTemperature() {
    delay(SENSOR_RAIL_SETTLE_MS);  // let the probe start up before we ask it anything
    probe.requestTemperatures();
    float t = probe.getTempCByIndex(0);
    Serial.printf("Guard temperature: %.2f C\n", t);

    return (t == -127 || (t >= SEN66_TEMP_MIN_C && t <= SEN66_TEMP_MAX_C));
}

void enableSen66Sensor() {
    digitalWrite(PIN_SENSOR_POWER, LOW);  // transistor Q1 opens, sensors get 3.3V
    delay(SENSOR_RAIL_SETTLE_MS);  // the SEN66 needs a moment after power up before any command

    sen66.begin(Wire, SEN66_I2C_ADDR_6B);
    sen66.startContinuousMeasurement();
    // The power stays on through the preheat sleep, so the SEN66 keeps
    // measuring while the CPU is off and its gas readings settle.
}

// Discard one sample, then average 16. Returns volts AT THE PIN, the caller
// still multiplies by the divider ratio.
float readVoltageAverage(uint8_t pin) {
    analogReadMilliVolts(pin);  // discard
    uint32_t sum = 0;
    for (int i = 0; i < 16; i++) {
        sum += analogReadMilliVolts(pin);
    }
    return (sum / 16.0) / 1000.0;  // millivolts to volts
}

void measureBatteryAndSolar() {
    // Pause charging so the charger does not push the battery reading up.
    digitalWrite(PIN_CHR_DISABLE, HIGH);
    delay(CHARGE_SETTLE_MS);

    batteryVolts = readVoltageAverage(PIN_SENSE_BAT) * BAT_DIVIDER_RATIO;  // divider ratio
    chargePercent = socFromVoltage(batteryVolts);

    solarVolts = readVoltageAverage(PIN_SENSE_SOLAR) * SOLAR_DIVIDER_RATIO;
    sunOut = solarVolts > SUN_SOLAR_THRESHOLD_V;

    digitalWrite(PIN_CHR_DISABLE, LOW);  // resume charging
    Serial.printf("Battery: %.2f V (%.0f%%)\n", batteryVolts, chargePercent);
}

void readSen66Values() {
    uint16_t rawPm1, rawPm25, rawPm4, rawPm10, rawCo2;
    int16_t rawHum, rawTemp, rawVoc, rawNox;
    sen66.readMeasuredValuesAsIntegers(rawPm1, rawPm25, rawPm4, rawPm10, rawHum,
                                      rawTemp, rawVoc, rawNox, rawCo2);

    uint16_t retries = 0;

    while ((rawPm1 == 0xFFFF || rawPm25 == 0xFFFF || rawPm4 == 0xFFFF || rawPm10 == 0xFFFF || rawCo2 == 0xFFFF || rawHum == 0x7FFF || rawTemp == 0x7FFF) &&
           retries < SEN66_READ_RETRIES) {
        retries++;
        delay(1000);
        sen66.readMeasuredValuesAsIntegers(rawPm1, rawPm25, rawPm4, rawPm10, rawHum,
                                          rawTemp, rawVoc, rawNox, rawCo2);
    }

    if ((rawPm1 == 0xFFFF || rawPm25 == 0xFFFF || rawPm4 == 0xFFFF || rawPm10 == 0xFFFF || rawCo2 == 0xFFFF || rawHum == 0x7FFF || rawTemp == 0x7FFF) &&
           retries < SEN66_READ_RETRIES) {
        Serial.println("Failed to read SEN66 values, sleeping...");
        digitalWrite(PIN_SENSOR_POWER, HIGH);
        airboxSleep(SLEEP_SKIP_MS, REASON_DEEP);
    }

    // Scaling: pm divide by 10, humidity by 100, temperature by 200,
    // co2 is already in ppm.
    pm1 = rawPm1 / 10.0;
    pm25 = rawPm25 / 10.0;
    pm4 = rawPm4 / 10.0;
    pm10 = rawPm10 / 10.0;
    humidity = rawHum / 100.0;
    temperature = rawTemp / 200.0;
    co2 = rawCo2;

    // rawVoc/rawNox above are the index values, we ignore those. The raw ticks
    // come from a second call; the server turns ticks into an index for us.
    /*int16_t tickHum, tickTemp;
    uint16_t tickVoc, tickNox, tickCo2;
    sen66.readMeasuredRawValues(tickHum, tickTemp, tickVoc, tickNox, tickCo2);
    vocRaw = tickVoc;
    noxRaw = tickNox;*/
}

void buildJson() {
    char geohash[101];
    airboxGetGeohash(geohash, sizeof(geohash));

    // %s = string, %.1f = number with one decimal, %u = whole number,
    // booleans with the ternary trick from the wiki.
    snprintf(json, sizeof(json),
             "{\"geohash\":\"%s\",\"charge\":%.1f,\"sun\":%s,\"co2\":%u,"
             "\"pm1\":%.1f,\"pm25\":%.1f,\"pm4\":%.1f,\"pm10\":%.1f,"
             "\"temp\":%.2f,\"hum\":%.1f,\"voc_raw\":%u,\"nox_raw\":%u}",
             geohash, chargePercent, sunOut ? "true" : "false", co2,
             pm1, pm25, pm4, pm10, temperature, humidity, 0, 0);

    Serial.printf("Payload: %s\n", json);
}

void doMeasure() {
  // Battery first, while the radio is off (WiFi drags the voltage down).
  measureBatteryAndSolar();

  if (chargePercent < MIN_CHARGE_PERCENT) {
      Serial.println("Battery too low, skipping measurement");
      digitalWrite(PIN_SENSOR_POWER, HIGH);
      airboxSleep(SLEEP_SKIP_MS, REASON_DEEP);
  }

  // Returns right away; WiFi connects in the background while we work.
  airboxBeginConnect();

  readSen66Values();
  digitalWrite(PIN_SENSOR_POWER, HIGH);  // data is in, sensors can power down

  buildJson();

  if (airboxWaitConnected(CONNECT_WINDOW_MS)) {
    airboxUpload(json);
  }
  else {
      Serial.println("WiFi not connected, trying again");
      airboxBeginConnect();
      if (airboxWaitConnected(CONNECT_WINDOW_MS)) {
        airboxUpload(json);
      }
      else {
        Serial.println("WiFi not connected, giving up");
      }
  }
  // If WiFi did not connect this reading is lost, the next cycle retries.
}

void setup() {
  int reason = airboxBegin();  // must stay the very first line

  hardwareInit();



  if (reason == -1 || reason == REASON_DEEP) {
    bool res = doCheckSafetyTemperature();
    if (res) {
      enableSen66Sensor();  // enable sensor and go to preheat sleep
      airboxSleep(SLEEP_PREHEAT_MS, REASON_PREHEAT);
    } else airboxSleep(SLEEP_SKIP_MS, REASON_DEEP);  // temps are bad, go to deep sleep.
  }

  else if (reason == REASON_PREHEAT) {
    doMeasure();
    airboxSleep(SLEEP_MEASURED_MS, REASON_DEEP);
  }
  else {
    airboxSleep(SLEEP_SKIP_MS, REASON_DEEP);
  }
}

void loop() {
  // nothing
}
