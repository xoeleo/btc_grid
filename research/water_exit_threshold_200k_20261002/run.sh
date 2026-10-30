#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
task_root=research/water_exit_threshold_200k_20261002
task_cp='grid-app/target/classes:grid-common/target/classes:grid-ledger/target/classes:grid-strategy-core/target/classes:grid-exchange-bybit/target/classes:grid-app/target/runtime-dependency/*'
mkdir -p "$task_root/classes"
javac -cp "$task_cp" -d "$task_root/classes" "$task_root/ReplayWaterExit.java" "$task_root/ThresholdWaterBacktest.java" "$task_root/ThresholdAcceptance.java"
task_cp="$task_root/classes:$task_cp"
java -cp "$task_cp" com.btcgrid.app.ThresholdAcceptance > "$task_root/acceptance.log" 2>&1
while read -r task_name task_path task_slip task_mode; do
  mkdir -p "$task_root/$task_name"
  java -Xmx1g -cp "$task_cp" com.btcgrid.app.ThresholdWaterBacktest "$task_root" "$task_name" "$task_path" 0.00014 0.00035 "$task_slip" "$task_mode" > "$task_root/$task_name/run.log" 2>&1
done <<'SCENARIOS'
threshold_olhc OLHC 1 THRESHOLD
threshold_ohlc OHLC 1 THRESHOLD
long_200k_olhc OLHC 1 LONG
long_200k_ohlc OHLC 1 LONG
threshold_close CLOSE 1 THRESHOLD
threshold_slip5 OLHC 5 THRESHOLD
SCENARIOS
python3 "$task_root/build_report.py"
