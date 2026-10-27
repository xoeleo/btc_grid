from dataclasses import replace
import pandas as pd
from engine import Config,simulate
from test_engine import bars
from validate_export import validate

def run_checks():
 checks=[];funding=pd.DataFrame(columns=['timestamp_ms','rate'])
 for levels in [20,50]:
  for direction,start in [('upper',109000),('lower',92000)]:
   sign=1 if direction=='upper' else -1
   prices=[100000,start,start]+[start*(1+sign*.002)**k for k in range(1,levels+6)]
   cfg=Config(levels=levels,low=.48,high=.52,adjustment_fraction=.02)
   r,t,s,d,b,p=simulate(bars(prices),funding,cfg,'trend');validate(r,t,s,d,b,p)
   first=b[0];fills=[x for x in t if x['batch']==1]
   assert first['end_reason']=='adjustment_completed_orders_cancelled'
   assert len(fills)==levels and all(x['leg']=='open' for x in fills)
   assert all(x['timestamp_ms']<=first['end_ms'] for x in fills)
   prefix=simulate(bars(prices).iloc[:10],funding,cfg,'prefix');assert prefix[1]==t[:len(prefix[1])]
   checks.append(f'{levels} {direction}: one rung per fill, full completion, no future data, ledger replay')
   # Reversing walk opens, returns, then reopens with a freshly moving center.
   z=[100000,start,start,start*(1+sign*.002),start*(1+sign*.004),start,start*(1+sign*.004),start*(1+sign*.006)]
   rr,tt,ss,dd,bb,pp=simulate(bars(z),funding,cfg,'reverse');validate(rr,tt,ss,dd,bb,pp)
   legs=[x['leg'] for x in tt if x['batch']==1];assert 'return' in legs and legs[-1]=='open'
   checks.append(f'{levels} {direction}: inventory-only returns and moving-center reversibility')
   cfg=replace(cfg,fill_fraction=.5)
   rr,tt,ss,dd,bb,pp=simulate(bars(prices),funding,cfg,'partial');validate(rr,tt,ss,dd,bb,pp)
   checks.append(f'{levels} {direction}: partial fills cancel and reprice both orders')
 cfg=Config(levels=20,low=.48,high=.52,adjustment_fraction=.02)
 r,t,s,d,b,p=simulate(bars([100000,109000,100000,109000,109000,109500]),funding,cfg,'empty');validate(r,t,s,d,b,p)
 assert b[0]['end_reason']=='unfilled_returned_inside_band' and not b[0]['ever_traded']
 checks.append('empty grid cancels inside band, no opposite simultaneous batch')
 return checks
if __name__=='__main__':print(run_checks())
