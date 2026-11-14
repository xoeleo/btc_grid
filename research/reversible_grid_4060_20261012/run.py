"""User-confirmed 40-60 thresholds; fixed 10% adjustment split into 20 units."""
from pathlib import Path
from dataclasses import replace
import json,hashlib,time
import pandas as pd
from engine import Config,simulate
from audit import audit
from test_4060 import run_checks
ROOT=Path(__file__).resolve().parent;PREVIOUS=ROOT.parent/'reversible_grid_20_20261012';OUT=ROOT/'outputs'

def main():
 checks=run_checks();print('checks passed',len(checks),flush=True)
 sources=json.loads((PREVIOUS/'source_hashes.json').read_text())
 for name in ['data/bybit_1m.csv.gz','data/funding.csv']:
  assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==sources[name]
 b=pd.read_csv(ROOT/'data/bybit_1m.csv.gz');fund=pd.read_csv(ROOT/'data/funding.csv');base=Config(levels=20,low=.40,high=.60,adjustment_fraction=.10)
 cases=[('direct4060_minute',replace(base,mode='direct')),('grid4060_minute',base),('grid4060_OHLC',replace(base,path='OHLC')),('grid4060_half_fill',replace(base,fill_fraction=.5)),('grid4060_fee2',replace(base,fee_multiplier=2)),('grid4060_borrow5',replace(base,borrow_apr=.05)),('grid4060_borrow10',replace(base,borrow_apr=.10))]
 results=[];audits=[];yearly=[]
 for name,cfg in cases:
  start=time.monotonic();print('START',name,flush=True)
  r,t,s,d,batches,pairs=simulate(b,fund,cfg,name);audits.append(audit(r,t,s,d))
  for suffix,rows in [('trades',t),('settlements',s),('daily',d),('batches',batches),('pairs',pairs)]:pd.DataFrame(rows).to_csv(OUT/f'{name}_{suffix}.csv',index=False)
  results.append(r);day=pd.DataFrame(d);dt=pd.to_datetime(day.timestamp_ms-1,unit='ms',utc=True);prev=500000.
  for year,group in day.groupby(dt.dt.year):
   end=float(group.equity.iloc[-1]);yearly.append(dict(id=name,year=int(year),return_pct=100*(end/prev-1)));prev=end
  (ROOT/'partial_results.json').write_text(json.dumps(results,indent=2,allow_nan=False))
  print('DONE',name,'equity',round(r['final_equity'],2),'CAGR',round(r['cagr']*100,3),'DD',round(r['max_drawdown']*100,3),'pairs',r['grid_pair_count'],'grid_profit',round(r['matched_grid_profit'],2),'funding',round(r['funding_net'],2),'fees',round(r['fees'],2),'interest',round(r['borrow_interest'],2),'seconds',round(time.monotonic()-start,1),flush=True)
 (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False));pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False);pd.DataFrame(yearly).to_csv(ROOT/'yearly.csv',index=False)
 (ROOT/'audit.json').write_text(json.dumps(dict(unit_checks=checks,scenarios=audits,passed=True),indent=2))
 prior=json.loads((PREVIOUS/'results.json').read_text());refs=[x for x in prior if x['id'] in ['grid20_minute','grid20_borrow5','grid20_borrow10']]
 (ROOT/'reference_results.json').write_text(json.dumps(refs,indent=2));pd.json_normalize(refs+results).to_csv(ROOT/'comparison.csv',index=False)
 paths=[ROOT/'engine.py',ROOT/'run.py',ROOT/'audit.py',ROOT/'test_engine.py',ROOT/'test_4060.py',ROOT/'validate_export.py',ROOT/'data/manifest.json',ROOT/'data/bybit_1m.csv.gz',ROOT/'data/funding.csv',ROOT/'reference_results.json']
 (ROOT/'source_hashes.json').write_text(json.dumps({str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},indent=2))
if __name__=='__main__':main()
