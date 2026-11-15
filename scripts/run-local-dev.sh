#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${WINDBLOG_ENV_FILE:-$ROOT_DIR/.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

port_open() {
  local host="$1"
  local port="$2"
  timeout 1 bash -c "</dev/tcp/${host}/${port}" >/dev/null 2>&1
}

postgres_host="${POSTGRES_HOST:-127.0.0.1}"
postgres_port="${POSTGRES_PORT:-${POSTGRES_HOST_PORT:-5432}}"
postgres_db="${POSTGRES_DB:-windblog}"
postgres_user="${POSTGRES_USER:-windblog}"

redis_host="${REDIS_HOST:-127.0.0.1}"
redis_port="${REDIS_PORT:-${REDIS_HOST_PORT:-6379}}"

if ! port_open "$postgres_host" "$postgres_port" && port_open "127.0.0.1" "55432"; then
  echo "[run-local-dev] ${postgres_host}:${postgres_port} 不可达，检测到本地测试栈端口 55432 可用，自动切换到测试栈依赖端口 (PG:55432, Redis:56379, RabbitMQ:55672, ES:59200)。"
  postgres_host="127.0.0.1"
  postgres_port="55432"
  redis_host="127.0.0.1"
  redis_port="56379"
  export RABBITMQ_HOST="127.0.0.1"
  export RABBITMQ_PORT="55672"
  export ELASTICSEARCH_HOSTS="http://127.0.0.1:59200"
fi

# Quarkus accepts DB_* as the canonical application names. Keep compatibility
# with Compose's POSTGRES_* names without putting secrets in command arguments.
export DB_JDBC_URL="${DB_JDBC_URL:-jdbc:postgresql://${postgres_host}:${postgres_port}/${postgres_db}}"
export DB_USERNAME="${DB_USERNAME:-$postgres_user}"
export DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD:-}}"

if [[ -z "${REDIS_URL:-}" ]]; then
  if [[ -n "${REDIS_PASSWORD:-}" ]]; then
    export REDIS_URL="redis://:${REDIS_PASSWORD}@${redis_host}:${redis_port}"
  else
    export REDIS_URL="redis://${redis_host}:${redis_port}"
  fi
fi

http_port="${QUARKUS_HTTP_PORT:-8080}"
if port_open "127.0.0.1" "$http_port"; then
  for candidate in 18080 18081 18082 18088; do
    if ! port_open "127.0.0.1" "$candidate"; then
      echo "[run-local-dev] 端口 ${http_port} 已被占用，自动切换 QUARKUS_HTTP_PORT=${candidate}。"
      http_port="$candidate"
      break
    fi
  done
fi
export QUARKUS_HTTP_PORT="$http_port"

for grpc_var in \
  GRPC_SERVER_CERTIFICATE \
  GRPC_SERVER_KEY \
  GRPC_SERVER_TRUST_STORE \
  GRPC_CLIENT_CA_CERTIFICATE \
  GRPC_CLIENT_CERTIFICATE \
  GRPC_CLIENT_KEY; do
  val="${!grpc_var:-}"
  if [[ "$val" == /deployments/certs/* && ! -e "$val" ]]; then
    local_cert="$ROOT_DIR/certs/${val#/deployments/certs/}"
    if [[ -e "$local_cert" ]]; then
      export "$grpc_var=$local_cert"
    fi
  fi
done

if [[ -z "$DB_PASSWORD" ]]; then
  printf 'Missing PostgreSQL password. Set POSTGRES_PASSWORD or DB_PASSWORD in %s.\n' "$ENV_FILE" >&2
  exit 64
fi

cd "$ROOT_DIR"
if (( $# == 0 )); then
  set -- bash mvnw quarkus:dev
fi
exec "$@"
