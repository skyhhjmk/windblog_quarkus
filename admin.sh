#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

COMPOSE=()
ENV_FILE="${WINDBLOG_ENV_FILE:-$SCRIPT_DIR/.env}"
if [[ "$ENV_FILE" != /* ]]; then
  ENV_FILE="$SCRIPT_DIR/${ENV_FILE#./}"
fi

detect_compose() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
    COMPOSE=(podman compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    COMPOSE=()
    return 1
  fi
}

configured_data_root="${WINDBLOG_DATA_DIR:-}"
if [[ -z "$configured_data_root" && -f "$ENV_FILE" ]]; then
  configured_data_root="$(sed -n 's/^WINDBLOG_DATA_DIR=//p' "$ENV_FILE" | tail -n 1)"
  configured_data_root="${configured_data_root%\"}"
  configured_data_root="${configured_data_root#\"}"
fi
DATA_ROOT="${configured_data_root:-.}"
if [[ "$DATA_ROOT" != /* ]]; then
  DATA_ROOT="$SCRIPT_DIR/${DATA_ROOT#./}"
fi

configured_cert_root="${WINDBLOG_CERT_DIR:-}"
if [[ -z "$configured_cert_root" && -f "$ENV_FILE" ]]; then
  configured_cert_root="$(sed -n 's/^WINDBLOG_CERT_DIR=//p' "$ENV_FILE" | tail -n 1)"
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

DATA_DIR_MODE="${WINDBLOG_DATA_DIR_MODE:-0777}"
CERT_DIR_MODE="${WINDBLOG_CERT_DIR_MODE:-0700}"
PRIVATE_FILE_MODE="${WINDBLOG_PRIVATE_FILE_MODE:-0600}"

read_env_value() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" && -f "$ENV_FILE" ]]; then
    value="$(sed -n "s/^${name}=//p" "$ENV_FILE" | tail -n 1)"
    value="${value%\"}"
    value="${value#\"}"
    value="${value%\'}"
    value="${value#\'}"
  fi
  printf '%s' "$value"
}

env_has_key() {
  local name="$1"
  [[ -f "$ENV_FILE" ]] && grep -Eq "^[[:space:]]*${name}=" "$ENV_FILE"
}

env_value_is_weak() {
  local value="${1:-}"
  local normalized
  normalized="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
  [[ -z "$normalized" || "$normalized" == changeit || "$normalized" == admin || "$normalized" == guest \
    || "$normalized" == password || "$normalized" == secret \
    || "$normalized" == replace_* || "$normalized" == use_a_secret_store_value \
    || "$normalized" == *change-me* || "$normalized" == *change_me* \
    || "$normalized" == *replace_with* || "$normalized" == *default* \
    || "$normalized" == *password* || "$normalized" == *secret* ]]
}

generate_secret() {
  local bytes="${1:-32}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    od -An -N "$bytes" -tx1 /dev/urandom | tr -d ' \n'
  fi
}

set_env_value() {
  local name="$1" value="$2" temp
  local env_dir
  env_dir="$(dirname -- "$ENV_FILE")"
  mkdir -p "$env_dir"
  temp="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
  if awk -v name="$name" -v value="$value" '
    BEGIN { replaced = 0 }
    $0 ~ "^[[:space:]]*" name "=" {
      if (!replaced) { print name "=" value; replaced = 1 }
      next
    }
    { print }
    END { if (!replaced) print name "=" value }
  ' "$ENV_FILE" > "$temp"; then
    chmod "$PRIVATE_FILE_MODE" "$temp"
    mv "$temp" "$ENV_FILE"
  else
    rm -f -- "$temp"
    return 1
  fi
}

ensure_env_value() {
  local name="$1" value="$2" replace_weak="${3:-false}"
  local current
  current="$(read_env_value "$name")"
  if [[ -n "$current" ]] && { [[ "$replace_weak" != true ]] || ! env_value_is_weak "$current"; }; then
    return 0
  fi
  set_env_value "$name" "$value"
}

ensure_production_value() {
  local name="$1" value="$2" legacy_value="$3" current
  current="$(read_env_value "$name")"
  if [[ -z "$current" || "$current" == "$legacy_value" ]]; then
    set_env_value "$name" "$value"
  fi
}

ensure_container_cert_path() {
  local name="$1" value="$2" current
  current="$(read_env_value "$name")"
  if [[ -z "$current" || "$current" == certs/ca/* || "$current" == /work/certs/* ]]; then
    set_env_value "$name" "$value"
  fi
}

backup_env_file() {
  local backup index=0
  while true; do
    backup="${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    if [[ "$index" -gt 0 ]]; then
      backup="${backup}.${index}"
    fi
    [[ ! -e "$backup" ]] && break
    index=$((index + 1))
  done
  cp -p -- "$ENV_FILE" "$backup"
  chmod "$PRIVATE_FILE_MODE" "$backup"
  printf '已有 .env 已备份：%s\n' "$backup"
}

rotate_local_truststore() {
  local password="$1" trust_store="$CERT_ROOT/ca/truststore.p12" ca_cert="$CERT_ROOT/ca/ca.crt"
  local staging_dir staged_store backup
  if [[ ! -s "$trust_store" || ! -s "$ca_cert" ]]; then
    printf '%s\n' '无法轮换本地 truststore：缺少现有 truststore 或 CA 证书。' >&2
    return 1
  fi
  staging_dir="$(mktemp -d "$CERT_ROOT/ca/truststore.rotate.XXXXXX")"
  staged_store="$staging_dir/truststore.p12"
  if ! keytool -importcert -noprompt -alias windblog-local-ca \
    -file "$ca_cert" -keystore "$staged_store" -storetype PKCS12 \
    -storepass "$password" >/dev/null 2>&1; then
    rmdir "$staging_dir"
    printf '%s\n' '本地 truststore 轮换失败，未修改现有 truststore。' >&2
    return 1
  fi
  chmod "$PRIVATE_FILE_MODE" "$staged_store"
  backup="${trust_store}.bak.$(date +%Y%m%d%H%M%S)"
  cp -p -- "$trust_store" "$backup"
  mv -- "$staged_store" "$trust_store"
  rmdir "$staging_dir"
  printf '本地 truststore 已轮换，旧文件备份为：%s\n' "$backup"
}

run_as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    printf '需要 sudo 执行：'
    printf ' %q' "$@"
    printf '\n'
    sudo "$@"
  else
    printf '当前用户不是 root，且未找到 sudo，无法执行：' >&2
    printf ' %q' "$@" >&2
    printf '\n' >&2
    return 1
  fi
}

detect_package_manager() {
  if command -v apt-get >/dev/null 2>&1; then
    printf '%s' apt-get
  elif command -v dnf >/dev/null 2>&1; then
    printf '%s' dnf
  elif command -v yum >/dev/null 2>&1; then
    printf '%s' yum
  elif command -v pacman >/dev/null 2>&1; then
    printf '%s' pacman
  elif command -v apk >/dev/null 2>&1; then
    printf '%s' apk
  elif command -v zypper >/dev/null 2>&1; then
    printf '%s' zypper
  else
    return 1
  fi
}

install_ssl_dependencies() {
  local package_manager packages=()
  if command -v openssl >/dev/null 2>&1 && command -v keytool >/dev/null 2>&1; then
    return 0
  fi

  package_manager="$(detect_package_manager || true)"
  if [[ -z "$package_manager" ]]; then
    printf '%s\n' '缺少 openssl 或 keytool，且无法识别 apt-get/dnf/yum/pacman/apk/zypper。' >&2
    printf '%s\n' '请手动安装 OpenSSL 和带 keytool 的 Java Runtime 后重试。' >&2
    return 1
  fi

  if ! command -v openssl >/dev/null 2>&1; then
    packages+=(openssl)
  fi
  if ! command -v keytool >/dev/null 2>&1; then
    case "$package_manager" in
      apt-get) packages+=(default-jre-headless) ;;
      dnf|yum|zypper) packages+=(java-17-openjdk-headless) ;;
      pacman) packages+=(jre-openjdk-headless) ;;
      apk) packages+=(openjdk21-jre-headless) ;;
    esac
  fi

  printf '检测到包管理器：%s；将安装：' "$package_manager"
  printf ' %s' "${packages[@]}"
  printf '\n'

  case "$package_manager" in
    apt-get)
      run_as_root apt-get update
      run_as_root apt-get install -y "${packages[@]}"
      ;;
    dnf)
      run_as_root dnf install -y "${packages[@]}"
      ;;
    yum)
      run_as_root yum install -y "${packages[@]}"
      ;;
    pacman)
      run_as_root pacman -Sy --needed --noconfirm "${packages[@]}"
      ;;
    apk)
      run_as_root apk add --no-cache "${packages[@]}"
      ;;
    zypper)
      run_as_root zypper --non-interactive install "${packages[@]}"
      ;;
  esac

  if ! command -v openssl >/dev/null 2>&1 || ! command -v keytool >/dev/null 2>&1; then
    printf '%s\n' 'SSL 证书依赖安装后仍不完整，请检查 openssl 和 keytool 是否在 PATH 中。' >&2
    return 1
  fi
}

ensure_compose() {
  if [[ "${#COMPOSE[@]}" -eq 0 ]] && ! detect_compose; then
    printf '%s\n' '未找到 Docker Compose 或 Podman Compose。请先安装容器运行时；SSL 证书依赖可单独使用 ./admin.sh deps 安装。' >&2
    return 1
  fi
}

compose() {
  ensure_compose
  "${COMPOSE[@]}" "$@"
}

prepare_data_dirs() {
  local name path
  mkdir -p "$DATA_ROOT"
  for name in "${DATA_DIRS[@]}"; do
    path="$DATA_ROOT/$name"
    mkdir -p "$path"
    # 本地开发容器可能以不同 UID 运行；只修改挂载根目录，不递归修改已有数据。
    set_path_mode "$DATA_DIR_MODE" "$path"
  done
}

set_path_mode() {
  local mode="$1" path="$2"
  if chmod "$mode" "$path" 2>/dev/null; then
    return 0
  fi
  # Podman rootless 的 :U 挂载可能把目录所有者映射为 subuid；在用户命名空间内修复权限。
  if command -v podman >/dev/null 2>&1 && podman unshare chmod "$mode" "$path" 2>/dev/null; then
    return 0
  fi
  if [[ "$(id -u)" -eq 0 ]] || command -v sudo >/dev/null 2>&1; then
    run_as_root chmod "$mode" "$path"
    return 0
  fi
  printf '无法设置目录权限 mode=%s：%s\n' "$mode" "$path" >&2
  return 1
}

prepare_mount_dirs() {
  mkdir -p "$DATA_ROOT" "$CERT_ROOT/ca"
  chmod "$CERT_DIR_MODE" "$CERT_ROOT" "$CERT_ROOT/ca"
  prepare_data_dirs
}

file_mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

normalize_mode() {
  local mode="$1"
  mode="${mode#0}"
  printf '%s' "$mode"
}

check_directory() {
  local label="$1" path="$2" expected_mode="${3:-}" mode
  expected_mode="$(normalize_mode "$expected_mode")"
  if [[ ! -d "$path" ]]; then
    printf 'FAIL %-18s 缺少目录：%s\n' "$label" "$path"
    return 1
  fi
  mode="$(file_mode "$path")"
  if [[ ! -w "$path" ]] && ! (command -v podman >/dev/null 2>&1 && podman unshare test -w "$path" >/dev/null 2>&1); then
    printf 'FAIL %-18s 不可写：%s（mode=%s）\n' "$label" "$path" "$mode"
    return 1
  fi
  if [[ -n "$expected_mode" && "$mode" != "$expected_mode" ]]; then
    printf 'WARN %-18s 权限为 %s，期望 %s：%s\n' "$label" "$mode" "$expected_mode" "$path"
    return 1
  fi
  printf 'OK   %-18s mode=%s %s\n' "$label" "$mode" "$path"
}

check_paths() {
  local failed=0 name path mode
  printf 'WindBlog 挂载目录和权限检查\n'
  printf '数据根目录：%s\n证书根目录：%s\n' "$DATA_ROOT" "$CERT_ROOT"

  check_directory '数据根目录' "$DATA_ROOT" || failed=1
  check_directory '证书根目录' "$CERT_ROOT" "$CERT_DIR_MODE" || failed=1
  check_directory '证书 CA 目录' "$CERT_ROOT/ca" "$CERT_DIR_MODE" || failed=1

  for name in "${DATA_DIRS[@]}"; do
    path="$DATA_ROOT/$name"
    check_directory "$name" "$path" "$DATA_DIR_MODE" || failed=1
  done

  for path in "$CERT_ROOT/ca/ca.key" "$CERT_ROOT/ca/server.key" "$CERT_ROOT/ca/client.key" "$CERT_ROOT/ca/truststore.p12"; do
    if [[ ! -f "$path" ]]; then
      printf 'WARN %-18s 尚未生成：%s\n' '证书私密文件' "$path"
      failed=1
    else
      mode="$(file_mode "$path")"
      if [[ "$mode" != "$(normalize_mode "$PRIVATE_FILE_MODE")" ]]; then
        printf 'WARN %-18s mode=%s，期望 %s：%s\n' '证书私密文件' "$mode" "$(normalize_mode "$PRIVATE_FILE_MODE")" "$path"
        failed=1
      else
        printf 'OK   %-18s mode=%s %s\n' '证书私密文件' "$mode" "$path"
      fi
    fi
  done

  for path in "$CERT_ROOT/ca/ca.crt" "$CERT_ROOT/ca/server.crt" "$CERT_ROOT/ca/client.crt"; do
    if [[ ! -f "$path" ]]; then
      printf 'WARN %-18s 尚未生成：%s\n' '证书公开文件' "$path"
      failed=1
    else
      printf 'OK   %-18s %s\n' '证书公开文件' "$path"
    fi
  done

  if [[ "$failed" -eq 0 ]]; then
    printf '%s\n' '目录和权限检查通过。'
  else
    printf '%s\n' '目录和权限检查发现问题；可执行 ./admin.sh prepare 自动创建目录和证书。' >&2
  fi
  return "$failed"
}

env_generate() {
  local env_dir current_trust_password rabbit_password elastic_password trust_password
  local rotate_truststore=0
  local existed=0
  env_dir="$(dirname -- "$ENV_FILE")"
  mkdir -p "$env_dir"
  if [[ -e "$ENV_FILE" ]]; then
    if [[ ! -f "$ENV_FILE" ]]; then
      printf '.env 路径不是普通文件：%s\n' "$ENV_FILE" >&2
      return 1
    fi
    existed=1
    backup_env_file
  else
    umask 077
    : > "$ENV_FILE"
    chmod "$PRIVATE_FILE_MODE" "$ENV_FILE"
  fi

  install_ssl_dependencies

  rabbit_password="$(read_env_value RABBITMQ_DEFAULT_PASS)"
  if env_value_is_weak "$rabbit_password" || [[ -z "$rabbit_password" ]]; then
    rabbit_password="$(generate_secret 32)"
  fi
  elastic_password="$(read_env_value ELASTIC_PASSWORD)"
  if env_value_is_weak "$elastic_password" || [[ -z "$elastic_password" ]]; then
    elastic_password="$(generate_secret 32)"
  fi

  current_trust_password="$(read_env_value GRPC_SERVER_TRUST_STORE_PASSWORD)"
  if [[ "$current_trust_password" == changeit && -s "$CERT_ROOT/ca/truststore.p12" ]] \
    && keytool -list -keystore "$CERT_ROOT/ca/truststore.p12" -storetype PKCS12 \
      -storepass changeit >/dev/null 2>&1; then
    rotate_truststore=1
    printf '%s\n' '检测到本地 truststore 使用 changeit；将随 .env 一起轮换为强密码。'
  elif [[ -z "$current_trust_password" && -s "$CERT_ROOT/ca/truststore.p12" ]] \
    && keytool -list -keystore "$CERT_ROOT/ca/truststore.p12" -storetype PKCS12 \
      -storepass changeit >/dev/null 2>&1; then
    current_trust_password=changeit
    rotate_truststore=1
    printf '%s\n' '检测到现有 truststore 使用 changeit；将随 .env 一起轮换为强密码。'
  fi
  trust_password="$current_trust_password"
  if [[ -z "$trust_password" ]]; then
    trust_password="$(generate_secret 32)"
  elif env_value_is_weak "$trust_password" && [[ "$rotate_truststore" -eq 0 ]]; then
    trust_password="$(generate_secret 32)"
  fi
  if [[ "$rotate_truststore" -eq 1 ]]; then
    trust_password="$(generate_secret 32)"
    rotate_local_truststore "$trust_password"
  fi

  # 本地可自动生成的密码使用十六进制随机值，避免 .env、URL 和 Compose 转义问题。
  ensure_env_value POSTGRES_PASSWORD "$(generate_secret 32)" true
  ensure_env_value REDIS_PASSWORD "$(generate_secret 32)" true
  ensure_env_value RABBITMQ_DEFAULT_PASS "$rabbit_password" true
  ensure_env_value RABBITMQ_PASSWORD "$rabbit_password" true
  ensure_env_value ELASTIC_PASSWORD "$elastic_password" true
  ensure_env_value ELASTICSEARCH_PASSWORD "$elastic_password" true
  ensure_env_value ADMIN_JWT_SECRET "$(generate_secret 32)" true
  ensure_env_value USER_JWT_SECRET "$(generate_secret 32)" true
  ensure_env_value SECURITY_EVENT_HASH_SECRET "$(generate_secret 32)" true
  ensure_env_value KIBANA_ENCRYPTION_KEY "$(generate_secret 32)" true
  ensure_env_value KIBANA_REPORTING_KEY "$(generate_secret 32)" true
  ensure_env_value GRPC_SERVER_TRUST_STORE_PASSWORD "$trust_password" true

  # 非密码项只在缺失或为空时补齐，不覆盖用户现有配置。
  ensure_env_value WINDBLOG_SITE_PUBLIC_URL 'https://your-domain.example'
  ensure_env_value WINDBLOG_IMAGE 'ghcr.io/skyhhjmk/windblog_quarkus:latest-native'
  ensure_env_value WINDBLOG_PULL_POLICY always
  ensure_env_value WINDBLOG_HOST_PORT 8080
  ensure_env_value WINDBLOG_GRPC_HOST_PORT 9000
  ensure_production_value QUARKUS_PROFILE prod dev
  ensure_env_value WINDBLOG_DATA_DIR .
  ensure_env_value WINDBLOG_DATA_DIR_MODE 0777
  ensure_env_value WINDBLOG_CERT_DIR ./certs
  ensure_env_value WINDBLOG_CERT_DIR_MODE 0700
  ensure_env_value WINDBLOG_PRIVATE_FILE_MODE 0600
  ensure_env_value WINDBLOG_HOST_BIND_IP 127.0.0.1
  ensure_env_value POSTGRES_USER windblog
  ensure_env_value POSTGRES_DB windblog
  ensure_env_value POSTGRES_HOST_PORT 5432
  ensure_env_value RABBITMQ_DEFAULT_USER windblog
  ensure_env_value RABBITMQ_USERNAME windblog
  ensure_env_value RABBITMQ_HOST_PORT 5672
  ensure_env_value RABBITMQ_MANAGEMENT_HOST_PORT 15672
  ensure_env_value REDIS_HOST_PORT 6379
  ensure_env_value ELASTICSEARCH_HOST_PORT 9200
  ensure_env_value KIBANA_HOST_PORT 5601
  ensure_env_value ELASTICSEARCH_USERNAME elastic
  ensure_env_value ELASTICSEARCH_SSL_VERIFY full
  ensure_env_value ELASTICSEARCH_SSL_TRUST_ALL false
  ensure_production_value COOKIE_SECURE true false
  ensure_env_value SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD true
  ensure_env_value CORS_ORIGINS https://your-domain.example
  ensure_production_value SECURITY_HEADERS_HSTS_ENABLED true false
  ensure_production_value SECURITY_HEADERS_CSP_ENFORCE true false
  ensure_production_value SECURITY_HEADERS_CSP_TRUSTED_TYPES_ENABLED true false
  ensure_env_value SECURITY_HEADERS_CSP_IMG_SOURCES none
  ensure_env_value SECURITY_HEADERS_CSP_CONNECT_SOURCES none
  ensure_env_value GRPC_SERVER_PLAINTEXT false
  ensure_env_value GRPC_SERVER_CLIENT_AUTH required
  ensure_container_cert_path GRPC_SERVER_CERTIFICATE /deployments/certs/ca/server.crt
  ensure_container_cert_path GRPC_SERVER_KEY /deployments/certs/ca/server.key
  ensure_container_cert_path GRPC_SERVER_TRUST_STORE /deployments/certs/ca/truststore.p12
  ensure_container_cert_path GRPC_CLIENT_CA_CERTIFICATE /deployments/certs/ca/ca.crt
  ensure_container_cert_path GRPC_CLIENT_CERTIFICATE /deployments/certs/ca/client.crt
  ensure_container_cert_path GRPC_CLIENT_KEY /deployments/certs/ca/client.key
  ensure_env_value GRPC_CLIENT_ALLOW_PLAINTEXT_FALLBACK false
  ensure_production_value WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED true false
  ensure_production_value WIND_BLOG_MEDIA_VIRUS_SCAN_REQUIRED true false
  ensure_env_value CLAMAV_HOST_BIND_IP 127.0.0.1
  ensure_env_value CLAMAV_HOST_PORT 3310

  chmod "$PRIVATE_FILE_MODE" "$ENV_FILE"
  if [[ "$existed" -eq 1 ]]; then
    printf '%s\n' ".env 已补全（已有非弱值保留）：$ENV_FILE"
  else
    printf '%s\n' ".env 已生成：$ENV_FILE"
  fi
  if ! env_has_key KIBANA_SERVICE_ACCOUNT_TOKEN || env_value_is_weak "$(read_env_value KIBANA_SERVICE_ACCOUNT_TOKEN)"; then
    printf '%s\n' '注意：KIBANA_SERVICE_ACCOUNT_TOKEN 不能离线伪造，请启动 Elasticsearch 后执行 src/main/resources/elasticsearch/create-kibana-token.sh。' >&2
  fi
  printf '%s\n' '下一步建议：./admin.sh env-check；检查通过后再执行 ./admin.sh prepare 和 ./admin.sh start。'
}

env_check_key() {
  local name="$1" minimum_length="$2" value
  value="$(read_env_value "$name")"
  if [[ -z "$value" ]]; then
    printf 'FAIL %-34s 缺失或为空\n' "$name"
    return 1
  fi
  if env_value_is_weak "$value"; then
    printf 'FAIL %-34s 使用默认值或占位值\n' "$name"
    return 1
  fi
  if [[ "${#value}" -lt "$minimum_length" ]]; then
    printf 'FAIL %-34s 长度不足（至少 %s）\n' "$name" "$minimum_length"
    return 1
  fi
  printf 'OK   %-34s 已配置\n' "$name"
}

env_check_exact() {
  local name="$1" expected="$2" actual
  actual="$(read_env_value "$name")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL %-34s 应为 %s\n' "$name" "$expected"
    return 1
  fi
  printf 'OK   %-34s=%s\n' "$name" "$expected"
}

env_check_bool() {
  local name="$1" actual
  actual="$(read_env_value "$name")"
  if [[ "$actual" != true && "$actual" != false ]]; then
    printf 'FAIL %-34s 必须为 true 或 false\n' "$name"
    return 1
  fi
  printf 'OK   %-34s=%s\n' "$name" "$actual"
}

env_check_port() {
  local name="$1" value
  value="$(read_env_value "$name")"
  if [[ -z "$value" ]]; then
    printf 'WARN %-34s 未配置（Compose 有默认值）\n' "$name"
    return 0
  fi
  if ! [[ "$value" =~ ^[0-9]+$ ]] || (( value < 1 || value > 65535 )); then
    printf 'FAIL %-34s 端口无效\n' "$name"
    return 1
  fi
  printf 'OK   %-34s 端口格式有效\n' "$name"
}

env_check() {
  local failed=0 profile bind_ip image site_url
  if [[ ! -f "$ENV_FILE" ]]; then
    printf 'FAIL .env 文件不存在：%s\n' "$ENV_FILE"
    printf '%s\n' '请先执行 ./admin.sh env-generate。' >&2
    return 1
  fi
  if [[ "$(file_mode "$ENV_FILE")" != "$(normalize_mode "$PRIVATE_FILE_MODE")" ]]; then
    printf 'FAIL .env 文件权限为 %s，期望 %s\n' "$(file_mode "$ENV_FILE")" "$(normalize_mode "$PRIVATE_FILE_MODE")"
    failed=1
  else
    printf 'OK   .env 文件权限为 %s\n' "$(file_mode "$ENV_FILE")"
  fi

  printf '%s\n' 'WindBlog .env 配置检查（不会输出任何密钥值）'
  env_check_key POSTGRES_PASSWORD 16 || failed=1
  env_check_key REDIS_PASSWORD 16 || failed=1
  env_check_key RABBITMQ_DEFAULT_USER 1 || failed=1
  env_check_key RABBITMQ_DEFAULT_PASS 16 || failed=1
  env_check_key RABBITMQ_PASSWORD 16 || failed=1
  env_check_key ELASTIC_PASSWORD 16 || failed=1
  env_check_key KIBANA_ENCRYPTION_KEY 32 || failed=1
  env_check_key KIBANA_REPORTING_KEY 32 || failed=1
  env_check_key KIBANA_SERVICE_ACCOUNT_TOKEN 16 || failed=1
  env_check_key ADMIN_JWT_SECRET 32 || failed=1
  env_check_key USER_JWT_SECRET 32 || failed=1
  env_check_key SECURITY_EVENT_HASH_SECRET 32 || failed=1
  env_check_key GRPC_SERVER_TRUST_STORE_PASSWORD 16 || failed=1

  if [[ "$(read_env_value RABBITMQ_DEFAULT_PASS)" != "$(read_env_value RABBITMQ_PASSWORD)" ]]; then
    printf '%s\n' 'FAIL RABBITMQ_DEFAULT_PASS 与 RABBITMQ_PASSWORD 不一致'
    failed=1
  fi
  profile="$(read_env_value QUARKUS_PROFILE)"
  profile="${profile:-prod}"
  if [[ "$profile" == prod || "$profile" == production ]]; then
    site_url="$(read_env_value WINDBLOG_SITE_PUBLIC_URL)"
    if [[ "$site_url" != https://* ]]; then
      printf '%s\n' 'FAIL WINDBLOG_SITE_PUBLIC_URL 生产环境必须使用 https:// 地址'
      failed=1
    fi
    if [[ -z "$(read_env_value CORS_ORIGINS)" || "$(read_env_value CORS_ORIGINS)" == *localhost* || "$(read_env_value CORS_ORIGINS)" == *your-domain.example* ]]; then
      printf '%s\n' 'FAIL CORS_ORIGINS 生产环境必须配置真实 HTTPS 来源'
      failed=1
    fi
    env_check_exact COOKIE_SECURE true || failed=1
    env_check_exact SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD true || failed=1
    env_check_exact SECURITY_HEADERS_HSTS_ENABLED true || failed=1
    env_check_exact SECURITY_HEADERS_CSP_ENFORCE true || failed=1
    env_check_exact SECURITY_HEADERS_CSP_TRUSTED_TYPES_ENABLED true || failed=1
    env_check_exact WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED true || failed=1
    env_check_exact WIND_BLOG_MEDIA_VIRUS_SCAN_REQUIRED true || failed=1
  fi

  env_check_exact GRPC_SERVER_PLAINTEXT false || failed=1
  env_check_exact GRPC_SERVER_CLIENT_AUTH required || failed=1
  env_check_exact GRPC_SERVER_CERTIFICATE /deployments/certs/ca/server.crt || failed=1
  env_check_exact GRPC_SERVER_KEY /deployments/certs/ca/server.key || failed=1
  env_check_exact GRPC_SERVER_TRUST_STORE /deployments/certs/ca/truststore.p12 || failed=1
  env_check_exact GRPC_CLIENT_CA_CERTIFICATE /deployments/certs/ca/ca.crt || failed=1
  env_check_exact GRPC_CLIENT_CERTIFICATE /deployments/certs/ca/client.crt || failed=1
  env_check_exact GRPC_CLIENT_KEY /deployments/certs/ca/client.key || failed=1
  env_check_exact GRPC_CLIENT_ALLOW_PLAINTEXT_FALLBACK false || failed=1
  for port_name in WINDBLOG_HOST_PORT WINDBLOG_GRPC_HOST_PORT POSTGRES_HOST_PORT REDIS_HOST_PORT \
    RABBITMQ_HOST_PORT RABBITMQ_MANAGEMENT_HOST_PORT ELASTICSEARCH_HOST_PORT KIBANA_HOST_PORT CLAMAV_HOST_PORT; do
    env_check_port "$port_name" || failed=1
  done

  bind_ip="$(read_env_value WINDBLOG_HOST_BIND_IP)"
  if [[ "$bind_ip" == 0.0.0.0 || "$bind_ip" == :: ]]; then
    printf '%s\n' 'WARN WINDBLOG_HOST_BIND_IP 绑定到所有网卡；请确认数据库、RabbitMQ、Elasticsearch、Kibana 端口有防火墙保护。'
  fi
  image="$(read_env_value WINDBLOG_IMAGE)"
  if [[ "$profile" == dev && "$image" =~ (ghcr\.io/.+:latest-native|ghcr\.io/.+:[^/[:space:]]+-native$) ]]; then
    printf '%s\n' 'FAIL QUARKUS_PROFILE=dev 不能搭配 GHCR 生产 native 镜像；请改用 QUARKUS_PROFILE=prod + COOKIE_SECURE=true，或自行构建 dev 镜像。'
    failed=1
  fi
  if [[ "$failed" -eq 0 ]]; then
    printf '%s\n' '.env 检查通过。'
  else
    printf '%s\n' '.env 检查未通过，请先修复 FAIL 项。' >&2
  fi
  return "$failed"
}

prepare_grpc_certs() {
  local ca_dir ca_key ca_cert server_key server_cert client_key client_cert trust_store trust_password
  local server_csr client_csr ca_serial
  prepare_mount_dirs
  install_ssl_dependencies

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
  trust_password="$(read_env_value GRPC_SERVER_TRUST_STORE_PASSWORD)"
  trust_password="${trust_password:-changeit}"

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

  chmod "$PRIVATE_FILE_MODE" "$ca_key" "$server_key" "$client_key" "$trust_store"
  chmod 0644 "$ca_cert" "$server_cert" "$client_cert"
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
  env_check
  prepare_grpc_certs
  service_build
  service_pull
  compose --profile security up -d --no-build windblog clamav
}

service_stop() {
  compose stop windblog
}

service_restart() {
  env_check
  prepare_grpc_certs
  service_build
  service_pull
  compose --profile security up -d --no-build --force-recreate windblog clamav
}

service_build() {
  printf '%s\n' '正在构建本地 Elasticsearch IK 镜像：windblog-elasticsearch:9.3.2-ik'
  compose build elasticsearch
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

service_prepare() {
  prepare_grpc_certs
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
  build       构建本地 Elasticsearch IK 镜像
  deps        自动安装 openssl 和 keytool 依赖
  env-generate 生成或补全 .env；已有强配置保留，修改前自动备份
  env-check   检查 .env 缺失项、弱密码、模式冲突和端口格式
  prepare     创建挂载目录、设置权限并准备 gRPC 证书
  certs       准备本地 gRPC mTLS 证书
  check-paths 目录和权限检查
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
    printf '%s\n' '7) 健康检查  8) 配置校验  9) 迁移旧命名卷  10) 进入容器  11) 准备 gRPC 证书  12) 目录和权限检查  13) 生成/补全 .env  14) 检查 .env  15) 构建 Elasticsearch IK 镜像  0) 退出'
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
      12) if ! check_paths; then read -r -p '按回车继续...' _; fi ;;
      13) if ! env_generate; then read -r -p '按回车继续...' _; fi ;;
      14) if ! env_check; then read -r -p '按回车继续...' _; fi ;;
      15) if ! service_build; then read -r -p '按回车继续...' _; fi ;;
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
  build) service_build ;;
  deps) install_ssl_dependencies ;;
  env-generate) env_generate ;;
  env-check) env_check ;;
  prepare) service_prepare ;;
  certs) prepare_grpc_certs ;;
  check-paths) check_paths ;;
  migrate) migrate_named_volumes ;;
  shell) service_shell ;;
  menu) run_menu ;;
  -h|--help|help) usage ;;
  *) usage; exit 2 ;;
esac
