#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${WINDBLOG_DATA_DIR:-${ROOT_DIR}/.codex-test-data}"
AUTH_SOURCE_DIR="${CODEX_SOURCE_DIR:-${HOME}/.codex}"
AUTH_DIR="${DATA_DIR}/codex-auth"

cd "${ROOT_DIR}"

prepare_auth_cache() {
  if [[ ! -r "${AUTH_SOURCE_DIR}/auth.json" ]]; then
    echo "Missing readable Codex auth cache: ${AUTH_SOURCE_DIR}/auth.json" >&2
    echo "Run 'codex login' on the host, then retry." >&2
    exit 1
  fi

  mkdir -p "${AUTH_DIR}"
  if ! podman unshare test -s "${AUTH_DIR}/auth.json"; then
    cp --preserve=mode,timestamps "${AUTH_SOURCE_DIR}/auth.json" "${AUTH_DIR}/auth.json"
  fi
  podman unshare chown -R "${CODEX_CONTAINER_UID}:${CODEX_CONTAINER_UID}" "${AUTH_DIR}"
  podman unshare chmod 700 "${AUTH_DIR}"
  podman unshare chmod 600 "${AUTH_DIR}/auth.json"
}

export CODEX_CREATOR_HOST_CODEX_DIR="${CODEX_CREATOR_HOST_CODEX_DIR:-${AUTH_DIR}}"
export WINDBLOG_IMAGE="${WINDBLOG_IMAGE:-ghcr.io/skyhhjmk/windblog_quarkus:1.0-SNAPSHOT}"
export WINDBLOG_PULL_POLICY="${WINDBLOG_PULL_POLICY:-never}"
export CODEX_CREATOR_IMAGE="${CODEX_CREATOR_IMAGE:-localhost/codex-creator:native-codex-0.150.1-final}"
export CODEX_CREATOR_PULL_POLICY="${CODEX_CREATOR_PULL_POLICY:-never}"
CODEX_CONTAINER_UID="${CODEX_CONTAINER_UID:-$(podman image inspect "${CODEX_CREATOR_IMAGE}" --format '{{.Config.User}}' | cut -d: -f1)}"
CODEX_CONTAINER_UID="${CODEX_CONTAINER_UID:-1001}"
export WINDBLOG_DATA_DIR="${DATA_DIR}"
export WINDBLOG_SITE_PUBLIC_URL="${WINDBLOG_SITE_PUBLIC_URL:-http://127.0.0.1:58080}"
export CORS_ORIGINS="${CORS_ORIGINS:-http://127.0.0.1:58080}"
export WINDBLOG_HOST_BIND_IP="${WINDBLOG_HOST_BIND_IP:-127.0.0.1}"
export WINDBLOG_HOST_PORT="${WINDBLOG_HOST_PORT:-58080}"
export WINDBLOG_GRPC_HOST_PORT="${WINDBLOG_GRPC_HOST_PORT:-59000}"
export POSTGRES_HOST_PORT="${POSTGRES_HOST_PORT:-55432}"
export REDIS_HOST_PORT="${REDIS_HOST_PORT:-56379}"
export RABBITMQ_HOST_PORT="${RABBITMQ_HOST_PORT:-55672}"
export RABBITMQ_MANAGEMENT_HOST_PORT="${RABBITMQ_MANAGEMENT_HOST_PORT:-55673}"
export ELASTICSEARCH_HOST_PORT="${ELASTICSEARCH_HOST_PORT:-59200}"
export KIBANA_HOST_PORT="${KIBANA_HOST_PORT:-55601}"
export CLAMAV_HOST_PORT="${CLAMAV_HOST_PORT:-55310}"
export CODEX_CREATOR_HOST_PORT="${CODEX_CREATOR_HOST_PORT:-58091}"
export CODEX_APP_SERVER_ENABLED="${CODEX_APP_SERVER_ENABLED:-true}"
export CODEX_COMMAND="${CODEX_COMMAND:-codex}"
export WINDBLOG_CODEX_CREATOR_EVENTS_ENABLED="${WINDBLOG_CODEX_CREATOR_EVENTS_ENABLED:-true}"
export WINDBLOG_CODEX_CREATOR_PREFER="${WINDBLOG_CODEX_CREATOR_PREFER:-true}"
export KIBANA_SERVICE_ACCOUNT_TOKEN="${KIBANA_SERVICE_ACCOUNT_TOKEN:-local-test-bootstrap-token}"

if [[ -z "${WINDBLOG_CODEX_CREATOR_SHARED_SECRET:-}" ]]; then
  secret_file="${DATA_DIR}/codex-integration-secret"
  if [[ ! -s "${secret_file}" ]]; then
    mkdir -p "${DATA_DIR}"
    umask 077
    openssl rand -hex 32 >"${secret_file}"
  fi
  export WINDBLOG_CODEX_CREATOR_SHARED_SECRET="$(<"${secret_file}")"
fi

compose_args=(
  compose -p windblog-codex-test --profile security
  -f docker-compose.yml
  -f docker-compose.local-test.yml
)

wait_for_health() {
  local container_name="$1"
  local attempts="${2:-45}"
  for _ in $(seq 1 "${attempts}"); do
    if [[ "$(podman inspect "${container_name}" --format '{{.State.Health.Status}}' 2>/dev/null || true)" == healthy ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for ${container_name} to become healthy." >&2
  podman logs --tail 80 "${container_name}" >&2 || true
  exit 1
}

prepare_kibana_token() {
  local token_file="${DATA_DIR}/kibana-service-token"
  mkdir -p "${DATA_DIR}"
  umask 077
  podman exec windblog-codex-test-elasticsearch sh -ec '
    curl -fsS -u "elastic:${ELASTIC_PASSWORD}" -X DELETE \
      http://localhost:9200/_security/service/elastic/kibana/credential/token/local-test >/dev/null 2>/dev/null || true
    curl -fsS -u "elastic:${ELASTIC_PASSWORD}" -X POST \
      http://localhost:9200/_security/service/elastic/kibana/credential/token/local-test
  ' | jq -er '.token.value' >"${token_file}"
  export KIBANA_SERVICE_ACCOUNT_TOKEN="$(<"${token_file}")"
}

up_core_services() {
  env -u ALL_PROXY -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u http_proxy -u https_proxy \
    podman "${compose_args[@]}" up -d db redis rabbitmq elasticsearch clamav
  wait_for_health windblog-codex-test-elasticsearch
  prepare_kibana_token
}

case "${1:-up}" in
  up)
    prepare_auth_cache
    up_core_services
    env -u ALL_PROXY -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u http_proxy -u https_proxy \
      podman "${compose_args[@]}" up -d
    env -u ALL_PROXY -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u http_proxy -u https_proxy \
      podman "${compose_args[@]}" up -d --force-recreate kibana
    ;;
  down)
    env -u ALL_PROXY -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u http_proxy -u https_proxy \
      podman "${compose_args[@]}" down
    ;;
  status)
    podman "${compose_args[@]}" ps
    ;;
  logs)
    podman "${compose_args[@]}" logs --tail="${2:-120}" "${3:-codex-creator}"
    ;;
  config)
    prepare_auth_cache
    podman "${compose_args[@]}" config --quiet
    ;;
  *)
    echo "Usage: $0 {up|down|status|logs [lines] [service]|config}" >&2
    exit 2
    ;;
esac
