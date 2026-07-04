// AirBox V2 pin map — WeMos/LOLIN S2 mini (ESP32-S2)
// Source: electronics/airbox.kicad_sch rev V2-rc1 (verified against exported netlist)
#pragma once

#include <stdint.h>

// I2C bus (SEN66 on J1, SCD30 header on J5). R14/R15 10k pull-ups sit on the
// always-on +3V3 rail. JP1/JP2 solder jumpers can mirror the bus onto
// GPIO1/GPIO2 but are open by default.
constexpr uint8_t PIN_I2C_SDA = 8;
constexpr uint8_t PIN_I2C_SCL = 9;

// ADC inputs (both on ADC1, safe to sample while WiFi is active).
constexpr uint8_t PIN_SENSE_SOLAR = 5;  // ADC1_CH4, VSOLAR via R18/R19 330k:100k
constexpr uint8_t PIN_SENSE_BAT   = 6;  // ADC1_CH5, VBAT via R16/R17 100k:100k

// Voltage divider ratios: Vreal = Vadc * ratio.
constexpr float SOLAR_DIVIDER_RATIO = (330.0f + 100.0f) / 100.0f;  // 4.3
constexpr float BAT_DIVIDER_RATIO   = (100.0f + 100.0f) / 100.0f;  // 2.0

// Sensor rail switch: Q1 (AO3401A P-FET) gate has a 10k pull-DOWN (R12), so
// the 3V3_SENSOR rail is ON when this pin is low or floating. Drive HIGH to
// cut sensor power, and keep it held HIGH through deep sleep (RTC hold),
// otherwise the SEN66 stays powered while the ESP32 sleeps.
constexpr uint8_t PIN_SENSOR_POWER = 7;

// BQ25185 charger. /CE has a 10k pull-down (R20): charging is enabled when
// this pin is low or floating, HIGH disables charging. STAT1/STAT2 are
// open-drain with no external pull-ups -> use internal pull-ups.
constexpr uint8_t PIN_CHR_STAT1   = 10;
constexpr uint8_t PIN_CHR_STAT2   = 11;
constexpr uint8_t PIN_CHR_DISABLE = 12;

// Status LED (D3 + R11 to GND), active HIGH.
constexpr uint8_t PIN_LED = 13;

// DS18B20 data (J7). 4.7k pull-up (R21) and sensor VCC are on the always-on
// +3V3 rail, so the probe works regardless of the sensor rail state.
constexpr uint8_t PIN_DS18B20 = 33;

// SCD30 "data ready" line on the legacy J5 header. Unused with SEN66 fitted.
constexpr uint8_t PIN_SCD30_RDY = 21;
