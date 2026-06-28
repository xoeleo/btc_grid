#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$root"

echo "Package preflight"
echo "================="
scripts/ops/btc-grid-targets.sh
echo
echo "Local status"
scripts/ops/btc-grid-status.sh || true
echo
echo "Building runtime package without mainnet state..."
scripts/package-grid-runtime.sh
