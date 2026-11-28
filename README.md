# 花生种植精准服务平台

当前提供农田管理、智能巡田、NDVI 影像分析、土壤监测、知识库管理、智能问答和生长周报。

## 本地启动

需要 Docker Engine 24+、Docker Compose v2、至少 8 GB 内存，以及用于容器镜像和模型的可用磁盘空间。首次构建需要联网。

```sh
docker compose up -d
docker compose ps
```

Compose 默认使用本地开发配置，公开端口仅绑定到本机。可复制 `.env.example` 为 `.env` 后覆盖默认值。`VITE_TIANDITU_TOKEN` 是浏览器端地图配置，修改后执行 `docker compose up -d --build frontend`。OSS 密钥、通知 Token 和管理密钥均通过后端环境配置。

首次使用问答时拉取 Qwen2 7B，并打开网页上传资料。BGE-M3 在第一次需要向量时下载，两个模型随后都保存在 Docker 数据卷里。

```sh
docker compose exec ollama ollama pull qwen2:7b
```

已下载模型的机器可设置 `RAG_WARM_UP=true`，让 AI 服务在接受请求前加载模型。使用 NVIDIA GPU 的机器可在本地 Compose 覆盖文件中为 `ollama` 添加 `gpus: all`。

## 服务入口

| 服务 | 地址 |
| --- | --- |
| 前端 | http://localhost:3000 |
| 后端健康检查 | http://localhost:8080/actuator/health |
| AI 接口文档 | http://localhost:8001/docs |
| PostgreSQL / PostGIS / pgvector | `localhost:5432`，数据库 `peanut` |
| InfluxDB | http://localhost:8086 |
| EMQX MQTT / 控制台 | `localhost:1883` / http://localhost:18083 |
| RabbitMQ AMQP / 控制台 | `localhost:5672` / http://localhost:15672 |

## 智能巡田与土壤监测

在「智能巡田 → 设备管理」登记无人机、机库与巡田传感器。传感器必选地块，无人机可关联同地块或共享机库。详情页显示电量、位置、故障、最近上报及维护历史。设备支持停用、维护中和逻辑退役，退役会保留历史。无人机 90 秒、机库与传感器 180 秒未上报后判为离线，可通过对应的 `PATROL_DRONE_OFFLINE_SECONDS`、`PATROL_DOCK_OFFLINE_SECONDS`、`PATROL_SENSOR_OFFLINE_SECONDS` 配置调整。

在「巡田任务」选择地块和已登记、启用且在线的无人机，填写由 GeoJSON Point 要素组成的航线。执行计划支持单次日期、周期 cron 和仅手动执行。选定自动起降时，关联机库也须启用且在线。

无人机和机库分别发布到 `drone/{device_sn}/status`、`dock/{device_sn}/status`。无人机完成作业后上报 `{"flightId":201,"phase":"done"}`。可在「飞行记录」上传多光谱 GeoTIFF（最多 20 MiB），也可在完成回报中附带共享影像目录内的 `rawImagePath`。红光、近红外波段默认用 3 和 4，可在无人机的设备能力里设置 `redBand` 和 `nirBand`。原始影像和生成结果保存在 `patrol-data` 数据卷。

传感器发布到 `sensor/{device_sn}/data`。示例是 `{"battery":92,"metrics":{"soil_temp":24.5,"soil_moisture":42.1,"soil_n":85,"soil_p":32,"soil_k":120}}`，可附带毫秒时间戳 `timestamp`。时序数据使用台账绑定的地块。在「传感器监测」可查看最新上报和 6 小时、24 小时或 7 天趋势。

历史任务的原序列号继续保留。管理员核实序列号对应的真实设备类型、登记合适的无人机并确认在线后，可向 `PUT /api/patrol/tasks/{id}/drone` 发送 `{"droneDeviceId":21}`，为待映射任务明确绑定无人机。已有飞行历史保持原有设备信息。

## 验证与停止

设备台账验收使用单独的 `peanut-device-qa` Compose 项目。先完成常规镜像构建；测试覆盖文件使用支持 `!reset` 和 `!override` 的 Compose 版本。以下命令适用于 PowerShell，测试前将 `PLAYWRIGHT_MODULE` 指向本地安装的 `playwright-core`，将 `PEANUT_TEST_CHROMIUM` 指向 Chromium 或 Edge 的可执行文件。

```powershell
$qa = @('-p','peanut-device-qa','-f','docker-compose.yml','-f','infra/compose.patrol-qa.yml')
docker compose @qa up -d --no-build --wait backend ai-service frontend
Get-Content -Raw -Encoding UTF8 ai-service/tests/patrol_device_acceptance.py |
  docker compose @qa exec -T -e PEANUT_DISPOSABLE_PATROL_TEST=true ai-service python -u -
$env:PEANUT_DISPOSABLE_PATROL_TEST = 'true'
$env:PEANUT_PATROL_TEST_URL = 'http://127.0.0.1:13000'
$env:PEANUT_PATROL_TEST_AI_CONTAINER = 'peanut-device-qa-ai-service-1'
node frontend/tests/patrol-device.cjs
docker compose @qa stop
```

后端单元测试在 `backend/` 执行 `mvn test`。NDVI 和 AI 知识库单元测试在 `ai-service/` 执行 `python -m unittest discover -s tests -v`。

```sh
docker compose ps
curl http://localhost:8080/actuator/health
curl http://localhost:8001/docs
docker compose down
```

PostgreSQL、模型、周报和其他服务的数据保存在命名数据卷中。`docker compose down` 保留卷，已有独立预览数据库可通过备份还原到 Compose 数据库，或通过本地 Compose 覆盖文件接管兼容的外部数据卷。
