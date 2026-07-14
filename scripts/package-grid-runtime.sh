#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN="${ROOT_DIR}/.tools/apache-maven-3.9.9/bin/mvn"
BUILD_DIR="${ROOT_DIR}/target/grid-runtime-package"
DIST_DIR="${BUILD_DIR}/btc_grid"
OUT_DIR="${ROOT_DIR}/target/dist"
INCLUDE_MAINNET_RUNTIME=0
FORCE_RUNTIME=0

usage() {
  cat <<USAGE
Usage: $0 [--include-mainnet-runtime] [--force-runtime]

Builds a portable btc_grid runtime package for a server.

Options:
  --include-mainnet-runtime  Include runtime/mainnet state files for migration.
  --force-runtime            Include runtime/mainnet even if the local app is running.

Output:
  target/dist/btc_grid-runtime-<timestamp>.tar.gz
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --include-mainnet-runtime)
      INCLUDE_MAINNET_RUNTIME=1
      ;;
    --force-runtime)
      FORCE_RUNTIME=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ ! -x "${MAVEN}" ]]; then
  echo "Missing Maven wrapper at ${MAVEN}" >&2
  exit 1
fi

is_local_app_running() {
  local pid_file="${ROOT_DIR}/runtime/grid-app.pid"
  if [[ -f "${pid_file}" ]] && kill -0 "$(cat "${pid_file}")" >/dev/null 2>&1; then
    return 0
  fi
  curl -fsS --noproxy '*' http://127.0.0.1:8787/health >/dev/null 2>&1
}

if [[ "${INCLUDE_MAINNET_RUNTIME}" -eq 1 && "${FORCE_RUNTIME}" -ne 1 ]] && is_local_app_running; then
  cat >&2 <<MSG
Local grid app is running.
Stop it before packaging runtime/mainnet to avoid copying a moving state:
  scripts/start-grid.sh stop

Use --force-runtime only for a non-cutover backup snapshot.
MSG
  exit 1
fi

cd "${ROOT_DIR}"
bash "${MAVEN}" -q -DskipTests -pl grid-app -am package dependency:copy-dependencies \
  -DincludeScope=runtime \
  -DoutputDirectory="${ROOT_DIR}/grid-app/target/runtime-dependency"

rm -rf "${BUILD_DIR}"
mkdir -p "${DIST_DIR}/app" "${DIST_DIR}/lib" "${DIST_DIR}/scripts" "${DIST_DIR}/runtime"

cp "${ROOT_DIR}/grid-app/target/grid-app-0.1.0-SNAPSHOT.jar" "${DIST_DIR}/app/"
cp "${ROOT_DIR}"/grid-{common,ledger,strategy-core,exchange-bybit,backtest}/target/*.jar "${DIST_DIR}/lib/"
cp "${ROOT_DIR}"/grid-app/target/runtime-dependency/*.jar "${DIST_DIR}/lib/"
cp "${ROOT_DIR}/scripts/start-grid-runtime.sh" "${DIST_DIR}/scripts/"
chmod +x "${DIST_DIR}/scripts/start-grid-runtime.sh"

if [[ "${INCLUDE_MAINNET_RUNTIME}" -eq 1 ]]; then
  mkdir -p "${DIST_DIR}/runtime/mainnet"
  cp -a "${ROOT_DIR}/runtime/mainnet/." "${DIST_DIR}/runtime/mainnet/"
fi

cat >"${DIST_DIR}/README-runtime.md" <<'README'
# btc_grid Runtime Package

Start:

```bash
scripts/start-grid-runtime.sh start
```

Status:

```bash
scripts/start-grid-runtime.sh status
curl -sS http://127.0.0.1:8787/health
curl -sS http://127.0.0.1:8787/api/control
```

Resume the migrated cycle only after checking `/api/control`:

```bash
curl -X POST http://127.0.0.1:8787/api/resume
```

Do not run a local and remote btc_grid instance against the same Bybit account at
the same time.
README

mkdir -p "${OUT_DIR}"
artifact="${OUT_DIR}/btc_grid-runtime-$(date +%Y%m%d-%H%M%S).tar.gz"
tar -C "${BUILD_DIR}" -czf "${artifact}" btc_grid

echo "${artifact}"
