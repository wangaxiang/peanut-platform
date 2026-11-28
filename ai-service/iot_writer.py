import json
import logging
import math
import os
import re
from urllib.parse import urlsplit

from influxdb_client import InfluxDBClient, Point, WritePrecision
from influxdb_client.client.write_api import SYNCHRONOUS
import paho.mqtt.client as mqtt

log = logging.getLogger(__name__)
METRICS = ("soil_temp", "soil_moisture", "soil_n", "soil_p", "soil_k")


class SensorWriter:
    def __init__(self):
        self.bucket = os.getenv("INFLUXDB_BUCKET", "sensors")
        self.influx = InfluxDBClient(url=os.getenv("INFLUXDB_URL", "http://127.0.0.1:8086"),
                                     token=os.getenv("INFLUXDB_TOKEN", "peanut-token-dev"),
                                     org=os.getenv("INFLUXDB_ORG", "peanut"), timeout=5000)
        self.write_api = self.influx.write_api(write_options=SYNCHRONOUS)
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.broker = urlsplit(os.getenv("MQTT_BROKER_URL", "tcp://localhost:1883"))
        username = os.getenv("MQTT_USERNAME")
        if username:
            self.client.username_pw_set(username, os.getenv("MQTT_PASSWORD"))
        if self.broker.scheme in {"ssl", "mqtts"}:
            self.client.tls_set()

    def start(self):
        default_port = 8883 if self.broker.scheme in {"ssl", "mqtts"} else 1883
        self.client.connect_async(self.broker.hostname, self.broker.port or default_port)
        self.client.loop_start()

    def close(self):
        self.client.disconnect()
        self.client.loop_stop()
        self.write_api.close()
        self.influx.close()

    @staticmethod
    def on_connect(client, _userdata, _flags, reason, _properties):
        if reason == 0:
            client.subscribe("sensor/+/data", qos=1)
        else:
            log.warning("Sensor MQTT subscription unavailable (%s)", reason)

    def on_message(self, _client, _userdata, message):
        try:
            serial = message.topic.split("/")[1]
            payload = json.loads(message.payload)
            if not isinstance(payload, dict) or message.retain or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", serial):
                raise ValueError("invalid sensor message")
            field_id = payload.get("fieldId", payload.get("field_id"))
            if type(field_id) is not int or not 0 < field_id <= 9223372036854775807:
                raise ValueError("invalid fieldId")
            metrics = payload.get("metrics", payload)
            timestamp = payload.get("timestamp")
            if not isinstance(metrics, dict) or (timestamp is not None
                    and (type(timestamp) is not int or not 0 < timestamp <= 9223372036854)):
                raise ValueError("invalid metrics or timestamp")
            self.write_sensor(serial, field_id, metrics, timestamp)
        except (ValueError, TypeError, OverflowError) as exc:
            log.warning("Ignoring invalid sensor reading (%s)", exc)
        except Exception:
            log.exception("Could not write reading from %s to InfluxDB", message.topic)

    def write_sensor(self, device_sn, field_id, metrics, timestamp=None):
        point = Point("soil_sensor").tag("device_sn", device_sn).tag("field_id", str(field_id))
        values = {key: metrics[key] for key in METRICS if key in metrics}
        if not values or any(type(value) not in {int, float} or not math.isfinite(value) for value in values.values()):
            raise ValueError("expected at least one finite soil metric")
        for key, value in values.items():
            point.field(key, float(value))
        if timestamp is not None:
            point.time(timestamp, WritePrecision.MS)
        self.write_api.write(bucket=self.bucket, record=point)
