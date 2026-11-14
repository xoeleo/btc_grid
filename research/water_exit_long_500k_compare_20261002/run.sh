#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
task_root=research/water_exit_long_500k_compare_20261002
task_cp='grid-app/target/classes:grid-common/target/classes:grid-ledger/target/classes:grid-strategy-core/target/classes:grid-exchange-bybit/target/classes:grid-app/target/runtime-dependency/*'
mkdir -p "$task_root/classes"
javac -cp "$task_cp" -d "$task_root/classes" "$task_root/ConfiguredWaterExit.java" "$task_root/LongCapitalBacktest.java" "$task_root/ComparisonAcceptance.java"
task_cp="$task_root/classes:$task_cp"
java -cp "$task_cp" com.btcgrid.app.ComparisonAcceptance > "$task_root/acceptance.log" 2>&1
while read -r task_name task_path task_half task_full; do
  mkdir -p "$task_root/$task_name"
  java -Xmx1g -cp "$task_cp" com.btcgrid.app.LongCapitalBacktest "$task_root" "$task_name" "$task_path" 0.00014 0.00035 1 "$task_half" "$task_full" > "$task_root/$task_name/run.log" 2>&1
done <<'SCENARIOS'
exit12_olhc OLHC 0.01 0.02
exit12_ohlc OHLC 0.01 0.02
exit25_olhc OLHC 0.02 0.05
exit25_ohlc OHLC 0.02 0.05
SCENARIOS
python3 "$task_root/build_report.py"
