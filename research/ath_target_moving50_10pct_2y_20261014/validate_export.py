"""Independent moving-order selection, price, inventory, and cash replay."""
from pathlib import Path
import json,math
from engine import quantize
import pandas as pd
from audit import audit
ROOT=Path(__file__).resolve().parent

def read(path):
 try:return pd.read_csv(path).to_dict('records')
 except pd.errors.EmptyDataError:return []

def validate(result,t,s,d,b,p):
 check=audit(result,t,s,d);cfg=result['config'];step=cfg['step'];delay=cfg['rearm_delay_bars']*result['interval_minutes']*60000
 if cfg['target_mode']=='frozen_ath':
  reference=cfg['initial_ath']
  for x in t+d:
   expected=max(0.,min(1.,(2*reference-x['spot'])/(1.5*reference)))
   assert abs(x['target_weight']-expected)<1e-10
   assert abs(x['ath_reference']-reference)<1e-8
  for x in b:
   expected=max(0.,min(1.,(2*reference-x['start_spot'])/(1.5*reference)))
   assert abs(x['start_target']-expected)<1e-10
   assert abs(x['start_weight']-expected)>=cfg['threshold']-1e-10
   assert abs(x['start_ath']-reference)<1e-8
   budget=quantize(cfg['adjustment_fraction']*x['start_equity']/x['start_mark'])
   if x['direction']=='buy_down_sell_back' and not cfg['allow_perp_long']:budget=min(budget,quantize(-x['start_q']))
   assert abs(x['planned_qty']-budget)<1e-8
 batches={int(x['batch']):x for x in b};states={};offset=0;closed={};pairqty={}
 for batch in b:
  total=int(batch['total_units']);n=int(batch['actual_slots']);each,extra=divmod(total,n)
  states[int(batch['batch'])]=dict(anchor=batch['anchor'],last_ms=batch['start_ms'],held=[0]*n,capacity=[each+(k<extra) for k in range(n)],offset=offset,done=False)
  offset+=n
 # There can be at most one active batch globally (at most two resting orders).
 for left,right in zip(b,b[1:]):assert left['end_ms']<=right['start_ms']
 for x in t:
  if x['kind']!='maker':continue
  bid=int(x['batch']);batch=batches[bid];state=states[bid];held=state['held'];capacity=state['capacity']
  assert not state['done'];assert x['timestamp_ms']<=batch['end_ms']
  leg=x['leg'];direction=-1 if batch['direction']=='sell_up_buy_back' else 1
  side=direction if leg=='open' else -direction
  eligible=[k for k in range(len(held)) if held[k]<capacity[k]] if leg=='open' else [k for k in range(len(held)) if held[k]>0]
  assert eligible,('no available inventory',x)
  k=eligible[0] if leg=='open' else eligible[-1]
  assert int(x['slot'])==state['offset']+k
  raw=state['anchor']*(1+step if side<0 else 1-step)
  price=math.ceil(raw/.1)*.1 if side<0 else math.floor(raw/.1)*.1
  assert abs(price-x['execution'])<1e-6,('moving price',price,x)
  assert abs(state['anchor']-x['anchor_before'])<1e-6
  assert x['timestamp_ms']>=state['last_ms']+delay,('stale order or same-minute fill',x)
  units=round(abs(x['delta'])/.001);available=capacity[k]-held[k] if leg=='open' else held[k]
  assert 0<units<=available and x['delta']*side>0
  held[k]+=units if leg=='open' else -units
  state['anchor']=x['execution'];state['last_ms']=x['timestamp_ms']
  if leg=='return':closed[int(x['slot'])]=closed.get(int(x['slot']),0)+abs(x['delta'])
  if sum(held)==int(batch['total_units']):
   state['done']=True
   assert batch['end_reason']=='adjustment_completed_orders_cancelled'
   assert batch['end_ms']==x['timestamp_ms'] and int(batch['completion_count'])==1
 for x in p:
  sid=int(x['slot']);pairqty[sid]=pairqty.get(sid,0)+x['quantity']
  assert x['close_ms']-x['open_ms']>=delay
  gross=-x['direction']*(x['open_price']-x['close_price'])*x['quantity'];rate=.00014*cfg['fee_multiplier']
  assert abs(gross-x['gross_profit'])<1e-6
  assert abs(x['open_fee']-x['quantity']*x['open_price']*rate)<1e-6
  assert abs(x['close_fee']-x['quantity']*x['close_price']*rate)<1e-6
  assert abs(gross-x['open_fee']-x['close_fee']-x['net_grid_profit'])<1e-6
 assert all(abs(q-pairqty.get(sid,0))<1e-6 for sid,q in closed.items())
 assert abs(sum(x['net_grid_profit'] for x in p)-result['matched_grid_profit'])<1e-5
 net=0
 for bid,state in states.items():
  batch=batches[bid];assert sum(state['held'])==int(batch['held_units'])
  direction=-1 if batch['direction']=='sell_up_buy_back' else 1
  net+=direction*sum(state['held'])*.001
 if cfg['mode']=='grid':
  initial_q=sum(x['delta'] for x in t if x['kind']=='initial')
  assert abs(initial_q+net+result['final_short_qty'])<1e-6
 check.update(moving_prices_verified=True,one_active_batch_verified=True,no_stale_or_same_bar_fills=True,inventory_verified=True)
 return check

def main():
 results=json.loads((ROOT/'results.json').read_text());checks=[]
 for result in results:
  rows=[read(ROOT/'outputs'/f'{result["id"]}_{suffix}.csv') for suffix in ['trades','settlements','daily','batches','pairs']]
  checks.append(validate(result,*rows));print('PASS',result['id'],flush=True)
 a=json.loads((ROOT/'audit.json').read_text());a['export_replay']=checks;(ROOT/'audit.json').write_text(json.dumps(a,indent=2));print('ALL',len(checks),'EXPORT AUDITS PASSED',flush=True)
if __name__=='__main__':main()
