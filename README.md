# AirBoxV2

<img width="4000" height="1900" alt="AirBox_render" src="https://github.com/user-attachments/assets/d45aea36-8e3e-422c-b068-460ecc5d543c" />

## What this is

The AirBox V2 Station is an open source air quality monitoring station based on the Sensirion SEN66 sensor. It measures particulate matter, CO2, VOC and NOx, runs on a solar charged lithium cell, and reports over Wi-Fi.

Everything needed to reproduce a unit is in this repository: schematics, PCB layout, enclosure files, firmware and the classroom material built around it.

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


## Capabilities:

- PM (Particulate Matter), CO2, NOx and VOC readings.
- Solar charging.
- 3000mAh battery.
- Power path for switching between solar/USB-C and battery mode seamlessly.
- Highly accurate.

