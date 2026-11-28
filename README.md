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

在「智能巡田」选择地块，填写设备序列号、五段 cron 和由 GeoJSON Point 要素组成的航线。任务创建时启用，默认按北京时间调度。通过任务开关暂停定时任务，通过「手动触发」立即下发飞行指令。

无人机将状态发布到 `drone/{drone_sn}/status`。作业完成后上报 `{"flightId":201,"phase":"done"}`。可在「飞行记录」上传含红光第 3 波段和近红外第 4 波段的 GeoTIFF（单个文件最多 20 MiB）。设备也可将影像送入共享影像目录，并在完成消息中带上相对路径 `rawImagePath`。设备回报与影像均到达后，平台生成 NDVI 图层。原始影像和生成结果保存在 `patrol-data` 数据卷。

传感器将 JSON 发布到 `sensor/{sn}/data`。示例消息为 `{"fieldId":101,"metrics":{"soil_temp":24.5,"soil_moisture":42.1,"soil_n":85,"soil_p":32,"soil_k":120}}`。可附上毫秒时间戳 `timestamp`。在「土壤监测」选择地块和传感器，查看最新上报和 6 小时、24 小时或 7 天趋势。

## 验证与停止

```sh
docker compose ps
curl http://localhost:8080/actuator/health
curl http://localhost:8001/docs
docker compose down
```

PostgreSQL、模型、周报和其他服务的数据保存在命名数据卷中。`docker compose down` 保留卷，已有独立预览数据库可通过备份还原到 Compose 数据库，或通过本地 Compose 覆盖文件接管兼容的外部数据卷。
