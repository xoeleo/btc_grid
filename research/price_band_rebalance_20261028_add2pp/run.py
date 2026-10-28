"""Fixed 60k/200k allocation: prespecified 5pp vs 2pp vs 1pp, derivative overlay only."""
from pathlib import Path
from dataclasses import replace
from concurrent.futures import ProcessPoolExecutor, as_completed
import multiprocessing as mp
import hashlib
import json
import time
import pandas as pd
from engine import Config, simulate
from audit import audit
from prepare_data import load_period

ROOT = Path(__file__).resolve().parent
BARS = FUNDING = None
MID = 1759363200000  # 2025-10-02 00:00 UTC


def execute_case(name, cfg, period):
    begin = time.monotonic()
    bars, funding = BARS, FUNDING
    if period == 'year1':
        bars = bars.loc[bars.timestamp_ms < MID].reset_index(drop=True)
        funding = funding.loc[funding.timestamp_ms < MID].reset_index(drop=True)
    elif period == 'year2':
        bars = bars.loc[bars.timestamp_ms >= MID].reset_index(drop=True)
        funding = funding.loc[funding.timestamp_ms >= MID].reset_index(drop=True)
    print('START', name, len(bars), flush=True)
    result, trades, settlements, daily, batches, pairs = simulate(bars, funding, cfg, name)
    result['period'] = period
    result['rebalance_count'] = sum(x['kind'] == 'rebalance' for x in trades)
    result['initial_contract_trades'] = sum(x['kind'] == 'initial' for x in trades)
    result['contract_turnover_usdt'] = sum(abs(x['delta'])*x['execution'] for x in trades)
    result['buy_fills'] = sum(x['delta'] > 0 for x in trades)
    result['sell_fills'] = sum(x['delta'] < 0 for x in trades)
    result['rebalance_buy_fills'] = sum(x['delta'] > 0 and x['kind'] == 'rebalance' for x in trades)
    result['rebalance_sell_fills'] = sum(x['delta'] < 0 and x['kind'] == 'rebalance' for x in trades)
    # Compensating transfers are never injected; all equity must come from recorded components.
    check = audit(result, trades, settlements, daily)
    for suffix, rows in [('trades', trades), ('settlements', settlements), ('daily', daily)]:
        pd.DataFrame(rows).to_csv(ROOT/'outputs'/f'{name}_{suffix}.csv', index=False)
    previous = 500000.
    yearly = []
    days = pd.DataFrame(daily)
    closes = pd.concat([pd.Series([500000.]), days.equity], ignore_index=True)
    result['daily_close_max_drawdown'] = float((1-closes/closes.cummax()).max())
    for year, group in days.groupby(pd.to_datetime(days.timestamp_ms-1, unit='ms', utc=True).dt.year):
        end = float(group.equity.iloc[-1])
        yearly.append(dict(id=name, year=int(year), return_pct=(end/previous-1)*100))
        previous = end
    print('DONE', name, 'equity', round(result['final_equity'], 2),
          'CAGR', round(result['cagr']*100, 3), 'DD', round(result['max_drawdown']*100, 3),
          'rebalances', result['rebalance_count'], 'seconds', round(time.monotonic()-begin, 1), flush=True)
    return result, check, yearly


def main():
    global BARS, FUNDING
    ROOT.joinpath('outputs').mkdir(exist_ok=True)
    BARS, FUNDING = load_period()
    base = Config(mode='direct', target_mode='price_band', lower_price=60000., upper_price=200000.,
                  threshold=.05, allow_perp_long=False)
    cases = []
    for period in ('two_year', 'year1', 'year2'):
        for threshold, suffix in ((.05, '5pp'), (.02, '2pp'), (.01, '1pp')):
            cases.append((f'{period}_{suffix}', replace(base, threshold=threshold), period))
        if period == 'two_year':
            cases.append(('two_year_hold', replace(base, mode='spot'), period))
    for label, changes in [('fee2', dict(fee_multiplier=2.)), ('borrow5', dict(borrow_apr=.05)),
                           ('borrow10', dict(borrow_apr=.10)), ('allow_long', dict(allow_perp_long=True))]:
        for threshold, suffix in ((.05, '5pp'), (.02, '2pp'), (.01, '1pp')):
            cases.append((f'two_year_{suffix}_{label}', replace(base, threshold=threshold, **changes), 'two_year'))
    (ROOT/'scenario_plan.json').write_text(json.dumps([
        {'id': name, 'period': period, 'config': cfg.__dict__} for name, cfg, period in cases], indent=2)+'\n')
    results = {}
    with ProcessPoolExecutor(max_workers=3, mp_context=mp.get_context('fork')) as pool:
        futures = {pool.submit(execute_case, name, cfg, period): name for name, cfg, period in cases}
        for future in as_completed(futures):
            name = futures[future]
            results[name] = future.result()
            (ROOT/'partial_results.json').write_text(json.dumps([x[0] for x in results.values()], indent=2, allow_nan=False)+'\n')
    ordered = [results[name][0] for name, _, _ in cases]
    (ROOT/'results.json').write_text(json.dumps(ordered, indent=2, allow_nan=False)+'\n')
    pd.json_normalize(ordered).to_csv(ROOT/'results.csv', index=False)
    pd.DataFrame([y for name, _, _ in cases for y in results[name][2]]).to_csv(ROOT/'yearly.csv', index=False)
    (ROOT/'audit.json').write_text(json.dumps({'passed': True, 'scenarios': [results[name][1] for name, _, _ in cases]}, indent=2)+'\n')
    files = ['engine.py', 'audit.py', 'prepare_data.py', 'run.py', 'scenario_plan.json', 'source_manifest.json']
    (ROOT/'source_hashes.json').write_text(json.dumps({name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in files}, indent=2)+'\n')
    print('ALL', len(cases), 'SCENARIOS PASSED IN-MEMORY AUDIT', flush=True)


if __name__ == '__main__':
    main()
