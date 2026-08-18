#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
  COMPOSE=(podman compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  printf '%s\n' '未找到 Docker Compose 或 Podman Compose。' >&2
  exit 1
fi

configured_data_root="${WINDBLOG_DATA_DIR:-}"
if [[ -z "$configured_data_root" && -f .env ]]; then
  configured_data_root="$(sed -n 's/^WINDBLOG_DATA_DIR=//p' .env | tail -n 1)"
  configured_data_root="${configured_data_root%\"}"
  configured_data_root="${configured_data_root#\"}"
fi
DATA_ROOT="${configured_data_root:-.}"
if [[ "$DATA_ROOT" != /* ]]; then
  DATA_ROOT="$SCRIPT_DIR/${DATA_ROOT#./}"
fi

configured_cert_root="${WINDBLOG_CERT_DIR:-}"
if [[ -z "$configured_cert_root" && -f .env ]]; then
  configured_cert_root="$(sed -n 's/^WINDBLOG_CERT_DIR=//p' .env | tail -n 1)"
  configured_cert_root="${configured_cert_root%\"}"
  configured_cert_root="${configured_cert_root#\"}"
fi
CERT_ROOT="${configured_cert_root:-./certs}"
if [[ "$CERT_ROOT" != /* ]]; then
  CERT_ROOT="$SCRIPT_DIR/${CERT_ROOT#./}"
fi

DATA_DIRS=(
  postgres-data
  redis-data
  rabbitmq_data
  elasticsearch-data
  kibana-data
  clamav-data
  windblog-logs
  uploads
)

compose() {
  "${COMPOSE[@]}" "$@"
}

prepare_data_dirs() {
  local name path
  mkdir -p "$DATA_ROOT"
  for name in "${DATA_DIRS[@]}"; do
    path="$DATA_ROOT/$name"
    if [[ ! -e "$path" ]]; then
      mkdir -p "$path"
      # 本地开发容器可能以不同 UID 运行；只放宽数据根目录权限，不递归修改已有数据。
      chmod 0777 "$path"
    else
      mkdir -p "$path"
    fi
  done
}

prepare_grpc_certs() {
  local ca_dir ca_key ca_cert server_key server_cert client_key client_cert trust_store trust_password
  local server_csr client_csr ca_serial
  if ! command -v openssl >/dev/null 2>&1 || ! command -v keytool >/dev/null 2>&1; then
    printf '%s\n' '生成本地 gRPC mTLS 证书需要 openssl 和 keytool。' >&2
    return 1
  fi

  ca_dir="$CERT_ROOT/ca"
  ca_key="$ca_dir/ca.key"
  ca_cert="$ca_dir/ca.crt"
  server_key="$ca_dir/server.key"
  server_cert="$ca_dir/server.crt"
  client_key="$ca_dir/client.key"
  client_cert="$ca_dir/client.crt"
  trust_store="$ca_dir/truststore.p12"
  server_csr="$ca_dir/server.csr"
  client_csr="$ca_dir/client.csr"
  ca_serial="$ca_dir/ca.srl"
  trust_password="${GRPC_SERVER_TRUST_STORE_PASSWORD:-}"
  if [[ -z "$trust_password" && -f .env ]]; then
    trust_password="$(sed -n 's/^GRPC_SERVER_TRUST_STORE_PASSWORD=//p' .env | tail -n 1)"
    trust_password="${trust_password%\"}"
    trust_password="${trust_password#\"}"
  fi
  trust_password="${trust_password:-changeit}"

  mkdir -p "$ca_dir"
  chmod 0700 "$CERT_ROOT" "$ca_dir"

  if [[ ! -s "$ca_key" || ! -s "$ca_cert" ]]; then
    printf '%s\n' '正在生成本地 gRPC 根 CA...'
    openssl req -x509 -newkey rsa:4096 -nodes \
      -keyout "$ca_key" -out "$ca_cert" -days 3650 \
      -subj '/CN=WindBlog Local CA/O=WindBlog' \
      -addext 'basicConstraints=critical,CA:TRUE' \
      -addext 'keyUsage=critical,keyCertSign,cRLSign' \
      >/dev/null 2>&1
  fi

  if [[ ! -s "$server_key" || ! -s "$server_cert" ]]; then
    printf '%s\n' '正在生成本地 gRPC 服务端证书...'
    openssl req -new -newkey rsa:2048 -nodes \
      -keyout "$server_key" -out "$server_csr" \
      -subj '/CN=main-node' \
      -addext 'subjectAltName=DNS:localhost,DNS:main-node,DNS:windblog,IP:127.0.0.1' \
      -addext 'extendedKeyUsage=serverAuth' \
      >/dev/null 2>&1
    openssl x509 -req -in "$server_csr" -CA "$ca_cert" -CAkey "$ca_key" \
      -CAcreateserial -CAserial "$ca_serial" -out "$server_cert" \
      -days 825 -sha256 -copy_extensions copy >/dev/null 2>&1
  fi

  if [[ ! -s "$client_key" || ! -s "$client_cert" ]]; then
    printf '%s\n' '正在生成本地 gRPC 客户端证书...'
    openssl req -new -newkey rsa:2048 -nodes \
      -keyout "$client_key" -out "$client_csr" \
      -subj '/CN=windblog-local-client' \
      -addext 'extendedKeyUsage=clientAuth' \
      >/dev/null 2>&1
    openssl x509 -req -in "$client_csr" -CA "$ca_cert" -CAkey "$ca_key" \
      -CAcreateserial -CAserial "$ca_serial" -out "$client_cert" \
      -days 825 -sha256 -copy_extensions copy >/dev/null 2>&1
  fi

  if [[ ! -s "$trust_store" ]]; then
    keytool -importcert -noprompt -alias windblog-local-ca \
      -file "$ca_cert" -keystore "$trust_store" -storetype PKCS12 \
      -storepass "$trust_password" >/dev/null 2>&1
  elif ! keytool -list -keystore "$trust_store" -storetype PKCS12 \
      -storepass "$trust_password" >/dev/null 2>&1; then
    printf '%s\n' "现有 truststore 密码不匹配：$trust_store" >&2
    return 1
  fi

  chmod 0600 "$ca_key" "$server_key" "$client_key" "$trust_store"
  rm -f "$server_csr" "$client_csr" "$ca_serial"
  printf 'gRPC mTLS 证书已准备：%s\n' "$ca_dir"
}

confirm() {
  local answer
  read -r -p "${1} [y/N] " answer
  [[ "$answer" =~ ^[Yy]$ ]]
}

volume_mountpoint() {
  local volume="$1"
  if command -v podman >/dev/null 2>&1; then
    podman volume inspect "$volume" --format '{{.Mountpoint}}' 2>/dev/null || true
  elif command -v docker >/dev/null 2>&1; then
    docker volume inspect "$volume" --format '{{.Mountpoint}}' 2>/dev/null || true
  fi
}

migrate_named_volumes() {
  local project volume target source has_data
  project="${COMPOSE_PROJECT_NAME:-$(basename "$SCRIPT_DIR")}"

  printf '%s\n' '此操作会停止 Compose 服务，把现有命名卷复制到当前目录；原命名卷不会删除。'
  if ! confirm '继续迁移吗？'; then
    return 0
  fi

  compose stop || true
  prepare_data_dirs

  declare -A volume_targets=(
    [postgres-data]="postgres-data"
    [redis-data]="redis-data"
    [rabbitmq_data]="rabbitmq_data"
    [elasticsearch-data]="elasticsearch-data"
    [kibana-data]="kibana-data"
    [clamav-data]="clamav-data"
    [windblog-logs]="windblog-logs"
  )

  for volume in "${!volume_targets[@]}"; do
    target="$DATA_ROOT/${volume_targets[$volume]}"
    source="$(volume_mountpoint "${project}_${volume}")"
    if [[ -z "$source" || ! -d "$source" ]]; then
      printf '跳过 %-22s（命名卷不存在）\n' "$volume"
      continue
    fi

    has_data=0
    if find "$target" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null | grep -q .; then
      has_data=1
    fi
    if [[ "$has_data" -eq 1 ]]; then
      printf '跳过 %-22s（目标目录已有内容，不覆盖）\n' "$volume"
      continue
    fi

    printf '复制 %-22s -> %s\n' "$volume" "$target"
    local staging="${target}.migration.$$"
    mkdir -p "$staging"
    if command -v podman >/dev/null 2>&1; then
      podman unshare cp -a "$source/." "$staging/"
    else
      cp -a "$source/." "$staging/"
    fi
    rmdir "$target"
    mv "$staging" "$target"
  done

  printf '%s\n' '迁移完成。原命名卷仍保留，可在确认目录数据正常后手工清理。'
}

service_start() {
  prepare_data_dirs
  prepare_grpc_certs
  service_pull
  compose up -d --no-build windblog
}

service_stop() {
  compose stop windblog
}

service_restart() {
  prepare_data_dirs
  prepare_grpc_certs
  service_pull
  compose up -d --no-build --force-recreate windblog
}

service_pull() {
  if ! compose pull windblog; then
    printf '%s\n' 'GHCR 镜像拉取失败：请先执行 podman login ghcr.io（或 docker login ghcr.io）。'
    printf '%s\n' '登录令牌需要具备 read:packages 权限；不要把令牌写入 .env。'
    return 1
  fi
}

service_status() {
  compose ps --all
}

service_logs() {
  compose logs -f --tail=200 windblog
}

service_health() {
  local endpoint published host port
  endpoint="$(compose port windblog 8080 2>/dev/null | tail -n 1)"
  if [[ -z "$endpoint" ]]; then
    printf '%s\n' 'WindBlog 尚未发布 HTTP 端口，请先启动服务。' >&2
    return 1
  fi
  host="${endpoint%:*}"
  port="${endpoint##*:}"
  published="http://${host#*:}:$port/q/health/ready"
  curl --fail --silent --show-error "$published"
  printf '\nHTTP 健康检查通过：%s\n' "$published"
}

service_config() {
  compose config --quiet
  printf '%s\n' 'Compose 配置校验通过。'
}

service_shell() {
  compose exec windblog /bin/bash
}

usage() {
  cat <<'EOF'
用法：./admin.sh [命令]

命令：
  start       准备目录并启动 WindBlog 及依赖
  stop        停止 WindBlog
  restart     拉取最新镜像并重启 WindBlog
  pull        从 GHCR 拉取 WindBlog 镜像
  status      查看 Compose 服务状态
  logs        跟踪 WindBlog 日志
  health      检查 WindBlog readiness
  config      校验 Compose 配置
  certs       准备本地 gRPC mTLS 证书
  migrate     将旧命名卷复制到目录挂载（不删除旧卷）
  shell       进入 WindBlog 容器
  menu        打开交互式菜单（默认）
EOF
}

run_menu() {
  local choice
  while true; do
    printf '\nWindBlog Compose 管理器\n'
    printf '%s\n' '1) 启动  2) 停止  3) 重启  4) 拉取镜像  5) 状态  6) 日志'
    printf '%s\n' '7) 健康检查  8) 配置校验  9) 迁移旧命名卷  10) 进入容器  11) 准备 gRPC 证书  0) 退出'
    read -r -p '请选择: ' choice || return 0
    case "$choice" in
      1) service_start ;;
      2) service_stop ;;
      3) service_restart ;;
      4) service_pull ;;
      5) service_status ;;
      6) service_logs ;;
      7) service_health ;;
      8) service_config ;;
      9) migrate_named_volumes ;;
      10) service_shell ;;
      11) prepare_grpc_certs ;;
      0) return 0 ;;
      *) printf '%s\n' '无效选项。' ;;
    esac
  done
}

command="${1:-menu}"
case "$command" in
  start) service_start ;;
  stop) service_stop ;;
  restart) service_restart ;;
  pull) service_pull ;;
  status) service_status ;;
  logs) service_logs ;;
  health) service_health ;;
  config) service_config ;;
  certs) prepare_grpc_certs ;;
  migrate) migrate_named_volumes ;;
  shell) service_shell ;;
  menu) run_menu ;;
  -h|--help|help) usage ;;
  *) usage; exit 2 ;;
esac
