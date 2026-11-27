# 花生种植精准服务平台 · AI 可执行技术规格文档

> **阅读说明**：本文档面向 AI 编程助手直接执行。每个模块包含：约定 → DDL → API 定义 → 核心算法 → 验收标准。按模块顺序实现，完成一个模块的全部 checklist 后再推进下一个。

---

## 0. 全局约定

### 0.1 技术栈

| 层 | 选型 |
|----|------|
| 后端语言 | Java 21 + Spring Boot 3.2 |
| 构建工具 | Maven 3.9 |
| 关系型数据库 | PostgreSQL 16 + PostGIS 3.4 |
| 时序数据库 | InfluxDB 2.7 |
| 向量数据库 | pgvector 0.7（PostgreSQL 扩展） |
| 消息队列 | RabbitMQ 3.13 |
| MQTT Broker | EMQX 5.x |
| 影像处理 | Python 3.11 + rasterio + GDAL + numpy |
| AI 推理服务 | Python 3.11 + FastAPI + ultralytics（YOLOv8）|
| RAG 服务 | Python 3.11 + FastAPI + LangChain + Qwen2-7B |
| 前端 | Vue 3.4 + Vite 5 + Element Plus 2.x + MapLibre GL JS 4.x |
| 移动端 | 微信小程序（原生）|
| 容器化 | Docker Compose（开发）/ Kubernetes（生产）|
| 认证 | JWT（access token 2h + refresh token 7d）|

### 0.2 目录结构

```
peanut-platform/
├── backend/                        # Spring Boot 主服务
│   ├── src/main/java/com/peanut/
│   │   ├── common/                 # 统一响应、异常、工具类
│   │   ├── config/                 # 数据库、MQ、MQTT 配置
│   │   ├── module/
│   │   │   ├── field/              # 模块一：农田管理
│   │   │   ├── patrol/             # 模块二：智能巡田
│   │   │   ├── pest/               # 模块三：病虫害
│   │   │   ├── prescription/       # 模块四：处方图
│   │   │   ├── irrigation/         # 模块五：水肥控制
│   │   │   └── knowledge/          # 模块六：知识库
│   │   └── PeanutApplication.java
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/           # Flyway 迁移脚本
├── ai-service/                     # Python AI 推理服务
│   ├── pest_detection/             # 病虫害检测
│   ├── ndvi_processor/             # 多光谱影像处理
│   ├── prescription/               # 处方图生成
│   └── rag/                        # 知识问答
├── frontend/                       # Vue 3 Web 端
│   └── src/
│       ├── views/
│       ├── components/
│       ├── api/                    # axios 封装，与后端 API 一一对应
│       └── stores/                 # Pinia 状态管理
├── docker-compose.yml
└── docs/                           # 本文档所在目录
```

### 0.3 统一响应格式

```java
// 所有接口返回此结构
public record ApiResult<T>(
    int code,       // 200=成功, 400=参数错误, 401=未登录, 500=服务异常
    String msg,
    T data
) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(200, "success", data);
    }
    public static <T> ApiResult<T> fail(int code, String msg) {
        return new ApiResult<>(code, msg, null);
    }
}
```

### 0.4 分页请求约定

```
GET /api/xxx?page=0&size=20&sort=createdAt,desc
```

响应体 `data` 字段为 Spring `Page<T>` 的 JSON 序列化结果。

---

## 1. 数据库完整 DDL

> 文件路径：`backend/src/main/resources/db/migration/V1__init.sql`

```sql
-- 启用扩展
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==================== 模块一：农田信息 ====================

CREATE TABLE field (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    geometry     GEOMETRY(POLYGON, 4326) NOT NULL,
    area_mu      DECIMAL(10,2) NOT NULL,          -- 亩
    soil_type    VARCHAR(50),                      -- 沙壤土/壤土/黏壤土
    variety      VARCHAR(100),                     -- 花生品种
    sow_date     DATE,
    owner_name   VARCHAR(50),
    remark       TEXT,
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    updated_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_field_geometry ON field USING GIST(geometry);

CREATE TABLE farm_event (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id) ON DELETE CASCADE,
    event_type   VARCHAR(50) NOT NULL,  -- sow/fertilize/spray/harvest/irrigate
    event_date   DATE NOT NULL,
    operator     VARCHAR(50),
    amount       DECIMAL(10,2),         -- 用量（单位随 event_type 而异）
    unit         VARCHAR(20),
    remark       TEXT,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块二：巡田任务 ====================

CREATE TABLE patrol_task (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id) ON DELETE CASCADE,
    task_name    VARCHAR(100),
    schedule_cron VARCHAR(50),          -- 定时表达式，如 0 6 * * 1,4
    drone_sn     VARCHAR(64),           -- 机库/无人机序列号
    route_geojson JSONB,                -- 航线 GeoJSON
    status       VARCHAR(20) DEFAULT 'active',  -- active/paused/deleted
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE patrol_flight (
    id           BIGSERIAL PRIMARY KEY,
    task_id      BIGINT NOT NULL REFERENCES patrol_task(id) ON DELETE CASCADE,
    field_id     BIGINT NOT NULL REFERENCES field(id) ON DELETE CASCADE,
    start_time   TIMESTAMPTZ,
    end_time     TIMESTAMPTZ,
    status       VARCHAR(20) DEFAULT 'pending',  -- pending/flying/processing/done/failed
    raw_image_path TEXT,                -- 原始多光谱影像存储路径
    ndvi_tile_url  TEXT,                -- NDVI 切片服务 URL
    ndvi_stats   JSONB,                 -- {min, max, mean, std, red_pct, yellow_pct, green_pct}
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块三：病虫害 ====================

CREATE TABLE pest_detection (
    id           BIGSERIAL PRIMARY KEY,
    flight_id    BIGINT REFERENCES patrol_flight(id) ON DELETE SET NULL,
    field_id     BIGINT NOT NULL REFERENCES field(id),
    source       VARCHAR(20) NOT NULL,  -- flight/manual_upload
    image_url    TEXT NOT NULL,
    detections   JSONB NOT NULL,        -- 检测结果数组（见 3.3 节格式）
    alert_sent   BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_pest_field ON pest_detection(field_id);

-- ==================== 模块四：处方图 ====================

CREATE TABLE prescription_map (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id),
    flight_id    BIGINT REFERENCES patrol_flight(id) ON DELETE SET NULL,
    map_type     VARCHAR(20) NOT NULL,  -- fertilize/spray
    zones        JSONB NOT NULL,        -- 分区 GeoJSON FeatureCollection，每个 Feature 含 amount 属性
    status       VARCHAR(20) DEFAULT 'draft',  -- draft/confirmed/executed
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块五：水肥一体化 ====================

CREATE TABLE irrigation_device (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id),
    device_sn    VARCHAR(64) NOT NULL UNIQUE,
    device_name  VARCHAR(100),
    zone_count   INT DEFAULT 1,         -- 分区数
    online       BOOLEAN DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE irrigation_task (
    id              BIGSERIAL PRIMARY KEY,
    field_id        BIGINT NOT NULL REFERENCES field(id),
    device_id       BIGINT NOT NULL REFERENCES irrigation_device(id),
    zone_ids        INT[] NOT NULL,
    trigger_type    VARCHAR(20) NOT NULL,  -- manual/scheduled/sensor
    water_amount_m3 DECIMAL(8,2),
    fertilizer      JSONB,                 -- {n_kg, p_kg, k_kg, concentration_pct}
    duration_min    INT,
    status          VARCHAR(20) DEFAULT 'pending',  -- pending/running/done/failed
    scheduled_at    TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块六：知识库 ====================

CREATE TABLE knowledge_chunk (
    id           BIGSERIAL PRIMARY KEY,
    source_title VARCHAR(200),
    content      TEXT NOT NULL,
    embedding    VECTOR(1536),          -- bge-m3 输出维度
    metadata     JSONB,                 -- {category, variety, growth_stage, ...}
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_knowledge_embedding ON knowledge_chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE TABLE qa_log (
    id           BIGSERIAL PRIMARY KEY,
    question     TEXT NOT NULL,
    answer       TEXT NOT NULL,
    sources      JSONB,                 -- 引用的 chunk id 列表
    field_id     BIGINT REFERENCES field(id),
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 2. 模块一：农田信息管理

### 2.1 API 定义

```
POST   /api/fields                  # 新建地块
GET    /api/fields                  # 分页查询（?page&size&keyword）
GET    /api/fields/{id}             # 地块详情
PUT    /api/fields/{id}             # 修改地块
DELETE /api/fields/{id}             # 删除地块

GET    /api/fields/{id}/events?page=0&size=20      # 农事记录分页，按农事日期倒序
POST   /api/fields/{id}/events      # 新增农事记录

GET    /api/fields/stats            # 汇总统计（总地块数、总面积、各品种占比）
```

### 2.2 请求/响应示例

```jsonc
// POST /api/fields  请求体
{
  "name": "东区3号田",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[115.456, 36.123], [115.460, 36.123],
                     [115.460, 36.126], [115.456, 36.126],
                     [115.456, 36.123]]]
  },
  "soilType": "壤土",
  "variety": "花育25号",
  "sowDate": "2024-05-10",
  "ownerName": "张三"
}

// 响应 data 字段
{
  "id": 101,
  "name": "东区3号田",
  "areaMu": 12.35,          // 后端自动由 geometry 计算
  "soilType": "壤土",
  "variety": "花育25号",
  "sowDate": "2024-05-10",
  "currentGrowthStage": "花针期",   // 根据 sowDate 自动推算
  "daysAfterSow": 42
}
```

创建或修改地块时，`geometry` 支持 GeoJSON 对象及其序列化字符串。地块响应中的 `geometry` 始终是 GeoJSON 对象。统计接口的 `data` 为 `{totalCount, totalAreaMu, varietyStats:[{variety,count,areaMu}]}`。

本地后端与数据库启动后，在 `frontend/` 执行 `npm ci` 和 `npm run dev`，浏览器访问 `http://localhost:3000`。在 `frontend/.env.local` 中配置 `VITE_TIANDITU_TOKEN` 并重启前端可切换到天地图影像。未配置时使用 Esri 卫星影像。

### 2.3 生育期推算算法

```java
// backend/.../field/FieldService.java
public String calcGrowthStage(LocalDate sowDate) {
    int days = (int) ChronoUnit.DAYS.between(sowDate, LocalDate.now());
    if (days < 0)   return "未播种";
    if (days < 25)  return "苗期";
    if (days < 55)  return "花针期";
    if (days < 85)  return "结荚期";
    if (days < 120) return "饱果期";
    return "收获期";
}
```

### 2.4 面积计算

```java
// 在 Service 层，接收到 GeoJSON 后用 PostGIS 计算面积
// SQL：SELECT ST_Area(ST_Transform(ST_GeomFromGeoJSON(:geojson), 32650)) / 666.67 AS area_mu
// 32650 = WGS84 UTM Zone 50N，单位米，666.67 m² = 1 亩
```

### 2.5 验收标准

- [x] `POST /api/fields` 传入 GeoJSON Polygon，响应 `areaMu` 精度 ±0.1 亩
- [x] `GET /api/fields` 支持 `keyword` 模糊匹配地块名、品种名
- [x] `currentGrowthStage` 随系统日期动态变化，无需手动更新
- [x] 删除地块时，级联删除关联的 farm_event、patrol_task（ON DELETE CASCADE 已在 DDL 配置）

使用 Java 21 在 `backend/` 执行 `mvn test` 可运行生育期单元测试。验证 PostGIS 面积及 API 时，准备一个安装了 PostGIS 和 pgvector 的临时 PostgreSQL 16 数据库，设置 `SPRING_DATASOURCE_URL`、`DB_USER`、`DB_PASS` 和 `PEANUT_TEST_POSTGIS=true`，再执行同一命令。

---

## 3. 模块二：智能巡田与精细监测

### 3.1 API 定义

```
POST   /api/patrol/tasks                    # 创建巡田任务（含航线+定时）
GET    /api/patrol/tasks?fieldId=&page=     # 任务列表
PUT    /api/patrol/tasks/{id}/status        # 启用/暂停任务

GET    /api/patrol/flights?fieldId=&page=   # 飞行记录列表
GET    /api/patrol/flights/{id}             # 飞行详情（含 NDVI 统计）
POST   /api/patrol/flights/{id}/trigger     # 手动触发一次飞行

GET    /api/iot/sensors?fieldId=            # 传感器实时数据
GET    /api/iot/sensors/{sn}/history        # 传感器历史数据（?start&end&interval）
```

### 3.2 无人机任务下发（MQTT）

```
// 下发 topic：drone/{drone_sn}/cmd/task
// 消息体（JSON）
{
  "taskId": 55,
  "flightId": 201,
  "action": "start",
  "route": {
    "type": "FeatureCollection",
    "features": [/* 航点 GeoJSON */]
  },
  "returnPolicy": "auto_land"
}

// 无人机状态上报 topic：drone/{drone_sn}/status
{
  "flightId": 201,
  "phase": "flying",          // takeoff/flying/landing/charging/idle
  "battery": 78,
  "lat": 36.124,
  "lng": 115.457,
  "altitude": 80,
  "timestamp": 1720000000000
}
```

### 3.3 NDVI 影像处理流水线（Python）

> 文件路径：`ai-service/ndvi_processor/processor.py`

```python
import rasterio
import numpy as np
from rasterio.transform import from_bounds

def compute_ndvi(tif_path: str, output_path: str) -> dict:
    """
    输入：多光谱 GeoTIFF（至少含红波段 band=3，近红外波段 band=4）
    输出：NDVI GeoTIFF + 统计信息字典
    """
    with rasterio.open(tif_path) as src:
        red = src.read(3).astype(float)   # band 3 = Red
        nir = src.read(4).astype(float)   # band 4 = NIR
        profile = src.profile.copy()

    # 计算 NDVI，避免除零
    with np.errstate(divide='ignore', invalid='ignore'):
        ndvi = np.where(
            (nir + red) == 0,
            -1,
            (nir - red) / (nir + red)
        )

    # 写出单波段 Float32 GeoTIFF
    profile.update(dtype=rasterio.float32, count=1, nodata=-9999)
    with rasterio.open(output_path, 'w', **profile) as dst:
        dst.write(ndvi.astype(rasterio.float32), 1)

    valid = ndvi[ndvi > -1]
    return {
        "min": float(valid.min()),
        "max": float(valid.max()),
        "mean": float(valid.mean()),
        "std": float(valid.std()),
        "red_pct": float((valid < 0.3).sum() / valid.size * 100),     # 胁迫
        "yellow_pct": float(((valid >= 0.3) & (valid < 0.6)).sum() / valid.size * 100),
        "green_pct": float((valid >= 0.6).sum() / valid.size * 100),  # 良好
    }
```

### 3.4 传感器数据写入 InfluxDB

```python
# ai-service/iot_writer.py
from influxdb_client import InfluxDBClient, Point, WritePrecision

def write_sensor(device_sn: str, field_id: int, metrics: dict):
    """
    metrics 示例：
    {"soil_temp": 24.5, "soil_moisture": 42.1, "soil_n": 85, "soil_p": 32, "soil_k": 120}
    """
    client = InfluxDBClient(url=INFLUXDB_URL, token=INFLUXDB_TOKEN, org=ORG)
    write_api = client.write_api()
    point = (
        Point("soil_sensor")
        .tag("device_sn", device_sn)
        .tag("field_id", str(field_id))
    )
    for k, v in metrics.items():
        point = point.field(k, float(v))
    write_api.write(bucket=BUCKET, record=point)
```

### 3.5 验收标准

- [ ] 创建巡田任务后，MQTT 按 cron 表达式自动下发飞行指令
- [ ] 飞行结束后，`patrol_flight.status` 自动更新为 `processing`，NDVI 处理完成后更新为 `done`
- [ ] `ndvi_stats` 中三色占比之和 = 100%（±0.1 允许误差）
- [ ] 传感器历史接口支持 `interval` 参数（1m/5m/1h）返回聚合数据

---

## 4. 模块三：病虫害识别与预警

### 4.1 API 定义

```
POST   /api/pest/detect/upload       # 手动上传图片检测
GET    /api/pest/detections          # 检测记录列表（?fieldId&start&end&page）
GET    /api/pest/detections/{id}     # 检测详情
POST   /api/pest/detections/{id}/expert   # 提交专家复核
```

### 4.2 AI 检测服务接口（FastAPI）

> 文件路径：`ai-service/pest_detection/main.py`

```python
from fastapi import FastAPI, UploadFile
from ultralytics import YOLO
import cv2, numpy as np

app = FastAPI()
model = YOLO("weights/peanut_pest_v8.pt")  # 训练好的权重

LABEL_MAP = {
    0: "叶斑病", 1: "锈病", 2: "茎腐病", 3: "白绢病",
    4: "蚜虫",   5: "红蜘蛛", 6: "蛴螬", 7: "棉铃虫"
}

@app.post("/detect")
async def detect(file: UploadFile, flight_id: int = None):
    img_bytes = await file.read()
    nparr = np.frombuffer(img_bytes, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    results = model(img, conf=0.45, iou=0.5)[0]

    detections = []
    for box in results.boxes:
        cls_id = int(box.cls.item())
        detections.append({
            "type": "disease" if cls_id < 4 else "insect",
            "name": LABEL_MAP[cls_id],
            "confidence": round(float(box.conf.item()), 3),
            "bbox": [int(x) for x in box.xyxy[0].tolist()]
        })

    return {"detections": detections, "count": len(detections)}
```

### 4.3 预警推送逻辑（后端 Java）

```java
// PestAlertService.java
// 规则：同一地块同一病害，24小时内不重复推送
public void processAlert(PestDetection detection) {
    List<Detection> items = detection.getDetections();
    if (items == null || items.isEmpty()) return;

    boolean alreadySent = pestDetectionRepo.existsAlertWithin24h(
        detection.getFieldId(),
        items.stream().map(Detection::getName).toList()
    );
    if (alreadySent) return;

    // 构造推送消息
    String msg = buildAlertMsg(detection);
    wechatNotifyService.send(detection.getFieldId(), msg);  // 推送至绑定手机
    detection.setAlertSent(true);
    pestDetectionRepo.save(detection);
}

private String buildAlertMsg(PestDetection d) {
    return String.format(
        "【病虫害预警】%s 检测到 %s，建议尽快查看。位置：%s",
        d.getField().getName(),
        d.getDetections().stream()
            .map(Detection::getName)
            .distinct()
            .collect(Collectors.joining("、")),
        d.getImageUrl()
    );
}
```

### 4.4 检测结果 JSON 结构（存入 `pest_detection.detections`）

```json
[
  {
    "type": "disease",
    "name": "叶斑病",
    "confidence": 0.91,
    "bbox": [1024, 768, 1280, 960],
    "geoLocation": {"lat": 36.123, "lng": 115.456},
    "areaMu": 0.08
  }
]
```

### 4.5 验收标准

- [ ] 上传图片 → 2 秒内返回检测结果
- [ ] 同一地块同一病害 24 小时内只推送一次预警
- [ ] 检测结果在前端地图上以红色圆点标注（含 bbox 预览弹窗）
- [ ] 支持 `source=flight` 的自动检测记录与 `source=manual_upload` 的人工上传记录统一管理

---

## 5. 模块四：智能分析与处方图生成

### 5.1 API 定义

```
POST   /api/prescriptions                   # 基于 flightId 自动生成处方图
GET    /api/prescriptions?fieldId=&page=    # 处方图列表
GET    /api/prescriptions/{id}              # 处方图详情（含 zones GeoJSON）
PUT    /api/prescriptions/{id}/zones        # 编辑分区作业量
POST   /api/prescriptions/{id}/confirm      # 确认并下发执行
```

### 5.2 处方图生成算法（Python）

> 文件路径：`ai-service/prescription/generator.py`

```python
import numpy as np
import rasterio
from scipy.interpolate import griddata
from shapely.geometry import shape, mapping
import geopandas as gpd

# ---- 施肥推荐量查表（kg/亩） ----
FERT_TABLE = {
    # (ndvi_grade, n_grade) -> (N, P, K) kg/亩
    ("low",    "low"):    (8.0, 5.0, 6.0),
    ("low",    "medium"): (6.0, 4.0, 5.0),
    ("low",    "high"):   (4.0, 3.0, 4.0),
    ("medium", "low"):    (5.0, 4.0, 5.0),
    ("medium", "medium"): (4.0, 3.0, 4.0),
    ("medium", "high"):   (3.0, 2.0, 3.0),
    ("high",   "low"):    (3.0, 3.0, 4.0),
    ("high",   "medium"): (2.0, 2.0, 3.0),
    ("high",   "high"):   (1.0, 1.0, 2.0),
}

def ndvi_grade(val: float) -> str:
    if val < 0.3: return "low"
    if val < 0.6: return "medium"
    return "high"

def n_grade(val: float) -> str:
    """土壤速效氮 mg/kg"""
    if val < 60: return "low"
    if val < 100: return "medium"
    return "high"

def generate_prescription(
    ndvi_tif: str,          # NDVI GeoTIFF 路径
    soil_points: list,      # [{"lat":, "lng":, "n_mgkg":}, ...]
    field_geojson: dict,    # 地块 GeoJSON Polygon
    map_type: str = "fertilize"   # fertilize / spray
) -> dict:
    """
    返回 GeoJSON FeatureCollection，每个 Feature 为一个管理分区
    Feature.properties 含 amount_n, amount_p, amount_k（单位 kg/亩）
    """
    with rasterio.open(ndvi_tif) as src:
        ndvi_arr = src.read(1)
        transform = src.transform
        crs = src.crs

    # 1. 将地块分为 N×N 网格
    field_poly = shape(field_geojson)
    bounds = field_poly.bounds  # (minx, miny, maxx, maxy)
    grid_n = 10  # 10×10 网格
    xs = np.linspace(bounds[0], bounds[2], grid_n)
    ys = np.linspace(bounds[1], bounds[3], grid_n)

    # 2. 在每个网格中心采样 NDVI 均值
    zones = []
    for i in range(grid_n - 1):
        for j in range(grid_n - 1):
            cell = {
                "type": "Polygon",
                "coordinates": [[
                    [xs[i], ys[j]], [xs[i+1], ys[j]],
                    [xs[i+1], ys[j+1]], [xs[i], ys[j+1]],
                    [xs[i], ys[j]]
                ]]
            }
            cell_poly = shape(cell)
            if not cell_poly.intersects(field_poly):
                continue

            # 采样该格内 NDVI 均值（通过 rasterio window）
            row_off, col_off = rasterio.transform.rowcol(
                transform, xs[i], ys[j]
            )
            cell_ndvi = float(np.nanmean(ndvi_arr[
                max(0, row_off-2):row_off+2,
                max(0, col_off-2):col_off+2
            ]))

            # 插值土壤氮值
            cell_cx = (xs[i] + xs[i+1]) / 2
            cell_cy = (ys[j] + ys[j+1]) / 2
            pts = np.array([[p["lng"], p["lat"]] for p in soil_points])
            vals = np.array([p["n_mgkg"] for p in soil_points])
            cell_n = float(griddata(pts, vals, (cell_cx, cell_cy), method="linear"))

            # 查表得施肥量
            ng = ndvi_grade(cell_ndvi)
            nn = n_grade(cell_n if not np.isnan(cell_n) else 80)
            n_kg, p_kg, k_kg = FERT_TABLE[(ng, nn)]

            zones.append({
                "type": "Feature",
                "geometry": mapping(cell_poly.intersection(field_poly)),
                "properties": {
                    "ndvi_mean": round(cell_ndvi, 3),
                    "ndvi_grade": ng,
                    "n_grade": nn,
                    "amount_n": n_kg,
                    "amount_p": p_kg,
                    "amount_k": k_kg
                }
            })

    return {"type": "FeatureCollection", "features": zones}
```

### 5.3 处方图下发（MQTT）

```
// 下发 topic：device/{device_sn}/cmd/prescription
{
  "prescriptionId": 88,
  "mapType": "fertilize",
  "zones": [
    {"zoneId": 1, "amount_n": 4.0, "amount_p": 3.0, "amount_k": 4.0}
  ]
}
```

### 5.4 验收标准

- [ ] 传入 flightId → 30 秒内生成处方图（含 zones GeoJSON）
- [ ] 处方图前端可视化：每个分区按施肥量深浅着色，hover 显示具体用量
- [ ] 编辑分区量后调用 `PUT /prescriptions/{id}/zones`，状态保持 `draft`
- [ ] `confirm` 后自动触发 MQTT 下发，状态变为 `executed`，不可再编辑

---

## 6. 模块五：水肥一体化远程管控

### 6.1 API 定义

```
GET    /api/irrigation/devices?fieldId=         # 设备列表（含在线状态）
GET    /api/irrigation/devices/{id}/status      # 设备实时状态（阀门开关、流量）

POST   /api/irrigation/tasks                    # 创建灌溉任务
GET    /api/irrigation/tasks?fieldId=&page=     # 任务记录
POST   /api/irrigation/tasks/{id}/cancel        # 取消待执行任务

POST   /api/irrigation/devices/{id}/control     # 手动控制（开/关指定分区阀门）
```

### 6.2 设备指令下发（MQTT）

```
// 下发 topic：irrigation/{device_sn}/cmd
{
  "taskId": 302,
  "action": "start",          // start / stop / pause
  "zones": [1, 2],
  "durationMin": 30,
  "fertilizerConfig": {
    "nKg": 2.5,
    "pKg": 1.0,
    "kKg": 2.0,
    "concentrationPct": 0.3
  }
}

// 设备状态上报 topic：irrigation/{device_sn}/status
{
  "taskId": 302,
  "phase": "running",          // idle/running/done/fault
  "zones": [
    {"id": 1, "valve": true, "flowLmin": 12.5},
    {"id": 2, "valve": true, "flowLmin": 11.8}
  ],
  "totalWaterM3": 0.42,
  "timestamp": 1720001234000
}
```

### 6.3 传感器自动触发逻辑（后端调度）

```java
// IrrigationAutoTriggerJob.java（每 10 分钟执行一次）
@Scheduled(fixedDelay = 600_000)
public void checkAndTrigger() {
    List<Field> fields = fieldRepo.findAllActive();
    for (Field field : fields) {
        // 查最新土壤含水量（InfluxDB 查询）
        double moisture = iotService.getLatestMoisture(field.getId());
        double threshold = field.getIrrigationThreshold();  // 默认 60%

        if (moisture >= threshold) continue;

        // 24 小时内已触发过则跳过
        boolean recentTrigger = irrigationTaskRepo
            .existsRecentSensorTask(field.getId(), LocalDateTime.now().minusHours(24));
        if (recentTrigger) continue;

        // 创建自动灌溉任务
        IrrigationTask task = IrrigationTask.builder()
            .fieldId(field.getId())
            .deviceId(field.getDefaultDeviceId())
            .zoneIds(new int[]{1})
            .triggerType("sensor")
            .durationMin(field.getDefaultDurationMin())
            .status("pending")
            .build();
        irrigationTaskRepo.save(task);
        mqttService.sendIrrigationCmd(task);
    }
}
```

### 6.4 验收标准

- [ ] 设备 MQTT 心跳 30 秒，超过 90 秒未上报则 `online = false`
- [ ] 手动控制指令下发后 3 秒内设备状态刷新至前端（WebSocket 推送）
- [ ] 自动触发任务在 24 小时内同一地块只执行一次
- [ ] 任务历史记录含实际用水量（从设备状态上报累计）

---

## 7. 模块六：知识库与智能问答

### 7.1 API 定义

```
POST   /api/knowledge/chunks            # 录入知识片段（管理员）
POST   /api/knowledge/qa                # 智能问答
GET    /api/knowledge/qa/history        # 问答历史（?fieldId&page）

GET    /api/reports/growth/{fieldId}    # 生成生长报告（返回 PDF URL）
```

### 7.2 RAG 问答服务（Python FastAPI）

> 文件路径：`ai-service/rag/main.py`

```python
from fastapi import FastAPI
from pydantic import BaseModel
from langchain_community.vectorstores.pgvector import PGVector
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_community.llms import Ollama
from langchain.chains import RetrievalQA
from langchain.prompts import PromptTemplate

app = FastAPI()

# 初始化 Embedding 模型（bge-m3，本地加载）
embeddings = HuggingFaceEmbeddings(model_name="BAAI/bge-m3")

# 连接 pgvector 向量库
vectorstore = PGVector(
    connection_string="postgresql://user:pass@localhost/peanut",
    embedding_function=embeddings,
    collection_name="knowledge_chunk"
)

# 本地 LLM（Qwen2-7B via Ollama）
llm = Ollama(model="qwen2:7b", temperature=0.3)

PROMPT_TEMPLATE = """你是一位花生种植领域的农业专家助手。
请根据以下参考资料回答问题，只使用参考资料中的信息，如果资料不足请说明。

参考资料：
{context}

问题：{question}

请用中文给出专业、简洁的回答："""

prompt = PromptTemplate(
    input_variables=["context", "question"],
    template=PROMPT_TEMPLATE
)

qa_chain = RetrievalQA.from_chain_type(
    llm=llm,
    retriever=vectorstore.as_retriever(search_kwargs={"k": 5}),
    chain_type_kwargs={"prompt": prompt},
    return_source_documents=True
)

class QARequest(BaseModel):
    question: str
    field_id: int | None = None

@app.post("/qa")
def answer(req: QARequest):
    result = qa_chain.invoke({"query": req.question})
    source_ids = [
        doc.metadata.get("chunk_id")
        for doc in result["source_documents"]
    ]
    return {
        "answer": result["result"],
        "sources": source_ids
    }
```

### 7.3 知识片段录入格式

```json
// POST /api/knowledge/chunks
{
  "sourceTitle": "花生育25号栽培技术手册",
  "content": "花育25号适宜在山东、河南、河北地区种植，全生育期约125天。花针期需保证土壤含水量在70%田间持水量以上，该阶段是产量形成的关键期，应避免干旱胁迫。",
  "metadata": {
    "category": "variety",
    "variety": "花育25号",
    "growthStage": "花针期"
  }
}
```

### 7.4 自动生长报告生成（每周一凌晨 2 点触发）

```java
// GrowthReportJob.java
@Scheduled(cron = "0 0 2 * * MON")
public void generateWeeklyReports() {
    List<Field> activeFields = fieldRepo.findByStatusAndSowDateNotNull("active");
    for (Field field : activeFields) {
        GrowthReportData data = GrowthReportData.builder()
            .fieldName(field.getName())
            .variety(field.getVariety())
            .growthStage(fieldService.calcGrowthStage(field.getSowDate()))
            .daysAfterSow(fieldService.calcDaysAfterSow(field.getSowDate()))
            .weeklyAvgMoisture(iotService.getWeeklyAvg(field.getId(), "soil_moisture"))
            .weeklyAvgTemp(iotService.getWeeklyAvg(field.getId(), "soil_temp"))
            .recentFarmEvents(farmEventRepo.findRecent7Days(field.getId()))
            .pestAlertCount(pestDetectionRepo.countRecent7Days(field.getId()))
            .build();

        byte[] pdf = reportRenderService.renderPdf(data);  // 使用 Thymeleaf + Flying Saucer 渲染
        String url = ossService.upload("reports/" + field.getId() + "_" + LocalDate.now() + ".pdf", pdf);
        wechatNotifyService.sendReport(field.getId(), url);
    }
}
```

### 7.5 验收标准

- [ ] 问答接口平均响应时间 < 5 秒（本地 7B 模型）
- [ ] 答案末尾显示引用来源（知识片段标题）
- [ ] 每周一自动推送 PDF 生长报告至绑定手机
- [ ] 知识库支持批量导入（CSV 格式：sourceTitle, content, category, variety, growthStage）

---

## 8. 前端关键页面规范

### 8.1 "一张图"主地图页

```
组件：MapView.vue
地图库：MapLibre GL JS 4.x
图层叠加顺序（从下到上）：
  1. 底图（天地图影像）
  2. 地块填充层（field-fill）：点击选中地块，高亮显示
  3. 地块描边层（field-line）
  4. NDVI 切片层（ndvi-raster）：透明度 0.7，可切换显示/隐藏
  5. 病虫害标注层（pest-markers）：红色圆点，点击弹窗
  6. 传感器站点层（sensor-markers）：蓝色图标

交互：
  - 右键地块 → 弹出菜单（查看详情/新建巡田任务/新建灌溉任务）
  - 左侧面板：地块列表，点击定位并高亮
  - 右上角：图层开关（NDVI/病虫害/传感器）
```

### 8.2 WebSocket 实时推送

```javascript
// frontend/src/composables/useRealtimeStatus.js
// 后端推送 topic 格式：/topic/field/{fieldId}/status
import { useWebSocket } from '@vueuse/core'

export function useRealtimeStatus(fieldId) {
  const { data } = useWebSocket(`ws://${API_HOST}/ws`, {
    onConnected(ws) {
      ws.send(JSON.stringify({ type: 'subscribe', fieldId }))
    }
  })
  return { data }
}
// 收到消息后更新 Pinia store，地图图层自动响应
```

---

## 9. 部署配置

### 9.1 docker-compose.yml（开发环境）

```yaml
version: '3.9'
services:
  postgres:
    image: postgis/postgis:16-3.4
    environment:
      POSTGRES_DB: peanut
      POSTGRES_USER: peanut
      POSTGRES_PASSWORD: peanut123
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]

  influxdb:
    image: influxdb:2.7
    environment:
      DOCKER_INFLUXDB_INIT_MODE: setup
      DOCKER_INFLUXDB_INIT_ORG: peanut
      DOCKER_INFLUXDB_INIT_BUCKET: sensors
      DOCKER_INFLUXDB_INIT_ADMIN_TOKEN: peanut-token-dev
    ports: ["8086:8086"]

  emqx:
    image: emqx:5.6
    ports: ["1883:1883", "18083:18083"]

  rabbitmq:
    image: rabbitmq:3.13-management
    ports: ["5672:5672", "15672:15672"]

  ai-service:
    build: ./ai-service
    ports: ["8001:8001"]
    volumes: ["./ai-service/weights:/app/weights"]
    depends_on: [postgres]

  backend:
    build: ./backend
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/peanut
      INFLUXDB_URL: http://influxdb:8086
      MQTT_BROKER_URL: tcp://emqx:1883
    depends_on: [postgres, influxdb, emqx, rabbitmq]

  frontend:
    build: ./frontend
    ports: ["3000:80"]
    depends_on: [backend]

volumes:
  pgdata:
```

### 9.2 application.yml 关键配置项

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/peanut}
    username: ${DB_USER:peanut}
    password: ${DB_PASS:peanut123}
  flyway:
    enabled: true
    locations: classpath:db/migration

influxdb:
  url: ${INFLUXDB_URL:http://localhost:8086}
  token: ${INFLUXDB_TOKEN:peanut-token-dev}
  org: peanut
  bucket: sensors

mqtt:
  broker-url: ${MQTT_BROKER_URL:tcp://localhost:1883}
  client-id: peanut-backend-${random.uuid}
  qos: 1

ai-service:
  base-url: ${AI_SERVICE_URL:http://localhost:8001}

jwt:
  secret: ${JWT_SECRET:change-me-in-production}
  access-expire-ms: 7200000    # 2h
  refresh-expire-ms: 604800000 # 7d
```

---

## 10. 完整实现 Checklist

按顺序完成每项，标注 `[x]` 表示已完成。

```
基础设施
[ ] docker-compose.yml 启动全部服务
[x] V1__init.sql 执行成功，所有表和索引创建完毕
[x] 后端 Spring Boot 启动，/actuator/health 返回 UP

模块一：农田管理
[x] POST /api/fields 创建地块，面积自动计算
[x] GET /api/fields 分页+关键词搜索
[x] 生育期推算随日期变化正确
[x] 前端地块圈画（MapLibre draw 插件）+ 提交

模块二：智能巡田
[ ] EMQX 收到设备注册，drone_sn 写入 patrol_task
[ ] NDVI 处理流水线 end-to-end 测试（输入 TIF → 输出统计）
[ ] InfluxDB 传感器数据写入 + 查询历史接口

模块三：病虫害
[ ] AI 推理服务 /detect 接口单测（上传测试图片）
[ ] 检测结果写入 pest_detection 表
[ ] 预警推送不重复触发（24h 去重）

模块四：处方图
[ ] 处方图生成函数单测（mock NDVI + soil_points）
[ ] 前端分区着色可视化
[ ] confirm 后 MQTT 下发指令

模块五：水肥控制
[ ] 设备心跳超时自动下线
[ ] 手动控制指令下发 + 状态实时刷新
[ ] 传感器自动触发灌溉（联调测试）

模块六：知识库
[ ] pgvector 索引创建 + embedding 写入测试
[ ] RAG 问答接口返回答案 + 引用来源
[ ] 周报定时任务触发 + PDF 生成

集成测试
[ ] 完整业务流：创建地块 → 触发巡田 → NDVI 生成 → 病虫害检测 → 处方图生成 → 灌溉执行
[ ] 前端端到端（E2E）：地图圈地、问答、处方图编辑
```
