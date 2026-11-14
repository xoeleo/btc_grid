#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN="${ROOT_DIR}/.tools/apache-maven-3.9.9/bin/mvn"
RUNTIME_DIR="${ROOT_DIR}/runtime"
PID_FILE="${RUNTIME_DIR}/grid-app.pid"
OUT_LOG="${RUNTIME_DIR}/grid-app.out.log"
EVENT_LOG="${RUNTIME_DIR}/grid-events.log"
CLASSPATH_FILE="${ROOT_DIR}/grid-app/target/classpath.txt"
MAIN_CLASS="com.btcgrid.app.GridApplication"

usage() {
  cat <<USAGE
Usage: $0 [start|stop|restart|status]

Logs:
  stdout/stderr: ${OUT_LOG}
  app events:    ${EVENT_LOG}
  status file:   ${RUNTIME_DIR}/grid-status.json
USAGE
}

is_running() {
  [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" >/dev/null 2>&1
}

build_app() {
  cd "${ROOT_DIR}"
  bash "${MAVEN}" -q -DskipTests -pl grid-app -am package dependency:build-classpath \
    -Dmdep.outputFile=target/classpath.txt \
    -DincludeScope=runtime
}

start_app() {
  mkdir -p "${RUNTIME_DIR}"

  if is_running; then
    echo "Grid app is already running, pid=$(cat "${PID_FILE}")"
    echo "stdout/stderr log: ${OUT_LOG}"
    echo "event log: ${EVENT_LOG}"
    return 0
  fi

  build_app

  local feishu_env="${REBALANCE_FEISHU_ENV_FILE:-${HOME}/.config/btc-grid/rebalance-feishu.env}"
  if [[ -f "${feishu_env}" ]]; then
    set -a
    source "${feishu_env}"
    set +a
  fi

  local cp
  cp="${ROOT_DIR}/grid-app/target/classes:${ROOT_DIR}/grid-app/target/grid-app-0.1.0-SNAPSHOT.jar:$(cat "${CLASSPATH_FILE}")"

  cd "${ROOT_DIR}"
  printf '\n==== start %s ====\n' "$(date -Is)" >>"${OUT_LOG}"
  setsid java ${JAVA_OPTS:-} -cp "${cp}" "${MAIN_CLASS}" >>"${OUT_LOG}" 2>&1 &
  echo "$!" >"${PID_FILE}"

  sleep 3
  if is_running; then
    echo "Grid app started, pid=$(cat "${PID_FILE}")"
    echo "stdout/stderr log: ${OUT_LOG}"
    echo "event log: ${EVENT_LOG}"
    echo "status page: http://127.0.0.1:8787/"
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
  echo "event log: ${EVENT_LOG}"
  echo "status file: ${RUNTIME_DIR}/grid-status.json"
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
