"""Isolated two-year study: frozen pre-period ATH, no future ATH anchoring."""
from pathlib import Path
from dataclasses import replace
from concurrent.futures import ProcessPoolExecutor,as_completed
import multiprocessing as mp
import json,hashlib,time,shutil
import pandas as pd
from engine import Config,simulate
from audit import audit
from test_10pct import run_checks
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
 base=Config(levels=50,target_mode='frozen_ath',initial_ath=peak,threshold=.10,adjustment_fraction=.10,fill_fraction=1.,allow_perp_long=False)
 cases=[('frozen_grid50_10pct',base),('frozen_direct_10pct',replace(base,mode='direct'))]
 saved={}
 with ProcessPoolExecutor(max_workers=2,mp_context=mp.get_context('fork')) as pool:
  futures={pool.submit(execute_case,name,cfg):name for name,cfg in cases}
  for future in as_completed(futures):
   saved[futures[future]]=future.result();(ROOT/'partial_results.json').write_text(json.dumps([x[0] for x in saved.values()],indent=2,allow_nan=False))
 results=[saved[name][0] for name,cfg in cases]
 scenario_checks=[saved[name][1] for name,cfg in cases]
 yearly=[y for name,cfg in cases for y in saved[name][2]]
 previous=ROOT.parent/'ath_target_moving50_2y_20261014'
 old={x['id']:x for x in json.loads((previous/'results.json').read_text())}
 provenance={}
 for source_name,new_name in [('frozen_grid50','frozen_grid50_5pct'),('frozen_direct','frozen_direct_5pct')]:
  result=dict(old[source_name],id=new_name)
  assert result['config']['fill_fraction']==1. and result['config']['threshold']==.05
  for suffix in ['trades','settlements','daily','batches','pairs']:
   source=previous/'outputs'/f'{source_name}_{suffix}.csv';destination=ROOT/'outputs'/f'{new_name}_{suffix}.csv'
   shutil.copy2(source,destination);digest=hashlib.sha256(source.read_bytes()).hexdigest()
   assert hashlib.sha256(destination.read_bytes()).hexdigest()==digest
   provenance[f'outputs/{new_name}_{suffix}.csv']={'source':str(source),'sha256':digest}
  results.append(result)
  prior_years=pd.read_csv(previous/'yearly.csv')
  for y in prior_years[prior_years.id==source_name].to_dict('records'):y['id']=new_name;yearly.append(y)
 (ROOT/'reference_provenance.json').write_text(json.dumps(provenance,indent=2))
 (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False));pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False)
 (ROOT/'audit.json').write_text(json.dumps({'unit_checks':checks,'new_scenarios':scenario_checks,'passed':True},indent=2))
 pd.DataFrame(yearly).to_csv(ROOT/'yearly.csv',index=False)
 files=['engine.py','audit.py','run.py','prepare_data.py','validate_export.py','test_engine.py','test_regression.py','test_target.py','test_10pct.py','reference_provenance.json','period_manifest.json','data/manifest.json','data/bybit_1m.csv.gz','data/funding.csv']
 (ROOT/'source_hashes.json').write_text(json.dumps({f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files},indent=2))
 print('ALL',len(results),'COMPARISONS SAVED (2 new, 2 verified references)' ,flush=True)
if __name__=='__main__':main()
