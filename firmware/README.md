airbox.pdf is the schematic of the airbox air quality monitor station
monitor the following data from peripherials:
solar voltage
battery voltage

STAT1 STAT2 logic:

CHARGING STATE STAT1 PIN STATE STAT2 PIN STATE
Charge completed, charger in sleep mode or
charge disabled (including VBAT > VRCH)
HIGH HIGH
Normal charging in progress (including
automatic recharge)
HIGH LOW
Recoverable fault (VIN_OVP, TS HOT, TS
COLD, TSHUT, system short protection)
LOW HIGH
Non-recoverable or latch-off fault (ILIM/ISET
pin short, BATOCP, safety timer expired)
LOW LOW

DO NOTE: some pins may need to be kept at a state during deep sleep. Use the RTC GPIO functionality for this. 

charge chip datasheet: the other PDF in here

This airbox has a DS18B20 chip attached and a SEN66 sensor from Sensirion.
Use the official Sen66 arduino library from sensirion

Measure battery flow: disable charging, wait 200ms, measure battery & solar, enable charging. 

Code sensor flow:
 Wake up from sleep, enable sensor power, check SD18B20 sensor. Verfy if the temp is within allowed limits of SEN66
If not, skip to post data stage. If all good, proceed to enable measurement on SEN66, wait 10 seconds, measure. Keep data. Shut sensor power. 


post data stage. 
Connect to wifi (20 second budget). On failure, run a captive-portal provisioning loop (softAP "AirBox-Setup") to set credentials and geohash; these are stored in NVS. 
Ping ingest.airbox.alacrity.ro icmp once timeout 1 second. ignore result.
Location: https://ingest.airbox.alacrity.ro/api/v2/submit
headers:
Authorization: ApiKey XXXXXXXXXX
Content-Type: application/json

{
  "geohash": "...",   // provisioned via captive portal, stored in NVS
  "charge": 0, # from 0 to 100 float
  "sun": false,
  "co2": 0
  "pm1": 0,
  "pm25": 0,
  "pm4": 0,
  "pm10": 0,
  "temp": 0,
  "hum": 0,
  "voc_index": 0,
  "nox_index": 0,
}
Disconnect wifi.
Go to deep sleep.


