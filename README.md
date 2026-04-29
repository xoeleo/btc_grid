# BTC Grid

Java multi-module implementation of a Bybit perpetual grid system centered on:

- 4H EMA baseline
- 4H Bollinger trigger conditions
- strategy-owned position ledger that does not depend on account position queries
- paired grid orders that requote from the latest fill
- gradual de-inventory when price is outside Bollinger and strategy PnL exceeds a threshold
- shared strategy core for live trading and backtesting
- config-driven bootstrap, runtime recovery, and local status inspection

## Modules

- `grid-common`: shared enums, models, and config
- `grid-ledger`: strategy-private ledger interfaces and in-memory implementation
- `grid-strategy-core`: state machine and order planning logic
- `grid-exchange-bybit`: Bybit SDK-based REST and private WebSocket adapters
- `grid-backtest`: replay runner and in-memory execution simulator
- `grid-app`: bootstrap entrypoint, runtime orchestration, persistence, and local HTTP status server

## Implemented Behavior

- Initial position is opened from the 4H EMA baseline deviation.
- Triggered grid mode places one buy and one sell order around the latest fill anchor.
- When one side fills, the opposite side is canceled and a new pair is generated from the fill price.
- When a pair times out, both sides are canceled, the spacing is shrunk, and a tighter pair is placed.
- De-inventory mode can issue reduce-only orders when the market is outside Bollinger and strategy PnL passes the configured threshold.
- Runtime state, processed execution ids, and tracked working orders are persisted for restart recovery.
- Startup reconciliation can reload strategy-owned state and re-check live working orders.
- A local HTTP status server exposes `/health`, `/status`, `/ledger`, `/runtime`, and `/orders`.
- Risk guards constrain max order size, max inventory, directional inventory, and same-side streaks.

## Runtime Files

- `runtime/grid-state.json`: persisted runtime state for recovery
- `runtime/grid-status.json`: latest runtime status snapshot
- `runtime/grid-events.log`: structured event log

## Local Run

The repository includes a local Maven install under `.tools/apache-maven-3.9.9`.

Build and test:

```powershell
& '.tools\apache-maven-3.9.9\bin\mvn.cmd' '-q' 'test'
```

Start the app from the assembled modules:

```powershell
& '.tools\apache-maven-3.9.9\bin\mvn.cmd' '-q' '-pl' 'grid-app' '-am' 'package'
```

The main class is `com.btcgrid.app.GridApplication`.

By default:

- symbol is `BTCUSDT`
- local HTTP status server is enabled on port `8787`
- live auto-start is disabled

Open `http://127.0.0.1:8787/` for the local control console. The console can
configure the strategy parameters including `symbol`, start a one-cycle run,
stop immediately, or submit a reduce-only market flatten order and then stop.
Runs started from this console force classic reduce sizing and stop
automatically after the active cycle returns to flat.

Runtime configuration lives in `grid-app/src/main/resources/application.properties`.

## Backtest CLI

The backtest module now includes a simple CSV-driven CLI:

```powershell
java -cp 'grid-backtest\target\classes;grid-common\target\classes;grid-ledger\target\classes;grid-strategy-core\target\classes' `
  com.btcgrid.backtest.engine.BacktestCli `
  --csv runtime\sample-backtest.csv `
  --ema-length-4h 55 `
  --boll-length-4h 20 `
  --maker-fee-rate 0.0002 `
  --taker-fee-rate 0.00055 `
  --market-slippage-bps 5 `
  --limit-fill-ratio-per-tick 0.5 `
  --max-volume-participation-rate 0.2 `
  --min-touch-fill-ratio 0.3 `
  --output-dir runtime\backtest-report
```

You can also rerun directly from a saved parameter snapshot:

```powershell
java -cp 'grid-backtest\target\classes;grid-common\target\classes;grid-ledger\target\classes;grid-strategy-core\target\classes' `
  com.btcgrid.backtest.engine.BacktestCli `
  --config-file runtime\sweep-report-best\best-case.properties `
  --output-dir runtime\best-case-rerun
```

When `--config-file` is used, command-line flags override properties loaded from the file.

CSV format:

- `timestamp,open,high,low,close[,volume]`
- `timestamp` supports ISO-8601, epoch seconds, or epoch milliseconds
- one row is treated as one 4H candle close

You can override both strategy parameters and instrument precision via CLI flags, for example:

- `--base-grid-spacing`
- `--base-grid-qty`
- `--max-initial-steps`
- `--max-order-qty`
- `--qty-step`
- `--price-tick`
- `--maker-fee-rate`
- `--taker-fee-rate`
- `--market-slippage-bps`
- `--limit-fill-ratio-per-tick`
- `--market-fill-ratio-per-tick`
- `--min-fill-qty`
- `--max-volume-participation-rate`
- `--min-touch-fill-ratio`

The CLI prints a summary including:

- snapshot count
- placed and canceled order count
- fill count
- final runtime state
- final net quantity
- realized and floating PnL
- total accumulated fees
- gross PnL, net PnL, and max drawdown
- gross realized profit and gross realized loss
- win rate, profit factor, and peak inventory quantity
- fee-to-gross-PnL ratio
- fill count will increase when partial-fill settings split one order across multiple bars

When `--output-dir` is provided, the CLI also writes:

- `summary.txt`: the same high-level summary shown in the terminal
- `events.csv`: every place, cancel, and fill event with strategy state, ledger context, fees, and net PnL
- `equity.csv`: per-snapshot equity curve with price, position, gross PnL, net PnL, fees, and open order count

Execution-model notes:

- limit orders can be split across multiple bars with `--limit-fill-ratio-per-tick`
- market orders can also be split with `--market-fill-ratio-per-tick`
- each simulated fill respects `--min-fill-qty` as the smallest slice
- limit-order fills are checked against candle `high/low`, not only candle close
- shallow touches can fill less than deep penetrations; `--min-touch-fill-ratio` sets the minimum fill coefficient once price is touched
- each bar can only consume a fraction of candle volume via `--max-volume-participation-rate`

## Sweep CLI

The backtest module also includes a parameter-sweep CLI for batch runs:

```powershell
java -cp 'grid-backtest\target\classes;grid-common\target\classes;grid-ledger\target\classes;grid-strategy-core\target\classes' `
  com.btcgrid.backtest.engine.BacktestSweepCli `
  --csv runtime\sample-backtest.csv `
  --sweep-file runtime\sample-sweep.properties `
  --output-dir runtime\sweep-report `
  --ema-length-4h 55 `
  --boll-length-4h 20 `
  --score-mode NET_PNL_MINUS_HALF_DRAWDOWN `
  --max-drawdown 500 `
  --top-n 10
```

Sweep file format:

```properties
base-grid-spacing=80,100,120
base-grid-qty=0.01,0.02
```

Each key is treated as one sweep dimension and expanded as a Cartesian product. All normal `BacktestCli` options can still be passed as shared defaults.

Sweep output:

- one subdirectory per case such as `case-001`, `case-002`
- each case directory contains the normal `summary.txt`, `events.csv`, `equity.csv`
- parent output directory contains `leaderboard.csv` plus a sweep-level `summary.txt`
- parent output directory also contains `best-case.properties`
- parent `top-cases` directory stores property snapshots for the top-ranked cases

Sweep ranking and filters:

- `--score-mode` supports `NET_PNL`, `NET_PNL_MINUS_DRAWDOWN`, `NET_PNL_MINUS_HALF_DRAWDOWN`, `PROFIT_FACTOR`, `WIN_RATE`
- `--min-net-pnl` filters weak cases before ranking
- `--max-drawdown` filters cases by drawdown ceiling
- `--min-win-rate` filters by realized win rate
- `--max-peak-inventory-qty` filters by inventory peak

## Local Compile

If Maven is not installed yet, the current source tree still compiles with plain `javac`:

```powershell
$sources = Get-ChildItem -Recurse -Filter *.java | ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Force -Path .build\classes | Out-Null
javac -d .build\classes $sources
```

## Next implementation steps

1. Add a dedicated backtest CLI that can load historical kline/csv data and export result metrics.
2. Extend the backtest simulator with fees, slippage, and partial fills.
3. Add end-to-end tests for state transitions, opposite-side cancellation, and re-quote behavior.
4. Add richer alert integrations beyond the current event-log based alert service.
5. Support more strategy-level analytics such as inventory aging and per-cycle attribution.
