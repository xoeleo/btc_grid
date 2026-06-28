#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
branch="$(git -C "$root" branch --show-current 2>/dev/null || true)"
commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || true)"

cat <<EOF
BTC Grid target entry sheet
===========================

Local project
  cwd:          $root
  git_branch:   ${branch:-none}
  git_commit:   ${commit:-none}
  runtime:      runtime/mainnet
  local_api:    http://127.0.0.1:8787
  start_script: scripts/start-grid.sh
  package:      scripts/package-grid-runtime.sh

Known runtime targets
  local_grid:
    api:        http://127.0.0.1:8787
    state:      runtime/mainnet/grid-state.json
    rebalance:  runtime/mainnet/rebalance-state.json

  host_108_grid:
    path:       /opt/btc_grid
    port:       8787
    service:    btc-grid.service

  host_108_rebalance:
    path:       /opt/btc_grid_rebalance
    port:       8789
    service:    btc-grid-rebalance.service

Safety checks
  1. Decide local vs 108 before touching any helper.
  2. Read runtime/mainnet files and event logs before explaining trigger behavior.
  3. Verify /health, /api/control, /api/rebalance-state, and process PID.
  4. Do not infer live behavior from source defaults alone.
EOF
