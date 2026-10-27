# Current-cycle profitable limit exit

Deployed only to Rebalance 8789 on 2026-09-21. Normal Grid 8787 was not restarted.

The durable instruction is `/opt/btc_grid_rebalance/runtime/mainnet/rebalance-state.json.profit-exit.json`, scoped to cycle start `1789894318196`.
Its presence inhibits all new automatic cycles. Existing grid trading continues until the dashboard's `markPricePnl` (市场价盈亏) is strictly positive. The prior asset-ratio cycle-end condition is bypassed for this instruction.

On trigger, the controller durably freezes grid generation, cancels only `RBAL_` orders, and confirms terminal status plus complete per-order executions before sizing exits. It closes the current cycle's signed cumulative quantity, not the shared exchange position. Consequently account-level `reduceOnly` is false; every order is bounded by remaining cycle inventory and the existing 0.1 BTC order cap. Exit orders are LIMIT only.

Limit prices include cumulative trading/funding fees, a conservative 0.001 exit fee rate and a 1 USDT net-profit reserve. The market-PnL trigger does not require that reserve first. Partial fills and funding updates cause remaining quantity and the protected limit to be recalculated. A potentially accepted submission is recorded before sending and is never blindly repeated after timeout/restart. Missing exchange evidence blocks further orders.

The limit constrains execution price, not fill timing. Future funding changes, fee corrections and exchange failures prevent an unconditional guarantee of final realized profit. Completion logs record actual cycle net PnL for verification.

Validation: 126 Maven tests passed, including 12 added exit/controller tests. Focused tests were rerun after the final recovery-state adjustment. The new read-only exchange adapter was exercised against actual open orders before activation. Postflight confirmed the same cycle, surviving grid orders, `rebalanceRunning=true`, SQLite integrity, private stream recovery and unchanged 8787 PID.

Production backup: `/opt/btc_grid_rebalance/backups/profit-exit-20260921/`. The released JAR was built from the online JAR, replacing only `RebalanceService`, `LiveControlService` and adding the exit-controller classes. Never restore the backup trading database over a live ledger. Removing the instruction and reverting code also removes the no-new-cycle gate, so rollback must preserve that operator restriction.

External ChatGPT Pro review was not performed: no browser/Pro tool was available. Development and tests were local.
