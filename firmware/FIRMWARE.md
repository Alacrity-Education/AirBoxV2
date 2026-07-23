# AirBox V2 firmware

Arduino-framework firmware for the AirBox V2 air quality station
(WeMos/LOLIN S2 mini, ESP32-S2), built with PlatformIO.

## Build & flash

```sh
pio run                 # build
pio run -t upload       # flash (hold "0", tap "RST" to enter the bootloader)
pio device monitor      # USB CDC serial log, 115200
```

Before flashing, set `API_KEY` in `include/config.h`. WiFi credentials and the
station geohash are no longer compiled in — they are provisioned at runtime via
the captive portal (step 4) and stored in NVS. To keep the API key out of git:
`git update-index --skip-worktree include/config.h`.

## Measurement cycle

Everything happens in `setup()`, one pass per wake-up, then deep sleep
(default cadence 10 min, `WAKE_INTERVAL_S`).

1. **Guard check** — sensor rail on, DS18B20 read. The SEN66 only runs if
   the probe reads within its absolute operating range (−10…+50 °C,
   `SEN66_TEMP_MIN_C`/`MAX_C`). A failed/missing probe also skips the SEN66
   (conservative: the guard exists to protect the sensor).
2. **SEN66** — start continuous measurement, wait 10 s, read one sample
   (with a few 1 s retries if PM/CO₂ are not ready yet), stop, cut sensor
   power. Values the sensor flags as unavailable are dropped, not zeroed.
3. **Battery/solar** — charger STAT pins logged, charging disabled for
   200 ms, both dividers sampled (16× averaged, calibrated mV), charging
   re-enabled. `charge` (%) comes from a Li-ion OCV curve; `sun` is
   `Vsolar ≥ 3.0 V` (`SUN_SOLAR_THRESHOLD_V`).
4. **Post** — connect WiFi (20 s budget); on failure a captive-portal
   provisioning loop (softAP `AirBox-Setup`) collects credentials/geohash into
   NVS and reboots. Once connected: one ICMP ping to the ingest host (1 s,
   result ignored), HTTPS POST to `INGEST_URL`, disconnect.
5. **Deep sleep** — awake time is subtracted from the interval to keep the
   cadence roughly fixed.

### Payload

```json
{"geohash":"...","charge":87.0,"sun":true,"co2":561,"pm1":2.1,"pm25":3.0,
 "pm4":3.2,"pm10":3.4,"temp":21.63,"hum":41.2,"voc_index":103.4,"nox_index":1.0}
```

Fields whose reading is unavailable are **omitted** (e.g. everything from the
SEN66 when it was skipped) rather than sent as 0. `temp` falls back to the
DS18B20 when the SEN66 didn't run, so temperature keeps reporting outside the
SEN66's range. `voc_index`/`nox_index` are omitted while the gas index
algorithms are still initializing (sensor reports 0), and after a cold start
they restart from baseline each cycle — an inherent limit of duty-cycled
operation.

TLS: by default the connection is encrypted but the server certificate is not
verified (`setInsecure`). Define `INGEST_ROOT_CA` in `config.h` to pin the CA.

## Hardware notes (schematic rev V2-rc1)

| Pin | Net | Behavior |
|-----|-----|----------|
| GPIO7 | SENSOR-POWER | Q1 P-FET gate via R13; R12 10k pulls the gate low → rail **on by default**. Drive HIGH to cut power. Held HIGH through deep sleep (`gpio_hold_en` + `gpio_deep_sleep_hold_en`), else the SEN66 would be powered all night. |
| GPIO12 | CHR-DISABLE | BQ25185 ~CE, R20 10k pull-down → charging enabled when low/floating (so also during deep sleep). HIGH disables charging (used for the unloaded battery measurement). |
| GPIO10/11 | CHR-STAT1/2 | Open-drain, no external pull-ups → internal pull-ups. HH=idle/done/disabled, HL=charging, LH=recoverable fault, LL=latch-off fault. |
| GPIO5 | SENSE-SOLAR | 330k:100k divider → V = ADC×4.3 |
| GPIO6 | SENSE-BAT | 100k:100k divider → V = ADC×2.0 |
| GPIO8/9 | SDA/SCL | SEN66 I²C @100 kHz; 10k pull-ups on the always-on 3V3 rail |
| GPIO33 | DS18B20-DATA | 4.7k pull-up; probe is powered from the always-on 3V3 rail |
| GPIO13 | LED | Active high; lit while awake (`STATUS_LED 0` to disable) |

Battery/solar measurement accuracy: the ADC sees a 50 kΩ (bat) / 77 kΩ
(solar) source impedance; use `VBAT_CAL`/`VSOLAR_CAL` in `config.h` to trim
against a multimeter if needed.
