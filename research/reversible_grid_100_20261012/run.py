from pathlib import Path
from dataclasses import replace
import json,hashlib,time
import pandas as pd
from engine import Config,simulate
from audit import audit
from test_engine import run_tests
ROOT=Path(__file__).resolve().parent;OUT=ROOT/'outputs';OUT.mkdir(exist_ok=True)

def main():
 checks=run_tests();print('unit checks passed',len(checks),flush=True)
 recent=pd.read_csv(ROOT/'data/bybit_1m.csv.gz');fund=pd.read_csv(ROOT/'data/funding.csv')
 coarse=pd.read_csv(ROOT.parent/'spot_short_rebalance_20261012/data/bybit_5m.csv.gz')
 cfg=Config();cases=[('direct_minute',recent,replace(cfg,mode='direct')),('grid100_minute',recent,cfg),('grid100_OHLC',recent,replace(cfg,path='OHLC')),('grid100_half_fill',recent,replace(cfg,fill_fraction=.5)),('grid100_fee2',recent,replace(cfg,fee_multiplier=2)),('grid100_penetration5bp',recent,replace(cfg,penetration=.0005)),('grid100_delay2bars',recent,replace(cfg,rearm_delay_bars=2)),('grid100_no_funding',recent,replace(cfg,funding_enabled=False)),('direct_no_funding',recent,replace(cfg,mode='direct',funding_enabled=False)),('direct_hourly_trigger',recent,replace(cfg,mode='direct',trigger_minutes=60)),('grid100_5minute',coarse,cfg)]
 results=[];audits=[];yearly=[]
 for name,b,c in cases:
  print('START',name,flush=True);start=time.monotonic()
  result,t,s,d,batches,pairs=simulate(b,fund,c,name)
  audits.append(audit(result,t,s,d))
  for suffix,rows in [('trades',t),('settlements',s),('daily',d),('batches',batches),('pairs',pairs)]:pd.DataFrame(rows).to_csv(OUT/f'{name}_{suffix}.csv',index=False)
  results.append(result);day=pd.DataFrame(d);dt=pd.to_datetime(day.timestamp_ms-1,unit='ms',utc=True);prev=500000.
  for year,group in day.groupby(dt.dt.year):
   end=float(group.equity.iloc[-1]);yearly.append(dict(id=name,year=int(year),return_pct=100*(end/prev-1)));prev=end
  (ROOT/'partial_results.json').write_text(json.dumps(results,indent=2,allow_nan=False))
  print('DONE',name,'equity',round(result['final_equity'],2),'CAGR',round(result['cagr']*100,3),'DD',round(result['max_drawdown']*100,3),'grid pairs',result['grid_pair_count'],'matched profit',round(result['matched_grid_profit'],2),'funding',round(result['funding_net'],2),'fees',round(result['fees'],2),'seconds',round(time.monotonic()-start,1),flush=True)
 (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False));pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False);pd.DataFrame(yearly).to_csv(ROOT/'yearly.csv',index=False)
 (ROOT/'audit.json').write_text(json.dumps(dict(unit_checks=checks,scenarios=audits,passed=True),indent=2))
 paths=[ROOT/'engine.py',ROOT/'run.py',ROOT/'fetch_data.py',ROOT/'audit.py',ROOT/'test_engine.py',ROOT/'data/manifest.json',ROOT/'data/bybit_1m.csv.gz',ROOT/'data/funding.csv']
 hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths};(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
if __name__=='__main__':main()
