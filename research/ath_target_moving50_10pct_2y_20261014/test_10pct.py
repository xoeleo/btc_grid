import pandas as pd
from engine import Config,simulate,quantize
from test_target import run_checks as previous_checks
from test_engine import bars
from validate_export import validate
FUND=pd.DataFrame(columns=['timestamp_ms','rate'])
def run_checks():
 checks=previous_checks()
 cfg=Config(levels=50,target_mode='frozen_ath',initial_ath=125000,threshold=.10,adjustment_fraction=.10,fill_fraction=1.)
 r,t,s,d,b,p=simulate(bars([100000,110000,110000]),FUND,cfg,'below10');validate(r,t,s,d,b,p)
 assert not b and len(t)==1
 checks.append('10 percentage point threshold: drift above 5 but below 10 does not trigger')
 for side,prices in [('upper',[100000,120000,120000]+[120000*1.002**k for k in range(1,70)]),('lower',[100000,80000,80000]+[80000*.998**k for k in range(1,70)])]:
  r,t,s,d,b,p=simulate(bars(prices),FUND,cfg,'10pct');validate(r,t,s,d,b,p)
  batch=b[0];assert abs(batch['start_weight']-batch['start_target'])>=.1
  assert batch['actual_slots']==50 and batch['end_reason']=='adjustment_completed_orders_cancelled'
  expected=quantize(.1*batch['start_equity']/batch['start_mark'])
  if side=='lower':expected=min(expected,quantize(-batch['start_q']))
  assert abs(batch['planned_qty']-expected)<1e-9
  fills=[x for x in t if x['batch']==1]
  assert len(fills)==50 and all(x['leg']=='open' for x in fills)
  assert sum(round(abs(x['delta'])/.001) for x in fills)==batch['total_units']
  prefix=simulate(bars(prices[:20]),FUND,cfg,'prefix');assert prefix[1]==t[:len(prefix[1])]
  checks.append(f'10pct {side}: 10% frozen budget, 50 whole-slot fills, cancellation, no future data')
 return checks
if __name__=='__main__':print(run_checks())
