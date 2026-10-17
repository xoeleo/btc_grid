from dataclasses import replace
import pandas as pd
from engine import Config,simulate
from test_engine import bars,START
from validate_export import validate
FUND=pd.DataFrame(columns=['timestamp_ms','rate'])
def run_checks():
 checks=[]
 cfg=Config(levels=50,threshold=.99,low=.01,high=.99,funding_overlay=True,funding_levels=100,initial_known_funding=.0003,initial_known_funding_ms=START-8*3600000)
 no=simulate(bars([100000]*5),FUND,replace(cfg,initial_known_funding=.0002),'equal');validate(no[0],*no[1:]);assert not no[4]
 checks.append('strictly greater than 0.02%, not >=')
 up=[100000,100000]+[100000*1.002**k for k in range(1,125)]
 result=simulate(bars(up),FUND,cfg,'up');validate(result[0],*result[1:]);r,t,s,d,b,p=result
 assert b[0]['actual_slots']==100 and abs(b[0]['planned_qty']-1.25)<1e-8
 assert b[0]['completion_count']==1 and b[0]['end_reason']=='funding_waiting_at_end'
 assert len(b)>=2 and b[1]['start_ms']>=b[0]['first_completed_ms']
 assert abs(b[1]['planned_qty']-round((b[1]['start_net_btc_qty']*.5)//.001)*.001)<.0011
 checks.append('100 parts of half net BTC, full grid stops and repeats on remaining exposure')
 closed=simulate(bars(up+[100000,100000]),FUND,cfg,'profit');validate(closed[0],*closed[1:])
 assert closed[4][0]['end_reason']=='funding_2pct_take_profit' and closed[4][0]['held_units']==0
 assert all(x['component']=='funding' for x in closed[1] if x['leg']=='exit')
 assert abs(closed[0]['final_short_qty']-2.5)<1e-8
 checks.append('VWAP minus 2% closes only own funding inventory, baseline position preserved')
 origin=simulate(bars([100000,100000,100500,101000,100000,100000]),FUND,cfg,'origin');validate(origin[0],*origin[1:])
 assert origin[4][0]['end_reason']=='funding_returned_to_anchor' and origin[4][0]['held_units']==0
 checks.append('unfinished funding grid returns to anchor and closes its own inventory')
 f=pd.DataFrame([{'timestamp_ms':START+3*60000,'rate':.0001}])
 low=simulate(bars(up),f,cfg,'low_after_start');validate(low[0],*low[1:])
 assert low[4][0]['completion_count']==1 and len(low[4])==1
 checks.append('funding drops below threshold: existing grid continues, no further new grid')
 prefix=simulate(bars(up[:25]),f,cfg,'prefix');assert prefix[1]==low[1][:len(prefix[1])]
 future=pd.concat([f,pd.DataFrame([{'timestamp_ms':START+200*60000,'rate':.02}])],ignore_index=True)
 changed=simulate(bars(up),future,cfg,'future');assert changed[1]==low[1]
 checks.append('future funding rates cannot change earlier orders or executions')
 assert all(x['net_btc_qty']>=-1e-8 for x in t)
 checks.append('combined net BTC quantity never negative; minimum lot constraint respected')
 # Base and funding orders coexist; reanchoring one actor must not erase the other.
 mixed=simulate(bars(up+[90000,90000,95000,95000]),FUND,replace(cfg,threshold=.1,low=.45,high=.55,target_mode='frozen_ath',initial_ath=125000),'mixed');validate(mixed[0],*mixed[1:])
 assert any(x['role']=='base' for x in mixed[4]) and any(x['role']=='funding' for x in mixed[4])
 checks.append('base wave and funding grid retain separate orders and cash allocation')
 # User revision: all current net BTC split into 100 parts.
 fullcfg=replace(cfg,funding_levels=100,funding_fraction=1.)
 fullup=[100000,100000]+[100000*1.002**k for k in range(1,225)]
 full=simulate(bars(fullup),FUND,fullcfg,'full100');validate(full[0],*full[1:])
 assert full[4][0]['actual_slots']==100 and abs(full[4][0]['planned_qty']-2.5)<1e-8
 assert full[4][0]['completion_count']==1 and full[4][0]['end_reason']=='funding_waiting_at_end'
 assert len(full[4])==1  # zero net quantity cannot create another 100-lot batch
 assert min(x['net_btc_qty'] for x in full[1])>=-1e-8
 checks.append('100 parts of all current net BTC; no further batch when remaining net is below 100 lots')
 profit=simulate(bars(fullup+[100000,100000]),FUND,fullcfg,'full100_exit');validate(profit[0],*profit[1:])
 assert profit[4][0]['end_reason']=='funding_2pct_take_profit'
 assert abs(profit[0]['final_short_qty']-2.5)<1e-8
 checks.append('full-net 100-part batch closes only its own shorts at VWAP minus 2%')
 # Corrected user rule: overall net BTC zero stops an unfinished funding budget.
 zero_cfg=replace(cfg,funding_fraction=1.,funding_completion_net_zero=True,funding_take_profit=.05,
                  threshold=.1,adjustment_fraction=.1,target_mode='frozen_ath',initial_ath=125000)
 zero_up=[100000,100000]+[100000*1.002**k for k in range(1,180)]
 zero=simulate(bars(zero_up),FUND,zero_cfg,'netzero');validate(zero[0],*zero[1:])
 stopped=next(x for x in zero[4] if x['role']=='funding' and x['completion_count'])
 assert stopped['stop_held_units']<stopped['total_units'] and stopped['completion_reason']=='net_btc_zero'
 assert 0<=stopped['stop_net_btc_qty']<.001
 assert not any(x['batch']==stopped['batch'] and x['kind']=='maker' and x['sequence']>stopped['stop_sequence'] for x in zero[1])
 assert abs(stopped['exit_price']-stopped['held_vwap']*.95)<1e-8
 checks.append('net BTC zero stops incomplete funding budget immediately; cancels both quotes and freezes VWAP times 95%')
 anchor=simulate(bars(zero_up+[100000,100000]),FUND,zero_cfg,'anchor_after_stop');validate(anchor[0],*anchor[1:])
 assert anchor[4][0]['end_reason']=='funding_waiting_at_end'
 profit=simulate(bars(zero_up+[90000,90000]),FUND,zero_cfg,'five_pct');validate(profit[0],*profit[1:])
 assert profit[4][0]['end_reason']=='funding_5pct_take_profit'
 for x in profit[1]:
  if x['leg']=='exit':assert x['component']=='funding'
 checks.append('after net-zero stop, returning to anchor does not exit; VWAP minus 5% closes own funding inventory')
 base_stop=simulate(bars(zero_up),FUND,replace(zero_cfg,levels=1,threshold=.01,adjustment_fraction=.9),'base_stop');validate(base_stop[0],*base_stop[1:])
 stopped=next(x for x in base_stop[4] if x.get('completion_reason')=='net_btc_zero')
 assert base_stop[1][stopped['stop_sequence']]['component']=='base'
 checks.append('base strategy sale also triggers net-zero funding stop before the next order can fill')
 past=simulate(bars(zero_up[:50]),FUND,zero_cfg,'prefix_zero')
 assert past[1]==zero[1][:len(past[1])]
 future=pd.DataFrame([{'timestamp_ms':START+200*60000,'rate':.03}])
 assert simulate(bars(zero_up),future,zero_cfg,'future_zero')[1]==zero[1]
 checks.append('corrected stop logic remains prefix invariant and independent of future funding rates')
 # Current revision keeps corrected net-zero stop and 5% exit, changes only to 200 shares.
 cfg200=replace(zero_cfg,funding_levels=200)
 path200=[100000,100000]+[100000*1.002**k for k in range(1,240)]
 two=simulate(bars(path200),FUND,cfg200,'zero200');validate(two[0],*two[1:])
 stopped=next(x for x in two[4] if x['role']=='funding' and x['completion_count'])
 assert stopped['actual_slots']==200 and abs(stopped['planned_qty']-4.)<1e-8
 assert stopped['completion_reason']=='net_btc_zero' and stopped['stop_net_btc_qty']<.001
 assert abs(stopped['exit_price']/stopped['held_vwap']-.95)<1e-10
 assert not any(x['batch']==stopped['batch'] and x['kind']=='maker' and x['sequence']>stopped['stop_sequence'] for x in two[1])
 down=simulate(bars(path200+[80000,80000]),FUND,cfg200,'exit200');validate(down[0],*down[1:])
 assert any(x['end_reason']=='funding_5pct_take_profit' for x in down[4] if x['role']=='funding')
 checks.append('200-share current rule: whole net BTC budget, immediate net-zero stop, no later quotes, frozen VWAP minus 5% exit')
 return checks
if __name__=='__main__':print(run_checks())
