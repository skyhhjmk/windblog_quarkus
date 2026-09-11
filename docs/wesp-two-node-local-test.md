# WESP 双实例本地测试

本测试在现有 `scripts/local-test.sh` 依赖栈之上启动两个独立的 WindBlog
容器：

| 实例 | 作用 | HTTP | gRPC TLS | 数据库 |
| --- | --- | ---: | ---: | --- |
| public | 公网可达主节点，不主动连接 peer | `127.0.0.1:58180` | `127.0.0.1:59180` | `wesp_public` |
| home | 家中主动连接主节点 | `127.0.0.1:58181` | `127.0.0.1:59181` | `wesp_home` |

`public` 使用 `primary` 角色，`home` 使用轻量 `edge` 角色；两者都运行同一
native-micro 镜像，但 home 不启用 Elasticsearch、RabbitMQ、Codex Creator、ClamAV
和后台日志/指标任务，模拟实际家用宽带节点。
这里的轻量化指运行时依赖与资源上限收敛，代码仍复用 WindBlog native runner；不是另拆
一个完全独立的 edge-agent 二进制。

两个实例使用 `windblog-codex-test_app-network` 内部 DNS；public 复用现有
PostgreSQL、Redis、RabbitMQ 和 Elasticsearch，home 仅使用 PostgreSQL/Redis。
WESP 测试链路使用 HTTP，便于在本机复现协议；gRPC 仍使用现有的双向 TLS 配置。实际部署应把
`WINDBLOG_WESP_PEER_URL` 改为 HTTPS（通常在反向代理处终止 TLS），并限制
公网入口只允许 WESP 路径。

## 1. 启动

在仓库根目录执行：

```bash
scripts/wesp-two-node-test.sh up
```

脚本会：

1. 检查并复用现有 local-test 依赖；
2. 创建隔离数据库 `wesp_public` 和 `wesp_home`（只在不存在时创建）；
3. 以 `edge` profile 构建当前源码，并使用 `native-micro` 运行时生成
   `localhost/windblog:wesp-two-node-edge` 轻量镜像（不部署 RabbitMQ、Elasticsearch、
   Codex Creator 或 ClamAV）；
4. 生成 32 字节随机共享令牌（64 个十六进制字符）；
5. 写出两个权限为 `0600` 的 env 文件；
6. 以持久化 Podman 容器启动两个实例。

默认本地出口预算为 `0`（不额外限速）。要模拟家用宽带预算，可在重启时指定每分钟字节
上限，例如 `WINDBLOG_WESP_TEST_MAX_EGRESS_BYTES_PER_MINUTE=65536
scripts/wesp-two-node-test.sh restart`；该值会写入两个实例的 env。

生成文件位于 `.codex-test-data/wesp-two-node/`（该目录已被忽略，不会提交）：

```text
wesp-shared-token       # 共享 Bearer/HMAC 令牌，权限 0600
public/wesp-config.json      # public 连接引导后写入的本地运行配置（权限 0600）
home/wesp-config.json        # home 连接引导后写入的本地运行配置（权限 0600）
env/public.env          # public 容器环境，权限 0600
env/home.env            # home 容器环境，权限 0600
public/uploads/         # public 媒体目录
public/blocks/          # public 内容寻址块目录
public/rsa-keys/        # public 本地 RSA 密钥
home/uploads/           # home 媒体目录
home/blocks/             # home 内容寻址块目录
home/rsa-keys/           # home 本地 RSA 密钥
```

不要把 token 或 env 文件内容粘贴到日志、工单或聊天中。查看路径：

```bash
scripts/wesp-two-node-test.sh env
```

首次启动还会为 public 和 home 两个独立数据库执行一次 Admin 安装。测试账号默认
为 `wespadmin` / `wespadmin@example.test`，密码为 `WespLocalAdmin!2026`；实际值写在
权限为 `0600` 的 `admin.env`，可在启动前用同名 `WINDBLOG_WESP_TEST_ADMIN_*` 环境变量
覆盖。两个实例的登录地址分别是 `http://127.0.0.1:58180` 和
`http://127.0.0.1:58181`。

默认构建需要本机可用的 GraalVM/native-container-build，首次编译时间会长于 JVM
调试镜像。若只需快速调试，可显式使用 JVM 变体：

```bash
WINDBLOG_WESP_TEST_BUILD_MODE=jvm scripts/wesp-two-node-test.sh restart
```

双节点脚本默认保留测试目录中已有的 `wesp-config.json`，避免覆盖已经通过管理后台完成的连接引导。
如需切换回脚本生成的节点 ID、peer 和租户配置，可设置
`WINDBLOG_WESP_TEST_RESET_RUNTIME_CONFIG=true`；旧配置会先改名备份并保留在对应节点目录中。

## 2. 健康检查和会话握手

```bash
curl -fsS http://127.0.0.1:58180/q/health/ready | jq .status
curl -fsS http://127.0.0.1:58181/q/health/ready | jq .status
```

两次都应输出 `UP`。下面的请求模拟 home 主动向 public 协商 WESP 会话；
`tenant_id`、`node_id` 和 `incarnation` 必须与 home.env 一致：

```bash
TOKEN="$(<.codex-test-data/wesp-two-node/wesp-shared-token)"
# 连接引导后，home 的运行时配置会覆盖 env 中的 incarnation；统一从文件读取，
# 尚未引导时则回退到脚本默认值。
CONFIG=.codex-test-data/wesp-two-node/home/wesp-config.json
NODE_ID="$(jq -r '.node_id // empty' "$CONFIG" 2>/dev/null || true)"; : "${NODE_ID:=wesp-home}"
TENANT_ID="$(jq -r '.tenant_id // empty' "$CONFIG" 2>/dev/null || true)"; : "${TENANT_ID:=wesp-local}"
DATASET_ID="$(jq -r '.dataset_id // empty' "$CONFIG" 2>/dev/null || true)"; : "${DATASET_ID:=public}"
INCARNATION="$(jq -r '.incarnation // empty' "$CONFIG" 2>/dev/null || true)"; : "${INCARNATION:=wesp-home-incarnation-1}"
RID="$(uuidgen)"
BODY="$(jq -cn --arg node "$NODE_ID" --arg tenant "$TENANT_ID" --arg incarnation "$INCARNATION" --arg dataset "$DATASET_ID" \
  '{protocol_major:1,protocol_minor:0,schema_version:1,node_id:$node,tenant_id:$tenant,incarnation:$incarnation,capabilities:[\"blocks\",\"manifests\",\"changes\",\"idempotent_batches\"],datasets:[{dataset_id:$dataset,received_frontier:\"0\",applied_frontier:\"0\"}],limits:{max_batch_bytes:1048576,max_operation_bytes:65536,max_block_bytes:2097152}}')"
curl -fsS -X POST http://127.0.0.1:58180/sync/v1/sessions \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-WESP-Node-Id: ${NODE_ID}" \
  -H "X-WESP-Request-Id: ${RID}" \
  -H 'Content-Type: application/json' \
  --data "${BODY}" | jq '{protocol_major,protocol_minor,schema_version,authority_mode,accepted_capabilities}'
```

预期 `protocol_major=1`、`schema_version=1`、`authority_mode="single"`，且
能力列表包含 `blocks`、`manifests`、`changes`、`idempotent_batches`。

## 2.1 新增节点连接流程

管理后台的“边缘节点”页面只有“新增节点”入口。先填写节点 ID、目标地址、区域等基本信息，
点击“测试连接”；服务端检查 `/q/health/ready`。检查成功后页面才询问目标管理员账号和密码，
点击“登录并连接”执行一次性引导。目标凭据只在本次请求中使用，不保存、不返回。

服务端随后调用目标的 `/api/admin/auth/login` 和 `/api/admin/auth/step-up`，再向
`POST /api/admin/edge-nodes/connection/bootstrap` 写入目标本地的 WESP peer、租户、数据集、
随机 incarnation 和共享 token。目标节点保存 `wesp-config.json` 后主动访问 public，家庭侧不需要
开放入站端口。主节点数据库只保存目标 URL、区域和连接状态，可重复添加多个目标节点。

本地双实例手工验证：在 public 管理端新增节点，目标地址填写 `http://127.0.0.1:58181`，
节点账号填写 `wespadmin`，密码读取 `.codex-test-data/wesp-two-node/admin.env`。容器中的回环地址
会自动尝试 `host.containers.internal`，因此不会再把目标误解析为 public 容器自身；失败会返回 502
和可读错误，而不是 500。

命令行最小探测（不传递密码）：

```bash
set -a; source .codex-test-data/wesp-two-node/admin.env; set +a
curl -fsS --get 'http://127.0.0.1:58180/api/admin/edge-nodes/connect/probe' \
  --data-urlencode 'targetUrl=http://127.0.0.1:58181' | jq .
```

## 3. 批次幂等和变更游标

空批次不修改业务数据，只用于验证认证、幂等键和响应状态：

```bash
BATCH="$(uuidgen)"
B='{"batch_id":"'"${BATCH}"'","operations":[]}'
COMMON=(-H "Authorization: Bearer ${TOKEN}" -H "X-WESP-Node-Id: ${NODE_ID}" -H "X-WESP-Request-Id: $(uuidgen)" -H 'Content-Type: application/json')
curl -fsS -X PUT "http://127.0.0.1:58180/sync/v1/batches/${BATCH}" \
  "${COMMON[@]}" -H "X-WESP-Body-SHA256: $(printf %s "${B}" | sha256sum | awk '{print $1}')" --data "${B}"
echo
curl -fsS -X PUT "http://127.0.0.1:58180/sync/v1/batches/${BATCH}" \
  "${COMMON[@]}" --data "${B}"
echo
```

第一次响应应为 `status=RECEIVED, duplicate=false`，第二次应为
`status=DUPLICATE, duplicate=true`。读取变更游标：

```bash
curl -fsS 'http://127.0.0.1:58180/sync/v1/changes?after=0&limit=5' \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-WESP-Node-Id: ${NODE_ID}" \
  -H "X-WESP-Request-Id: $(uuidgen)" | jq '{next_cursor,has_more,server_frontier,item_count:(.items|length)}'
```

管理页面中的“全量同步”在 WESP 节点上会写入 `SYNC_REQUEST` 操作。家庭节点主动
拉到该操作后，把对应 peer 游标重置为 0，再以幂等方式重放完整操作日志；因此公网
主节点不需要连接家庭节点，也不会退回旧 gRPC 全量推送。

## 4. 附件块、摘要和断点读取

该步骤验证附件二进制块不经过 JSON、以内容摘要寻址，并支持 Range/ETag：

```bash
TMP="$(mktemp)"
printf 'WESP attachment smoke test\n' >"${TMP}"
HASH="$(sha256sum "${TMP}" | awk '{print $1}')"
curl -fsS -X PUT "http://127.0.0.1:58180/sync/v1/blocks/${HASH}" \
  -H "Authorization: Bearer ${TOKEN}" -H "X-WESP-Node-Id: ${NODE_ID}" \
  -H "X-WESP-Request-Id: $(uuidgen)" -H "X-WESP-Body-SHA256: ${HASH}" \
  -H 'Content-Type: application/octet-stream' --data-binary "@${TMP}" | jq .
curl -fsS -D /tmp/wesp-block.headers -o /tmp/wesp-block.part \
  -H "Authorization: Bearer ${TOKEN}" -H "X-WESP-Node-Id: ${NODE_ID}" \
  -H "X-WESP-Request-Id: $(uuidgen)" -H "Range: bytes=0-9" -H "If-Range: ${HASH}" \
  "http://127.0.0.1:58180/sync/v1/blocks/${HASH}"
rg 'HTTP/|ETag|Content-Range|Accept-Ranges' /tmp/wesp-block.headers
rm -f "${TMP}" /tmp/wesp-block.headers /tmp/wesp-block.part
```

预期 PUT 返回 `status=STORED`；GET 返回 `206 Partial Content`、匹配的
`ETag`、`Content-Range` 和 `Accept-Ranges: bytes`。上传同一摘要可以重复执行，
不会产生重复块。

## 5. 主动连接和离线恢复

home 只有 `WINDBLOG_WESP_PEER_URL=http://windblog-wesp-public:8080`，因此由
home 的 30 秒调度器主动发起会话、推送 outbox、拉取 public changes。确认容器
和端口：

```bash
scripts/wesp-two-node-test.sh status
podman logs --since 2m windblog-wesp-home | tail -80
```

模拟公网主离线，home 应仍能提供本地健康检查；恢复 public 后，home 会在下一个
调度周期继续使用已保存的游标：

```bash
podman stop windblog-wesp-public
curl -fsS http://127.0.0.1:58181/q/health/ready | jq .status
podman start windblog-wesp-public
until curl -fsS http://127.0.0.1:58180/q/health/ready >/dev/null; do sleep 2; done
sleep 35
scripts/wesp-two-node-test.sh status
```

如需观察游标（凭据从仓库 `.env` 读取，不要打印密码）：

```bash
set -a; source .env; set +a
podman exec -e PGPASSWORD="${POSTGRES_PASSWORD}" windblog-codex-test-db \
  psql -U "${POSTGRES_USER}" -d wesp_home -c \
  'select peer_id,cursor_row,updated_at from wesp_sync_cursors;'
```

## 6. 停止和再次启动

停止只移除两个应用容器，保留数据库、令牌和块目录，便于重复测试：

```bash
scripts/wesp-two-node-test.sh stop
scripts/wesp-two-node-test.sh up
```

`restart` 等价于重新构建镜像并重建两个容器。若不再需要这些测试数据库，确认
其中没有用户数据后，再由操作者显式删除 `wesp_public`/`wesp_home`；脚本不会
自动执行删除。
