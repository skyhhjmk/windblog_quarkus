# 本地完整测试环境

使用 `scripts/local-test.sh` 启动一套与现有容器隔离的 WindBlog 测试栈。它不会复用或覆盖当前运行中的 `postgresdb`、`redis`、`rabbitmq`、`elasticsearch` 等容器。

```bash
./scripts/local-test.sh up
./scripts/local-test.sh status
./scripts/local-test.sh logs 200 codex-creator
./scripts/local-test.sh down
```

测试栈包含 WindBlog、Codex Creator、PostgreSQL/pgvector、Redis、RabbitMQ、Elasticsearch、Kibana、Filebeat 和 ClamAV。业务数据与日志保存在被 Git 忽略的 `.codex-test-data/`，Elasticsearch/Kibana 使用同一测试项目下的隔离 named volume；`down` 不删除数据卷。

默认入口：

- WindBlog HTTP：`http://127.0.0.1:58080`
- WindBlog gRPC：`127.0.0.1:59000`
- Codex Creator：`http://127.0.0.1:58091`
- Kibana：`http://127.0.0.1:55601`
- PostgreSQL：`127.0.0.1:55432`
- Redis：`127.0.0.1:56379`
- RabbitMQ：`127.0.0.1:55672`，管理界面 `127.0.0.1:55673`
- Elasticsearch：`127.0.0.1:59200`
- ClamAV：`127.0.0.1:55310`

脚本只从宿主机 Codex 登录目录复制 `auth.json` 到隔离测试目录，并按镜像实际运行 UID 设置权限；原始登录目录不会挂载进容器，凭证也不会写入镜像或提交到 Git。启用 app-server 后，Codex Creator 会在容器启动时校验登录状态。

本地启动器还会在测试数据目录生成并复用 `mcp-bearer-token`，供 app-server 和 MCP 端点共同认证；不会将令牌写入 Git。

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
Codex CLI 版本由镜像固定为 `0.150.1`。

可用以下请求验证 Codex app-server 与远端模型目录链路：

```bash
curl -fsS http://127.0.0.1:58091/internal/health
curl -fsS -X POST http://127.0.0.1:58091/api/admin/models/discover
```

第二个请求会使用已登录的 Codex 账户并产生远端 API 请求；不需要进行模型推理即可完成环境连通性验证。
