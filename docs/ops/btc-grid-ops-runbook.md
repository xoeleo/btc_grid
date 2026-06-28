# BTC Grid Ops Runbook

Use this for local and 108 BTC Grid status, rebalance diagnosis, and package
handoff.

## Target

```bash
scripts/ops/btc-grid-targets.sh
```

Important split:

- Local runtime: `runtime/mainnet`, `127.0.0.1:8787`
- 108 normal grid: `/opt/btc_grid`, port `8787`
- 108 rebalance runtime: `/opt/btc_grid_rebalance`, port `8789`

Do not assume a question is about 108 if the user says the previous strategy was
local.

## Status

```bash
scripts/ops/btc-grid-status.sh
scripts/ops/btc-grid-status.sh http://127.0.0.1:8789
BTC_GRID_BASE_URL=http://host:8787 scripts/ops/btc-grid-status.sh
```

The script reads:

- `/health`
- `/api/control`
- `/api/rebalance-config`
- `/api/rebalance-state`
- `/api/rebalance-current-cycle`
- `/api/rebalance-orders`
- `/api/rebalance-cycles`
- local `runtime/mainnet` file presence

## Rebalance Diagnosis

For trigger or threshold questions, use this order:

1. Read `runtime/mainnet/rebalance-config.properties`.
2. Read `runtime/mainnet/rebalance-state.json`.
3. Inspect `runtime/mainnet/grid-events.log` for `rebalance.grid.start`,
   `rebalance.cycle.restored`, and stop/start markers.
4. Query `/api/rebalance-config` and `/api/rebalance-state`.
5. Explain the active runner state, not only source defaults.

## Package Handoff

```bash
scripts/ops/btc-grid-package-check.sh
```

For package deployment, verify after remote start:

```bash
curl -sS http://127.0.0.1:8787/health
curl -sS http://127.0.0.1:8787/api/control
curl -sS http://127.0.0.1:8787/api/rebalance-state
```

For the separate 108 rebalance runtime, use port `8789` instead of `8787`.
