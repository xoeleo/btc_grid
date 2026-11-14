"""Acceptance by independently replaying saved CSVs and each fixed slot's inventory."""
from pathlib import Path
import json,math
import pandas as pd
from audit import audit
ROOT=Path(__file__).resolve().parent

def read(path):
 try:return pd.read_csv(path).to_dict('records')
 except pd.errors.EmptyDataError:return []

def main():
 results=json.loads((ROOT/'results.json').read_text());checks=[]
 for result in results:
  name=result['id'];t,s,d,b,p=[read(ROOT/'outputs'/f'{name}_{suffix}.csv') for suffix in ['trades','settlements','daily','batches','pairs']]
  check=audit(result,t,s,d)
  if b:
   batches={int(x['batch']):x for x in b};offset=0;slot_info={}
   for batch in b:
    total=int(batch['total_units']);n=int(batch['actual_slots']);each,extra=divmod(total,n)
    direction=-1 if batch['direction']=='sell_up_buy_back' else 1
    for k in range(n):slot_info[offset+k]=(int(batch['batch']),k+1,direction,each+(k<extra))
    offset+=n
   held={sid:0 for sid in slot_info};last={};closed_qty={};pair_qty={};completed_batches=set();batch_units={bid:0 for bid in batches}
   step=result['config']['step'];delay=result['config']['rearm_delay_bars'];interval=result['interval_minutes']*60000
   for x in t:
    if x['kind']!='maker':continue
    sid=int(x['slot']);bid,level,side,capacity=slot_info[sid];batch=batches[bid]
    assert x['timestamp_ms']<=batch['end_ms'] if batch.get('retired') else True
    assert bid not in completed_batches,('fill after batch completion',name,bid,x)
    units=round(abs(x['delta'])/.001);leg=x['leg'];expected_side=side if leg=='open' else -side
    assert x['delta']*expected_side>0
    held[sid]+=units if leg=='open' else -units
    assert 0<=held[sid]<=capacity
    batch_units[bid]+=units if leg=='open' else -units
    if result['config'].get('stop_on_completion') and batch_units[bid]==int(batch['total_units']):completed_batches.add(bid)
    if sid in last and last[sid]['leg']!=leg:assert x['timestamp_ms']-last[sid]['timestamp_ms']>=delay*interval
    last[sid]=x
    exponent=level if leg=='open' else level-1
    factor=(1+step)**exponent if side<0 else (1-step)**exponent
    raw=batch['anchor']*factor
    expected_price=math.ceil(raw/.1)*.1 if expected_side<0 else math.floor(raw/.1)*.1
    assert abs(expected_price-x['execution'])<1e-6
    assert x['timestamp_ms']>=batch['start_ms']+delay*interval
    if leg=='return':
     closed_qty[sid]=closed_qty.get(sid,0)+abs(x['delta'])
     assert x['weight']<=result['config']['high']+1e-6 if x['delta']>0 else x['weight']>=result['config']['low']-1e-6
   for x in p:
    sid=int(x['slot']);pair_qty[sid]=pair_qty.get(sid,0)+x['quantity']
    assert x['close_ms']-x['open_ms']>=delay*interval
    gross=-x['direction']*(x['open_price']-x['close_price'])*x['quantity']
    assert abs(gross-x['gross_profit'])<1e-6
    rate=.00014*result['config']['fee_multiplier']
    assert abs(x['open_fee']-x['quantity']*x['open_price']*rate)<1e-6
    assert abs(x['close_fee']-x['quantity']*x['close_price']*rate)<1e-6
    assert abs(gross-x['open_fee']-x['close_fee']-x['net_grid_profit'])<1e-6
   assert all(abs(closed_qty[sid]-pair_qty.get(sid,0))<1e-6 for sid in closed_qty)
   assert abs(sum(x['net_grid_profit'] for x in p)-result['matched_grid_profit'])<1e-5
   expected_q=t[0]['q']+sum(slot_info[sid][2]*units*.001 for sid,units in held.items())
   assert abs(expected_q+result['final_short_qty'])<1e-6
   for batch in b:
    if batch['end_reason']=='adjustment_completed_orders_cancelled':
     assert int(batch['held_units'])==int(batch['total_units'])
     assert int(batch['completion_count'])==1
     bt=[x for x in t if int(x['batch'])==int(batch['batch'])]
     assert bt[-1]['leg']=='open' and bt[-1]['timestamp_ms']==batch['end_ms']
   check.update(fixed_slots=len(slot_info),matched_pairs=len(p),slot_inventory_reconciles=True)
  actual=d[-1]['spot_qty']*d[-1]['spot']+d[-1]['actual_usdt']+d[-1]['unrealized_perp']
  assert abs(actual-result['final_equity'])<1e-5
  checks.append(check);print('export audit passed',name,'trades',len(t),'pairs',len(p),flush=True)
 a=json.loads((ROOT/'audit.json').read_text());a['independent_export_and_slot_replay']=checks;(ROOT/'audit.json').write_text(json.dumps(a,indent=2));print('ALL EXPORT AND SLOT AUDITS PASSED',len(checks),flush=True)
if __name__=='__main__':main()
