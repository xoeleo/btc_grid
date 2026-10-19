from test_4060 import run_checks as prior_checks
from test_engine import bars, START
from engine import Config, simulate, quantize
from audit import audit
import pandas as pd

def run_checks():
    checks = prior_checks()
    funding = pd.DataFrame(columns=['timestamp_ms', 'rate'])
    for levels in [20, 50]:
        cfg = Config(levels=levels, low=.45, high=.55, adjustment_fraction=.05, stop_on_completion=True)
        for side, prices in [('upper',[100000,123000,123000,139000,139000,123000,123000]), ('lower',[100000,80000,80000,70000,70000,80000,80000])]:
            r,t,s,d,b,p = simulate(bars(prices), funding, cfg, f'{levels}_{side}')
            audit(r,t,s,d)
            complete = [x for x in b if x['end_reason']=='adjustment_completed_orders_cancelled']
            assert complete
            batch = complete[0]
            assert batch['actual_slots']==levels
            assert abs(batch['planned_qty']-quantize(.05*batch['start_equity']/batch['anchor']))<1e-8
            fills = [x for x in t if x['batch']==batch['batch']]
            assert len([x for x in fills if x['leg']=='open'])==levels
            assert fills[-1]['leg']=='open' and fills[-1]['timestamp_ms']==batch['end_ms']
            assert batch['held_units']==batch['total_units'] and batch['completion_count']==1
            assert all(x['timestamp_ms']<=batch['end_ms'] for x in fills)
            prefix = simulate(bars(prices).iloc[:4], funding, cfg, 'prefix')
            assert prefix[1]==t[:len(prefix[1])]
            checks.append(f'{levels} slots {side}: fixed 5%, full completion cancellation, no future data')
    return checks

if __name__ == '__main__': print(run_checks())
