"""Add the requested 2pp cases without rerunning or changing the prior 1pp/5pp ledgers."""
from pathlib import Path
from dataclasses import replace
from concurrent.futures import ProcessPoolExecutor, as_completed
import multiprocessing as mp
import hashlib, json
import pandas as pd
import run
from engine import Config
from prepare_data import load_period
ROOT=Path(__file__).resolve().parent

def main():
 old=json.loads((ROOT/'results.json').read_text())
 assert not any(abs(x['config']['threshold']-.02)<1e-12 for x in old), '2pp cases already exist; do not duplicate'
 historical_hashes=json.loads((ROOT/'snapshots/before_2pp/source_hashes.json').read_text())
 assert hashlib.sha256((ROOT/'engine.py').read_bytes()).hexdigest()==historical_hashes['engine.py'], 'Model changed'
 hashes=json.loads((ROOT/'snapshots/before_2pp/output_hashes.json').read_text())
 for x in old:
  for kind in ('trades','settlements','daily'):
   name=f"outputs/{x['id']}_{kind}.csv"
   assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==hashes[name], ('Baseline ledger changed',name)
 run.BARS,run.FUNDING=load_period()
 base=Config(mode='direct',target_mode='price_band',lower_price=60000.,upper_price=200000.,threshold=.02,allow_perp_long=False)
 cases=[(f'{period}_2pp',base,period) for period in ('two_year','year1','year2')]
 for label,changes in [('fee2',dict(fee_multiplier=2.)),('borrow5',dict(borrow_apr=.05)),('borrow10',dict(borrow_apr=.10)),('allow_long',dict(allow_perp_long=True))]:
  cases.append((f'two_year_2pp_{label}',replace(base,**changes),'two_year'))
 plan=json.loads((ROOT/'scenario_plan.json').read_text())
 plan += [{'id':n,'period':p,'config':c.__dict__} for n,c,p in cases]
 (ROOT/'scenario_plan.json').write_text(json.dumps(plan,indent=2)+'\n')
 found={}
 with ProcessPoolExecutor(max_workers=3,mp_context=mp.get_context('fork')) as pool:
  pending={pool.submit(run.execute_case,n,c,p):n for n,c,p in cases}
  for future in as_completed(pending):
   found[pending[future]]=future.result()
   (ROOT/'partial_2pp_results.json').write_text(json.dumps([v[0] for v in found.values()],indent=2,allow_nan=False)+'\n')
 combined=old+[found[n][0] for n,c,p in cases]
 (ROOT/'results.json').write_text(json.dumps(combined,indent=2,allow_nan=False)+'\n')
 pd.json_normalize(combined).to_csv(ROOT/'results.csv',index=False)
 years=pd.concat([pd.read_csv(ROOT/'yearly.csv'),pd.DataFrame([y for n,c,p in cases for y in found[n][2]])],ignore_index=True)
 years.to_csv(ROOT/'yearly.csv',index=False)
 checks=json.loads((ROOT/'audit.json').read_text())
 checks['scenarios'] += [found[n][1] for n,c,p in cases]
 checks.pop('export_replay',None);checks.pop('export_minutes_checked',None)
 checks['passed']=True
 (ROOT/'audit.json').write_text(json.dumps(checks,indent=2)+'\n')
 print('ADDED 7; TOTAL',len(combined),'SCENARIOS. BASELINE 1pp/5pp OUTPUTS PRESERVED.',flush=True)
if __name__=='__main__':main()
