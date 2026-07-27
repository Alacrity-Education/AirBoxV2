# AirBox V2 firmware

Arduino-framework firmware for the AirBox V2 air quality station
(WeMos/LOLIN S2 mini, ESP32-S2), built with PlatformIO.

The sketch (`firmware.ino`) holds the sensor + payload code; all networking,
deep sleep, credential storage and setup mode live behind the black-box API in
`airbox_net.h`, documented in [AIRBOX_NET.md](AIRBOX_NET.md).

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

The firmware runs a small state machine driven by the sleep-reason code that
`airboxBegin()` returns (`-1` = fresh boot; full flow diagram in
[AIRBOX_NET.md](AIRBOX_NET.md)). One measurement cycle spans **two wake-ups**,
because the SEN66 gas signals need minutes of continuous measurement after
power-on to settle (datasheet §1.4 switch-on behavior: VOC raw < 60 s, NOx
raw < 300 s).

1. **Fresh boot / `REASON_DEEP_SLEEP` — guard check + preheat** — DS18B20
   read (it hangs off the always-on rail, so the sensor rail stays off).
   Within the SEN66's absolute operating range (−10…+50 °C,
   `SEN66_TEMP_MIN_C`/`MAX_C`): sensor rail on, SEN66 continuous measurement
   started, then deep sleep 5 min (`SLEEP_PREHEAT_MS`,
   `REASON_PREHEAT_SLEEP`) **with the sensor rail kept powered** so the
   sensor preheats through the nap. Probe failed/missing or out of range:
   deep sleep 15 min (`SLEEP_SKIP_MS`, `REASON_DEEP_SLEEP`) and retry
   (conservative: the guard exists to protect the sensor). WiFi is never
   started on this wake.
2. **`REASON_PREHEAT_SLEEP` — measure + upload** — the SEN66 has been
   measuring ~5 min. Battery/solar first, **radio still off**: charger STAT
   pins logged, charging paused (BQ25185 /CE high) for 2 s
   (`CHARGE_SETTLE_MS`) so the battery rests unloaded, both dividers sampled
   (16× averaged, calibrated mV), charging re-enabled. `charge` (%) comes
   from a Li-ion OCV curve; `sun` is `Vsolar ≥ 3.0 V`
   (`SUN_SOLAR_THRESHOLD_V`). Then WiFi association starts in the background,
   the guard probe is re-read (payload `temp` fallback), the SEN66 sampled
   (a few 1 s retries if PM/CO₂ are not ready yet), stopped, and the rail
   cut. JSON built; wait for the link (up to 60 s, `CONNECT_WINDOW_MS`,
   returns as soon as connected): connected → one ICMP ping to the ingest
   host (1 s, result ignored), HTTPS POST to `INGEST_URL`, disconnect; no
   link → the measurement is dropped this cycle. Values the sensor flags as
   unavailable are dropped, not zeroed. Then deep sleep 10 min
   (`SLEEP_MEASURED_MS`, `REASON_DEEP_SLEEP`).

A good cycle yields a reading roughly every 15–16 minutes (5 min preheat +
awake time + 10 min sleep).

Setup mode (captive portal, softAP `AirBox-Setup`) is entered by holding the
GPIO21 button 15 s from any state — the button also wakes the board out of
any sleep, including the preheat nap — or automatically on a freshly flashed
board with no stored credentials.

### Payload

```json
{"geohash":"...","charge":87.0,"sun":true,"co2":561,"pm1":2.1,"pm25":3.0,
 "pm4":3.2,"pm10":3.4,"temp":21.63,"hum":41.2,"voc_raw":30112,"nox_raw":15833}
```

Fields whose reading is unavailable are **omitted** (e.g. everything from the
SEN66 when it was skipped) rather than sent as 0. `temp` falls back to the
DS18B20 when the SEN66 didn't run, so temperature keeps reporting outside the
SEN66's range. `voc_raw`/`nox_raw` are the SGP41's **raw ticks**
(SRAW_VOC/SRAW_NOx), not index values: the ingest converts them to
`voc_index`/`nox_index` server-side with the stateful Sensirion Gas Index
Algorithm, whose per-device state survives across cycles — something the
on-device algorithm cannot do under duty-cycled operation (it restarts from
baseline every wake). The firmware therefore never sends the index itself (an
explicitly sent index would override the raw processing on the server). Ticks
the sensor flags as unknown (0xFFFF) are omitted; the ~5 min preheat covers
the raw signals' switch-on time (VOC < 60 s, NOx < 300 s per datasheet §1.4).

TLS: by default the connection is encrypted but the server certificate is not
verified (`setInsecure`). Define `INGEST_ROOT_CA` in `config.h` to pin the CA.

## Hardware notes (schematic rev V2-rc1)

| Pin | Net | Behavior |
|-----|-----|----------|
| GPIO7 | SENSOR-POWER | Q1 P-FET gate via R13; R12 10k pulls the gate low → rail **on by default**. Drive HIGH to cut power. Actively held at its current level through every deep sleep (`gpio_hold_en` + `gpio_deep_sleep_hold_en`): LOW (rail on) across the preheat nap so the SEN66 keeps measuring, HIGH otherwise (else the SEN66 would be powered all night). |
| GPIO12 | CHR-DISABLE | BQ25185 ~CE, R20 10k pull-down → charging enabled when low/floating (so also during deep sleep). HIGH disables charging (used for the unloaded battery measurement). |
| GPIO10/11 | CHR-STAT1/2 | Open-drain, no external pull-ups → internal pull-ups. HH=idle/done/disabled, HL=charging, LH=recoverable fault, LL=latch-off fault. |
| GPIO5 | SENSE-SOLAR | 330k:100k divider → V = ADC×4.3 |
| GPIO6 | SENSE-BAT | 100k:100k divider → V = ADC×2.0 |
| GPIO8/9 | SDA/SCL | SEN66 I²C @100 kHz; 10k pull-ups on the always-on 3V3 rail |
| GPIO21 | SETUP-BTN | Momentary button to GND on the legacy J5 SCD30-RDY pin; no external pull-up → internal pull-up awake, RTC pull-up in deep sleep; RTC pad → ext0 wake |
| GPIO33 | DS18B20-DATA | 4.7k pull-up; probe is powered from the always-on 3V3 rail |
| GPIO13 | LED | Active high; lit while awake (`STATUS_LED 0` to disable) |

Battery/solar measurement accuracy: the ADC sees a 50 kΩ (bat) / 77 kΩ
(solar) source impedance; use `VBAT_CAL`/`VSOLAR_CAL` in `config.h` to trim
against a multimeter if needed.
