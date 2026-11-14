from test_4060 import run_checks as previous_checks
from test_engine import bars,START
from engine import Config,simulate,quantize
from audit import audit
import pandas as pd

def run_checks():
 checks=previous_checks()
 cfg=Config(levels=100,low=.45,high=.55,adjustment_fraction=.05,stop_on_completion=True)
 f=pd.DataFrame(columns=['timestamp_ms','rate'])
 for name,prices in [('upper',[100000,123000,123000,139000,139000,123000,123000]),('lower',[100000,80000,80000,70000,70000,80000,80000])]:
  r,t,s,d,b,p=simulate(bars(prices),f,cfg,name);audit(r,t,s,d)
  complete=[x for x in b if x['end_reason']=='adjustment_completed_orders_cancelled'];assert complete
  first=complete[0];assert first['actual_slots']==100
  expected=quantize(.05*first['start_equity']/first['anchor']);assert abs(first['planned_qty']-expected)<1e-8
  bt=[x for x in t if x['batch']==first['batch']]
  assert len([x for x in bt if x['leg']=='open'])==100
  assert all(x['timestamp_ms']<=first['end_ms'] for x in bt)
  assert first['completion_count']==1 and first['held_units']==first['total_units']
  assert bt[-1]['leg']=='open'
 return checks+['45-55 upper: 100 fixed slots, 5% budget, all-fill cancellation','45-55 lower: 100 fixed slots, 5% budget, all-fill cancellation']
if __name__=='__main__':print(run_checks())
