"""Independent account, component cash, actor inventory and order-price replay."""
from pathlib import Path
import math,json
import pandas as pd
from audit import audit
ROOT=Path(__file__).resolve().parent

def read(path):
 try:return pd.read_csv(path).to_dict('records')
 except pd.errors.EmptyDataError:return []

def validate(result,t,s,d,b,p):
 check=audit(result,t,s,d);cfg=result['config'];step=cfg['step'];interval=result['interval_minutes']*60000
 overlay_q=0.;overlay_cash=0.;positions={};states={};last_quote={};completed=set();offset=0;exited=set();zero_stops=set()
 for batch in b:
  bid=int(batch['batch']);n=int(batch['actual_slots']);units=int(batch['total_units']);each,extra=divmod(units,n)
  states[bid]={'anchor':batch['anchor'],'last_ms':batch['start_ms'],'capacities':[each+(i<extra) for i in range(n)],'held':[0]*n,'offset':offset,'role':batch['role']}
  states[bid]['lots']=[[] for _ in range(n)]
  offset+=n
  if batch['role']=='funding':
   assert batch['signal_funding_rate']>cfg['funding_trigger']
   assert batch['signal_funding_ms']<=batch['start_ms']
   expected=math.floor((batch['start_net_btc_qty']*cfg['funding_fraction']+1e-12)/.001)*.001
   assert abs(batch['planned_qty']-expected)<1e-8
   assert n==cfg['funding_levels']
 events=[(x['timestamp_ms'],0,'settlement',x) for x in s]
 events += [(x['timestamp_ms'],2+i/1000000,'trade',x) for i,x in enumerate(t)]
 events += [(x['timestamp_ms'],-1,'daily',x) for x in d]
 for ts,order,kind,x in sorted(events,key=lambda z:(z[0],z[1])):
  if kind=='settlement':
   if x['kind']=='funding':
    expected=-overlay_q*x['mark']*x['rate'];assert abs(expected-x['overlay_payment'])<1e-6
    overlay_cash+=expected
  elif kind=='trade':
   bid=int(x['batch']);leg=x['leg']
   if x['component']=='funding':overlay_q+=x['delta'];overlay_cash-=x['delta']*x['execution']+x['fee']
   assert abs(overlay_q-x['overlay_q'])<1e-7 and abs(overlay_cash-x['overlay_cash'])<1e-5
   assert abs(x['net_btc_qty']-(x['spot_qty']+x['q']))<1e-7
   if cfg['funding_overlay']:assert x['net_btc_qty']>=-1e-8
   if x['kind']=='maker':
    batch=b[bid-1];state=states[bid];held=state['held'];capacity=state['capacities'];side=-1 if batch['direction']=='sell_up_buy_back' else 1
    assert bid not in completed
    if leg=='return':side=-side
    eligible=[k for k in range(len(held)) if held[k]<capacity[k]] if leg=='open' else [k for k in range(len(held)) if held[k]>0]
    assert eligible
    k=eligible[0] if leg=='open' else eligible[-1]
    assert int(x['slot'])==state['offset']+k
    raw=state['anchor']*(1+step if side<0 else 1-step)
    price=math.ceil(raw/.1)*.1 if side<0 else math.floor(raw/.1)*.1
    assert abs(price-x['execution'])<1e-6
    assert x['timestamp_ms']>=state['last_ms']+cfg['rearm_delay_bars']*interval
    assert x['delta']*side>0
    units=round(abs(x['delta'])/.001);held[k]+=units if leg=='open' else -units
    lots=state['lots'][k]
    if leg=='open':lots.append([units,x['execution']])
    else:
     remaining=units
     while remaining:
      used=min(remaining,lots[0][0]);lots[0][0]-=used;remaining-=used
      if not lots[0][0]:lots.pop(0)
    assert 0<=held[k]<=capacity[k]
    state['anchor']=x['execution'];state['last_ms']=x['timestamp_ms']
    if sum(held)==int(batch['total_units']) and not (batch['role']=='funding' and cfg.get('funding_completion_net_zero')):completed.add(bid)
   elif leg=='exit':
    batch=b[bid-1];state=states[bid];qty=sum(state['held'])*.001
    assert x['component']=='funding' and x['delta']>0 and abs(x['delta']-qty)<1e-8
    profit_reason=f'funding_{cfg["funding_take_profit"]*100:g}pct_take_profit'
    assert batch['end_reason'] in ['funding_returned_to_anchor',profit_reason]
    if batch['end_reason']==profit_reason:
     assert bid in completed
     assert abs(batch['exit_price']-batch['held_vwap']*(1-cfg['funding_take_profit']))<1e-6
     assert x['execution']<=batch['exit_price']*(1+cfg['slip'])+1e-6
    else:assert x['execution']<=batch['anchor']*(1+cfg['slip'])+1e-6
    state['held']=[0]*len(state['held'])
    state['lots']=[[] for _ in state['held']];exited.add(bid)
   if cfg.get('funding_completion_net_zero'):
    for candidate in b:
     cbid=int(candidate['batch']);cs=states[cbid]
     if candidate['role']!='funding' or cbid in completed or cbid in exited:continue
     if candidate['start_sequence']>x['sequence'] or not sum(cs['held']):continue
     if math.floor((max(0.,x['net_btc_qty'])+1e-12)/.001)==0:
      assert candidate['completion_reason']=='net_btc_zero'
      assert candidate['stop_sequence']==x['sequence'] and candidate['first_completed_ms']==ts
      assert candidate['stop_held_units']==sum(cs['held'])
      vwap=sum(units*price for lots in cs['lots'] for units,price in lots)/sum(cs['held'])
      assert abs(candidate['held_vwap']-vwap)<1e-6
      assert abs(candidate['stop_net_btc_qty']-x['net_btc_qty'])<1e-8
      completed.add(cbid);zero_stops.add(cbid)
  else:
   assert abs(overlay_q-x['overlay_q'])<1e-7 and abs(overlay_cash-x['overlay_cash'])<1e-5
   assert abs(x['overlay_pnl']-(overlay_cash+overlay_q*x['mark']))<1e-5
   expected_core=x['equity']-x['overlay_pnl'];assert abs(expected_core-x['core_equity'])<1e-5
   expected_ratio=(x['spot_qty']*x['spot']+(-x['short_qty']-overlay_q)*x['mark'])/expected_core
   assert abs(expected_ratio-x['core_weight'])<1e-8
 for batch in b:assert sum(states[int(batch['batch'])]['held'])==int(batch['held_units'])
 if cfg.get('funding_completion_net_zero'):
  assert zero_stops=={int(x['batch']) for x in b if x['role']=='funding' and x['completion_count']}
 assert abs(overlay_q-result['overlay_q'])<1e-7
 assert abs(sum(batch['cash_flow']+batch['funding_received'] for batch in b if batch['role']=='funding')-overlay_cash)<1e-5
 for x in p:
  gross=-x['direction']*(x['open_price']-x['close_price'])*x['quantity']
  assert abs(gross-x['gross_profit'])<1e-6
  assert abs(gross-x['open_fee']-x['close_fee']-x['net_grid_profit'])<1e-6
 assert abs(sum(x['net_grid_profit'] for x in p)-result['matched_grid_profit'])<1e-5
 check.update(component_cash_verified=True,moving_order_prices_verified=True,full_budget_then_takeprofit_verified=True,nonnegative_net_btc_verified=True)
 if cfg.get('funding_completion_net_zero'):check.update(net_zero_stop_verified=True,inventory_vwap_verified=True,zero_stops=len(zero_stops))
 return check

def main():
 results=json.loads((ROOT/'results.json').read_text());checks=[]
 for result in results:
  rows=[read(ROOT/'outputs'/f'{result["id"]}_{suffix}.csv') for suffix in ['trades','settlements','daily','batches','pairs']]
  checks.append(validate(result,*rows));print('PASS',result['id'],flush=True)
 a=json.loads((ROOT/'audit.json').read_text());a['export_replay']=checks;(ROOT/'audit.json').write_text(json.dumps(a,indent=2));print('ALL EXPORT AUDITS PASSED',len(checks))
if __name__=='__main__':main()
