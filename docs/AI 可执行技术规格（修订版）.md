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
    embedding    VECTOR(1536),          -- BGE-M3 的 1024 维结果统一补零后写入
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

### 1.1 已部署项目的增量迁移（V5）

> **重要：** 现场 Flyway 已执行 V1 初始化、V2 周报、V3 巡田监测和 V4 单次计划。已执行迁移均不可修改。经用户确认，设备管理使用下一个版本 `V5__patrol_device_management_and_schema_alignment.sql`，保留存量数据和 Flyway 历史。全新数据库按 V1 至 V5 顺序执行。

文件路径：`backend/src/main/resources/db/migration/V5__patrol_device_management_and_schema_alignment.sql`。以下为迁移目标结构，执行脚本以仓库中的 SQL 文件为准。

```sql
-- 模块二：巡田设备统一台账（仅管理无人机、机库、巡田传感器）
CREATE TABLE patrol_device (
    id                  BIGSERIAL PRIMARY KEY,
    device_type         VARCHAR(20) NOT NULL CHECK (device_type IN ('drone','dock','sensor')),
    device_sn           VARCHAR(64) NOT NULL UNIQUE,
    device_name         VARCHAR(100) NOT NULL,
    brand               VARCHAR(80),
    model               VARCHAR(100),
    field_id            BIGINT REFERENCES field(id) ON DELETE RESTRICT,
    parent_device_id    BIGINT REFERENCES patrol_device(id) ON DELETE SET NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','inactive','maintenance','retired')),
    online              BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at        TIMESTAMPTZ,
    current_phase       VARCHAR(30),
    last_status         JSONB NOT NULL DEFAULT '{}'::jsonb,
    battery_pct         DECIMAL(5,2) CHECK (battery_pct IS NULL OR battery_pct BETWEEN 0 AND 100),
    longitude           DECIMAL(10,7),
    latitude            DECIMAL(10,7),
    altitude_m          DECIMAL(8,2),
    firmware_version    VARCHAR(80),
    capabilities        JSONB NOT NULL DEFAULT '{}'::jsonb,
    fault_code          VARCHAR(80),
    fault_message       TEXT,
    installed_at        TIMESTAMPTZ,
    last_maintenance_at TIMESTAMPTZ,
    next_maintenance_at TIMESTAMPTZ,
    remark              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (device_type <> 'sensor' OR field_id IS NOT NULL),
    CHECK (device_type <> 'dock' OR parent_device_id IS NULL)
);
CREATE INDEX idx_patrol_device_field_type ON patrol_device(field_id, device_type);
CREATE INDEX idx_patrol_device_online ON patrol_device(device_type, online, status);
CREATE INDEX idx_patrol_device_parent ON patrol_device(parent_device_id);

CREATE TABLE patrol_device_maintenance (
    id                  BIGSERIAL PRIMARY KEY,
    device_id           BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE RESTRICT,
    maintenance_type    VARCHAR(30) NOT NULL, -- inspection/repair/firmware/battery/calibration/other
    status              VARCHAR(20) NOT NULL DEFAULT 'completed', -- planned/in_progress/completed/cancelled
    description         TEXT NOT NULL,
    operator_name       VARCHAR(80),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    next_maintenance_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_patrol_maintenance_device ON patrol_device_maintenance(device_id, created_at DESC);

-- 巡田任务关联已登记无人机；scheduled_at 和 ndvi_image_path 分别由 V4 和 V3 建立。
ALTER TABLE patrol_task ADD COLUMN drone_device_id BIGINT REFERENCES patrol_device(id) ON DELETE RESTRICT;
ALTER TABLE patrol_task ADD COLUMN requires_dock BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE patrol_flight ADD COLUMN drone_device_id BIGINT REFERENCES patrol_device(id) ON DELETE RESTRICT;
CREATE INDEX idx_patrol_task_drone ON patrol_task(drone_device_id);
CREATE INDEX idx_patrol_flight_drone ON patrol_flight(drone_device_id);

-- 模块五原算法引用了 V1 中不存在的地块字段，统一在本次增量迁移补齐。
ALTER TABLE field ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';
ALTER TABLE field ADD COLUMN irrigation_threshold_pct DECIMAL(5,2) NOT NULL DEFAULT 60.00;
ALTER TABLE field ADD COLUMN default_irrigation_duration_min INT NOT NULL DEFAULT 30;
ALTER TABLE field ADD COLUMN default_irrigation_device_id BIGINT REFERENCES irrigation_device(id) ON DELETE SET NULL;

-- 模块五历史记录原先只接收设备上报，却没有实际用水量列。
ALTER TABLE irrigation_task ADD COLUMN actual_water_m3 DECIMAL(8,2);
ALTER TABLE irrigation_task ADD COLUMN last_status_at TIMESTAMPTZ;
ALTER TABLE irrigation_device ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive','maintenance','retired'));
```

迁移注意事项：
- V1 的 `patrol_task.drone_sn` 可能是机库或无人机序列号，V5 保留旧列和原值。管理员核对设备类型后登记真实无人机，再用 `PUT /api/patrol/tasks/{id}/drone` 明确绑定 `droneDeviceId`。新任务一律要求 `droneDeviceId`。
- 新建巡田任务必须通过 `drone_device_id` 关联 `patrol_device` 中 `device_type='drone'` 且 `status='active'` 的设备；`drone_sn` 仅用于旧数据兼容，不再作为新建任务的输入字段。
- 设备采用逻辑退役，不物理删除有任务/飞行/维护历史的设备；API 的 DELETE 实际将 `status` 设为 `retired`。由于设备与地块采用 RESTRICT 关联，删除地块前必须先解绑或迁移关联设备，不能级联丢失设备归属。
- `patrol_device` 不包含模块五的灌溉控制器。巡田无人机/机库/土壤传感器与 `irrigation_device` 是两类设备台账，分别管理。

---

## 2. 模块一：农田信息管理

### 2.1 API 定义

```
POST   /api/fields                  # 新建地块
GET    /api/fields                  # 分页查询（?page&size&keyword）
GET    /api/fields/{id}             # 地块详情
PUT    /api/fields/{id}             # 修改地块
DELETE /api/fields/{id}             # 删除地块；存在绑定设备时需先解绑/迁移

GET    /api/fields/{id}/events?page=0&size=20      # 农事记录分页，按农事日期倒序
POST   /api/fields/{id}/events      # 新增农事记录

GET    /api/fields/stats            # 汇总统计（总地块数、总面积、各品种占比）
```

### 2.2 请求/响应示例

```jsonc
// POST /api/fields  请求体
{
  "name": "东区3号田",
  "geometry": "{\"type\":\"Polygon\",\"coordinates\":[[[115.456,36.123],[115.460,36.123],[115.460,36.126],[115.456,36.126],[115.456,36.123]]]}",
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

创建或修改地块时，`geometry` 使用序列化后的 GeoJSON 字符串。地块响应中的 `geometry` 始终是 GeoJSON 对象。统计接口的 `data` 为 `{totalCount, totalAreaMu, varietyStats:[{variety,count,areaMu}]}`。

本地后端与数据库启动后，在 `frontend/` 执行 `npm ci` 和 `npm run dev`，浏览器访问 `http://localhost:3000`。在 `frontend/.env.local` 中配置 `VITE_TIANDITU_TOKEN` 并重启前端可切换到天地图影像。未配置时使用 Esri 卫星影像。绘图使用 MapLibre 官方示例采用的 npm 包 `@mapbox/mapbox-gl-draw`。

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
- [x] 在本地浏览器加载天地图真实影像，并验证圈地保存、地块高亮与详情、农事记录、生育期更新和面积统计。

使用 Java 21 在 `backend/` 执行 `mvn test` 可运行生育期单元测试。验证 PostGIS 面积及 API 时，准备一个安装了 PostGIS 和 pgvector 的临时 PostgreSQL 16 数据库，设置 `SPRING_DATASOURCE_URL`、`DB_USER`、`DB_PASS` 和 `PEANUT_TEST_POSTGIS=true`，再执行同一命令。

---

## 3. 模块二：智能巡田与精细监测

模块二由五个子页面组成：**设备管理、巡田任务、飞行记录、NDVI 监测、传感器监测**。业务顺序是先登记设备并绑定地块，再创建巡田任务；任务只允许选择系统中已登记且状态可用的无人机，飞行数据与传感器数据都必须能追溯到设备台账。

### 3.1 设备类型与管理规则

`patrol_device` 是模块二统一设备台账，`device_type` 仅允许：

| 类型 | 用途 | 关联规则 | 核心状态信息 |
|---|---|---|---|
| `drone` 无人机 | 执行巡田航线、采集多光谱影像 | 可选绑定一个 `dock`；可绑定负责地块，也可作为跨地块共享设备 | 在线状态、电量、位置、高度、飞行阶段、固件、故障 |
| `dock` 机库 | 无人机自动起降、充电、停放（设备确实支持时） | 可关联一个地块；无人机通过 `parent_device_id` 关联机库 | 在线状态、位置、舱门/充电状态、故障 |
| `sensor` 巡田传感器 | 采集土壤温度、湿度及 N/P/K 等指标 | 必须绑定一个地块；不绑定机库 | 在线状态、最近上报时间、电量（设备支持时）、指标数据、故障 |

设备的**业务状态**与**网络在线状态**分开管理：
- `status`：`active`（启用）、`inactive`（停用）、`maintenance`（维护中）、`retired`（退役）。
- `online`：由 MQTT 最近心跳/数据上报推导的在线状态，不允许前端手工把设备改成在线。
- 默认离线阈值：无人机 90 秒、机库 180 秒、巡田传感器 180 秒；阈值应配置化。实际设备协议不同可调整，不能将模块五灌溉设备的心跳规则直接套用到模块二。
- 新增设备是管理员在平台登记，不是设备第一次发 MQTT 消息就自动注册。未知序列号的 MQTT 消息只记录告警/日志，不自动创建设备，避免伪造设备接入。
- 序列号 `device_sn` 全局唯一。设备登记、修改、绑定、退役均需校验序列号、设备类型和关联关系。
- 无人机绑定机库时，`parent_device_id` 必须指向 `device_type='dock'` 的有效机库；机库不能再作为其他设备的子设备。若无人机与机库都绑定了具体地块，两者地块必须一致。传感器必须绑定地块；同一设备不能同时属于两个地块。
- 设备删除采用逻辑退役。若设备仍被启用任务引用、存在待执行飞行或执行中的飞行，或机库仍绑定未退役无人机，不允许退役；退役时立即置 `online=false` 并忽略后续控制消息。存在历史记录时保留设备记录和外键关系。
- 设备维护记录独立保存，不覆盖设备历史状态。维护中设备不可被新任务选择。

### 3.2 API 定义

```text
# 设备台账
POST   /api/patrol/devices                         # 登记设备
GET    /api/patrol/devices?deviceType=&fieldId=&status=&online=&keyword=&page=  # 分页查询
GET    /api/patrol/devices/stats                   # 设备总数、在线数、离线数、维护数，按类型汇总
GET    /api/patrol/devices/{id}                    # 设备详情（含最近状态、绑定关系）
PUT    /api/patrol/devices/{id}                    # 修改名称、型号、地块/机库绑定、能力、备注等
PUT    /api/patrol/devices/{id}/status             # 启用、停用或标记维护中
DELETE /api/patrol/devices/{id}                    # 逻辑退役，不物理删除
POST   /api/patrol/devices/{id}/maintenance        # 新增维护/检修记录
GET    /api/patrol/devices/{id}/maintenance?page=  # 维护历史

# 巡田任务与飞行
POST   /api/patrol/tasks                            # 创建巡田任务
GET    /api/patrol/tasks?fieldId=&page=             # 任务列表
PUT    /api/patrol/tasks/{id}/status                # 启用/暂停任务
PUT    /api/patrol/tasks/{id}/drone                 # 人工核对后为历史任务绑定已登记无人机
POST   /api/patrol/tasks/{id}/flights               # 为任务创建一次待执行飞行
GET    /api/patrol/flights?fieldId=&page=           # 飞行记录列表
GET    /api/patrol/flights/{id}                     # 飞行详情（含设备、影像、NDVI 统计）
POST   /api/patrol/flights/{id}/trigger             # 下发该次飞行
POST   /api/patrol/flights/{id}/image               # 上传多光谱影像（multipart 字段 file）

# 传感器实时/历史数据
GET    /api/iot/sensors?fieldId=                    # 已登记巡田传感器及最近指标/在线状态
GET    /api/iot/sensors/{sn}/latest                 # 指定传感器最新一条指标
GET    /api/iot/sensors/{sn}/history                # 历史数据（?start&end&interval）
```

设备登记请求示例：

```json
{
  "deviceType": "drone",
  "deviceSn": "DRONE-PEANUT-001",
  "deviceName": "东区巡田无人机 01",
  "brand": "DJI",
  "model": "多光谱机型",
  "fieldId": 101,
  "parentDeviceId": 12,
  "capabilities": {"multispectral": true, "autoDock": true}
}
```

`parentDeviceId` 仅在无人机绑定机库时填写；传感器必须填写 `fieldId`；机库不能填写 `parentDeviceId`。请求字段由后端按 `deviceType` 校验。登记成功后初始 `online=false`，收到合法设备消息后才转为在线。

创建巡田任务请求示例：

```json
{
  "fieldId": 101,
  "taskName": "东区 3 号田每周巡查",
  "droneDeviceId": 21,
  "routeGeojson": {"type":"FeatureCollection","features":[/* 至少两个 Point 航点 */]},
  "scheduleCron": "0 6 * * 1,4"
}
```

`droneDeviceId` 必填；不再让用户直接输入 `droneSn` 或“机库序列号”。机库由无人机的 `parentDeviceId` 关系展示和校验。执行计划 `scheduleCron`（五段 cron）与 `scheduledAt`（ISO 8601 时间戳）二选一，也允许两者都为空表示仅手动执行；两者不可同时填写。创建任务时校验：地块存在；无人机类型为 `drone`、业务状态为 `active`；若无人机绑定了具体地块，则该地块必须与任务地块一致；若任务要求机库自动起降，则绑定机库必须有效且在线。手动触发时无人机必须在线；定时任务在实际下发时再次检查在线状态，不在线则本次飞行标记失败并记录原因，不得静默丢弃。

手动飞行流程：先调用 `POST /api/patrol/tasks/{id}/flights` 创建 `pending` 飞行记录，再调用 `POST /api/patrol/flights/{id}/trigger` 下发。重复请求需按 `flightId` 幂等，不能重复起飞。相同任务同一分钟只生成一条定时飞行记录。任务暂停后不再生成新的定时飞行，但已在执行的飞行允许完成。

飞行记录上传影像支持 `POST /api/patrol/flights/{id}/image`。设备如在状态消息中传入 `rawImagePath`，仅允许访问服务端配置的影像共享目录，必须校验路径在白名单目录内，禁止直接信任设备上传的任意文件路径。生产环境优先使用受控对象存储 URI。

NDVI 处理成功后，飞行详情返回 `ndviImagePath`、`ndviStats` 和 `ndviTileUrl`（TileJSON）。数据库中的 `ndvi_image_path` 和 `ndvi_tile_url` 都要持久化，不能只在 API VO 中返回未落库字段。

### 3.3 无人机/机库 MQTT 通信

后端启动时统一订阅 `drone/+/status`、`dock/+/status`，不要在每次创建任务时重复订阅同一设备主题。收到消息后按 topic 中的序列号查 `patrol_device`，校验设备类型和状态，再更新 `last_seen_at`、`online` 及设备实时状态。下发任务时使用该无人机登记记录中的 `device_sn`。

```text
下发 topic：drone/{device_sn}/cmd/task
```

```json
{
  "taskId": 55,
  "flightId": 201,
  "action": "start",
  "route": {"type":"FeatureCollection","features":[/* 航点 GeoJSON */]},
  "returnPolicy": "auto_land",
  "issuedAt": "2026-11-28T06:00:00+08:00"
}
```

```text
无人机状态 topic：drone/{device_sn}/status
机库状态 topic：dock/{device_sn}/status
```

```json
{
  "taskId": 55,
  "flightId": 201,
  "phase": "flying",
  "battery": 78,
  "lat": 36.124,
  "lng": 115.457,
  "altitude": 80,
  "faultCode": null,
  "faultMessage": null,
  "timestamp": 1795821600000
}
```

无人机 `phase` 枚举：`idle/takeoff/flying/landing/charging/done/fault`。其中 `done` 表示飞行作业结束，后端将 `patrol_flight.status` 从 `flying` 更新为 `processing`，然后进入影像处理；`fault` 更新为 `failed` 并记录故障信息。飞行记录状态统一为 `pending/dispatching/flying/processing/done/failed`。只有 NDVI 处理成功后才标记 `done`。机库状态按实际设备协议维护舱门、充电等状态，不假定所有机库都支持相同字段。

### 3.4 巡田传感器 MQTT 与 InfluxDB

MQTT topic：`sensor/{device_sn}/data`。现有 `ai-service/iot_writer.py` 继续负责 InfluxDB 写入；后端同时订阅 `sensor/+/data` 以更新设备在线状态。消息中的序列号以 topic 为准，写入前由 AI 服务通过后端内部设备查询接口（或由后端同步的设备绑定缓存）解析 `patrol_device`，取得登记设备的 `field_id`；**不信任消息体自行声明的 `fieldId`**，如为兼容旧模拟器保留该字段，必须与登记信息一致，否则拒绝写入并记录告警。未知序列号或停用设备不得写入 InfluxDB。

```json
{
  "timestamp": 1795821600000,
  "metrics": {
    "soil_temp": 24.5,
    "soil_moisture": 42.1,
    "soil_n": 85,
    "soil_p": 32,
    "soil_k": 120
  },
  "battery": 92
}
```

只有登记且 `device_type='sensor'`、`status='active'` 的设备可以写入时序数据。InfluxDB measurement 为 `soil_sensor`，tag 至少包含 `device_sn` 和从设备台账取得的 `field_id`；metrics 只允许白名单指标，时间戳缺失时由服务端补当前时间。每次合法上报都更新设备 `last_seen_at`、`online=true`、电量（若提供）。历史查询起止时间使用 ISO 8601，支持 `1m`、`5m`、`1h` 均值聚合；返回窗口起点，窗口内缺失指标为 `null`。传感器解绑或退役后保留 InfluxDB 历史数据，不级联删除。

```python
# ai-service/iot_writer.py（示意：field_id 必须来自已登记设备映射，不直接信任消息体）
from influxdb_client import Point, WritePrecision

def write_sensor(write_api, bucket: str, org: str, device_sn: str,
                 registered_field_id: int, metrics: dict, timestamp_ms: int | None = None):
    point = (
        Point("soil_sensor")
        .tag("device_sn", device_sn)
        .tag("field_id", str(registered_field_id))
    )
    for key in ("soil_temp", "soil_moisture", "soil_n", "soil_p", "soil_k"):
        if metrics.get(key) is not None:
            point = point.field(key, float(metrics[key]))
    if timestamp_ms is not None:
        point = point.time(timestamp_ms, WritePrecision.MS)
    write_api.write(bucket=bucket, org=org, record=point)
```

### 3.5 NDVI 影像处理流水线（Python）

文件路径：`ai-service/ndvi_processor/processor.py`。输入多光谱 GeoTIFF；红光波段和近红外波段索引必须按实际相机型号配置，当前示例默认 band=3 和 band=4，不能假设所有设备都一致。处理时需排除 GeoTIFF nodata、NaN、Inf 和分母为 0 的像元；有效像元数为 0 时返回明确错误，不生成误导性统计。三色占比仅以有效像元为分母，合计应为 100%（允许 ±0.1）。输出 NDVI GeoTIFF 应保留原始 CRS、transform 和范围，写出 nodata=-9999；瓦片服务按 TileJSON 返回。

```python
import rasterio
import numpy as np

def compute_ndvi(tif_path: str, output_path: str, red_band: int = 3, nir_band: int = 4) -> dict:
    with rasterio.open(tif_path) as src:
        red = src.read(red_band, masked=True).astype("float32")
        nir = src.read(nir_band, masked=True).astype("float32")
        profile = src.profile.copy()
        valid_mask = (~np.ma.getmaskarray(red) & ~np.ma.getmaskarray(nir)
                      & np.isfinite(red.filled(np.nan)) & np.isfinite(nir.filled(np.nan)))
        red_arr, nir_arr = red.filled(np.nan), nir.filled(np.nan)
        denom = nir_arr + red_arr
        valid_mask &= np.isfinite(denom) & (denom != 0)
        if not valid_mask.any():
            raise ValueError("GeoTIFF 中没有可用于 NDVI 的有效像元")
        ndvi = np.full(red_arr.shape, -9999.0, dtype=np.float32)
        ndvi[valid_mask] = (nir_arr[valid_mask] - red_arr[valid_mask]) / denom[valid_mask]
        valid = ndvi[valid_mask]
        profile.update(dtype=rasterio.float32, count=1, nodata=-9999.0)
        with rasterio.open(output_path, "w", **profile) as dst:
            dst.write(ndvi, 1)
        total = int(valid.size)
        red_pct = float((valid < 0.3).sum() / total * 100)
        yellow_pct = float(((valid >= 0.3) & (valid < 0.6)).sum() / total * 100)
        green_pct = float((valid >= 0.6).sum() / total * 100)
        return {
            "min": float(valid.min()), "max": float(valid.max()),
            "mean": float(valid.mean()), "std": float(valid.std()),
            "red_pct": red_pct, "yellow_pct": yellow_pct, "green_pct": green_pct,
            "valid_pixel_count": total
        }
```

### 3.6 前端页面与交互

模块二导航展开为：

1. **设备管理**（新增一级页面）：设备统计卡片（总数/在线/离线/维护中）；设备列表支持按类型、地块、在线状态、业务状态和名称/序列号搜索；“添加设备”弹窗按无人机/机库/传感器动态显示字段；支持编辑、停用/启用、逻辑退役；在线状态、最近上报时间、电量、位置、故障信息一目了然。
2. **设备详情**：展示设备基本信息、绑定地块/所属机库、能力信息、最近 MQTT 状态、维护记录时间线；提供“新增维护记录”入口。
3. **巡田任务**：创建任务时先选地块，再从符合条件的已登记无人机中选择；列表显示无人机名称/序列号/在线状态及关联机库，不再出现手工填写“机库序列号”输入框。
4. **飞行记录**：显示任务、无人机、起止时间、状态、影像处理状态；点击进入详情查看影像和 NDVI。
5. **NDVI 监测**：地图叠加 NDVI 栅格切片，右侧显示红/黄/绿比例、均值、标准差和有效像元数。
6. **传感器监测**：按地块展示已登记传感器卡片和在线状态；选择传感器查看 6h/24h/7d 土壤温湿度及 N/P/K 曲线。

建议前端组件：`PatrolDeviceManagement.vue`、`PatrolDeviceForm.vue`、`PatrolDeviceDetail.vue`、`PatrolDeviceMaintenance.vue`、`PatrolTask.vue`、`FlightList.vue`、`NdviMap.vue`、`SensorDashboard.vue`。页面必须使用真实设备 API，不得用静态数组冒充新增成功或在线状态。

### 3.7 验收标准

**原模块二基础能力（已验收，历史结果保留）：**
- [x] 模拟无人机 cron 与手动 MQTT 任务下发、飞行状态转换已验收。
- [x] 飞行完成后进入影像处理，NDVI 处理成功后状态变为 `done`。
- [x] NDVI 三色占比之和为 100%（±0.1）。
- [x] InfluxDB 传感器写入、1m/5m/1h 聚合查询及土壤趋势页已验收。

**设备管理补充验收：**
- [x] 可登记无人机、机库、巡田传感器；序列号重复时返回 400。
- [x] 设备列表支持类型/地块/状态筛选；设备详情显示绑定关系和最新状态。
- [x] 可编辑设备、启用/停用、逻辑退役；有活动任务或飞行时禁止退役；历史维护记录保留。
- [x] 可新增维护记录并在设备详情时间线查看。
- [x] MQTT 仅更新已登记设备；未知序列号不会自动注册；在线状态按设备类型超时下线。
- [x] 创建巡田任务只能选择有效无人机；旧 `drone_sn` 不再作为新任务输入；机库关系正确展示。
- [x] 手动触发时检查无人机在线；重复触发幂等；设备离线时有明确失败原因。
- [x] 传感器数据使用台账中的地块绑定写入 InfluxDB，不信任未经校验的 payload.fieldId。
- [x] NDVI 无效像元/空影像处理有明确错误；输出影像路径、统计和 TileJSON 均可追溯。
- [x] 浏览器完整验收设备新增、编辑、绑定、维护、退役、任务选择和实时状态。

2026-11-28 原模块二在本机 Compose 使用模拟无人机、模拟传感器和合成多光谱 GeoTIFF 完成基础链路验收，提交记录见《项目启动指令》。该历史验收**不代表本节新增的设备台账与维护功能已经完成**。

2026-11-28 设备管理补充已在独立的 Compose 项目通过验收。使用模拟无人机、机库和传感器，验证真实 EMQX、PostGIS 与 InfluxDB 上的行为。离线阈值测试将无人机设为 12 秒、机库和传感器设为 18 秒，正式预览仍使用 90／180／180 秒。Edge 验证设备与巡田表单、三色 NDVI 和两张土壤折线图。正式预览应用 V5 前后，地块、农事记录、知识片段、问答、巡田任务和飞行记录的校验值一致。


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

### 4.5 实现约束

- YOLO 检测需要真实花生病虫害模型权重 `weights/peanut_pest_v8.pt`。权重缺失时应返回明确的 `MODEL_NOT_CONFIGURED`/503，或进入有明显“演示数据”标识的模拟模式；不得用通用 YOLOv8n 权重冒充花生病虫害识别结果。
- 巡田影像若为多光谱 GeoTIFF，需先提取/转换为模型要求的 RGB 图像再检测；不能把 TIFF 文件未经验证直接交给仅支持普通图片的检测流程。
- 只有飞行影像带有可靠地理参考、或人工上传图片提供可信 GPS EXIF/人工定位时，才写入 `geoLocation`；无坐标的检测结果仍可在列表和详情中查看，但不应出现在地图定位层。
- 24 小时去重应按“地块 + 单个病害类型”分别判断，不能因为某条记录中一种病害已推送，就抑制同一批次内其他新病害的首次预警。

### 4.6 验收标准

- [ ] 上传图片 → 2 秒内返回检测结果
- [ ] 同一地块同一病害 24 小时内只推送一次预警
- [ ] 检测结果在前端地图上以红色圆点标注（含 bbox 预览弹窗）
- [ ] 支持 `source=flight` 的自动检测记录与 `source=manual_upload` 的人工上传记录统一管理；普通图片无地理坐标时不伪造 geoLocation

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
// 当前为协议占位符：device/{device_sn}/cmd/prescription 尚无对应通用设备台账。
// 实现 confirm 前必须确认执行设备类型、能力和最终 topic；未确认时只允许生成/编辑草稿，不得真实下发。
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
- [ ] `confirm` 后状态先变为 `confirmed`；设备确认后进入 `executing`，执行完成后变为 `executed`，失败为 `failed`；已确认的处方图不可再编辑。执行设备类型、topic 和喷洒设备能力须在开发前确认。

---

## 6. 模块五：水肥一体化远程管控

### 6.1 API 定义

```
POST   /api/irrigation/devices                  # 登记水肥控制器
GET    /api/irrigation/devices?fieldId=         # 设备列表（含在线状态）
GET    /api/irrigation/devices/{id}             # 设备详情
PUT    /api/irrigation/devices/{id}             # 修改名称、地块、分区数、业务状态
DELETE /api/irrigation/devices/{id}             # 逻辑退役，不物理删除历史设备
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

本节依赖 V5 增加的 `field.status`、`field.irrigation_threshold_pct`、`field.default_irrigation_duration_min`、`field.default_irrigation_device_id`、`irrigation_task.actual_water_m3` 和 `irrigation_device.status`。默认灌溉设备必须属于当前地块且 `status='active'`、`online=true`；最新土壤湿度数据缺失或超过配置的新鲜度窗口（默认 30 分钟）时跳过自动灌溉并记录原因。

```java
// IrrigationAutoTriggerJob.java（每 10 分钟执行一次）
@Scheduled(fixedDelay = 600_000)
public void checkAndTrigger() {
    List<Field> fields = fieldRepo.findAllActive();
    for (Field field : fields) {
        // 查最新土壤含水量（InfluxDB 查询）
        double moisture = iotService.getLatestMoisture(field.getId());
        if (Double.isNaN(moisture) || !iotService.hasFreshMoisture(field.getId(), Duration.ofMinutes(30))) continue;
        if (field.getDefaultIrrigationDeviceId() == null) continue;
        IrrigationDevice device = irrigationDeviceRepo.findById(field.getDefaultIrrigationDeviceId()).orElse(null);
        if (device == null || !device.isOnline() || !"active".equals(device.getStatus())) continue;
        double threshold = field.getIrrigationThresholdPct();  // 默认 60%

        if (moisture >= threshold) continue;

        // 24 小时内已触发过则跳过
        boolean recentTrigger = irrigationTaskRepo
            .existsRecentSensorTask(field.getId(), LocalDateTime.now().minusHours(24));
        if (recentTrigger) continue;

        // 创建自动灌溉任务
        IrrigationTask task = IrrigationTask.builder()
            .fieldId(field.getId())
            .deviceId(field.getDefaultIrrigationDeviceId())
            .zoneIds(new int[]{1})
            .triggerType("sensor")
            .durationMin(field.getDefaultIrrigationDurationMin())
            .status("pending")
            .build();
        irrigationTaskRepo.save(task);
        mqttService.sendIrrigationCmd(task);
    }
}
```

### 6.4 验收标准

- [ ] 水肥控制器按约定心跳上报；超过 90 秒未上报则 `online = false`（此规则仅适用于模块五灌溉设备，不替代模块二设备各自的超时配置）
- [ ] 手动控制指令下发后 3 秒内设备状态刷新至前端（WebSocket 推送）
- [ ] 自动触发任务在 24 小时内同一地块只执行一次
- [ ] 任务历史记录含实际用水量（从设备状态上报累计）

---

## 7. 模块六：知识库与智能问答

### 7.1 API 定义

```
GET    /api/knowledge/chunks?page=      # 管理员分页查看知识片段
POST   /api/knowledge/chunks            # 录入一条或多条知识片段（管理员）
POST   /api/knowledge/chunks/import     # 导入 UTF-8 CSV（管理员）
POST   /api/knowledge/chunks/import/file # 导入 PDF、DOCX、TXT、MD、CSV 或 ZIP（管理员）
POST   /api/knowledge/qa                # 智能问答
GET    /api/knowledge/qa/history        # 问答历史（?fieldId&page）

GET    /api/reports/growth/{fieldId}    # 生成生长报告（返回 PDF URL）
GET    /api/reports/growth/{fieldId}/pdf?date=YYYY-MM-DD # 查看已生成的 PDF
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

- [x] 问答接口平均响应时间 < 5 秒（本地 7B 模型，服务预热后）
- [x] 答案末尾显示引用来源（知识片段标题）
- [x] 每周一自动推送 PDF 生长报告至绑定手机（本机单接收人配置）
- [x] 知识库支持批量导入（CSV 格式：sourceTitle, content, category, variety, growthStage）

本机真实 BGE-M3、PostgreSQL/pgvector 和 GPU 加速的 Qwen2 7B 已验证资料录入、问答、引用标题、地块关联和历史。旧配置含冷加载的首问为 37.60 秒，预热后五个问题平均 5.51 秒。启用服务启动时预热，并将本机回答预算设为 48 token 后，经 Spring Boot 问答接口测了八个不同问题（含一条带上下文的追问），平均 2.81 秒，服务就绪后的首问 3.99 秒，最慢 4.62 秒。测试同时检查了答案要点、引用来源和历史记录。模型在空闲卸载或服务重启时仍需加载。2026-11-27 通过定时任务验证真实 OSS 上传和 PushPlus 微信一对一推送，接收人在手机确认收到通知并打开了测试 PDF。常规预览启用了每周一 02:00（北京时间）的任务，运行该任务时后端须保持启动。

### 模块六本地运行与配置

在 `ai-service/` 使用 Python 3.11 执行 `python -m pip install -r requirements.txt`，运行 `ollama pull qwen2:7b`，再执行 `python -m uvicorn rag.main:app --host 127.0.0.1 --port 8001`。BGE-M3 默认从 BAAI 官方仓库加载，也可通过 `BGE_MODEL_PATH` 指向已下载的权重目录。独立运行时 PostgreSQL 连接可配置 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASS`。若在其他主机运行 Ollama，可设置 `OLLAMA_HOST`。AI 服务的 `OLLAMA_CONTEXT_TOKENS` 和 `OLLAMA_ANSWER_TOKENS` 默认为 4096 和 240。本机 4 GiB 显存预览采用 `OLLAMA_CONTEXT_TOKENS=1024`、`OLLAMA_ANSWER_TOKENS=128`、`RAG_WARM_UP=true` 和 `OLLAMA_KEEP_ALIVE=30m`。预热在 AI 服务开始接受请求前加载向量模型和 Qwen，配置的空闲保留时间到期后再次提问可能出现冷加载。

本机预览原先以 48 token 完成短答测速。为避免播种日期等内容在句中被截断，预览的回答预算提高至 128 token。若模型仍达到输出上限，系统只展示已完整生成的句子。没有完整句子时会提示缩小问题范围。

FastAPI 的 `/embed` 一次性写入知识片段、元数据和向量，Spring Boot 接收请求、校验、转发和统计导入结果。检索直接查询 `knowledge_chunk`，写入与检索使用相同的维度补零规则。保留 ivfflat 索引，当前小知识库使用精确余弦检索。问答页显示引用标题，历史按地块和时间查询。配置 `KNOWLEDGE_ADMIN_TOKEN` 时，管理页会要求输入密钥，只存于当前页面内存。本地预览可留空。

「知识库管理 → 导入文件」可选择单个 PDF、DOCX、TXT、Markdown、CSV 或 ZIP，单次上传最多 20 MiB。ZIP 内可以放支持的资料文件与子目录，支持最多 100 个条目，解压后的总量最多 40 MiB。系统提取正文、保留来源文件名并按段生成向量，单次最多 100 个知识片段。扫描件需要先做 OCR，密码 PDF 需先解锁。入库结果展示每个文件的写入数量或失败原因。

定时任务默认按上海时区每周一 02:00 为生长中的地块生成近七天报告。未配置 OSS 时，PDF 写入本地目录，可通过知识问答页选择地块获取。Linux 部署需安装可嵌入的中文字体或设置 `GROWTH_REPORT_FONT`。

1. 在阿里云 OSS 控制台创建私有 Bucket，记录 Bucket 名称、地域 ID 和外网 Endpoint。给专用 RAM 身份授予对该 Bucket 下 `reports/*` 的 `oss:PutObject` 和 `oss:GetObject` 权限。[阿里云 OSS 控制台入门](https://help.aliyun.com/zh/oss/user-guide/get-started-with-oss/)
2. 在启动 **后端** 的进程环境中配置 `GROWTH_REPORT_OSS_BUCKET`、`GROWTH_REPORT_OSS_REGION`、`GROWTH_REPORT_OSS_ENDPOINT`、`GROWTH_REPORT_OSS_ACCESS_KEY_ID`、`GROWTH_REPORT_OSS_ACCESS_KEY_SECRET`。配置成功后使用 V4 签名上传，通知中的 OSS 下载链接有效期为 7 天。[阿里云预签名下载文档](https://help.aliyun.com/zh/oss/developer-reference/download-using-a-presigned-url)
3. 将支持报告通知的服务地址设置为 `GROWTH_REPORT_WEBHOOK_URL`。这个地址接收 JSON `POST`，字段是 `fieldId`、ISO 日期 `reportDate` 和 `pdfUrl`。通知服务根据 `fieldId` 找到绑定手机，确认接收后返回 2xx，并可用 `Idempotency-Key` 对重复请求去重。已有可从手机访问的后端域名时，也可配置 `GROWTH_REPORT_PUBLIC_BASE_URL`，使通知使用平台报告链接。

单人预览可使用 PushPlus 微信一对一通知，配置 `GROWTH_REPORT_PUSHPLUS_TOKEN`（本机属性名 `growth-report.pushplus-token`）。这个配置将该实例周报发给令牌关联的微信，适用于单一接收人。PushPlus 会接收到可下载报告的临时链接。真实验收以手机收到通知并能打开测试周报为准。[PushPlus 一对一消息](https://dev.pushplus.plus/doc/function/one.html)

执行 `python -m unittest discover -s tests -v` 运行向量测试，设置 `PEANUT_TEST_RAG=true` 并连接临时数据库会额外验证真实 pgvector 写入和排序。Java 集成测试与模块一共用 `PEANUT_TEST_POSTGIS=true` 开关。

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
  7. 巡田设备层（patrol-device-markers）：按设备类型区分无人机/机库/传感器，显示在线状态

交互：
  - 右键地块 → 弹出菜单（查看详情/新建巡田任务/新建灌溉任务）
  - 左侧面板：地块列表，点击定位并高亮
  - 右上角：图层开关（NDVI/病虫害/巡田设备/传感器）
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


### 8.3 模块导航结构

- 农田管理
- 智能巡田
  - 设备管理（无人机/机库/巡田传感器）
  - 巡田任务
  - 飞行记录
  - NDVI 监测
  - 传感器监测
- 病虫害识别与预警
- 智能分析与处方图
- 水肥一体化（仅管理灌溉控制器，与模块二巡田设备分开）
- 知识库与智能问答

---

## 9. 部署配置

### 9.1 docker-compose.yml（开发环境）

仓库的 `docker-compose.yml` 在以下约定基础上增加了 Ollama、模型数据卷和健康检查。数据库镜像基于 `postgis/postgis:16-3.4`，加入与现有向量库一致的 pgvector 0.7.4。首次安装、环境变量以及各服务地址见根目录 `README.md`。

2026-11-28 本机切换验收通过。PostgreSQL、InfluxDB、EMQX、RabbitMQ、Ollama、后端、AI 服务和前端均达到健康状态，切换前后地块、知识片段和问答记录数量一致。已验证后端健康检查、FastAPI 文档及浏览器的农田管理和知识库入口。本次启动复用了已验证的本机构建镜像和模型缓存。

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

## 10. 跨模块一致性与实施风险

1. **Flyway 迁移不可回写：** 本次补充使用 V5，在已经执行的 V1 至 V4 基础上增量升级，禁止修改已执行迁移、删除 `flyway_schema_history` 或清库重建。
2. **巡田设备与灌溉设备分离：** 模块二 `patrol_device` 管无人机/机库/巡田传感器；模块五 `irrigation_device` 管水肥控制器。两个台账不能共用设备类型或 MQTT topic。
3. **巡田任务与设备绑定：** 新任务使用 `drone_device_id` 外键；旧 `drone_sn` 保留作兼容字段，历史序列号需人工核对设备类型后迁移，不能默认当作无人机。
4. **传感器地块归属：** InfluxDB 的 `field_id` 由设备台账绑定关系确定，不信任 MQTT payload 自带的地块 ID。设备退役不删除时序历史。
5. **模块四处方图下发目标未定义：** 当前通用 `device/{device_sn}/cmd/prescription` 没有对应表；模块五 `irrigation_device` 是水肥控制器，不能默认也代表喷洒设备。开始实现确认下发前，必须确认肥料/农药处方各自的执行设备、能力、协议和 topic。未确认时只做草稿生成、编辑和地图展示。
6. **模块五算法字段缺失已纳入 V5：** `field.status`、灌溉阈值、默认灌溉设备/时长以及任务实际用水量原先被代码引用但 V1 没有列，按 1.1 节 V5 迁移补齐。无新鲜土壤数据或无在线默认灌溉设备时禁止自动下发。
7. **模块三模型权重：** 通用 YOLOv8n 不是花生病虫害模型。没有训练权重时必须明确报未配置或使用标记清楚的演示模式，不得把通用模型结果当成真实检测。
8. **模块四空间参考：** 土壤采样点、地块 GeoJSON 和 NDVI 栅格必须统一到同一 CRS 后再插值/采样；土壤点为空或不足以线性插值时需使用明确的 nearest/默认值策略，并在结果中标明数据来源。
9. **模块六向量维度：** 当前表为 `vector(1536)`，BGE-M3 原始输出为 1024 维。现有实现采用补零时，文档写入、查询和历史向量必须使用完全一致的维度处理；需有维度单测。未来若改为 1024 维，应另做迁移，不得直接改已执行 V1。
10. **验收勾选规则：** `[x]` 只代表已经实际执行并通过测试的能力；规格新增项默认 `[ ]`。模拟设备通过不代表真实设备硬件、云端机库协议或生产环境安全性已验收。

---

## 11. 完整实现 Checklist

按顺序完成每项，标注 `[x]` 表示已实际实现并通过对应测试。原有验收结果和本次设备管理补充验收均保留。

```
基础设施
[x] Docker Compose 服务启动与健康检查（2026-11-28 本机验收）
[x] V1__init.sql 已在现有环境执行
[x] 后端 Spring Boot /actuator/health 返回 UP
[x] V5__patrol_device_management_and_schema_alignment.sql 在现有数据上升级成功且无数据丢失

模块一：农田管理
[x] POST /api/fields 创建地块，面积自动计算
[x] GET /api/fields 分页+关键词搜索
[x] 生育期推算随日期变化正确
[x] 前端地块圈画（MapLibre draw 插件）+ 提交

模块二：智能巡田（原有能力已验收）
[x] 模拟无人机 cron 与手动 MQTT 任务下发、飞行状态转换
[x] NDVI 处理流水线基础端到端测试
[x] InfluxDB 传感器数据写入 + 查询历史接口及趋势页
[x] 无人机/机库/巡田传感器设备登记、查询、编辑和逻辑退役
[x] 设备详情、地块/机库绑定、维护记录
[x] MQTT 设备在线心跳、电量/定位/故障状态更新及离线判定
[x] 新建巡田任务从设备台账选择有效无人机
[x] 设备离线/维护/停用时阻止不合规任务下发
[x] 新增设备管理前端页面及浏览器验收

模块三：病虫害
[ ] 花生病虫害模型权重配置检查及 /detect 接口测试
[ ] 检测结果写入 pest_detection 表
[ ] 按地块+病害类型执行 24h 去重
[ ] flight 影像转换/RGB 输入及无地理坐标场景处理正确

模块四：处方图
[ ] 处方图生成函数单测（NDVI + soil_points，CRS 一致）
[ ] 前端分区着色可视化
[ ] 明确施肥/喷洒执行设备、能力与 MQTT 协议
[ ] confirm → executing → executed/failed 状态及设备回执联调

模块五：水肥控制
[ ] 水肥控制器登记/编辑/退役及在线状态
[ ] 设备心跳超时自动下线
[ ] 手动控制指令下发 + 状态实时刷新
[ ] 任务历史记录 actual_water_m3 正确
[ ] 传感器自动触发灌溉（有新鲜数据、设备在线时联调）

模块六：知识库
[x] pgvector 索引创建 + embedding 写入测试
[x] RAG 问答接口返回答案 + 引用来源
[x] 周报定时任务触发 + PDF 生成及手机通知
[ ] 写入/查询向量维度一致性自动测试

集成测试
[ ] 完整业务流：创建地块 → 登记设备 → 触发巡田 → NDVI 生成 → 病虫害检测 → 处方图生成 → 灌溉执行
[ ] 前端端到端（E2E）：地图圈地、设备管理、巡田任务、问答、处方图编辑
```
