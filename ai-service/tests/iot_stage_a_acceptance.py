"""Stage A integration checks. Execute only inside the disposable patrol QA project."""
import json
import os
from concurrent.futures import ThreadPoolExecutor
from time import monotonic, sleep, time

from influxdb_client import InfluxDBClient
import psycopg2
import requests

BASE = "http://backend:8080/api"
PREFIX = f"qa-iot-{int(time())}"
ADMIN = os.environ.get("PEANUT_PATROL_TEST_TOKEN")
COORDS = [[115.456, 36.123], [115.460, 36.123], [115.460, 36.126], [115.456, 36.123]]


def call(method, route, status=200, token=ADMIN, **kwargs):
    headers = {"Authorization": "Bearer "+token} if token else {}
    response = requests.request(method, BASE+route, headers=headers, timeout=22, **kwargs)
    assert response.status_code == status, (method, route, response.status_code, response.text)
    if "text/csv" in response.headers.get("Content-Type", ""):
        return response.text
    result = response.json()
    return result.get("data") if status == 200 else result


def wait_until(fetch, seconds=18):
    deadline = monotonic()+seconds
    while monotonic()<deadline:
        result=fetch()
        if result:
            return result
        sleep(0.25)
    raise AssertionError("Expected change was not observed")


def database():
    return psycopg2.connect(host=os.environ.get("DB_HOST", "postgres"),
                            user=os.environ.get("DB_USER", "peanut"),
                            password=os.environ.get("DB_PASS", "peanut123"),
                            dbname=os.environ.get("DB_NAME", "peanut"))


def register(field, device_type, suffix, **extra):
    body = {"deviceType": device_type, "deviceSn": PREFIX+"-"+suffix,
            "deviceName": PREFIX+" "+suffix, "fieldId": field, **extra}
    return call("POST","/patrol/devices",json=body), body


def csv_checks(field):
    path="/patrol/devices/import"
    header="deviceType,deviceSn,deviceName,fieldId\n"
    serial=PREFIX+"-csv"
    text=header+f"weather_station,{serial},CSV Station,{field}\n"
    bad=call("POST",path+"/preview",files={"file":("devices.csv",text+f"unknown,{serial}-2,Invalid,{field}\n")})
    assert bad["errors"][0]["row"] == 3
    call("POST",path+"/execute",status=400,json={"jobId":bad["jobId"]})
    assert call("GET","/patrol/devices",params={"keyword":serial})["totalElements"] == 0
    preview=call("POST",path+"/preview",files={"file":("devices.csv",text)})
    assert not preview["errors"]
    result=call("POST",path+"/execute",json={"jobId":preview["jobId"]})
    assert result["importedCount"] == 1
    assert call("POST",path+"/execute",json={"jobId":preview["jobId"]})["importedCount"] == 1
    assert serial in call("GET","/patrol/devices/export",params={"keyword":serial})
    call("GET","/patrol/devices/export",status=403,token=None)
    print("CSV preview: row errors, no preview write, atomic execute, idempotence and protected export passed.")


def simulator_checks(field):
    base="/iot/simulator"
    stages=call("GET",base+"/templates")["scenarios"]
    assert set(stages)=={"normal","soil_dry","high_temperature","device_offline","low_battery","sensor_stuck","spike"}
    entry=call("POST",base+"/devices",json={"deviceType":"soil_multi_sensor","deviceName":PREFIX+" simulation",
                                           "fieldId":field,"settings":{"sampleIntervalSeconds":5,"randomSeed":47}})
    device=entry["device"]; ident=device["id"]
    path=base+f"/devices/{ident}"
    profile=path+"/profile"
    recent=path+"/preview"
    assert device["accessMode"]=="simulated" and device["deviceSn"].startswith("SIM-") and not device["online"]
    call("POST",path+"/start",status=403,token=None,json={"scenario":"normal"})
    call("PUT",profile,json={"sampleIntervalSeconds":5,"randomSeed":47,"metrics":{
        "soil_moisture_vwc_pct":{"baseline":37,"noise":0},
        "soil_temp_c":{"baseline":25,"noise":0},
        "battery_pct":{"baseline":90,"noise":0}}})
    call("POST",path+"/start",json={"scenario":"normal"})
    first=wait_until(lambda: (points if len(points:=call("GET",recent)["points"])>=2 else None))
    assert first[-1]["values"]["soil_temp_c"]==25 and all(row["source"]=="simulated" for row in first)
    detail=call("GET",f"/patrol/devices/{ident}")
    assert detail["online"] and detail["latestMetrics"]["soil_temp_c"]["unit"]=="°C"
    call("POST",path+"/pause")
    paused_count=len(call("GET",recent)["points"])
    sleep(6)
    assert len(call("GET",recent)["points"])==paused_count
    call("POST",path+"/resume")
    wait_until(lambda:len(call("GET",recent)["points"])>paused_count)
    call("POST",path+"/start",json={"scenario":"soil_dry"})
    wait_until(lambda:call("GET",f"/patrol/devices/{ident}")["latestMetrics"]["soil_moisture_vwc_pct"]["value"]<37)
    call("POST",path+"/start",json={"scenario":"high_temperature"})
    wait_until(lambda:call("GET",f"/patrol/devices/{ident}")["latestMetrics"]["soil_temp_c"]["value"]>25)
    call("POST",path+"/start",json={"scenario":"low_battery"})
    wait_until(lambda:call("GET",f"/patrol/devices/{ident}")["batteryPct"]<=20)
    call("POST",path+"/start",json={"scenario":"sensor_stuck"})
    wait_until(lambda:call("GET",f"/patrol/devices/{ident}")["latestMetrics"]["soil_temp_c"]["value"]==25)
    call("POST",path+"/start",json={"scenario":"spike"})
    wait_until(lambda:any(row["quality"].get("soil_temp_c")=="suspect" for row in call("GET",recent)["points"]))
    call("POST",path+"/start",json={"scenario":"device_offline"})
    offline_count=len(call("GET",recent)["points"])
    sleep(6)
    assert len(call("GET",recent)["points"])==offline_count
    wait_until(lambda:not call("GET",f"/patrol/devices/{ident}")["online"],seconds=20)
    call("POST",path+"/stop",json={"keepLastOnline":False})
    assert not call("GET",f"/patrol/devices/{ident}")["online"]
    call("POST",path+"/inject",json={"metricCode":"soil_temp_c","value":51.5})
    assert call("GET",recent)["points"][-1]["values"]["soil_temp_c"]==51.5
    call("POST",path+"/inject",status=400,json={"metricCode":"air_temp_c","value":51})
    all_samples=call("GET",recent)["points"]
    reset=call("POST",path+"/reset")
    assert reset["profile"]["status"]=="stopped" and reset["profile"]["sequence"]==0
    assert all_samples==call("GET",recent)["points"]

    with InfluxDBClient(url=os.environ["INFLUXDB_URL"],token=os.environ["INFLUXDB_TOKEN"],
                        org=os.environ["INFLUXDB_ORG"]) as influx:
        bucket=os.environ["INFLUXDB_BUCKET"]
        flux=f'from(bucket:"{bucket}") |> range(start:-1h) |> filter(fn:(r)=>r._measurement=="device_telemetry" and r.device_id=="{ident}" and r._field=="soil_temp_c")'
        records=[row for table in influx.query_api().query(flux) for row in table.records]
        assert len(records)>=2 and all(row.values["source"]=="simulated" and row.values["field_id"]==str(field) for row in records)
    with database() as connection, connection.cursor() as cursor:
        cursor.execute("SELECT count(*),count(DISTINCT message_id) FROM iot_telemetry_dedupe WHERE device_id=%s",(ident,))
        size,unique=cursor.fetchone()
        assert size==unique and size>=len(records)
    print("Simulator: InfluxDB persisted samples, actual device latest values, lifecycle, seven scenarios, quality, tags and retained history passed.")


def main():
    if os.environ.get("PEANUT_DISPOSABLE_PATROL_TEST")!="true" or not ADMIN:
        raise SystemExit("Run only in isolated QA with PEANUT_PATROL_TEST_TOKEN")
    field=call("POST","/fields",json={"name":PREFIX,"geometry":json.dumps({"type":"Polygon","coordinates":[COORDS]})})["id"]
    with database() as connection, connection.cursor() as cursor:
        cursor.execute("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
        assert [row[0] for row in cursor.fetchall()]==["1","2","3","4","5","6"]
    assert call("GET","/iot/session")["role"]=="ADMIN"
    assert call("GET","/iot/session",token=None)["role"]=="VIEWER"
    call("GET","/iot/session",status=401,token="invalid-test-token")
    assert call("GET","/patrol/devices",token="qa-stage-a-operator")["totalElements"]==0
    call("GET","/patrol/devices",token="qa-stage-a-operator",params={"fieldId":field},status=403)
    assert len(call("GET","/patrol/device-types"))>=10 and len(call("GET","/iot/metrics"))>=18
    custom="qa_"+str(int(time()))
    custom_metric=custom+"_metric"
    call("POST","/iot/metrics",json={"code":custom_metric,"displayName":"Test Metric","category":"other",
                                     "canonicalUnit":"count","enabled":True})
    type_body={"code":custom,"name":"QA Custom Sensor","category":"other","enabled":True,
               "defaultOfflineTimeoutSeconds":180,"capabilitySchema":{"fieldRequired":True,"metrics":[custom_metric]}}
    call("POST","/patrol/device-types",json=type_body)
    generic,_=register(field,custom,"custom")
    assert generic["metricCodes"]==[custom_metric]
    call("PUT","/patrol/device-types/"+custom,json={**type_body,"enabled":False})
    call("POST","/patrol/devices",status=400,json={"deviceType":custom,"deviceSn":PREFIX+"-disabled",
                                                    "deviceName":"Disabled type","fieldId":field})
    station,body=register(field,"weather_station","weather")
    insect,_=register(field,"insect_light","insect")
    call("POST","/patrol/devices",status=400,json=body)
    call("POST","/patrol/devices",status=403,token=None,json=body)
    updated=call("PUT",f"/patrol/devices/{station['id']}",json={**body,"deviceName":"Updated weather","tags":["demo"]})
    assert updated["deviceName"]=="Updated weather" and updated["tags"]==["demo"]
    group=call("POST","/patrol/device-groups",json={"groupName":PREFIX})
    call("PUT",f"/patrol/device-groups/{group['id']}/members",json={"deviceIds":[station["id"],insect["id"]]})
    assert call("GET","/patrol/devices",params={"groupId":group["id"]})["totalElements"]==2
    call("POST",f"/patrol/devices/{insect['id']}/disable")
    call("POST",f"/patrol/devices/{insect['id']}/enable")
    call("POST",f"/patrol/devices/{insect['id']}/maintenance",
         json={"maintenanceType":"inspection","status":"completed","description":"QA inspection"})
    retired=call("POST",f"/patrol/devices/{insect['id']}/retire",json={"reason":"QA"})
    assert retired["status"]=="retired" and not retired["online"]
    assert call("GET",f"/patrol/devices/{insect['id']}/maintenance")["totalElements"]==1
    call("DELETE",f"/patrol/device-groups/{group['id']}")
    call("GET",f"/patrol/devices/{station['id']}")
    credentials=call("POST",f"/patrol/devices/{station['id']}/credentials/rotate")
    assert credentials["state"]=="pending_broker_provisioning"
    with database() as connection, connection.cursor() as cursor:
        cursor.execute("SELECT secret_hash FROM iot_device_credential WHERE device_id=%s",(station["id"],))
        hashed=cursor.fetchone()[0]
        assert hashed.startswith("pbkdf2-sha256:") and credentials["secret"] not in hashed
    print("V6, scoped access, extensible catalog, device lifecycle, groups, maintenance retention and credential hashes passed.")
    csv_checks(field)
    simulator_checks(field)


if __name__ == "__main__":
    main()
