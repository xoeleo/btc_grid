from pathlib import Path
from dataclasses import replace
from concurrent.futures import ProcessPoolExecutor,as_completed
import multiprocessing as mp
import pandas as pd
import json,hashlib,time
from engine import Config,simulate
from audit import audit
from prepare_data import load_period
from test_funding import run_checks
ROOT=Path(__file__).resolve().parent
BARS=FUNDING=None

def execute_case(name,cfg):
 start=time.monotonic();print('START',name,flush=True)
 r,t,s,d,b,p=simulate(BARS,FUNDING,cfg,name);check=audit(r,t,s,d)
 for suffix,rows in [('trades',t),('settlements',s),('daily',d),('batches',b),('pairs',p)]:pd.DataFrame(rows).to_csv(ROOT/'outputs'/f'{name}_{suffix}.csv',index=False)
 print('DONE',name,'equity',round(r['final_equity'],2),'annual',round(r['cagr']*100,3),'drawdown',round(r['max_drawdown']*100,3),'funding_batches',r['funding_batches'],'seconds',round(time.monotonic()-start,1),flush=True)
 return r,check

def main():
 global BARS,FUNDING
 checks=run_checks();print('CHECKS',len(checks),flush=True)
 m=json.loads((ROOT/'period_manifest.json').read_text())
 for f in ['bybit_1m.csv.gz','funding.csv']:assert hashlib.sha256((ROOT/'data'/f).read_bytes()).hexdigest()==m['files'][f]['sha256']
 BARS,FUNDING,peak,peak_ms=load_period()
 all_f=pd.read_csv(ROOT/'data/funding.csv');previous=all_f[all_f.timestamp_ms<m['start_ms']].iloc[-1]
 metadata={'initial_known_rate':float(previous.rate),'initial_known_timestamp_ms':int(previous.timestamp_ms),'above_threshold_records':int((FUNDING.rate>.0002).sum()),'trigger_threshold':.0002,'signal':'most recently settled funding rate; never next settlement rate'}
 (ROOT/'funding_signal_manifest.json').write_text(json.dumps(metadata,indent=2))
 base=Config(levels=50,target_mode='frozen_ath',initial_ath=peak,threshold=.1,adjustment_fraction=.1,fill_fraction=1.,initial_known_funding=float(previous.rate),initial_known_funding_ms=int(previous.timestamp_ms))
 cases=[('base_10pct',base),('netzero_tp5',replace(base,funding_overlay=True,funding_levels=100,funding_fraction=1.,funding_completion_net_zero=True,funding_take_profit=.05)),('netzero_tp5_OHLC',replace(base,funding_overlay=True,funding_levels=100,funding_fraction=1.,funding_completion_net_zero=True,funding_take_profit=.05,path='OHLC'))]
 saved={}
 with ProcessPoolExecutor(max_workers=3,mp_context=mp.get_context('fork')) as pool:
  futures={pool.submit(execute_case,name,cfg):name for name,cfg in cases}
  for future in as_completed(futures):
   saved[futures[future]]=future.result();(ROOT/'partial_results.json').write_text(json.dumps([x[0] for x in saved.values()],indent=2,allow_nan=False))
 results=[saved[name][0] for name,cfg in cases]
 original=json.loads((ROOT.parent/'ath_target_moving50_10pct_2y_20261014/results.json').read_text())
 reference=next(x for x in original if x['id']=='frozen_grid50_10pct')
 fields=['final_equity','cagr','max_drawdown','funding_net','fees','matched_grid_profit','final_short_qty','maker_fills','batches_completed']
 for key in fields:assert abs(results[0][key]-reference[key])<1e-7,(key,results[0][key],reference[key])
 (ROOT/'baseline_regression.json').write_text(json.dumps({'passed':True,'reference':'../ath_target_moving50_10pct_2y_20261014/results.json','fields':fields},indent=2))
 (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False));pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False)
 (ROOT/'audit.json').write_text(json.dumps({'unit_checks':checks,'scenarios':[saved[name][1] for name,cfg in cases],'baseline_regression':True,'passed':True},indent=2))
 files=['engine.py','audit.py','run.py','prepare_data.py','test_engine.py','test_funding.py','validate_export.py','period_manifest.json','funding_signal_manifest.json','data/manifest.json','data/bybit_1m.csv.gz','data/funding.csv']
 (ROOT/'source_hashes.json').write_text(json.dumps({f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files},indent=2));print('ALL THREE SCENARIOS SAVED',flush=True)
if __name__=='__main__':main()
