# AirBoxV2

<img width="4000" height="1900" alt="AirBox_render" src="https://github.com/user-attachments/assets/d45aea36-8e3e-422c-b068-460ecc5d543c" />

## What this is

The AirBox V2 Station is an open source air quality monitoring station based on the Sensirion SEN66 sensor. It measures particulate matter, CO2, VOC and NOx, runs on a solar charged lithium cell, and reports over Wi-Fi.

Everything needed to reproduce a unit is in this repository: schematics, PCB layout, enclosure files, firmware and the classroom material built around it.

## Repo layout

AirBoxV2/
├── electronics/     KiCad project for the main board and the digits daughterboard, with JLCPCB gerbers
├── badge/           KiCad project for the AirBox badge board
├── firmware/        ESP32-S2 Arduino firmware, pin map and config headers, component datasheets
├── middleware/      Spring Boot ingest service: REST API, AQI and gas index calculation, database migrations, Grafana dashboards
├── infrastructure/  Ansible playbooks and Compose files for deploying the stack (Caddy, Grafana, database)
└── LICENSE

## Why another air quality monitor?

The AirBox-class monitors are meant to be educational devices first and **foremost**.  Although they are just as capable to measure air quality as any other device, they were also meant to be built by aspiring engineers, scientists and enthusiasts alike. From the ground up.

Commercial monitors are usually sealed opaque boxes. **AirBox was meant to be soldered, opened, flashed, inspected, broken and fixed again.**

We have an entire **5 weeks long bootcamp** dedicated to ALL the information that goes into making an AirBox (legit from the atom up).

This is what a unit built (soldered, design walkthrough, mechanical build) by one of our students looks like:
<img width="500" height="482" alt="assembled_airbox" src="https://github.com/user-attachments/assets/44616029-51c7-40d7-a352-dd8eaddf4fc3" />



More documentation can be found [here](https://wiki.alacrity.ro/Projects/airbox-v2).

## Track record
125 students taught through the AirBox online bootcamp.
12 students brought to Bucharest for a 10 day residential build program.
All lessons are online, open source and publicly available at wiki.alacrity.ro.
Units in the field: TODO deployed across TODO locations.

## Specifications
 
| | |
|---|---|
| Microcontroller | ESP32-S2 Mini (WEMOS board), details [here](https://www.wemos.cc/en/latest/s2/s2_mini.html) |
| Air quality sensor | Sensirion SEN66, I2C |
| Additional sensors | DS18B20 external temperature probe (over temp protection), ADC battery and solar rail monitoring |
| Connectivity | Wi-Fi 802.11 b/g/n, 2.4 GHz |
| Battery | 3000 mAh Li-ion, single 18650 cell |
| Charging | Solar and USB-C, seamless power path between source and battery |
| Solar panel | 6V, 2W |
| Runtime on battery alone | 48 hours at the default sampling interval |
| Case | fully printed in ASA|
| Dimensions and weight | 138mm (w) x 166mm (L) x 42mm (h) |


## Build your one

1. Order the PCB (you can find all the files [here](https://github.com/Alacrity-Education/AirBoxV2/tree/main/electronics/jlcpcb)).
2. Assemble the PCB. BEWARE: we intentionally included SMD components as we think this is a very important part of the learning experience. We recommend you use **0.5mm flux core solder**, flux, a pair of tweezers and a lot of patience. A soldering microscope is nice but not required. Full assembly walkthrough [here](https://wiki.alacrity.ro/en/Projects/airbox-v2) under the  "Assembly instructions" chapter. It is legit a step-by-step.
3. Flash the [firmware](https://github.com/Alacrity-Education/AirBoxV2/tree/main/firmware).
4. First boot:
TODO: change after firmware is finalized

## Known limitations
 
- The SEN66 is specified down to -10 °C. Winter temps  go below that, so outdoor readings in January may be out of spec or unavailable.
- The sensor must never be exposed to condensing humidity, which constrains how the enclosure can be vented outdoors.
- The station depends on a local Wi-Fi network. Many locations will not put an unmanaged device on their network/may not have WiFi at all, which is the main reason V3 moves to cellular.
- VOC and NOx are indices, not concentrations.


## Citing AirBox
 
If you use AirBox in research, a school project or a publication, please cite it. [TODO: add machine readable metadata].
 
```bibtex
@software{airbox_v2,
  author       = {TODO},
  title        = {AirBox V2: an open source educational air quality monitoring station},
  year         = {TODO},
  publisher    = {Zenodo},
  doi          = {TODO},
  url          = {https://github.com/Alacrity-Education/AirBoxV2}
}
```

## Contact
 
Asociația Alacrity Education, Str. G-ral Christian Tell, Bucharest, Romania.
 
- Website: [alacrity.ro](https://alacrity.education/en)
- Email: contact@alacrity.ro
- Wiki and courses: [wiki.alacrity.ro](https://wiki.alacrity.ro)
- Bugs and hardware questions: [open an issue](../../issues/new/choose)

Schools in Romania that want to run an AirBox build: write to us, we have done this before and can help you scope it.



