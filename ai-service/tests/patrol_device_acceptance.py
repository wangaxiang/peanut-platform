"""MQTT, PostGIS, InfluxDB and NDVI acceptance. Run only in a disposable Compose project."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path
from queue import Queue
from time import monotonic, sleep, time
from zoneinfo import ZoneInfo
import json
import math
import os

from influxdb_client import InfluxDBClient
import numpy as np
import paho.mqtt.client as mqtt
from PIL import Image
import rasterio
from rasterio.io import MemoryFile
from rasterio.transform import from_bounds
import requests

API = "http://backend:8080/api"
PREFIX = f"qa-registry-{int(time())}"
COORDS = [[115.456, 36.123], [115.460, 36.123], [115.460, 36.126], [115.456, 36.126], [115.456, 36.123]]
ROUTE = {"type": "FeatureCollection", "features": [
    {"type": "Feature", "geometry": {"type": "Point", "coordinates": point}, "properties": {}}
    for point in COORDS[:2]
]}
TOPICS = {name: f"{PREFIX}-{name}" for name in ("drone", "dock", "sensor", "unknown", "offline", "once")}


def call(method, path, status=200, **kwargs):
    token = os.getenv("PEANUT_PATROL_TEST_TOKEN")
    headers = {"Authorization": "Bearer " + token} if token else {}
    response = requests.request(method, API + path, timeout=15, headers=headers, **kwargs)
    assert response.status_code == status, (path, response.status_code, response.text)
    return response.json().get("data") if status == 200 else response.json()


def wait_until(action, seconds=12):
    deadline = monotonic() + seconds
    while monotonic() < deadline:
        result = action()
        if result:
            return result
        sleep(0.3)
    raise AssertionError("Expected state was not observed before timeout")


def field(label):
    return call("POST", "/fields", json={"name": label, "geometry": json.dumps({
        "type": "Polygon", "coordinates": [COORDS]})})["id"]


def device(device_type, field_id, serial, **kwargs):
    return {"deviceType": device_type, "deviceSn": serial, "deviceName": serial,
            "fieldId": field_id, **kwargs}


def create_device(body):
    return call("POST", "/patrol/devices", json=body)


def get_device(row):
    return call("GET", f"/patrol/devices/{row['id']}")


def make_tiff(valid):
    red = np.repeat([40, 30, 10] if valid else [0, 0, 0], 30)
    array = np.ones((2, 90, 90), dtype="uint16")
    array[0] = red
    array[1] = 100 - red if valid else 0
    array[:, 0, :] = 0
    with MemoryFile() as memory:
        with memory.open(driver="GTiff", width=90, height=90, count=2, dtype="uint16",
                         transform=from_bounds(115.456, 36.123, 115.460, 36.126, 90, 90),
                         crs="EPSG:4326", nodata=0) as output:
            output.write(array)
        return memory.read()


def verify_image(client, flight_id, serial):
    url = f"/patrol/flights/{flight_id}"
    send(client, "drone", serial, {"flightId": flight_id, "phase": "done", "rawImagePath": "../outside.tif"})
    wait_until(lambda: call("GET", url)["status"] == "processing")
    assert call("GET", url)["rawImagePath"] is None
    call("POST", url + "/image", files={"file": ("invalid.tif", make_tiff(False), "image/tiff")})
    wait_until(lambda: call("GET", url)["status"] == "failed")
    assert "有效像元" in call("GET", url)["errorMessage"]
    call("POST", url + "/image", files={"file": ("valid.tif", make_tiff(True), "image/tiff")})
    wait_until(lambda: call("GET", url)["status"] == "done")
    result = call("GET", url)
    stats = result["ndviStats"]
    assert stats["valid_pixel_count"] == 8010
    assert abs(sum(stats[key] for key in ("red_pct", "yellow_pct", "green_pct")) - 100) <= 0.1
    assert result["ndviImagePath"] and result["ndviTileUrl"]
    assert call("GET", "/patrol/flights", params={"ndviOnly": True, "fieldId": result["fieldId"]})["totalElements"] == 1
    metadata = requests.get("http://backend:8080" + result["ndviTileUrl"], timeout=12).json()
    assert metadata["tilejson"] == "3.0.0"
    west, south, east, north = metadata["bounds"]
    n = 2 ** 17

    def x(degrees):
        return int((degrees + 180) / 360 * n)

    def y(degrees):
        return int((1 - math.asinh(math.tan(math.radians(degrees))) / math.pi) / 2 * n)

    colors = set()
    for tile_x in range(x(west), x(east) + 1):
        for tile_y in range(y(north), y(south) + 1):
            path = metadata["tiles"][0].format(z=17, x=tile_x, y=tile_y)
            response = requests.get("http://backend:8080" + path, timeout=12)
            response.raise_for_status()
            pixels = np.asarray(Image.open(BytesIO(response.content))).reshape(-1, 4)
            colors.update(map(tuple, np.unique(pixels, axis=0)))
    assert {(220, 58, 53, 235), (248, 198, 48, 235), (48, 158, 88, 235)} <= colors
    return result


def send(client, device_type, sn, payload):
    suffix = "data" if device_type == "sensor" else "status"
    client.publish(f"{device_type}/{sn}/{suffix}", json.dumps(payload), qos=1).wait_for_publish(timeout=5)


def verify_sensors(client, soil, other_field):
    sn = soil["deviceSn"]
    topic = "/iot/sensors/"
    now = int(time() // 3600) * 3600 - 3600
    good_metrics = {"soil_temp": 24.5, "soil_moisture": 42.5, "soil_n": 85, "soil_p": 32, "soil_k": 120}
    send(client, "sensor", TOPICS["unknown"], {"fieldId": other_field, "metrics": good_metrics})
    send(client, "sensor", sn, {"fieldId": other_field, "metrics": {"soil_temp": 999}})
    sleep(1)
    assert not get_device(soil)["online"]
    for offset, value in ((60, 20), (75, 22), (180, 30), (420, 40)):
        send(client, "sensor", sn, {"timestamp": (now + offset) * 1000, "battery": 92,
                                   "metrics": {**good_metrics, "soil_temp": value}})
    wait_until(lambda: call("GET", topic + sn + "/latest").get("soil_temp") == 40)
    assert get_device(soil)["batteryPct"] == 92
    assert call("GET", topic.rstrip("/"), params={"fieldId": soil["fieldId"]})[0]["deviceSn"] == sn
    for interval, values in (("1m", [21, 30, 40]), ("5m", [24, 40]), ("1h", [28])):
        history = call("GET", topic + sn + "/history", params={"interval": interval})
        assert [row["soil_temp"] for row in history] == values, (interval, history)
    call("GET", topic + TOPICS["unknown"] + "/latest", status=404)
    call("GET", topic + TOPICS["unknown"] + "/history", status=404)
    call("PUT", f"/patrol/devices/{soil['id']}/status", json={"status": "inactive"})
    send(client, "sensor", sn, {"metrics": {"soil_temp": 999}})
    sleep(1)
    assert call("GET", topic + sn + "/latest")["soil_temp"] == 40
    with InfluxDBClient(url=os.environ["INFLUXDB_URL"], token=os.environ["INFLUXDB_TOKEN"],
                        org=os.environ["INFLUXDB_ORG"]) as influx:
        query = f'from(bucket: "{os.environ["INFLUXDB_BUCKET"]}") |> range(start: -1d)' \
                f' |> filter(fn: (r) => r._measurement == "soil_sensor" and (r.device_sn == "{sn}" or r.device_sn == "{TOPICS["unknown"]}"))'
        rows = [record for table in influx.query_api().query(query) for record in table.records]
        assert rows and all(r.values["field_id"] == str(soil["fieldId"]) and r.values["device_sn"] == sn for r in rows)
    call("PUT", f"/patrol/devices/{soil['id']}/status", json={"status": "active"})


def main():
    if os.getenv("PEANUT_DISPOSABLE_PATROL_TEST") != "true":
        raise SystemExit("Set PEANUT_DISPOSABLE_PATROL_TEST=true only in an isolated Compose project")
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    queue = Queue()
    client.on_message = lambda c, u, message: queue.put(json.loads(message.payload))
    client.connect("emqx", 1883)
    for key in ("drone", "offline", "once"):
        client.subscribe(f"drone/{TOPICS[key]}/cmd/task", qos=1)
    client.loop_start()
    try:
        field_id, other_field = field(PREFIX), field(PREFIX + "-other")
        dock = create_device(device("dock", field_id, TOPICS["dock"]))
        drone_body = device("drone", field_id, TOPICS["drone"], parentDeviceId=dock["id"],
                            capabilities={"redBand": 1, "nirBand": 2})
        drone = create_device(drone_body)
        soil = create_device(device("sensor", field_id, TOPICS["sensor"]))
        offline = create_device(device("drone", field_id, TOPICS["offline"]))
        once = create_device(device("drone", field_id, TOPICS["once"]))
        assert not any(row["online"] for row in (dock, drone, soil, offline, once))
        call("POST", "/patrol/devices", status=400, json=drone_body)
        call("POST", "/patrol/devices", status=400, json=device("sensor", None, PREFIX + "-unbound"))
        call("POST", "/patrol/devices", status=400, json=device("drone", field_id, PREFIX + "-bad-parent",
                                                              parentDeviceId=soil["id"]))
        call("PUT", f"/patrol/devices/{dock['id']}", status=400,
             json=device("dock", other_field, dock["deviceSn"]))
        result = call("GET", "/patrol/devices", params={"fieldId": field_id, "deviceType": "drone", "keyword": PREFIX, "online": False})
        assert result["totalElements"] == 3
        assert call("GET", "/patrol/devices/stats")["byType"]["sensor"]["total"] >= 1
        path = f"/patrol/devices/{drone['id']}"
        updated = call("PUT", path, json={**drone_body, "deviceName": PREFIX + "-edited"})
        assert updated["deviceName"].endswith("-edited") and updated["parentDeviceId"] == dock["id"]
        assert call("PUT", path + "/status", json={"status": "maintenance"})["status"] == "maintenance"
        result = call("POST", path + "/maintenance", json={"maintenanceType": "repair", "status": "completed",
                                                          "description": "QA maintenance record"})
        assert call("GET", path + "/maintenance")["content"][0]["id"] == result["id"]
        call("PUT", path + "/status", json={"status": "active"})
        assert get_device(drone)["lastMaintenanceAt"]
        send(client, "drone", TOPICS["unknown"], {"phase": "idle"})
        assert call("GET", "/patrol/devices", params={"keyword": TOPICS["unknown"]})["totalElements"] == 0

        task_body = {"fieldId": field_id, "taskName": PREFIX + "-manual", "droneDeviceId": drone["id"],
                     "routeGeojson": ROUTE, "requiresDock": True}
        call("POST", "/patrol/tasks", status=409, json=task_body)
        call("POST", "/patrol/tasks", status=400, json={**task_body, "droneSn": drone["deviceSn"]})
        call("POST", "/patrol/tasks", status=409, json={**task_body, "droneDeviceId": dock["id"]})
        send(client, "dock", dock["deviceSn"], {"phase": "closed", "doorOpen": False})
        send(client, "drone", drone["deviceSn"], {"phase": "idle", "battery": 78, "lat": 36.124,
                                                "lng": 115.457, "altitude": 8, "faultCode": "T001",
                                                "faultMessage": "QA fault"})
        wait_until(lambda: get_device(drone)["online"] and get_device(dock)["online"])
        data = get_device(drone)
        assert data["batteryPct"] == 78 and data["longitude"] == 115.457 and data["faultCode"] == "T001"
        assert get_device(dock)["lastStatus"]["doorOpen"] is False
        call("PUT", path + "/status", json={"status": "maintenance"})
        assert "维护中" in call("POST", "/patrol/tasks", status=409, json=task_body)["msg"]
        call("PUT", path + "/status", json={"status": "active"})
        call("POST", "/patrol/tasks", status=409, json={**task_body, "fieldId": other_field})
        task = call("POST", "/patrol/tasks", json=task_body)
        call("DELETE", path, status=409)
        call("DELETE", f"/patrol/devices/{dock['id']}", status=409)
        flight = call("POST", f"/patrol/tasks/{task['id']}/flights")
        call("PUT", f"/patrol/tasks/{task['id']}/status", json={"status": "paused"})
        call("DELETE", path, status=409)
        call("PUT", f"/patrol/tasks/{task['id']}/status", json={"status": "active"})
        trigger = f"/patrol/flights/{flight['id']}/trigger"
        wait_until(lambda: not get_device(drone)["online"], seconds=20)
        assert "离线" in call("POST", trigger, status=409)["msg"]
        send(client, "drone", drone["deviceSn"], {"phase": "idle", "faultCode": None, "faultMessage": None})
        send(client, "dock", dock["deviceSn"], {"phase": "closed"})
        wait_until(lambda: get_device(drone)["online"] and get_device(dock)["online"])
        assert get_device(drone)["faultCode"] is None
        with ThreadPoolExecutor(max_workers=4) as pool:
            flights = list(pool.map(lambda _: call("POST", trigger), range(4)))
        assert all(row["id"] == flight["id"] for row in flights)
        command = queue.get(timeout=8)
        assert command["flightId"] == flight["id"] and command["issuedAt"]
        sleep(0.4)
        assert queue.empty()
        verify_image(client, flight["id"], drone["deviceSn"])
        call("POST", trigger)
        assert queue.empty()
        print("API: registry, bindings, maintenance, retirement guards, telemetry and idempotent manual flight passed.")
        print("NDVI: allowlisted image path, invalid pixels, configurable bands and 3-color TileJSON passed.")

        verify_sensors(client, soil, other_field)
        print("InfluxDB: trusted field binding and 1m/5m/1h aggregates passed; unknown, forged and inactive readings excluded.")
        Path("/tmp/patrol-device-fixture.json").write_text(json.dumps({
            "prefix": PREFIX, "fieldId": field_id, "flightId": flight["id"], "sensor": soil["deviceSn"]}))

        now = datetime.now(timezone.utc)
        slot = (now + timedelta(seconds=24)).replace(second=0, microsecond=0) + timedelta(minutes=1)
        local_slot = slot.astimezone(ZoneInfo("Asia/Shanghai"))
        for key in ("drone", "offline", "once"):
            send(client, "drone", TOPICS[key], {"phase": "idle"})
        wait_until(lambda: get_device(drone)["online"] and get_device(offline)["online"] and get_device(once)["online"])
        cron = call("POST", "/patrol/tasks", json={**task_body, "requiresDock": False, "taskName": PREFIX + "-cron",
                                                 "scheduleCron": f"{local_slot.minute} {local_slot.hour} * * *"})
        failed = call("POST", "/patrol/tasks", json={**task_body, "requiresDock": False, "taskName": PREFIX + "-offline",
                                                   "droneDeviceId": offline["id"], "scheduledAt": slot.isoformat()})
        one = call("POST", "/patrol/tasks", json={**task_body, "requiresDock": False, "taskName": PREFIX + "-once",
                                                "droneDeviceId": once["id"], "scheduledAt": slot.isoformat()})
        while (slot - datetime.now(timezone.utc)).total_seconds() > 3:
            send(client, "drone", drone["deviceSn"], {"phase": "idle"})
            send(client, "drone", once["deviceSn"], {"phase": "idle"})
            sleep(2)
        received = [queue.get(timeout=25), queue.get(timeout=12)]
        assert {item["taskId"] for item in received} == {cron["id"], one["id"]}, received
        rows = call("GET", "/patrol/flights", params={"fieldId": field_id})["content"]
        failure = next(row for row in rows if row["taskId"] == failed["id"])
        assert failure["status"] == "failed" and "离线" in failure["errorMessage"]
        assert not get_device(dock)["online"] and not get_device(soil)["online"]
        for item in received:
            serial = drone["deviceSn"] if item["taskId"] == cron["id"] else once["deviceSn"]
            send(client, "drone", serial, {"phase": "fault", "flightId": item["flightId"], "faultMessage": "QA finished"})
            wait_until(lambda: call("GET", f"/patrol/flights/{item['flightId']}")["status"] == "failed")
            call("PUT", f"/patrol/tasks/{item['taskId']}/status", json={"status": "paused"})
        call("PUT", f"/patrol/tasks/{task['id']}/status", json={"status": "paused"})
        assert call("DELETE", path)["status"] == "retired"
        assert call("GET", path + "/maintenance")["content"][0]["id"] == result["id"]
        assert call("GET", f"/patrol/flights/{flight['id']}")["droneDeviceId"] == drone["id"]
        assert queue.empty()
        print("Scheduler: cron and one-time commands arrived; offline schedule recorded an explicit failure. Retirement preserved history.")
    finally:
        client.disconnect()
        client.loop_stop()


if __name__ == "__main__":
    main()
