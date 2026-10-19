#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
task_root=research/dip_grid_500k_20261011
task_cp="$task_root/frozen/grid-app:$task_root/frozen/grid-common:$task_root/frozen/grid-ledger:$task_root/frozen/grid-strategy-core:$task_root/frozen/grid-exchange-bybit:$task_root/frozen/dependencies/*"
mkdir -p "$task_root/classes"
javac -cp "$task_cp" -d "$task_root/classes" "$task_root/ConfiguredWaterExit.java" "$task_root/BaselineReplay.java" "$task_root/DipBands.java" "$task_root/DipGridBacktest.java" "$task_root/DipAcceptance.java"
java -cp "$task_root/classes:$task_cp" com.btcgrid.app.DipAcceptance > "$task_root/acceptance.log" 2>&1
