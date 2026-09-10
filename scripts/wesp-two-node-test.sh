#!/usr/bin/env bash
set -Eeuo pipefail

# Start two isolated WESP test nodes as persistent Podman containers. The
# existing local-test dependency containers are reused; only the two WindBlog
# application containers and their data directories are created here.
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${WINDBLOG_WESP_TEST_DATA_DIR:-${ROOT_DIR}/.codex-test-data/wesp-two-node}"
INFRA_DB_CONTAINER="${WINDBLOG_WESP_INFRA_DB_CONTAINER:-windblog-codex-test-db}"
INFRA_NETWORK="${WINDBLOG_WESP_INFRA_NETWORK:-windblog-codex-test_app-network}"
PUBLIC_PORT="${WINDBLOG_WESP_PUBLIC_PORT:-58180}"
HOME_PORT="${WINDBLOG_WESP_HOME_PORT:-58181}"
PUBLIC_GRPC_PORT="${WINDBLOG_WESP_PUBLIC_GRPC_PORT:-59180}"
HOME_GRPC_PORT="${WINDBLOG_WESP_HOME_GRPC_PORT:-59181}"

PUBLIC_DB="${WINDBLOG_WESP_PUBLIC_DB:-wesp_public}"
HOME_DB="${WINDBLOG_WESP_HOME_DB:-wesp_home}"
TOKEN_FILE="${DATA_DIR}/wesp-shared-token"
ADMIN_ENV_FILE="${DATA_DIR}/admin.env"
LOG_DIR="${DATA_DIR}/logs"
ENV_DIR="${DATA_DIR}/env"
IMAGE="${WINDBLOG_WESP_TEST_IMAGE:-localhost/windblog:wesp-two-node-edge}"
BUILD_MODE="${WINDBLOG_WESP_TEST_BUILD_MODE:-native-micro}"
PUBLIC_CONTAINER="${WINDBLOG_WESP_PUBLIC_CONTAINER:-windblog-wesp-public}"
HOME_CONTAINER="${WINDBLOG_WESP_HOME_CONTAINER:-windblog-wesp-home}"
TEST_ADMIN_USERNAME="${WINDBLOG_WESP_TEST_ADMIN_USERNAME:-wespadmin}"
TEST_ADMIN_EMAIL="${WINDBLOG_WESP_TEST_ADMIN_EMAIL:-wespadmin@example.test}"
TEST_ADMIN_PASSWORD="${WINDBLOG_WESP_TEST_ADMIN_PASSWORD:-WespLocalAdmin!2026}"
TEST_MAX_EGRESS_BYTES_PER_MINUTE="${WINDBLOG_WESP_TEST_MAX_EGRESS_BYTES_PER_MINUTE:-0}"

mkdir -p "${DATA_DIR}" "${LOG_DIR}" "${ENV_DIR}"
chmod 700 "${DATA_DIR}" "${LOG_DIR}" "${ENV_DIR}"

load_infra_env() {
  local source_env="${ROOT_DIR}/.env"
  if [[ ! -r "${source_env}" ]]; then
    echo "缺少 ${source_env}，无法复用本地测试依赖凭据。" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "${source_env}"
  set +a
  : "${POSTGRES_USER:=windblog}"
  : "${POSTGRES_PASSWORD:?请在 .env 中设置 POSTGRES_PASSWORD}"
  : "${REDIS_PASSWORD:?请在 .env 中设置 REDIS_PASSWORD}"
  : "${RABBITMQ_USERNAME:=windblog}"
  : "${RABBITMQ_PASSWORD:?请在 .env 中设置 RABBITMQ_PASSWORD}"
  : "${ELASTICSEARCH_USERNAME:=elastic}"
  : "${ELASTIC_PASSWORD:?请在 .env 中设置 ELASTIC_PASSWORD}"
  : "${GRPC_SERVER_TRUST_STORE_PASSWORD:?请在 .env 中设置 GRPC_SERVER_TRUST_STORE_PASSWORD}"
}

ensure_dependencies() {
  local db_status
  db_status="$(podman inspect "${INFRA_DB_CONTAINER}" --format '{{.State.Health.Status}}' 2>/dev/null || true)"
  if [[ "${db_status}" != "healthy" ]]; then
    echo "本地测试依赖未就绪，启动 scripts/local-test.sh ..."
    bash "${ROOT_DIR}/scripts/local-test.sh" up
  fi
  for container in "${INFRA_DB_CONTAINER}" windblog-codex-test-redis windblog-codex-test-rabbitmq windblog-codex-test-elasticsearch; do
    if [[ "$(podman inspect "${container}" --format '{{.State.Status}}' 2>/dev/null || true)" != "running" ]]; then
      echo "依赖容器 ${container} 未运行。" >&2
      exit 1
    fi
  done
  if ! podman network inspect "${INFRA_NETWORK}" >/dev/null 2>&1; then
    echo "找不到 Podman 网络 ${INFRA_NETWORK}。请先运行 scripts/local-test.sh up。" >&2
    exit 1
  fi
}

ensure_database() {
  local database="$1"
  if ! podman exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "${INFRA_DB_CONTAINER}" \
      psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB:-windblog}" -tAc \
      "SELECT 1 FROM pg_database WHERE datname='${database}'" | grep -q 1; then
    podman exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "${INFRA_DB_CONTAINER}" \
      psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB:-windblog}" -v ON_ERROR_STOP=1 \
      -c "CREATE DATABASE \"${database}\" OWNER \"${POSTGRES_USER}\"" >/dev/null
  fi
}

ensure_token() {
  if [[ ! -s "${TOKEN_FILE}" ]]; then
    umask 077
    openssl rand -hex 32 >"${TOKEN_FILE}"
  fi
  chmod 600 "${TOKEN_FILE}"
  WESP_TOKEN="$(<"${TOKEN_FILE}")"
  export WESP_TOKEN
}

write_admin_env() {
  umask 077
  cat >"${ADMIN_ENV_FILE}" <<EOF
WINDBLOG_WESP_TEST_ADMIN_USERNAME=${TEST_ADMIN_USERNAME}
WINDBLOG_WESP_TEST_ADMIN_EMAIL=${TEST_ADMIN_EMAIL}
WINDBLOG_WESP_TEST_ADMIN_PASSWORD=${TEST_ADMIN_PASSWORD}
EOF
  chmod 600 "${ADMIN_ENV_FILE}"
}

ensure_admin() {
  local node="$1" port="$2" installed body
  installed="$(curl -fsS "http://127.0.0.1:${port}/api/admin/install/status" \
    | jq -r '.installed // false')"
  if [[ "${installed}" == "true" ]]; then
    return 0
  fi
  body="$(jq -cn \
    --arg username "${TEST_ADMIN_USERNAME}" \
    --arg email "${TEST_ADMIN_EMAIL}" \
    --arg password "${TEST_ADMIN_PASSWORD}" \
    --arg siteUrl "http://127.0.0.1:${port}" \
    --arg title "WindBlog WESP ${node}" \
    '{username:$username,email:$email,password:$password,siteTitle:$title,siteSubtitle:"本地 WESP 双节点测试",siteDescription:"仅供本地协议验收",siteKeywords:["wesp","local-test"],siteAuthor:"WindBlog",siteUrl:$siteUrl}')"
  curl -fsS -X POST "http://127.0.0.1:${port}/api/admin/install" \
    -H 'Content-Type: application/json' --data "${body}" >/dev/null
}

write_env() {
  local node="$1" db="$2" host_port="$3" grpc_port="$4" peer="$5"
  local node_dir="${DATA_DIR}/${node}"
  local node_role="edge"
  if [[ "${node}" == "public" ]]; then
    node_role="primary"
  fi
  mkdir -p "${node_dir}/uploads" "${node_dir}/blocks" "${node_dir}/logs" "${node_dir}/rsa-keys"
  chmod 700 "${node_dir}" "${node_dir}/uploads" "${node_dir}/blocks" "${node_dir}/logs" "${node_dir}/rsa-keys"
  # Paths below are container paths. The host data directory is mounted at
  # /data by start_node; the certificate directory is mounted read-only.
  cat >"${ENV_DIR}/${node}.env" <<EOF
QUARKUS_PROFILE=edge
QUARKUS_HTTP_HOST=0.0.0.0
QUARKUS_HTTP_PORT=8080
QUARKUS_GRPC_SERVER_PORT=9000
GRPC_SERVER_PLAINTEXT=false
GRPC_SERVER_CLIENT_AUTH=required
GRPC_SERVER_CERTIFICATE=/app/certs/ca/server.crt
GRPC_SERVER_KEY=/app/certs/ca/server.key
GRPC_SERVER_TRUST_STORE=/app/certs/ca/truststore.p12
GRPC_SERVER_TRUST_STORE_PASSWORD=${GRPC_SERVER_TRUST_STORE_PASSWORD}
WINDBLOG_NODE_ROLE=${node_role}
WINDBLOG_NODE_ID=wesp-${node}
WINDBLOG_SITE_PUBLIC_URL=http://127.0.0.1:${host_port}
# The two-node harness is bound to local plain HTTP ports.  Keep this aligned
# with the native image's build-time default; secure cookies would be unusable
# over the harness' http://127.0.0.1 endpoints and trigger Quarkus static-init
# mismatch checks when the image is rebuilt in a container.
COOKIE_SECURE=false
CORS_ORIGINS=http://127.0.0.1:${host_port}
SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD=false
WINDBLOG_CODEX_CREATOR_EVENTS_ENABLED=false
WINDBLOG_OUTBOX_ENABLED=false
WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED=false
WIND_BLOG_MEDIA_VIRUS_SCAN_REQUIRED=false
DB_JDBC_URL=jdbc:postgresql://windblog-codex-test-db:5432/${db}
DB_USERNAME=${POSTGRES_USER}
DB_PASSWORD=${POSTGRES_PASSWORD}
REDIS_HOST=windblog-codex-test-redis
REDIS_PORT=6379
REDIS_PASSWORD=${REDIS_PASSWORD}
RABBITMQ_HOST=windblog-codex-test-rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=${RABBITMQ_USERNAME}
RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
ELASTICSEARCH_HOSTS=http://windblog-codex-test-elasticsearch:9200
ELASTICSEARCH_USERNAME=${ELASTICSEARCH_USERNAME}
ELASTICSEARCH_PASSWORD=${ELASTIC_PASSWORD}
ELASTICSEARCH_SSL_VERIFY=none
ELASTICSEARCH_SSL_TRUST_ALL=true
ADMIN_JWT_SECRET=${WESP_TOKEN}-${node}-admin
USER_JWT_SECRET=${WESP_TOKEN}-${node}-user
SECURITY_EVENT_HASH_SECRET=${WESP_TOKEN}-${node}-events
MEDIA_UPLOAD_DIR=/data/uploads
WINDBLOG_WESP_ENABLED=true
WINDBLOG_WESP_PEER_URL=${peer}
WINDBLOG_WESP_AUTH_TOKEN=${WESP_TOKEN}
WINDBLOG_WESP_LOCAL_TARGET_ALIAS=http://windblog-wesp-home:8080
WINDBLOG_WESP_LOCAL_TARGET_PORT=${HOME_PORT}
WINDBLOG_WESP_LOCAL_PEER_ALIAS=http://windblog-wesp-public:8080
WINDBLOG_WESP_TENANT_ID=wesp-local
WINDBLOG_WESP_DATASET_ID=public
WINDBLOG_WESP_INCARNATION=wesp-${node}-incarnation-1
WINDBLOG_WESP_CONFIG_FILE=/data/wesp-config.json
WINDBLOG_WESP_BLOCK_STORE=/data/blocks
WINDBLOG_WESP_REQUEST_TIMEOUT=10S
WINDBLOG_WESP_MAX_PULL_ITEMS=64
WINDBLOG_WESP_MAX_EGRESS_BYTES_PER_MINUTE=${TEST_MAX_EGRESS_BYTES_PER_MINUTE}
WINDBLOG_OUTBOX_ENABLED=false
WINDBLOG_CODEX_CREATOR_EVENTS_ENABLED=false
WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED=false
WIND_BLOG_MEDIA_VIRUS_SCAN_REQUIRED=false
WINDBLOG_STORAGE_METRICS_ENABLED=false
WINDBLOG_LOG_COMPRESSION_ENABLED=false
QUARKUS_LOG_FILE_ENABLED=false
EOF
  chmod 600 "${ENV_DIR}/${node}.env"
}

container_running() {
  local container="$1"
  [[ "$(podman inspect "${container}" --format '{{.State.Status}}' 2>/dev/null || true)" == "running" ]]
}

stop_node() {
  local container="$1"
  if podman container exists "${container}"; then
    podman rm -f "${container}" >/dev/null || true
  fi
}

build_app() {
  # Build the edge profile with the micro native runtime by default. The JVM
  # path remains available for fast debugging when explicitly requested.
  case "${BUILD_MODE}" in
    native-micro)
      # The local edge harness uses plain HTTP cookies. Keep the build-time
      # value identical to the generated env file; Quarkus validates
      # static-init config against the runtime environment.
      (cd "${ROOT_DIR}" && ./mvnw -q -Dmaven.test.skip=true -Dnative \
        -Dquarkus.profile=edge -Dcookie.secure=false \
        -Dquarkus.container-image.build=false package)
      podman build -q -f "${ROOT_DIR}/src/main/docker/Dockerfile.native-micro" \
        -t "${IMAGE}" "${ROOT_DIR}" >/dev/null
      ;;
    jvm)
      (cd "${ROOT_DIR}" && ./mvnw -q -Dmaven.test.skip=true \
        -Dquarkus.profile=edge -Dquarkus.container-image.build=false package)
      podman build -q -f "${ROOT_DIR}/Containerfile.wesp-test" -t "${IMAGE}" "${ROOT_DIR}" >/dev/null
      ;;
    *)
      echo "不支持的 WINDBLOG_WESP_TEST_BUILD_MODE: ${BUILD_MODE}（可选 native-micro 或 jvm）" >&2
      exit 2
      ;;
  esac
}

start_node() {
  local node="$1" container="$2" host_port="$3" grpc_port="$4"
  local rsa_mount_dir="/work/rsa_keys"
  if [[ "${BUILD_MODE}" == "jvm" ]]; then
    rsa_mount_dir="/app/rsa_keys"
  fi
  stop_node "${container}"
  # Bind mounts are owned by the invoking developer. Keep the rootless user
  # mapping aligned so the native image can persist uploads and WESP blocks
  # without making the test data world-writable.
  podman run -d --name "${container}" --network "${INFRA_NETWORK}" \
    --userns=keep-id --user "$(id -u):$(id -g)" \
    --env-file "${ENV_DIR}/${node}.env" \
    -p "127.0.0.1:${host_port}:8080" \
    -p "127.0.0.1:${grpc_port}:9000" \
    -v "${DATA_DIR}/${node}:/data:Z" \
    -v "${DATA_DIR}/${node}/rsa-keys:${rsa_mount_dir}:Z" \
    -v "${ROOT_DIR}/certs:/app/certs:ro,Z" \
    "${IMAGE}" >/dev/null
}

wait_ready() {
  local node="$1" container="$2" port="$3"
  for _ in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:${port}/q/health/ready" >/dev/null 2>&1; then
      return 0
    fi
    if ! container_running "${container}"; then
      echo "${node} 容器已退出，查看: podman logs ${container}" >&2
      podman logs --tail 100 "${container}" >&2 || true
      exit 1
    fi
    sleep 2
  done
  echo "等待 ${node} (${port}) 就绪超时。" >&2
  podman logs --tail 100 "${container}" >&2 || true
  exit 1
}

status() {
  for spec in "public:${PUBLIC_CONTAINER}:${PUBLIC_PORT}:${PUBLIC_GRPC_PORT}" "home:${HOME_CONTAINER}:${HOME_PORT}:${HOME_GRPC_PORT}"; do
    IFS=: read -r node container http_port grpc_port <<<"${spec}"
    if podman container exists "${container}"; then
      state="$(podman inspect "${container}" --format '{{.State.Status}}' 2>/dev/null || true)"
      echo "${node}: ${state} http=http://127.0.0.1:${http_port} grpc=127.0.0.1:${grpc_port} env=${ENV_DIR}/${node}.env"
    else
      echo "${node}: stopped"
    fi
  done
}

load_infra_env
ensure_dependencies
ensure_database "${PUBLIC_DB}"
ensure_database "${HOME_DB}"
ensure_token
write_admin_env
write_env public "${PUBLIC_DB}" "${PUBLIC_PORT}" "${PUBLIC_GRPC_PORT}" ""
write_env home "${HOME_DB}" "${HOME_PORT}" "${HOME_GRPC_PORT}" "http://${PUBLIC_CONTAINER}:8080"

case "${1:-up}" in
  up)
    build_app
    start_node public "${PUBLIC_CONTAINER}" "${PUBLIC_PORT}" "${PUBLIC_GRPC_PORT}"
    start_node home "${HOME_CONTAINER}" "${HOME_PORT}" "${HOME_GRPC_PORT}"
    wait_ready public "${PUBLIC_CONTAINER}" "${PUBLIC_PORT}"
    wait_ready home "${HOME_CONTAINER}" "${HOME_PORT}"
    ensure_admin public "${PUBLIC_PORT}"
    ensure_admin home "${HOME_PORT}"
    echo "双实例已启动：public=http://127.0.0.1:${PUBLIC_PORT} home=http://127.0.0.1:${HOME_PORT}"
    echo "共享 WESP token 文件：${TOKEN_FILE}"
    status
    ;;
  stop|down)
    stop_node "${PUBLIC_CONTAINER}"
    stop_node "${HOME_CONTAINER}"
    status
    ;;
  restart)
    build_app
    start_node public "${PUBLIC_CONTAINER}" "${PUBLIC_PORT}" "${PUBLIC_GRPC_PORT}"
    start_node home "${HOME_CONTAINER}" "${HOME_PORT}" "${HOME_GRPC_PORT}"
    wait_ready public "${PUBLIC_CONTAINER}" "${PUBLIC_PORT}"
    wait_ready home "${HOME_CONTAINER}" "${HOME_PORT}"
    ensure_admin public "${PUBLIC_PORT}"
    ensure_admin home "${HOME_PORT}"
    status
    ;;
  status)
    status
    ;;
  logs)
    node="${2:-home}"
    container="${HOME_CONTAINER}"
    [[ "${node}" == "public" ]] && container="${PUBLIC_CONTAINER}"
    podman logs --tail "${3:-120}" "${container}"
    ;;
  env)
    echo "public=${ENV_DIR}/public.env"
    echo "home=${ENV_DIR}/home.env"
    echo "token=${TOKEN_FILE}"
    echo "admin=${ADMIN_ENV_FILE}"
    ;;
  *)
    echo "用法: $0 {up|stop|restart|status|logs [public|home] [lines]|env}" >&2
    exit 2
    ;;
esac
