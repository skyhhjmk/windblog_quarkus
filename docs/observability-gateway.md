# 生产观测栈与 Grafana 网关

Admin 的“打开 Grafana”按钮返回一个短时效地址：

```
/observability/grafana/d/windblog-platform-slo/windblog-platform-slo?orgId=1&kiosk&auth_token=<JWT>
```

生产 Compose 里没有 edge nginx（它只存在于 `docker-compose.local-test.yml`），公网请求由 EdgeOne → bt-main 的 APISIX 进入。因此这条路径必须同时满足两个条件，否则会落到 WindBlog 应用上并返回 404：

1. `otel-collector`、`prometheus`、`grafana` 三个服务实际运行；
2. APISIX 有 `/observability/grafana/*` 路由，并把 `auth_token` 换成 Grafana 需要的
   `X-JWT-Assertion` 请求头（以及供 iframe 后续请求使用的 HttpOnly Cookie）。

## 1. 启动观测栈

Compose 已定义 `otel-collector`、`prometheus`、`grafana`、`grafana-jwt-key-init`。首次启动前要让绑定目录可写（否则 Prometheus 会因 `queries.active: permission denied` 崩溃重启）：

```bash
cd /root/windblog_quarkus
mkdir -p prometheus-data grafana-data
chown -R 65534:65534 prometheus-data   # prometheus 镜像以 nobody 运行
chown -R 472:472 grafana-data          # grafana 镜像以 uid 472 运行
docker compose --env-file .env up -d otel-collector prometheus grafana
```

国内主机可用 `docker.1ms.run/` 镜像前缀 pull 后再 `docker tag` 成 Compose 期望的镜像名：

```bash
docker pull docker.1ms.run/otel/opentelemetry-collector-contrib:0.139.0
docker tag  docker.1ms.run/otel/opentelemetry-collector-contrib:0.139.0 otel/opentelemetry-collector-contrib:0.139.0
# prom/prometheus:v3.8.1、grafana/grafana:12.3.0 同理
```

Grafana 只监听容器 3000 端口。为了给独立 Compose 项目里的 APISIX 使用，`docker-compose.yml` 通过
`GRAFANA_HOST_BIND_IP`/`GRAFANA_HOST_PORT` 把它发布到宿主机；生产 `.env` 设为：

```dotenv
GRAFANA_HOST_BIND_IP=10.0.0.100
GRAFANA_HOST_PORT=13000
```

默认值是 `127.0.0.1:13000`，即不对外暴露。Grafana 关闭了匿名访问和登录表单，只接受 WindBlog 签发的 JWT，因此该端口只应交给网关使用。

## 2. 配置 APISIX 路由

路由定义在 `deploy/apisix/windblog-grafana-route.json`（id `00000000000000000210`，优先级 100）：

- `uri: /observability/grafana/*`，host 覆盖 `geekcorelab.cn` / `geekcorelab.com` 两个域的 www 与非 www；
- upstream 指向 `10.0.0.100:13000`，`pass_host: pass`，开启 websocket；
- `proxy-rewrite` 设置 `X-Forwarded-Proto: https` 并移除客户端自带的 `X-JWT-Assertion`；
- `serverless-pre-function`（access 阶段）把查询参数 `auth_token` 写入 `X-JWT-Assertion` 并下发
  `windblog_grafana_jwt` HttpOnly Cookie；后续 iframe 请求只带 Cookie 时再用 Cookie 还原请求头。

应用方式（admin key 从 `apisix_conf/config.yaml` 读取，不要出现在日志或 shell 历史里）：

```bash
KEY=$(awk '/^[[:space:]]+key:[[:space:]]/{print $2; exit}' /root/windblog/apisix/apisix_conf/config.yaml | tr -d '"')
curl -X PUT -H "X-API-KEY: $KEY" -H "Content-Type: application/json" \
  --data-binary @/root/windblog/apisix/routes/windblog-grafana-route.json \
  http://127.0.0.1:9180/apisix/admin/routes/00000000000000000210
```

路由文件需要同时保存在 `bt-main:/root/windblog/apisix/routes/`，APISIX 的 etcd 数据在容器重建后仍保留，但保留一份文件便于审计和重建。

## 3. 验证

```bash
# Grafana 自身
curl -o /dev/null -w '%{http_code}\n' http://10.0.0.100:13000/observability/grafana/api/health

# 网关：带 token 应 200 并下发 windblog_grafana_jwt；只带 Cookie 应 200；都不带应 302 到 /login
curl -sS -D - -o /dev/null -H 'Host: geekcorelab.cn' \
  'http://127.0.0.1:9080/observability/grafana/d/windblog-platform-slo/windblog-platform-slo?orgId=1&kiosk&auth_token=<JWT>'

# 公网
curl -sS -o /dev/null -w '%{http_code}\n' https://geekcorelab.cn/observability/grafana/api/health
```

## 4. 仪表盘指标说明

`observability/grafana/provisioning/dashboards/json/windblog-platform-slo.json` 的查询已与当前
Quarkus/Micrometer → OTel Collector → Prometheus 的实际指标名对齐：

| 面板 | 指标 |
| --- | --- |
| HTTP 请求率 / 5xx | `http_server_requests_milliseconds_count`（状态码标签是 `status`） |
| HTTP P95 | `http_server_requests_milliseconds_bucket`（单位毫秒） |
| JVM 堆内存 | `jvm_memory_used_bytes{area="heap"}` |
| Redis 内存 | `redis_memory_used_bytes` |
| JVM GC 暂停占比 | `jvm_gc_collections_elapsed_milliseconds_total` |

`windblog:collector_export_success:ratio` 记录规则使用 `otelcol_exporter_*_total` 名称。
bt-main 目前没有部署 `cadvisor`（`docker.1ms.run` 上没有该 gcr 镜像），因此原“容器 CPU 使用率”面板改成了 GC 暂停占比；如需容器指标，先解决 cadvisor 镜像来源再恢复该查询。
