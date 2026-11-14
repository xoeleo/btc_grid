"""Independent accounting and execution replay for the fixed price-band study.

This module deliberately does not import the strategy engine.  It replays the
exported trades and settlements from first principles so that a bookkeeping or
execution-recording bug cannot make its own audit pass.
"""

import math


CAPITAL = 500_000.0
MAKER = 0.00014
TAKER = 0.00035
LOT = 0.001


def _number(row, key, default=None):
    value = row.get(key, default)
    return None if value is None else float(value)


def _close(actual, expected, label, absolute=1e-5):
    actual = float(actual)
    expected = float(expected)
    tolerance = max(absolute, 1e-10 * max(1.0, abs(actual), abs(expected)))
    assert abs(actual - expected) <= tolerance, (label, actual, expected, tolerance)


def _target(cfg, spot):
    mode = cfg.get("target_mode", "fixed")
    if mode == "price_band":
        lower = float(cfg["lower_price"])
        upper = float(cfg["upper_price"])
        assert lower < upper and lower > 0.0
        return max(0.0, min(1.0, (upper - float(spot)) / (upper - lower)))
    if mode == "fixed":
        return float(cfg["fixed_target"])
    reference = float(cfg["initial_ath"])
    if mode == "rolling_ath":
        reference = max(reference, float(spot))
    return max(0.0, min(1.0, (2.0 * reference - float(spot)) / (1.5 * reference)))


def _trigger_threshold(cfg, pre_weight, target):
    del pre_weight, target
    return float(cfg.get("threshold", 0.05))


def _lot_multiple(quantity):
    units = abs(float(quantity)) / LOT
    assert abs(units - round(units)) <= 1e-7, ("quantity is not a .001 lot", quantity)


def _replay_trade(state, trade, cfg, index):
    q, cash, basis, realized, funding, interest, fees, spot_qty = state
    _close(trade["pre_q"], q, "trade pre_q")
    _close(trade["pre_cash"], cash, "trade pre_cash")
    _close(trade["pre_basis"], basis, "trade pre_basis")

    delta = float(trade["delta"])
    execution = float(trade["execution"])
    spot = float(trade["spot"])
    mark = float(trade["mark"])
    assert execution > 0.0 and abs(delta) >= LOT - 1e-9
    _lot_multiple(delta)
    kind = str(trade["kind"])
    fee_rate = (MAKER if kind == "maker" else TAKER) * float(cfg.get("fee_multiplier", 1.0))
    if cfg.get("mode") == "direct":
        assert kind in ("initial", "rebalance"), ("unexpected direct trade kind", kind)
    fee = abs(delta) * execution * fee_rate
    _close(trade["fee"], fee, "trade fee")

    slip = float(cfg.get("slip", 0.0))
    if kind != "maker":
        # ``quote`` is the linear-contract quote used by the order.  The
        # spot and mark feeds are separate accounting inputs and can differ
        # materially from the execution quote.
        quote = trade.get("quote")
        assert quote is not None, ("direct trade is missing linear quote", index)
        quote = float(quote)
        assert quote > 0.0
        expected_execution = quote * (1.0 + slip if delta > 0 else 1.0 - slip)
        _close(execution, expected_execution, "taker execution/slippage", absolute=1e-6)

    pre_equity = spot_qty * spot + cash + q * mark
    assert pre_equity > 0.0
    pre_weight = (spot_qty * spot + q * mark) / pre_equity
    target = _target(cfg, spot)
    if cfg.get("target_mode") == "price_band":
        _close(trade["target_weight"], target, "price-band trade target", absolute=1e-9)
    if cfg.get("mode") == "direct" and kind == "rebalance":
        threshold = _trigger_threshold(cfg, pre_weight, target)
        assert abs(pre_weight - target) + 1e-10 >= threshold, (
            "direct trigger below threshold", index, pre_weight, target, threshold
        )

    gap = target * pre_equity - (spot_qty * spot + q * mark)
    if gap > 1e-8:
        assert delta > 0.0, ("wrong execution direction", index, gap, delta)
    elif gap < -1e-8:
        assert delta < 0.0, ("wrong execution direction", index, gap, delta)

    if abs(q) < 1e-9 or q * delta > 0.0:
        basis += delta * execution
    else:
        average = basis / q
        closed = min(abs(q), abs(delta))
        closing_delta = math.copysign(closed, delta)
        realized += closed * (execution - average) * math.copysign(1.0, q)
        basis += closing_delta * average + (delta - closing_delta) * execution
    cash -= delta * execution + fee
    q += delta
    fees += fee
    realized -= fee
    if abs(q) < 1e-9:
        q = 0.0
        basis = 0.0

    if not cfg.get("allow_perp_long", False):
        assert q <= 1e-8, ("unexpected net long perpetual position", index, q)
    _close(trade["q"], q, "trade q")
    _close(trade["cash"], cash, "trade cash")
    _close(trade["basis"], basis, "trade basis")
    _close(trade["actual_usdt"], cash + basis, "trade actual_usdt")
    _close(trade["realized"], realized, "trade realized")
    _close(trade["funding"], funding, "trade funding")
    _close(trade["interest"], interest, "trade interest")
    _close(cash + basis, realized + funding - interest, "cash/basis PnL identity")
    _close(trade["spot_qty"], spot_qty, "trade spot quantity")
    return q, cash, basis, realized, funding, interest, fees, spot_qty


def audit(result, trades, settlements, daily):
    """Replay exported accounting and execution rows and return audit evidence.

    The function intentionally accepts the same four arguments as the older
    study audits.  ``batches`` and ``pairs`` are not needed for cash/basis
    correctness and are therefore not trusted here.
    """
    cfg = result["config"]
    if cfg.get("target_mode") == "price_band":
        # The new study has one fixed, auditable mapping.  A changed range is
        # a different experiment and must not silently pass this audit.
        _close(cfg["lower_price"], 60_000.0, "price-band lower anchor", absolute=1e-9)
        _close(cfg["upper_price"], 200_000.0, "price-band upper anchor", absolute=1e-9)
        assert any(abs(float(cfg.get("threshold", -1.0)) - value) <= 1e-12 for value in (0.05, 0.01)), (
            "price-band threshold must be the audited 5-point or 1-point case", cfg.get("threshold")
        )
        _close(_target(cfg, 60_000.0), 1.0, "price-band lower clamp", absolute=1e-12)
        _close(_target(cfg, 200_000.0), 0.0, "price-band upper clamp", absolute=1e-12)
        _close(_target(cfg, 0.0), 1.0, "price-band zero clamp", absolute=1e-12)
        _close(_target(cfg, 300_000.0), 0.0, "price-band high clamp", absolute=1e-12)

    assert daily, "daily marks are required for an independent replay"
    rows_for_spot = list(trades) or list(daily)
    spot_qty = _number(rows_for_spot[0], "spot_qty")
    assert spot_qty is not None and spot_qty > 0.0
    for row in list(trades) + list(daily):
        _close(row["spot_qty"], spot_qty, "constant spot inventory")

    # Funding and interest settle before trades at the same timestamp; daily
    # marks close the preceding interval before a next-bar trade at that time.
    events = []
    for index, row in enumerate(settlements):
        kind = row["kind"]
        assert kind in ("funding", "interest")
        events.append((int(row["timestamp_ms"]), 0 if kind == "funding" else 1, index, "settlement", row))
    for index, row in enumerate(trades):
        events.append((int(row["timestamp_ms"]), 2, int(row.get("sequence", index)), "trade", row))
    for index, row in enumerate(daily):
        events.append((int(row["timestamp_ms"]), -1, index, "day", row))
    events.sort(key=lambda event: event[:3])

    q = cash = basis = realized = funding = interest = fees = 0.0
    seen_sequences = set()
    seen_execution_ids = set()
    next_trade_sequence = 0
    last_trade_timestamp = None
    last_daily_timestamp = None
    start_value = result.get("start_ms")
    if start_value is None:
        start_value = min(int(row["timestamp_ms"]) for row in trades + settlements)
    start_ms = int(start_value)
    last_borrow_tick = start_ms
    borrow_day_ms = 365.25 * 86_400_000.0
    hour_ms = 3_600_000
    state = (q, cash, basis, realized, funding, interest, fees, spot_qty)

    for timestamp, _, sequence, kind, row in events:
        if kind == "settlement":
            _close(row["q"], q, "settlement q")
            _close(row["basis"], basis, "settlement basis")
            mark = float(row["mark"])
            rate = float(row["rate"])
            payment = float(row["payment"])
            if row["kind"] == "funding":
                _close(payment, -q * mark * rate, "funding payment")
                funding += payment
            else:
                assert timestamp % hour_ms == 0 and timestamp > start_ms
                # The engine advances its borrow clock at every hourly bar,
                # including hours with zero charge.  At an interest row the
                # latest possible prior clock tick is therefore the previous
                # hour boundary; a non-hour-aligned study start is retained.
                last_borrow_tick = max(last_borrow_tick, (timestamp // hour_ms - 1) * hour_ms)
                debt = max(0.0, -cash - basis)
                expected_charge = debt * float(cfg.get("borrow_apr", 0.0)) * (timestamp - last_borrow_tick) / borrow_day_ms
                assert expected_charge > 0.0, ("interest row without positive expected charge", row)
                _close(-payment, expected_charge, "borrow interest time-weighted charge")
                interest -= payment
                last_borrow_tick = timestamp
            cash += payment
            _close(row["cash"], cash, "settlement cash")
            _close(cash + basis, realized + funding - interest, "settlement PnL identity")
            state = (q, cash, basis, realized, funding, interest, fees, spot_qty)
            continue

        if kind == "trade":
            if last_trade_timestamp is not None:
                assert timestamp >= last_trade_timestamp, ("trade time moved backwards", row)
            last_trade_timestamp = timestamp
            if "sequence" in row:
                assert sequence not in seen_sequences, ("duplicate trade sequence", sequence)
                assert sequence == next_trade_sequence, ("trade sequence gap", sequence, next_trade_sequence)
                seen_sequences.add(sequence)
                next_trade_sequence += 1
            for key in ("execution_id", "exec_id"):
                if row.get(key) is not None:
                    execution_id = str(row[key])
                    assert execution_id not in seen_execution_ids, ("duplicate execution id", execution_id)
                    seen_execution_ids.add(execution_id)
            state = _replay_trade(state, row, cfg, sequence)
            q, cash, basis, realized, funding, interest, fees, spot_qty = state
            continue

        if last_daily_timestamp is not None:
            assert timestamp > last_daily_timestamp, ("daily mark time is not increasing", row)
        last_daily_timestamp = timestamp
        spot = float(row["spot"])
        mark = float(row["mark"])
        equity = spot_qty * spot + cash + q * mark
        _close(row["equity"], equity, "daily equity")
        _close(row["actual_usdt"], cash + basis, "daily actual_usdt")
        _close(row["short_qty"], -q, "daily short quantity")
        _close(row["funding"], funding, "daily funding")
        _close(row["fees"], fees, "daily fees")
        _close(row["interest"], interest, "daily interest")
        _close(row["realized"], realized, "daily realized")
        _close(row["weight"], (spot_qty * spot + q * mark) / equity, "daily weight")
        if cfg.get("target_mode") == "price_band":
            _close(row["target_weight"], _target(cfg, spot), "daily price-band target", absolute=1e-9)
        _close(row["cash"], cash, "daily cash")
        _close(row["basis"], basis, "daily basis")
        _close(cash + basis, realized + funding - interest, "daily PnL identity")

    # Final state is independently reconstructed from the last mark.
    last = daily[-1]
    final_equity = spot_qty * float(last["spot"]) + cash + q * float(last["mark"])
    _close(result["final_equity"], final_equity, "final equity")
    _close(result["final_actual_usdt"], cash + basis, "final actual_usdt")
    _close(result["final_short_qty"], -q, "final short quantity")
    _close(result["final_spot_qty"], spot_qty, "final spot quantity")
    _close(result["funding_net"], funding, "result funding")
    _close(result["fees"], fees, "result fees")
    _close(result["borrow_interest"], interest, "result interest")
    _close(result["perp_realized_net_fees"], realized, "result realized")
    _close(result["perp_unrealized"], q * float(last["mark"]) - basis, "result unrealized")
    _close(result["final_weight"], (spot_qty * float(last["spot"]) + q * float(last["mark"])) / final_equity,
           "result final weight")
    if cfg.get("target_mode") == "price_band":
        _close(result["final_target"], _target(cfg, last["spot"]), "result final target", absolute=1e-9)

    # The strategy's economic decomposition must close against the fixed
    # initial capital.  ``spot_pnl`` is supplied by the engine because the
    # initial spot open is not repeated in every export row.
    spot_pnl = float(result["spot_pnl"])
    initial_trade = next((trade for trade in trades if trade.get("kind") == "initial"), None)
    if initial_trade is not None:
        _close(spot_pnl, spot_qty * (float(last["spot"]) - float(initial_trade["spot"])),
               "spot PnL from immutable spot inventory")
    unrealized = q * float(last["mark"]) - basis
    _close(
        result["final_equity"],
        CAPITAL + spot_pnl + realized + unrealized + funding - interest,
        "final PnL decomposition",
    )
    _close(last["equity"], result["final_equity"], "last daily equity")

    return {
        "id": result.get("id"),
        "passed": True,
        "trades": len(trades),
        "settlements": len(settlements),
        "daily_marks": len(daily),
        "cash_basis_replay_verified": True,
        "funding_interest_causality_verified": True,
        "price_band_target_verified": cfg.get("target_mode") != "price_band" or True,
        "direct_trigger_thresholds_verified": cfg.get("mode") != "direct" or True,
        "spot_inventory_constant_verified": True,
    }
