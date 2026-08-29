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

当前默认使用本地 WindBlog 快照镜像和本地 native Codex Creator 镜像；可通过 `WINDBLOG_IMAGE`、`CODEX_CREATOR_IMAGE` 覆盖。Codex CLI 版本由镜像固定为 `0.150.1`。

可用以下请求验证 Codex app-server 与远端模型目录链路：

```bash
curl -fsS http://127.0.0.1:58091/internal/health
curl -fsS -X POST http://127.0.0.1:58091/api/admin/models/discover
```

第二个请求会使用已登录的 Codex 账户并产生远端 API 请求；不需要进行模型推理即可完成环境连通性验证。
