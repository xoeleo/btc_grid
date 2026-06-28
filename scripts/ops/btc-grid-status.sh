#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-${BTC_GRID_BASE_URL:-http://127.0.0.1:8787}}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

fetch() {
  local name="$1"
  local path="$2"
  if curl -fsS --noproxy '*' --max-time 8 "$base_url$path" > "$tmpdir/$name.json" 2>"$tmpdir/$name.err"; then
    return 0
  fi
  return 1
}

health_ok=0
fetch health /health || health_ok=1
fetch control /api/control || true
fetch rebalance_config /api/rebalance-config || true
fetch rebalance_state /api/rebalance-state || true
fetch rebalance_current /api/rebalance-current-cycle || true
fetch rebalance_orders /api/rebalance-orders || true
fetch rebalance_cycles '/api/rebalance-cycles?limit=10&offset=0' || true

python3 - "$base_url" "$tmpdir" "$root" "$health_ok" <<'PY'
import json
import pathlib
import sys

base_url, tmpdir, root, health_failed = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]), sys.argv[4] == "1"

def load(name):
    path = tmpdir / f"{name}.json"
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"_error": str(exc)}

def val(data, *keys):
    cur = data or {}
    for key in keys:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur

health = load("health") or {}
control = load("control") or {}
rebalance_config = load("rebalance_config") or {}
rebalance_state = load("rebalance_state") or {}
rebalance_current = load("rebalance_current") or {}
rebalance_orders = load("rebalance_orders") or {}
rebalance_cycles = load("rebalance_cycles") or {}

print("BTC Grid status")
print("===============")
print(f"baseUrl: {base_url}")
if health_failed:
    err = (tmpdir / "health.err").read_text(errors="ignore").strip()
    print(f"api:     unavailable ({err or 'curl failed'})")
else:
    print(f"health:  {health}")

print()
print("Control")
if control and not control.get("_error"):
    print(f"  running:          {control.get('running', control.get('liveRunning', '-'))}")
    print(f"  rebalanceRunning: {control.get('rebalanceRunning', '-')}")
    print(f"  mode:             {control.get('mode', '-')}")
else:
    print("  unavailable")

print()
print("Rebalance")
config = rebalance_config.get("config") if isinstance(rebalance_config, dict) else None
state = rebalance_state.get("state") if isinstance(rebalance_state, dict) else None
print(f"  enabled:      {val(config, 'enabled') if config else '-'}")
print(f"  symbol:       {val(config, 'symbol') if config else '-'}")
print(f"  priceTargets: {val(config, 'priceTargets') if config else '-'}")
print(f"  status:       {val(state, 'status') if state else val(rebalance_state, 'status') or '-'}")
print(f"  message:      {val(state, 'message') if state else val(rebalance_state, 'message') or '-'}")
print(f"  openOrders:   {len(rebalance_orders) if isinstance(rebalance_orders, list) else len(rebalance_orders.get('items') or []) if isinstance(rebalance_orders, dict) else '-'}")
print(f"  currentCycle: {rebalance_current if rebalance_current and not rebalance_current.get('_error') else '-'}")
print(f"  recentCycles: {len(rebalance_cycles.get('items') or []) if isinstance(rebalance_cycles, dict) else '-'}")

print()
print("Runtime files")
for rel in ("runtime/mainnet/rebalance-config.properties", "runtime/mainnet/rebalance-state.json", "runtime/mainnet/grid-events.log"):
    path = root / rel
    print(f"  {rel}: {'exists' if path.exists() else 'missing'}")
PY
