"""Replay CSV accounting and independently verify every minute's policy decision.

This validator uses source prices/funding, not a second call to the simulation.
It checks missed as well as spurious triggers and values after CSV serialization.
"""
from pathlib import Path
import json
import numpy as np
import pandas as pd
from audit import audit
from prepare_data import load_period

ROOT = Path(__file__).resolve().parent
LOT = .001


def rows(path):
    try:
        return pd.read_csv(path).to_dict('records')
    except pd.errors.EmptyDataError:
        return []


def policy_check(result, trades, settlements, bars, funding):
    cfg = result['config']
    ts = bars.timestamp_ms.to_numpy(dtype=np.int64)
    spot = bars.spot_open.to_numpy()
    mark = bars.mark_open.to_numpy()
    linear = bars.linear_open.to_numpy()
    sq = 500000. / spot[0]
    assert ts[0] % 3_600_000 == 0 and np.all(np.diff(ts) == 60_000), 'This oracle requires complete minute data and an hourly-aligned start'
    funding = funding.loc[(funding.timestamp_ms >= ts[0]) & (funding.timestamp_ms <= ts[-1])]
    tt = np.array([x['timestamp_ms'] for x in trades], dtype=np.int64)
    assert np.all(tt[1:] >= tt[:-1]), 'Exported trades are not sorted'
    dq = np.array([x['delta'] for x in trades])
    cash_changes = np.array([-x['delta']*x['execution']-x['fee'] for x in trades])
    if len(tt):
        assert len(set(tt)) == len(tt), 'Multiple direct market orders in a minute'
        assert np.all(np.isin(tt, ts)), 'Trade not at an observed minute open'
        positions = np.searchsorted(ts, tt)
        np.testing.assert_allclose([x['spot'] for x in trades], spot[positions], rtol=0, atol=1e-7)
        np.testing.assert_allclose([x['mark'] for x in trades], mark[positions], rtol=0, atol=1e-7)
        np.testing.assert_allclose([x['quote'] for x in trades], linear[positions], rtol=0, atol=1e-7)
    before_trades = np.searchsorted(tt, ts, side='left')
    q = np.r_[0., np.cumsum(dq)][before_trades]
    cash = np.r_[0., np.cumsum(cash_changes)][before_trades]
    if settlements:
        ordered = sorted(settlements, key=lambda x: x['timestamp_ms'])
        st = np.array([x['timestamp_ms'] for x in ordered], dtype=np.int64)
        payments = np.array([x['payment'] for x in ordered])
        cash += np.r_[0., np.cumsum(payments)][np.searchsorted(st, ts, side='right')]
    equity = sq*spot + cash + q*mark
    assert np.all(equity > 0)
    target = np.clip((cfg['upper_price']-spot)/(cfg['upper_price']-cfg['lower_price']), 0, 1)
    weight = (sq*spot + q*mark)/equity
    gap = target*equity - sq*spot - q*mark
    execution = linear * np.where(gap > 0, 1+cfg['slip'], 1-cfg['slip'])
    denominator = mark + target*(execution-mark) + target*np.where(gap > 0, 1., -1.)*execution*.00035*cfg['fee_multiplier']
    raw_delta = gap/denominator
    if not cfg['allow_perp_long']:
        raw_delta = np.where(raw_delta > 0, np.minimum(raw_delta, np.maximum(0., -q)), raw_delta)
    delta = np.sign(raw_delta)*np.floor((np.abs(raw_delta)+1e-12)/LOT)*LOT
    trigger = np.abs(weight-target) >= cfg['threshold']
    trigger &= ts % (cfg['trigger_minutes']*60_000) == 0
    trigger[0] = True
    if cfg['mode'] == 'spot':
        trigger[:] = False
    executable = trigger & (np.abs(delta) >= LOT*.999) & (np.abs(delta)*execution >= 5)
    expected_times = ts[executable]
    if not np.array_equal(expected_times, tt):
        missing = np.setdiff1d(expected_times, tt)
        extra = np.setdiff1d(tt, expected_times)
        raise AssertionError((result['id'], 'Missing/spurious triggers', missing[:5], extra[:5]))
    if len(tt):
        np.testing.assert_allclose(delta[executable], dq, rtol=0, atol=1e-8,
                                   err_msg='Wrong signed quantity after cost-aware target/lot/short-only cap')
    fs = [x for x in settlements if x['kind'] == 'funding']
    expected_funding = funding.loc[funding.timestamp_ms > ts[0]]
    assert np.array_equal(np.array([x['timestamp_ms'] for x in fs], dtype=np.int64), expected_funding.timestamp_ms.to_numpy())
    if fs:
        fi = np.searchsorted(ts, expected_funding.timestamp_ms.to_numpy())
        rate = expected_funding.rate.to_numpy() if cfg['funding_enabled'] else np.zeros(len(fi))
        np.testing.assert_allclose([x['rate'] for x in fs], rate, rtol=0, atol=1e-14)
        np.testing.assert_allclose([x['mark'] for x in fs], mark[fi], rtol=0, atol=1e-7)
        np.testing.assert_allclose([x['q'] for x in fs], q[fi], rtol=0, atol=1e-8)
        np.testing.assert_allclose([x['payment'] for x in fs], -q[fi]*mark[fi]*rate, rtol=0, atol=1e-5)
    # Guarantee there are no silently omitted borrowing charges at hourly boundaries.
    basis_changes = np.array([x['basis']-x['pre_basis'] for x in trades])
    basis = np.r_[0., np.cumsum(basis_changes)][before_trades]
    hourly = (ts > ts[0]) & (ts % 3_600_000 == 0)
    # cash includes this hour's interest; undo it to recover the pre-interest debt.
    interest_rows = [x for x in settlements if x['kind'] == 'interest']
    interest_at = np.zeros(len(ts))
    if interest_rows:
        ii = np.searchsorted(ts, [x['timestamp_ms'] for x in interest_rows])
        interest_at[ii] = [-x['payment'] for x in interest_rows]
    pre_interest_cash = cash + interest_at
    charge = np.maximum(0., -pre_interest_cash-basis)*cfg['borrow_apr']/365.25/24
    charge[~hourly] = 0.
    np.testing.assert_allclose(interest_at, charge, rtol=0, atol=1e-7,
                               err_msg='Missing or incorrect hourly negative-USDT interest')
    return dict(minutes_checked=len(ts), all_triggers_verified=True, source_prices_verified=True,
                funding_schedule_and_rates_verified=True, complete_hourly_interest_verified=True)


def main():
    bars, funding = load_period()
    results = json.loads((ROOT/'results.json').read_text())
    checks = []
    for result in results:
        name = result['id']
        tr, se, da = [rows(ROOT/'outputs'/f'{name}_{kind}.csv') for kind in ('trades', 'settlements', 'daily')]
        sub = bars.loc[(bars.timestamp_ms >= result['start_ms']) & (bars.timestamp_ms < result['end_exclusive_ms'])].reset_index(drop=True)
        rates = funding.loc[(funding.timestamp_ms >= result['start_ms']) & (funding.timestamp_ms < result['end_exclusive_ms'])]
        check = audit(result, tr, se, da)
        check.update(policy_check(result, tr, se, sub, rates))
        checks.append(check)
        print('PASS', name, check['minutes_checked'], 'source minute decisions + exported accounting', flush=True)
    record = json.loads((ROOT/'audit.json').read_text())
    record['export_replay'] = checks
    record['passed'] = all(x.get('passed') for x in checks)
    (ROOT/'audit.json').write_text(json.dumps(record, indent=2)+'\n')
    print('ALL', len(checks), 'EXPORTED SCENARIOS VERIFIED', flush=True)


if __name__ == '__main__':
    main()
