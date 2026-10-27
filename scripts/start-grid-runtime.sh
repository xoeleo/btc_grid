#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/runtime"
PID_FILE="${RUNTIME_DIR}/grid-app.pid"
OUT_LOG="${RUNTIME_DIR}/grid-app.out.log"
MAIN_CLASS="com.btcgrid.app.GridApplication"

usage() {
  cat <<USAGE
Usage: $0 [start|stop|restart|status]

This script starts a packaged btc_grid runtime. It does not run Maven.

Logs:
  stdout/stderr: ${OUT_LOG}
  mainnet events: ${RUNTIME_DIR}/mainnet/grid-events.log
  mainnet status: ${RUNTIME_DIR}/mainnet/grid-status.json
USAGE
}

app_jar() {
  local jar
  jar="$(find "${ROOT_DIR}/app" -maxdepth 1 -type f -name 'grid-app-*.jar' | sort | tail -n 1)"
  if [[ -z "${jar}" ]]; then
    echo "Missing app jar under ${ROOT_DIR}/app" >&2
    return 1
  fi
  printf '%s\n' "${jar}"
}

is_running() {
  local pid
  if [[ -f "${PID_FILE}" ]]; then
    pid="$(cat "${PID_FILE}")"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      return 0
    fi
  fi
  return 1
}

find_running_pid() {
  pgrep -f "java .*${ROOT_DIR}/app/.*${MAIN_CLASS}" | head -n 1 || true
}

adopt_running_process() {
  local pid
  pid="$(find_running_pid)"
  if [[ -z "${pid}" ]]; then
    return 1
  fi
  echo "${pid}" >"${PID_FILE}"
  return 0
}

start_app() {
  mkdir -p "${RUNTIME_DIR}"

  if is_running || adopt_running_process; then
    echo "Grid app is already running, pid=$(cat "${PID_FILE}")"
    echo "stdout/stderr log: ${OUT_LOG}"
    return 0
  fi

  local jar cp
  local feishu_env="${REBALANCE_FEISHU_ENV_FILE:-${HOME}/.config/btc-grid/rebalance-feishu.env}"
  if [[ -f "${feishu_env}" ]]; then
    set -a
    source "${feishu_env}"
    set +a
  fi
  jar="$(app_jar)"
  cp="${jar}:${ROOT_DIR}/lib/*"
  local java_args="${JAVA_OPTS:-}"
  if [[ -n "${RUNTIME_HTTP_PORT:-}" ]]; then
    java_args="${java_args} -Druntime.http-port=${RUNTIME_HTTP_PORT}"
  fi

  cd "${ROOT_DIR}"
  printf '\n==== start %s ====\n' "$(date -Is)" >>"${OUT_LOG}"
  nohup setsid java ${java_args} -cp "${cp}" "${MAIN_CLASS}" >>"${OUT_LOG}" 2>&1 &
  echo "$!" >"${PID_FILE}"

  sleep 3
  if is_running; then
    echo "Grid app started, pid=$(cat "${PID_FILE}")"
    echo "stdout/stderr log: ${OUT_LOG}"
    echo "status page: http://127.0.0.1:${RUNTIME_HTTP_PORT:-8787}/"
  else
    echo "Grid app failed to stay running. Check ${OUT_LOG}" >&2
    rm -f "${PID_FILE}"
    return 1
  fi
}

stop_app() {
  if ! is_running; then
    echo "Grid app is not running"
    rm -f "${PID_FILE}"
    return 0
  fi

  local pid
  pid="$(cat "${PID_FILE}")"
  kill "${pid}"

  for _ in {1..20}; do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      rm -f "${PID_FILE}"
      echo "Grid app stopped"
      return 0
    fi
    sleep 1
  done

  echo "Grid app did not stop within 20 seconds, pid=${pid}" >&2
  return 1
}

status_app() {
  if is_running; then
    echo "Grid app is running, pid=$(cat "${PID_FILE}")"
  else
    echo "Grid app is not running"
  fi
  echo "stdout/stderr log: ${OUT_LOG}"
  echo "mainnet event log: ${RUNTIME_DIR}/mainnet/grid-events.log"
  echo "mainnet status file: ${RUNTIME_DIR}/mainnet/grid-status.json"
}

case "${1:-start}" in
  start)
    start_app
    ;;
  stop)
    stop_app
    ;;
  restart)
    stop_app
    start_app
    ;;
  status)
    status_app
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
