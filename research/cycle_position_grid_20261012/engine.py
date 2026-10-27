"""Isolated, causal, signed-inventory BTC grid research. No exchange connectivity."""
from dataclasses import asdict, dataclass
from pathlib import Path
import csv
import hashlib
import json
import math
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent
DATA = ROOT.parent / 'dip_grid_500k_20261011/data'
CAPITAL = 500_000.0
MAKER, TAKER = .00014, .00035


@dataclass(frozen=True)
class Config:
    fast: int = 10
    slow: int = 40
    bullish: float = .45
    neutral: float = .1125
    lot: float = .025
    levels: int = 3
    minimum_step: float = .005
    maximum_step: float = .02
    atr_scale: float = 1.5
    warmup_days: int = 60


def simulate(bars, funding, sig, config, name, path='OLHC', slippage_bps=1,
             fee_multiplier=1, fill_fraction=1, grid=True, start=0, end=None,
             export=True, grid_mode='all'):
    end = len(bars) if end is None else end
    interval_ms = int(bars.timestamp_ms.iloc[1]-bars.timestamp_ms.iloc[0])
    assert interval_ms in [60000,3600000]
    assert bars.timestamp_ms.iloc[start] % 3600000 == 0
    values = bars.to_numpy()
    rates = dict(zip(funding.timestamp_ms.astype(int), funding.rate))
    states, targets, anchors, steps = (sig[c].to_numpy() for c in ['state','target','anchor','step'])
    available = sig.available_ms.to_numpy()
    signal_start = int(available[0])
    cash, qty, fees, paid_total = CAPITAL, 0., 0., 0.
    peak_equity = CAPITAL
    max_dd, max_dd_usdt, min_equity, peak_qty, peak_exposure = 0., 0., CAPITAL, 0., 0.
    trades, settlements, cycles, equities, controls = [], [], [], [], []
    cycle_start, cycle_cash, cycle_fees, cycle_paid = None, 0., 0., 0.
    cid, buys, sells, cycle_peak = 1, 0, 0, 0.
    orders = []
    previous_state = -2
    previous_target = 0.
    previous_direction = 1
    current_cap = 0.
    slip = slippage_bps / 10000
    max_reconciliation_error = 0.
    signed_value_total = 0.

    def observe(mark):
        nonlocal peak_equity, max_dd, max_dd_usdt, min_equity, peak_qty, peak_exposure
        eq = cash + qty*mark
        assert eq > 0 and cash >= -1e-6
        peak_equity = max(peak_equity, eq)
        max_dd = max(max_dd, (peak_equity-eq)/peak_equity*100)
        max_dd_usdt = max(max_dd_usdt, peak_equity-eq)
        min_equity = min(min_equity, eq)
        peak_qty = max(peak_qty, abs(qty))
        peak_exposure = max(peak_exposure, abs(qty)*mark/eq*100)
        return eq

    def fill(ts, side, amount, price, mark, kind):
        nonlocal cash, qty, fees, cycle_start, cycle_cash, cycle_fees, cycle_paid
        nonlocal cid, buys, sells, cycle_peak, signed_value_total, max_reconciliation_error
        rate = (MAKER if kind == 'grid' else TAKER)*fee_multiplier
        direction = 1 if side == 'BUY' else -1
        if qty*direction >= 0:
            equity = cash + qty*mark
            # Capacity is checked at actual execution, including entry fees.
            denominator = mark+current_cap*(direction*(price-mark)+price*rate)
            maximum = max(0., (current_cap*equity-abs(qty)*mark)/denominator)
            amount = min(amount, maximum)
            if side == 'BUY':
                amount = min(amount, cash/(price*(1+rate)))
        else:
            amount = min(amount, abs(qty))
        amount = math.floor((amount+1e-10)*1000)/1000
        if amount < .001:
            return False
        if cycle_start is None:
            cycle_start, cycle_cash = ts, cash
            cycle_fees, cycle_paid, buys, sells, cycle_peak = 0., 0., 0, 0, 0.
        before = qty
        signed = amount if side == 'BUY' else -amount
        fee = amount*price*rate
        cash -= signed*price + fee
        signed_value_total += signed*price
        qty = round(qty+signed, 10)
        fees += fee
        cycle_fees += fee
        buys += side == 'BUY'
        sells += side == 'SELL'
        cycle_peak = max(cycle_peak, abs(qty))
        trades.append((ts, cid, side, amount, price, fee, qty, kind, before, mark, current_cap))
        max_reconciliation_error = max(max_reconciliation_error,
            abs(cash-(CAPITAL-signed_value_total-fees-paid_total)))
        observe(mark)
        if qty == 0:
            cycles.append((cid, cycle_start, ts, (ts-cycle_start)/3600000,
                           cash-cycle_cash, cycle_fees, cycle_paid, buys, sells, kind, cycle_peak))
            cycle_start = None
            cid += 1
        return True

    for i in range(start, end):
        ts, op, hi, lo, cl, mo, mh, ml, mc = values[i]
        ts = int(ts)
        h = (ts-signal_start)//3600000
        assert 0 <= h < len(sig) and int(available[h]) <= ts
        # Funding is charged on carried inventory before any same-timestamp execution.
        if ts in rates:
            paid = qty*mo*rates[ts]
            cash -= paid
            paid_total += paid
            if cycle_start is not None:
                cycle_paid += paid
            settlements.append((ts, qty, mo, rates[ts], paid))
        observe(mo)
        if ts % 3600000 == 0:
            orders = []
            state = int(states[h])
            if state in [-1, 1]:
                previous_direction = state
            target = float(targets[h])
            current_cap = min(.85, abs(target)+config.levels*config.lot) if target else 0.
            equity = cash+qty*mo
            exposure = qty*mo/equity
            band = config.levels*config.lot
            # Cash in bearish/warmup state; in other states maintain a target band.
            changed = state != previous_state or abs(target-previous_target)>1e-10
            if (changed or abs(exposure) > current_cap+1e-10 or
                    (target and exposure*np.sign(target) < abs(target)-band)):
                desired = target*equity/mo
                # Opposite direction is a separate Cycle; never cross zero in one execution.
                if qty*desired < 0:
                    side = 'SELL' if qty > 0 else 'BUY'
                    fill(ts, side, abs(qty), op*(1+slip if side=='BUY' else 1-slip), mo, 'regime_close')
                    equity = cash+qty*mo
                    desired = target*equity/mo
                if abs(desired-qty) >= .001:
                    side = 'BUY' if desired > qty else 'SELL'
                    fill(ts, side, abs(desired-qty), op*(1+slip if side=='BUY' else 1-slip),
                         mo, 'regime' if state != previous_state else 'risk_rebalance')
            controls.append((ts, state, target, current_cap, anchors[h], steps[h], qty))
            previous_state = state
            previous_target = target
            if (grid and (grid_mode == 'all' or state == 0) and target and
                    np.isfinite(anchors[h]) and np.isfinite(steps[h])):
                anchor, spacing = anchors[h], steps[h]
                amount = math.floor(config.lot*(cash+qty*mo)/anchor*1000)/1000
                for level in range(1, config.levels+1):
                    buy_px = math.floor(anchor*(1-level*spacing)*10)/10
                    sell_px = math.ceil(anchor*(1+level*spacing)*10)/10
                    # Avoid crossing limits on placement: such orders cannot claim maker fees.
                    if buy_px < op-.1 and (target>0 or qty<0):
                        orders.append(['BUY', buy_px, amount*fill_fraction])
                    if sell_px > op+.1 and (target<0 or qty>0):
                        orders.append(['SELL', sell_px, amount*fill_fraction])
        if path == 'CLOSE':
            points, marks = [op, cl], [mo, mc]
            duration = interval_ms-1
        else:
            points = [op, lo, hi, cl] if path == 'OLHC' else [op, hi, lo, cl]
            marks = [mo, ml, mh, mc] if path == 'OLHC' else [mo, mh, ml, mc]
            duration = (interval_ms-1)//3
        # One posted order can fill at most once per hour. No invented instant replenishment.
        for k, (point, mark) in enumerate(zip(points, marks)):
            if orders:
                eligible = [o for o in orders if
                            (o[0]=='BUY' and point <= o[1]-.1) or
                            (o[0]=='SELL' and point >= o[1]+.1)]
                eligible.sort(key=lambda o: -o[1] if o[0]=='BUY' else o[1])
                for order in eligible:
                    # Fill time/mark is conservatively the path endpoint, not invented tick data.
                    # A reduce-side grid order cannot open the opposite direction after going flat.
                    if not ((target>0 and order[0]=='SELL' and qty<=0) or
                            (target<0 and order[0]=='BUY' and qty>=0)):
                        fill(ts+k*duration, order[0], order[2], order[1], mark, 'grid')
                    orders.remove(order)
            observe(mark)
        if (ts+interval_ms) % 3600000 == 0 or i == end-1:
            equities.append((ts+interval_ms-1, cash+qty*mc, qty, mc, abs(qty)*mc, cash, states[h]))
    last_mark = float(values[end-1, 8])
    final = cash+qty*last_mark
    closed_pnl = sum(c[4] for c in cycles)
    summary = dict(scenario=name, path=path, initial_capital=CAPITAL,
        start_ms=int(values[start, 0]), end_ms=int(values[end-1, 0])+interval_ms,
        minutes=(end-start)*interval_ms//60000, bar_interval_ms=interval_ms, final_equity_marked=final, net_pnl=final-CAPITAL,
        return_pct=(final/CAPITAL-1)*100, max_drawdown_pct=max_dd,
        max_drawdown_usdt=max_dd_usdt, min_equity=min_equity,
        final_equity_liquidated_estimate=final-abs(qty)*last_mark*(slip+TAKER*fee_multiplier),
        open_qty=qty, open_cycle_pnl=final-CAPITAL-closed_pnl,
        closed_cycle_pnl=closed_pnl, trading_fees=fees, funding_paid=paid_total,
        peak_btc_qty=peak_qty, peak_exposure_pct=peak_exposure,
        completed_cycles=len(cycles), winning_cycles=sum(c[4]>0 for c in cycles),
        losing_cycles=sum(c[4]<=0 for c in cycles), fills=len(trades),
        open_cycle_hours=0 if cycle_start is None else (int(values[end-1, 0])+interval_ms-cycle_start)/3600000,
        slippage_bps=slippage_bps, fee_multiplier=fee_multiplier, fill_fraction=fill_fraction,
        grid_enabled=grid, grid_mode=grid_mode, config=asdict(config), reconciliation_error=max_reconciliation_error,
        grid_fills=sum(t[7]=='grid' for t in trades))
    summary = {k: v.item() if isinstance(v, np.generic) else v for k, v in summary.items()}
    assert max_reconciliation_error < 1e-5
    if export:
        folder = ROOT/name
        folder.mkdir(exist_ok=True)
        for filename, header, rows in [
            ('trades.csv', 'timestamp_ms,cycle_id,side,qty,price,fee,net_qty,kind,qty_before,mark,exposure_cap', trades),
            ('cycles.csv', 'cycle_id,start_ms,end_ms,duration_hours,pnl,trading_fee,funding_paid,buys,sells,reason,peak_qty', cycles),
            ('funding_events.csv', 'timestamp_ms,qty,mark,rate,paid', settlements),
            ('equity.csv', 'timestamp_ms,equity,net_qty,mark,net_notional,cash,state', equities),
            ('controls.csv', 'timestamp_ms,state,target,cap,anchor,step,net_qty', controls)]:
            with (folder/filename).open('w', newline='') as f:
                writer = csv.writer(f)
                writer.writerow(header.split(','))
                writer.writerows(rows)
        (folder/'summary.json').write_text(json.dumps(summary, indent=2))
    return summary
