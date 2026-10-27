#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
task_root=research/dip_grid_500k_20261011
bash "$task_root/compile.sh"
task_cp="$task_root/classes:$task_root/frozen/grid-app:$task_root/frozen/grid-common:$task_root/frozen/grid-ledger:$task_root/frozen/grid-strategy-core:$task_root/frozen/grid-exchange-bybit:$task_root/frozen/dependencies/*"
task_interval="${1:-0.05}"
mkdir -p "$task_root/baseline_verify"
java -Xmx512m -cp "$task_cp" com.btcgrid.app.BaselineReplay "$task_root" baseline_verify OLHC 0.00014 0.00035 1 0.01 0.02 > "$task_root/baseline_verify/run.log" 2>&1
for task_path in OLHC OHLC; do
    task_name="dip5_${task_path,,}"
    mkdir -p "$task_root/$task_name"
    java -Xmx512m -cp "$task_cp" com.btcgrid.app.DipGridBacktest "$task_root" "$task_name" "$task_path" "$task_interval" > "$task_root/$task_name/run.log" 2>&1
done
python3 "$task_root/build_report.py"
