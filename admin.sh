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
  service_pull
  compose up -d --no-build windblog
}

service_stop() {
  compose stop windblog
}

service_restart() {
  prepare_data_dirs
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
    printf '%s\n' '7) 健康检查  8) 配置校验  9) 迁移旧命名卷  10) 进入容器  0) 退出'
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
  migrate) migrate_named_volumes ;;
  shell) service_shell ;;
  menu) run_menu ;;
  -h|--help|help) usage ;;
  *) usage; exit 2 ;;
esac
