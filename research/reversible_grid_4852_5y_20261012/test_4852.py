from test_variants import run_checks as prior_checks
from test_engine import bars, START
from engine import Config, simulate, quantize
from audit import audit
import pandas as pd

def run_checks():
    checks = prior_checks()
    funding = pd.DataFrame(columns=['timestamp_ms', 'rate'])
    for levels in [20, 50]:
        cfg = Config(levels=levels, low=.48, high=.52, adjustment_fraction=.02, stop_on_completion=True)
        for side, prices in [('upper',[100000,109000,109000,125000,125000,109000,109000]), ('lower',[100000,92000,92000,78000,78000,92000,92000])]:
            r,t,s,d,b,p = simulate(bars(prices), funding, cfg, f'{levels}_{side}')
            audit(r,t,s,d)
            complete = [x for x in b if x['end_reason']=='adjustment_completed_orders_cancelled']
            assert complete
            batch = complete[0]
            assert batch['actual_slots']==levels
            assert abs(batch['planned_qty']-quantize(.02*batch['start_equity']/batch['anchor']))<1e-8
            fills = [x for x in t if x['batch']==batch['batch']]
            assert len([x for x in fills if x['leg']=='open'])==levels
            assert fills[-1]['leg']=='open' and fills[-1]['timestamp_ms']==batch['end_ms']
            assert batch['held_units']==batch['total_units'] and batch['completion_count']==1
            assert all(x['timestamp_ms']<=batch['end_ms'] for x in fills)
            prefix = simulate(bars(prices).iloc[:4], funding, cfg, 'prefix')
            assert prefix[1]==t[:len(prefix[1])]
            checks.append(f'{levels} slots {side}: 48-52 fixed 2%, full completion cancellation, no future data')
    # A zero-fill trigger that rebounds must retire, then permit a later fresh trigger.
    cfg=Config(levels=20,low=.48,high=.52,adjustment_fraction=.02,stop_on_completion=True)
    test_bars=bars([100000,109000,100000,109000,125000,125000])
    r,t,s,d,b,p=simulate(test_bars,funding,cfg,'zero_fill_recovery');audit(r,t,s,d)
    assert b[0]['end_reason']=='unfilled_returned_inside_band' and not b[0]['ever_traded']
    assert b[0]['held_units']==0 and b[0]['end_ms']==START+2*60000
    assert b[1]['start_ms']==START+3*60000 and b[1]['first_completed_ms'] is not None
    assert not any(x['batch']==b[0]['batch'] for x in t)
    checks.append('unfilled in-band recovery retires empty grid and permits future trigger')
    return checks

if __name__ == '__main__': print(run_checks())
