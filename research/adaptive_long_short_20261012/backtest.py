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
    lot: float = .02
    levels: int = 3
    minimum_step: float = .004
    maximum_step: float = .015
    atr_scale: float = 1.5
    warmup_days: int = 60


def signals(bars, config):
    """An hour can see only daily/hourly bars that ended before that hour."""
    index = pd.to_datetime(bars.timestamp_ms, unit='ms', utc=True)
    frame = bars.set_index(index)
    daily = frame.resample('1D').agg({'close': 'last'})
    fast = daily.close.ewm(span=config.fast, adjust=False).mean()
    slow = daily.close.ewm(span=config.slow, adjust=False).mean()
    state = pd.Series(np.where((fast > slow) & (daily.close > slow) &
                              (fast > fast.shift(5)), 1,
                              np.where((fast < slow) & (daily.close < slow), -1, 0)),
                      index=daily.index).shift(1)
    state.iloc[:config.warmup_days] = np.nan
    hourly = frame.resample('1h').agg({'open': 'first', 'high': 'max', 'low': 'min', 'close': 'last'})
    previous = hourly.close.shift(1)
    tr = pd.concat([hourly.high-hourly.low, abs(hourly.high-previous),
                    abs(hourly.low-previous)], axis=1).max(axis=1)
    step = (tr.ewm(alpha=1/24, adjust=False).mean() / hourly.close *
            config.atr_scale).clip(config.minimum_step, config.maximum_step).shift(1)
    state = state.reindex(hourly.index, method='ffill').fillna(-2).astype(int)
    return pd.DataFrame({'state': state, 'anchor': previous, 'step': step,
                         'available_ms': hourly.index.astype('int64') // 1_000_000})


def load_data():
    manifest = json.loads((DATA/'manifest.json').read_text())
    for name, expected in manifest['files'].items():
        assert hashlib.sha256((DATA/name).read_bytes()).hexdigest() == expected, name
    bars = pd.read_csv(DATA/'bars.csv.gz')
    funding = pd.read_csv(DATA/'funding.csv')
    assert len(bars) == manifest['rows']
    assert bars.timestamp_ms.iloc[0] == manifest['start_ms']
    assert bars.timestamp_ms.iloc[-1]+60000 == manifest['end_exclusive_ms']
    assert np.all(np.diff(bars.timestamp_ms) == 60000)
    for prefix in ['', 'mark_']:
        assert (bars[prefix+'high'] >= bars[[prefix+'open', prefix+'close']].max(axis=1)).all()
        assert (bars[prefix+'low'] <= bars[[prefix+'open', prefix+'close']].min(axis=1)).all()
    assert np.array_equal(funding.timestamp_ms, np.arange(manifest['start_ms'],
                         manifest['end_exclusive_ms'], 28_800_000))
    return bars, funding, manifest


def simulate(bars, funding, sig, config, name, path='OLHC', slippage_bps=1,
             fee_multiplier=1, fill_fraction=1, grid=True, start=0, end=None,
             export=True, grid_mode='all'):
    end = len(bars) if end is None else end
    assert start % 60 == 0 and end % 60 == 0
    values = bars.to_numpy()
    rates = dict(zip(funding.timestamp_ms.astype(int), funding.rate))
    states, anchors, steps = (sig[c].to_numpy() for c in ['state', 'anchor', 'step'])
    cash, qty, fees, paid_total = CAPITAL, 0., 0., 0.
    peak_equity = CAPITAL
    max_dd, max_dd_usdt, min_equity, peak_qty, peak_exposure = 0., 0., CAPITAL, 0., 0.
    trades, settlements, cycles, equities, controls = [], [], [], [], []
    cycle_start, cycle_cash, cycle_fees, cycle_paid = None, 0., 0., 0.
    cid, buys, sells, cycle_peak = 1, 0, 0, 0.
    orders = []
    previous_state = -2
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
        # Funding is charged on carried inventory before any same-timestamp execution.
        if ts in rates:
            paid = qty*mo*rates[ts]
            cash -= paid
            paid_total += paid
            if cycle_start is not None:
                cycle_paid += paid
            settlements.append((ts, qty, mo, rates[ts], paid))
        observe(mo)
        if i % 60 == 0:
            orders = []
            state = int(states[i//60])
            if state in [-1, 1]:
                previous_direction = state
            target = (state*config.bullish if state in [-1,1] else
                      previous_direction*config.neutral if state == 0 else 0.)
            current_cap = min(.85, abs(target)+config.levels*config.lot) if target else 0.
            equity = cash+qty*mo
            exposure = qty*mo/equity
            band = config.levels*config.lot
            # Cash in bearish/warmup state; in other states maintain a target band.
            if (state != previous_state or abs(exposure) > current_cap+1e-10 or
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
            controls.append((ts, state, target, current_cap, anchors[i//60], steps[i//60], qty))
            previous_state = state
            if (grid and (grid_mode == 'all' or state == 0) and target and
                    np.isfinite(anchors[i//60]) and np.isfinite(steps[i//60])):
                anchor, spacing = anchors[i//60], steps[i//60]
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
            duration = 59999
        else:
            points = [op, lo, hi, cl] if path == 'OLHC' else [op, hi, lo, cl]
            marks = [mo, ml, mh, mc] if path == 'OLHC' else [mo, mh, ml, mc]
            duration = 19999
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
        if i % 60 == 59 or i == end-1:
            equities.append((ts+59999, cash+qty*mc, qty, mc, abs(qty)*mc, cash, states[i//60]))
    last_mark = float(values[end-1, 8])
    final = cash+qty*last_mark
    closed_pnl = sum(c[4] for c in cycles)
    summary = dict(scenario=name, path=path, initial_capital=CAPITAL,
        start_ms=int(values[start, 0]), end_ms=int(values[end-1, 0])+60000,
        minutes=end-start, final_equity_marked=final, net_pnl=final-CAPITAL,
        return_pct=(final/CAPITAL-1)*100, max_drawdown_pct=max_dd,
        max_drawdown_usdt=max_dd_usdt, min_equity=min_equity,
        final_equity_liquidated_estimate=final-abs(qty)*last_mark*(slip+TAKER*fee_multiplier),
        open_qty=qty, open_cycle_pnl=final-CAPITAL-closed_pnl,
        closed_cycle_pnl=closed_pnl, trading_fees=fees, funding_paid=paid_total,
        peak_btc_qty=peak_qty, peak_exposure_pct=peak_exposure,
        completed_cycles=len(cycles), winning_cycles=sum(c[4]>0 for c in cycles),
        losing_cycles=sum(c[4]<=0 for c in cycles), fills=len(trades),
        open_cycle_hours=0 if cycle_start is None else (int(values[end-1, 0])+60000-cycle_start)/3600000,
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


def run():
    bars, funding, manifest = load_data()
    split = int(np.searchsorted(bars.timestamp_ms, pd.Timestamp('2025-10-02', tz='UTC').value//10**6))
    # Prespecified small family. Only first-year results determine selection.
    configs = [Config(fast=f, slow=s, bullish=b, neutral=b*.375)
               for f, s in [(10, 40), (20, 60)] for b in [.45, .65]]
    training = []
    for j, config in enumerate(configs):
        s = simulate(bars, funding, signals(bars, config), config, f'train_{j}', end=split, export=False)
        s['selection_score'] = s['return_pct']-1.5*s['max_drawdown_pct']
        training.append(s)
        print('training', j, s['return_pct'], s['max_drawdown_pct'], flush=True)
    selected = max(range(len(configs)), key=lambda j: training[j]['selection_score'])
    config = configs[selected]
    sig = signals(bars, config)
    sig.to_csv(ROOT/'signals.csv', index_label='hour_utc')
    cases = [('adaptive_olhc', {}), ('adaptive_ohlc', {'path':'OHLC'}),
             ('adaptive_close', {'path':'CLOSE'}),
             ('stress_cost', {'slippage_bps':5, 'fee_multiplier':2}),
             ('stress_half_fill', {'fill_fraction':.5}),
             ('no_grid', {'grid':False}),
             ('holdout_olhc', {'start':split}),
             ('holdout_ohlc', {'start':split, 'path':'OHLC'}),
             ('holdout_cost', {'start':split, 'slippage_bps':5, 'fee_multiplier':2}),
             ('holdout_no_grid', {'start':split, 'grid':False})]
    results = {}
    for name, options in cases:
        s = simulate(bars, funding, sig, config, name, **options)
        results[name] = s
        print(name, s['return_pct'], s['max_drawdown_pct'], s['fills'], flush=True)
    (ROOT/'results.json').write_text(json.dumps(dict(
        manifest=manifest, training=training, selected_index=selected,
        selected_config=asdict(config), selection_rule='first-year return_pct - 1.5 * max_drawdown_pct',
        selection_end_utc='2025-10-02', results=results), indent=2))


if __name__ == '__main__':
    run()
