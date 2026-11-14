from pathlib import Path
from dataclasses import replace
import json,hashlib,math
import pandas as pd
import numpy as np
from engine import CAPITAL,MAKER,TAKER,Config,correction,simulate
ROOT=Path(__file__).resolve().parent;OUT=ROOT/'outputs';OUT.mkdir(exist_ok=True)

def tests():
 sv=500000.;q=0.;c=0.;m=60000.;p=m*(1-.0001)
 d=correction(sv,c,q,m,p,TAKER);c-=d*p+abs(d)*p*TAKER;q+=d
 assert abs((sv+q*m)/(sv+c+q*m)-.5)<.0002
 for s in [40000.,80000.]:
  value=500000/60000*s;e=value+c+q*s;gap=.5*e-value-q*s;pe=s*(1+.0001 if gap>0 else 1-.0001)
  delta=correction(value,c,q,s,pe,TAKER)
  assert delta>0 if s<60000 else delta<0
  nc=c-delta*pe-abs(delta)*pe*TAKER;nq=q+delta
  assert abs((value+nq*s)/(value+nc+nq*s)-.5)<.0002
 assert correction(10000.,100000.,-.1,50000.,50000.,TAKER)<=.1
 rows=[]
 start=1727827200000
 for i,price in enumerate([60000.,80000.,81000.,79000.]):
  r={'timestamp_ms':start+i*3600000}
  for prefix in ['spot','linear','mark']:
   for suffix,ratio in [('open',1),('high',1.04),('low',.96),('close',1)]:r[prefix+'_'+suffix]=price*ratio
  rows.append(r)
 b=pd.DataFrame(rows);f=pd.DataFrame([dict(timestamp_ms=start,rate=.0001),dict(timestamp_ms=start+3600000,rate=.0001)])
 result,t,settle,day,ep=simulate(b,f,Config(mode='grid',step=.005),'unit')
 assert len(settle)==1 and settle[0]['q']==t[0]['q']
 assert abs(settle[0]['payment']+settle[0]['q']*80000*.0001)<1e-10
 assert all(x['timestamp_ms']>ep[x['episode']-1]['start_ms'] for x in t if x['kind']=='maker')
 _,short_t,_,_,_=simulate(b.iloc[:3],f,Config(),'prefix')
 assert short_t==t[:len(short_t)]
 for x in t:
  rate=MAKER if x['kind']=='maker' else TAKER
  assert abs(x['fee']-abs(x['delta'])*x['execution']*rate)<1e-10
 assert result['final_short_qty']>=0
 return ['fee-aware initial net 50%','sell hedge on rise','buy hedge on fall','short-only clipping','funding on carried inventory, not new orders','no maker fills in placement bar','no future data dependence','maker/taker fee split']

def audit(result,trades,settlements,daily):
 """Independent replay exported ledger, including actual USDT and daily inventory marks."""
 q=0.;cash=0.;basis=0.;realized=0.;funding=0.;interest=0.;fees=0.
 events=[]
 for x in settlements:events.append((x['timestamp_ms'],0 if x['kind']=='funding' else 1,'settlement',x))
 for i,x in enumerate(trades):events.append((x['timestamp_ms'],2+i/1000000,'trade',x))
 for x in daily:events.append((x['timestamp_ms'],-1,'day',x))
 for ts,_,kind,x in sorted(events,key=lambda z:(z[0],z[1])):
  if kind=='settlement':
   assert abs(q-x['q'])<1e-7
   if x['kind']=='funding':
    expected=-q*x['mark']*x['rate'];assert abs(expected-x['payment'])<1e-5;funding+=expected
   else:interest-=x['payment']
   cash+=x['payment'];assert abs(cash-x['cash'])<1e-5
  elif kind=='trade':
   assert abs(q-x['pre_q'])<1e-7 and abs(cash-x['pre_cash'])<1e-5
   d=x['delta'];fee=abs(d)*x['execution']*(MAKER if x['kind']=='maker' else TAKER)*result['config']['fee_multiplier']
   assert abs(fee-x['fee'])<1e-7
   if d<0:basis+=d*x['execution']
   else:
    avg=basis/q;realized+=d*(avg-x['execution']);basis+=d*avg
   realized-=fee;fees+=fee;cash-=d*x['execution']+fee;q+=d
   if abs(q)<1e-9:q=0.;basis=0.
   assert q<=1e-8 and abs(q-x['q'])<1e-7
   assert abs(basis-x['basis'])<1e-5
   assert abs(cash+basis-(realized+funding-interest))<1e-5
  else:
   expected=x['spot_qty']*x['spot']+cash+q*x['mark']
   assert abs(expected-x['equity'])<1e-5,(result['id'],ts,expected,x['equity'])
   assert abs(-q-x['short_qty'])<1e-7 and abs(cash+basis-x['actual_usdt'])<1e-5
 last=daily[-1]
 assert abs(last['equity']-result['final_equity'])<1e-5
 assert abs(funding-result['funding_net'])<1e-5 and abs(fees-result['fees'])<1e-5
 assert abs(realized-result['perp_realized_net_fees'])<1e-5
 assert abs(interest-result['borrow_interest'])<1e-5
 return dict(id=result['id'],passed=True,trades=len(trades),settlements=len(settlements),daily_marks=len(daily))

def main():
 checks=tests();recent=pd.read_csv(ROOT/'data/bybit_5m.csv.gz');hourly=pd.read_csv(ROOT/'data/bybit_60m.csv.gz');fund=pd.read_csv(ROOT/'data/funding.csv')
 start=int(recent.timestamp_ms.iloc[0]);rhour=hourly[hourly.timestamp_ms>=start].reset_index(drop=True)
 later=hourly[hourly.timestamp_ms>=pd.Timestamp('2023-01-01',tz='UTC').value//1000000].reset_index(drop=True)
 base=Config();cases=[]
 def add(name,b,**kw):cases.append((name,b,replace(base,**kw)))
 for mode in ['spot','fixed','direct','grid']:add('recent_'+mode,recent,mode=mode)
 add('recent_grid_OHLC',recent,path='OHLC')
 add('recent_direct_OHLC',recent,mode='direct',path='OHLC')
 add('recent_grid_step025',recent,step=.0025)
 add('recent_grid_step1',recent,step=.01)
 add('recent_grid_half_fill',recent,fill_fraction=.5)
 for mode in ['direct','grid']:
  add('recent_'+mode+'_cost2',recent,mode=mode,fee_multiplier=2,slip=.0005)
  add('recent_'+mode+'_no_funding',recent,mode=mode,funding_enabled=False)
  add('recent_'+mode+'_borrow5',recent,mode=mode,borrow_apr=.05)
  add('recent_'+mode+'_borrow10',recent,mode=mode,borrow_apr=.10)
 for mode in ['spot','fixed','direct','grid']:add('long_'+mode,hourly,mode=mode)
 for mode in ['direct','grid']:add('from2023_'+mode,later,mode=mode)
 for mode in ['direct','grid']:add('recent_hourly_'+mode,rhour,mode=mode)
 results=[];audits=[];yearly=[]
 for name,b,c in cases:
  result,t,s,d,e=simulate(b,fund,c,name)
  for label,rows in [('trades',t),('settlements',s),('daily',d),('episodes',e)]:pd.DataFrame(rows).to_csv(OUT/f'{name}_{label}.csv',index=False)
  audits.append(audit(result,t,s,d));results.append(result)
  day=pd.DataFrame(d);dt=pd.to_datetime(day.timestamp_ms-1,unit='ms',utc=True);previous=CAPITAL
  for year,group in day.groupby(dt.dt.year):
   end=float(group.equity.iloc[-1]);yearly.append(dict(id=name,year=int(year),return_pct=100*(end/previous-1)));previous=end
  print(name,'end',round(result['final_equity'],2),'CAGR',round(result['cagr']*100,3),'DD',round(result['max_drawdown']*100,3),'funding',round(result['funding_net'],2),'fees',round(result['fees'],2),'interest',round(result['borrow_interest'],2),flush=True)
 (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False));pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False)
 pd.DataFrame(yearly).to_csv(ROOT/'yearly.csv',index=False)
 (ROOT/'audit.json').write_text(json.dumps(dict(unit_checks=checks,scenarios=audits,passed=True),indent=2))
 hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'engine.py',ROOT/'run.py',ROOT/'fetch_data.py',ROOT/'data/manifest.json',ROOT/'data/funding.csv',ROOT/'data/bybit_5m.csv.gz',ROOT/'data/bybit_60m.csv.gz']}
 (ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
if __name__=='__main__':main()
