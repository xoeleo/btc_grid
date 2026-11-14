from dataclasses import replace
import pandas as pd
from engine import Config,simulate
from test_engine import bars,START
from validate_export import validate
from test_regression import run_checks as old_checks
FUND=pd.DataFrame(columns=['timestamp_ms','rate'])

def run_checks():
 checks=old_checks()
 # Exact economic endpoints under a frozen reference, before changing market prices.
 for price,expected in [(50000,1.),(100000,2/3),(125000,.5),(200000,0.),(250000,0.)]:
  cfg=Config(mode='direct',target_mode='frozen_ath',initial_ath=100000,allow_perp_long=True)
  r,t,s,d,b,p=simulate(bars([price,price]),FUND,cfg,'endpoints');validate(r,t,s,d,b,p)
  assert abs(d[0]['target_weight']-expected)<1e-10
  assert abs(d[0]['weight']-expected)<.001
 checks.append('linear mapping endpoints, clamps and first allocation after fees')
 # The same old peak must not change the past when future new peaks are appended.
 prices=[100000,120000,120000]+[120000*1.002**k for k in range(1,70)]
 cfg=Config(levels=50,target_mode='rolling_ath',initial_ath=125000,allow_perp_long=True)
 r,t,s,d,b,p=simulate(bars(prices),FUND,cfg,'up');validate(r,t,s,d,b,p)
 assert b and b[0]['start_weight']-b[0]['start_target']>=.05
 assert b[0]['actual_slots']==50 and b[0]['end_reason']=='adjustment_completed_orders_cancelled'
 assert all(x['target_weight']>=2/3-1e-10 for x in t)
 assert r['final_ath']>=max(prices)
 short=simulate(bars(prices[:20]),FUND,cfg,'prefix');assert short[1]==t[:len(short[1])]
 future=simulate(bars(prices+[1000000,2000000]),FUND,cfg,'future');assert future[1][:len(t)]==t
 checks.append('rolling highs and targets are causal; future ATH cannot change past trades')
 checks.append('dynamic upper trigger, frozen 5% budget and 50 net slots completion')
 down=[100000,80000,80000]+[80000*.998**k for k in range(1,70)]
 rr,tt,ss,dd,bb,pp=simulate(bars(down),FUND,cfg,'down');validate(rr,tt,ss,dd,bb,pp)
 assert bb and bb[0]['start_target']-bb[0]['start_weight']>=.05
 assert bb[0]['end_reason']=='adjustment_completed_orders_cancelled'
 checks.append('dynamic lower trigger, inventory-only returns and completion')
 # Signed position can cross short -> long -> short, with correct basis and funding.
 cfg=Config(mode='direct',target_mode='frozen_ath',initial_ath=100000,allow_perp_long=True)
 f=pd.DataFrame([{'timestamp_ms':START+2*60000,'rate':.0001}])
 rr,tt,ss,dd,bb,pp=simulate(bars([100000,40000,40000,200000,200000]),f,cfg,'cross');validate(rr,tt,ss,dd,bb,pp)
 assert any(x['q']>0 for x in tt) and tt[-1]['q']<0
 assert ss[0]['q']>0 and ss[0]['payment']<0
 checks.append('signed perp basis, short/long crossing, positive funding paid by long')
 cfg=replace(cfg,allow_perp_long=False)
 rr,tt,ss,dd,bb,pp=simulate(bars([100000,40000,40000,40000]),FUND,cfg,'short_only');validate(rr,tt,ss,dd,bb,pp)
 assert all(x['q']<=1e-9 for x in tt) and rr['short_only_target_unreachable_hours']>0
 checks.append('short-only comparison clips purchases and reports unreachable target')
 cfg=Config(levels=50,target_mode='frozen_ath',initial_ath=125000,allow_perp_long=False)
 for direction,prices in [('upper',[100000,120000,120000]+[120000*1.002**k for k in range(1,70)]),('lower',[100000,80000,80000]+[80000*.998**k for k in range(1,70)])]:
  r,t,s,d,b,p=simulate(bars(prices),FUND,cfg,'frozen');validate(r,t,s,d,b,p)
  assert b[0]['end_reason']=='adjustment_completed_orders_cancelled'
  assert b[0]['actual_slots']==50 and b[0]['held_units']==b[0]['total_units']
  assert all(x['ath_reference']==125000 for x in t)
  prefix=simulate(bars(prices[:20]),FUND,cfg,'prefix');assert prefix[1]==t[:len(prefix[1])]
  future=simulate(bars(prices+[1000000,2000000]),FUND,cfg,'future')
  assert future[1][:len(t)]==t and future[0]['final_ath']==125000
  checks.append(f'frozen {direction}: 50-slot completion, moving-center inventory, frozen ATH and no future dependence')
 return checks
if __name__=='__main__':print(run_checks())
