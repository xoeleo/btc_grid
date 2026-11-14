#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
task_root=research/water_exit_2y_20261002
if [[ "${1:-}" == --fetch ]]; then
  python3 "$task_root/fetch_data.py"
fi
mkdir -p "$task_root/smoke/data" "$task_root/current_fee_olhc" "$task_root/current_fee_ohlc" "$task_root/close_only" "$task_root/slippage_5bp_olhc"
cp -- "$task_root/data/funding.csv" "$task_root/smoke/data/funding.csv"
python3 "$task_root/generate_controller.py"
task_cp='grid-app/target/classes:grid-common/target/classes:grid-ledger/target/classes:grid-strategy-core/target/classes:grid-exchange-bybit/target/classes:grid-app/target/runtime-dependency/*'
mkdir -p "$task_root/classes"
javac -cp "$task_cp" -d "$task_root/classes" "$task_root/ReplayWaterExit.java" "$task_root/WaterGridBacktest.java" "$task_root/ReplayAcceptance.java"
task_cp="$task_root/classes:$task_cp"
java -cp "$task_cp" com.btcgrid.app.ReplayAcceptance > "$task_root/acceptance.log" 2>&1
java -Xmx1g -cp "$task_cp" com.btcgrid.app.WaterGridBacktest "$task_root" current_fee_olhc OLHC 0.00014 0.00035 1 > "$task_root/current_fee_olhc/run.log" 2>&1
java -Xmx1g -cp "$task_cp" com.btcgrid.app.WaterGridBacktest "$task_root" current_fee_ohlc OHLC 0.00014 0.00035 1 > "$task_root/current_fee_ohlc/run.log" 2>&1
java -Xmx1g -cp "$task_cp" com.btcgrid.app.WaterGridBacktest "$task_root" close_only CLOSE 0.00014 0.00035 1 > "$task_root/close_only/run.log" 2>&1
java -Xmx1g -cp "$task_cp" com.btcgrid.app.WaterGridBacktest "$task_root" slippage_5bp_olhc OLHC 0.00014 0.00035 5 > "$task_root/slippage_5bp_olhc/run.log" 2>&1
python3 "$task_root/build_report.py"
