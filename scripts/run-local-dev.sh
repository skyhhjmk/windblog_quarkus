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

postgres_host="${POSTGRES_HOST:-127.0.0.1}"
postgres_port="${POSTGRES_PORT:-${POSTGRES_HOST_PORT:-5432}}"
postgres_db="${POSTGRES_DB:-windblog}"
postgres_user="${POSTGRES_USER:-windblog}"

# Quarkus accepts DB_* as the canonical application names. Keep compatibility
# with Compose's POSTGRES_* names without putting secrets in command arguments.
export DB_JDBC_URL="${DB_JDBC_URL:-jdbc:postgresql://${postgres_host}:${postgres_port}/${postgres_db}}"
export DB_USERNAME="${DB_USERNAME:-$postgres_user}"
export DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD:-}}"

redis_host="${REDIS_HOST:-127.0.0.1}"
redis_port="${REDIS_PORT:-${REDIS_HOST_PORT:-6379}}"
if [[ -z "${REDIS_URL:-}" ]]; then
  if [[ -n "${REDIS_PASSWORD:-}" ]]; then
    export REDIS_URL="redis://:${REDIS_PASSWORD}@${redis_host}:${redis_port}"
  else
    export REDIS_URL="redis://${redis_host}:${redis_port}"
  fi
fi

if [[ -z "$DB_PASSWORD" ]]; then
  printf 'Missing PostgreSQL password. Set POSTGRES_PASSWORD or DB_PASSWORD in %s.\n' "$ENV_FILE" >&2
  exit 64
fi

cd "$ROOT_DIR"
if (( $# == 0 )); then
  set -- bash mvnw quarkus:dev
fi
exec "$@"
