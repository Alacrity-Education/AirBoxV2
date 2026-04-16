#!/usr/bin/env python3
import json
import math
import os
import random
import time
from dataclasses import dataclass, field
from typing import Dict, List

import paho.mqtt.client as mqtt


MQTT_HOST = os.environ["MQTT_HOST"]
MQTT_PORT = 1883
MQTT_TOPIC = "airbox/v2/submit"
MQTT_USERNAME = os.environ["MQTT_USERNAME"]
MQTT_PASSWORD = os.environ["MQTT_PASSWORD"]

PUBLISH_EVERY_SECONDS = 5 * 60
QOS = 1


@dataclass
class SensorState:
    sensor_id: str
    geohash: str
    values: Dict[str, float] = field(default_factory=dict)
    phase: float = field(default_factory=lambda: random.uniform(0, math.tau))

    def step(self) -> Dict[str, object]:
        # Slow daily-ish oscillation plus small random drift
        self.phase += random.uniform(0.03, 0.08)
        wave = math.sin(self.phase)

        def update(name: str, drift: float, noise: float, lo: float, hi: float) -> float:
            current = self.values[name]
            target_pull = 0.03 * (self.baseline(name) - current)
            current += target_pull
            current += drift * wave
            current += random.uniform(-noise, noise)
            current = max(lo, min(hi, current))
            self.values[name] = current
            return current

        pm1 = update("pm1", drift=0.3, noise=0.4, lo=0, hi=80)
        pm25 = update("pm25", drift=0.5, noise=0.7, lo=0, hi=120)
        pm4 = update("pm4", drift=0.6, noise=0.8, lo=0, hi=150)
        pm10 = update("pm10", drift=0.8, noise=1.0, lo=0, hi=180)
        temp = update("temp", drift=0.15, noise=0.10, lo=-10, hi=45)
        hum = update("hum", drift=0.6, noise=0.5, lo=10, hi=95)
        voc = update("voc", drift=3.0, noise=5.0, lo=0, hi=1200)
        nox = update("nox", drift=2.0, noise=4.0, lo=0, hi=1000)
        co2 = update("co2", drift=8.0, noise=12.0, lo=350, hi=2500)

        # Keep particulate values monotonic / realistic
        pm25 = max(pm25, pm1)
        pm4 = max(pm4, pm25)
        pm10 = max(pm10, pm4)

        self.values["pm25"] = pm25
        self.values["pm4"] = pm4
        self.values["pm10"] = pm10

        return {
            "id": self.sensor_id,
            "geohash": self.geohash,
            "installation": "outdoor",
            "pm1": round(pm1, 1),
            "pm25": round(pm25, 1),
            "pm4": round(pm4, 1),
            "pm10": round(pm10, 1),
            "temp": round(temp, 1),
            "hum": round(hum, 1),
            "voc": int(round(voc)),
            "nox": int(round(nox)),
            "co2": int(round(co2)),
        }

    def baseline(self, name: str) -> float:
        return BASELINES[name]


BASELINES = {
    "pm1": 4.0,
    "pm25": 7.0,
    "pm4": 9.0,
    "pm10": 12.0,
    "temp": 22.5,
    "hum": 46.0,
    "voc": 120.0,
    "nox": 80.0,
    "co2": 650.0,
}


def make_sensor(sensor_id: str, geohash: str, offset: Dict[str, float] | None = None) -> SensorState:
    offset = offset or {}
    values = {}
    for key, base in BASELINES.items():
        delta = offset.get(key, 0.0)
        jitter = random.uniform(-0.05, 0.05) * max(base, 1)
        values[key] = base + delta + jitter
    return SensorState(sensor_id=sensor_id, geohash=geohash, values=values)


def on_connect(client, userdata, flags, reason_code, properties=None):
    print(f"Connected with rc={reason_code}")


def on_disconnect(client, userdata, disconnect_flags, reason_code, properties=None):
    print(f"Disconnected rc={reason_code}")

def main():
    sensors: List[SensorState] = [
        make_sensor("mock-1", "sxfs9zxke", {"temp": -0.4, "co2": 20}),
        make_sensor("mock-2", "sxfscbpq5", {"temp": 0.2, "hum": 3, "pm25": 1}),
        make_sensor("mock-3", "sxfsf0hqn", {"voc": 30, "nox": 15}),
        make_sensor("mock-4", "sxfsdx2p7", {"temp": 1.5, "pm10": 8, "co2": -30}),
        make_sensor("mock-5", "sxfse3kq2", {"hum": -8, "voc": 60, "nox": 25}),
        make_sensor("mock-6", "sxfsgu4h1", {"temp": -1.2, "co2": 50, "pm25": 3}),
        make_sensor("mock-7", "sxft1bnd4", {"temp": 2.0, "hum": 10, "voc": -20}),
        make_sensor("mock-8", "sxft4pwr6", {"pm1": 2, "pm25": 5, "pm10": 10}),
        make_sensor("mock-9", "sxfthjm3e", {"temp": -2.5, "nox": 40, "co2": 80}),
    ]

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect

    print(f"Connecting to MQTT broker at {MQTT_HOST} with username {MQTT_USERNAME}")    

    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
    client.loop_start()

    try:
        while True:
            for sensor in sensors:
                payload = sensor.step()
                payload_json = json.dumps(payload, separators=(",", ":"))
                result = client.publish(MQTT_TOPIC, payload_json, qos=QOS, retain=False)
                result.wait_for_publish()
                print(f"published -> {MQTT_TOPIC}: {payload_json}")
            time.sleep(PUBLISH_EVERY_SECONDS)
    except KeyboardInterrupt:
        pass
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
