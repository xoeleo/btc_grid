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
 return checks
if __name__=='__main__':print(run_checks())
