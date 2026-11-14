"""Isolated two-year study: frozen pre-period ATH, no future ATH anchoring."""
from pathlib import Path
from dataclasses import replace
from concurrent.futures import ProcessPoolExecutor,as_completed
import multiprocessing as mp
import json,hashlib,time
import pandas as pd
from engine import Config,simulate
from audit import audit
from test_target import run_checks
from prepare_data import load_period
ROOT=Path(__file__).resolve().parent
BARS=FUNDING=None

def execute_case(name,cfg):
 start=time.monotonic();print('START',name,flush=True)
 r,t,s,d,b,p=simulate(BARS,FUNDING,cfg,name)
 check=audit(r,t,s,d)
 for suffix,rows in [('trades',t),('settlements',s),('daily',d),('batches',b),('pairs',p)]:pd.DataFrame(rows).to_csv(ROOT/'outputs'/f'{name}_{suffix}.csv',index=False)
 day=pd.DataFrame(d);dates=pd.to_datetime(day.timestamp_ms-1,unit='ms',utc=True)
 yearly=[];previous=500000.
 for year,group in day.groupby(dates.dt.year):
  end=float(group.equity.iloc[-1]);yearly.append({'id':name,'year':int(year),'return_pct':100*(end/previous-1)});previous=end
 print('DONE',name,'equity',round(r['final_equity'],2),'CAGR',round(r['cagr']*100,3),'DD',round(r['max_drawdown']*100,3),'pairs',r['grid_pair_count'],'seconds',round(time.monotonic()-start,1),flush=True)
 return r,check,yearly

def main():
 global BARS,FUNDING
 checks=run_checks();print('CHECKS',len(checks),flush=True)
 manifest=json.loads((ROOT/'period_manifest.json').read_text())
 for f in ['bybit_1m.csv.gz','funding.csv']:assert hashlib.sha256((ROOT/'data'/f).read_bytes()).hexdigest()==manifest['files'][f]['sha256']
 BARS,FUNDING,peak,peak_ms=load_period();assert peak==manifest['initial_ath'] and peak_ms==manifest['initial_ath_timestamp_ms']
 base=Config(levels=50,target_mode='frozen_ath',initial_ath=peak,threshold=.05,adjustment_fraction=.05,allow_perp_long=False)
 fixed=Config(levels=50,target_mode='fixed',low=.45,high=.55,adjustment_fraction=.05,allow_perp_long=False)
 cases=[('frozen_grid50',base),('frozen_direct',replace(base,mode='direct')),('fixed50_grid50',fixed),('fixed50_direct',replace(fixed,mode='direct')),('spot_hold',Config(mode='spot')),
        ('frozen_grid50_OHLC',replace(base,path='OHLC')),('frozen_grid50_half',replace(base,fill_fraction=.5)),('frozen_grid50_fee2',replace(base,fee_multiplier=2)),('frozen_grid50_borrow5',replace(base,borrow_apr=.05)),('frozen_grid50_allow_long',replace(base,allow_perp_long=True))]
 saved={}
 with ProcessPoolExecutor(max_workers=4,mp_context=mp.get_context('fork')) as pool:
  futures={pool.submit(execute_case,name,cfg):name for name,cfg in cases}
  for future in as_completed(futures):
   saved[futures[future]]=future.result();(ROOT/'partial_results.json').write_text(json.dumps([x[0] for x in saved.values()],indent=2,allow_nan=False))
 results=[saved[name][0] for name,cfg in cases]
 (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False));pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False)
 (ROOT/'audit.json').write_text(json.dumps({'unit_checks':checks,'scenarios':[saved[name][1] for name,cfg in cases],'passed':True},indent=2))
 pd.DataFrame([y for name,cfg in cases for y in saved[name][2]]).to_csv(ROOT/'yearly.csv',index=False)
 files=['engine.py','audit.py','run.py','prepare_data.py','validate_export.py','test_engine.py','test_regression.py','test_target.py','period_manifest.json','data/manifest.json','data/bybit_1m.csv.gz','data/funding.csv']
 (ROOT/'source_hashes.json').write_text(json.dumps({f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files},indent=2))
 print('ALL',len(results),'SCENARIOS SAVED',flush=True)
if __name__=='__main__':main()
