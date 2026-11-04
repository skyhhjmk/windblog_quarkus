# 本地完整测试环境

使用 `scripts/local-test.sh` 启动一套与现有容器隔离的 WindBlog 测试栈。它不会复用或覆盖当前运行中的 `postgresdb`、`redis`、`rabbitmq`、`elasticsearch` 等容器。

```bash
./scripts/local-test.sh up
./scripts/local-test.sh status
./scripts/local-test.sh logs 200 windblog
./scripts/local-test.sh down
```

测试栈包含同源 edge、WindBlog、PostgreSQL/pgvector、Redis、RabbitMQ、Elasticsearch、Kibana、Filebeat、OpenTelemetry Collector、Prometheus、Grafana 和 ClamAV。业务数据与日志保存在被 Git 忽略的 `.codex-test-data/`，Elasticsearch/Kibana 使用同一测试项目下的隔离 named volume；`down` 不删除数据卷。

默认入口：

- WindBlog HTTP（同源 edge）：`http://127.0.0.1:58080`
- WindBlog 直连诊断端口：`http://127.0.0.1:58081`
- WindBlog gRPC：`127.0.0.1:59000`
- Kibana：`http://127.0.0.1:55601`
- PostgreSQL：`127.0.0.1:55432`
- Redis：`127.0.0.1:56379`
- RabbitMQ：`127.0.0.1:55672`，管理界面 `127.0.0.1:55673`
- Elasticsearch：`127.0.0.1:59200`
- ClamAV：`127.0.0.1:55310`

Admin 首页中的 Grafana iframe 通过 `58080/observability/grafana/` 转发。edge 只存在于本地 Compose：它将首次 iframe 请求的短时 JWT 转为本机 HTTP Cookie，并以 `X-JWT-Assertion` 转发给 Grafana；它不是 Kubernetes 工作负载。

生产 Compose 没有 edge nginx，由 APISIX 承担同样的 JWT 转换和转发，见 `docs/observability-gateway.md`。

当前工作区的 `.env` 使用从源码构建的 `localhost/windblog:local-native`，并设为
`WINDBLOG_PULL_POLICY=never`，所以本地测试不会被未更新的 Docker Hub 镜像覆盖。重新构建：

```bash
./mvnw -q -Dmaven.test.skip=true -Dnative -Dquarkus.profile=dev \
  -Dquarkus.container-image.build=false package
podman build -f src/main/docker/Dockerfile.native -t localhost/windblog:local-native .
bash scripts/local-test.sh up
```

如需临时使用 Docker Hub 版本，可在命令前显式设置
`WINDBLOG_IMAGE=docker.io/hhjmk/windblog_quarkus:latest-native WINDBLOG_PULL_POLICY=always`。
本地命令使用 `dev` profile 构建 native 镜像，并将 `.env` 中的 `COOKIE_SECURE` 传给容器；
因此默认的 HTTP 测试地址应设为 `COOKIE_SECURE=false`，否则浏览器不会回传登录和 CSRF Cookie。
使用 Docker Hub 的生产 native 镜像时，启动器会自动恢复 `COOKIE_SECURE=true`；生产 native
镜像仍应使用 `prod` profile、HTTPS 和安全 Cookie。可用
`WINDBLOG_LOCAL_TEST_QUARKUS_PROFILE`、`WINDBLOG_LOCAL_TEST_COOKIE_SECURE` 覆盖。
