"""Independent 20/50-slot simulations on frozen one-minute data, two local processes."""
from pathlib import Path
from dataclasses import replace
from concurrent.futures import ProcessPoolExecutor, as_completed
import json, hashlib, time
import pandas as pd
from engine import Config, simulate
from audit import audit
from test_variants import run_checks

ROOT = Path(__file__).resolve().parent
PREVIOUS = ROOT.parent/'reversible_grid_4555_100_stop_20261012'
OUT = ROOT/'outputs'
BARS = FUNDING = None

def load_data():
    global BARS, FUNDING
    BARS = pd.read_csv(ROOT/'data/bybit_1m.csv.gz')
    FUNDING = pd.read_csv(ROOT/'data/funding.csv')

def execute_case(name, cfg):
    start=time.monotonic()
    print('START',name,flush=True)
    r,t,s,d,b,p=simulate(BARS,FUNDING,cfg,name)
    check=audit(r,t,s,d)
    for suffix, rows in [('trades',t),('settlements',s),('daily',d),('batches',b),('pairs',p)]:
        pd.DataFrame(rows).to_csv(OUT/f'{name}_{suffix}.csv',index=False)
    day=pd.DataFrame(d);dates=pd.to_datetime(day.timestamp_ms-1,unit='ms',utc=True)
    years=[];previous=500000.
    for year, group in day.groupby(dates.dt.year):
        end=float(group.equity.iloc[-1]);years.append(dict(id=name,year=int(year),return_pct=100*(end/previous-1)));previous=end
    print('DONE',name,'equity',round(r['final_equity'],2),'CAGR',round(r['cagr']*100,3),'DD',round(r['max_drawdown']*100,3),'pairs',r['grid_pair_count'],'seconds',round(time.monotonic()-start,1),flush=True)
    return r,check,years

def main():
    checks=run_checks();print('checks passed',len(checks),flush=True)
    source=json.loads((PREVIOUS/'source_hashes.json').read_text())
    for name in ['data/bybit_1m.csv.gz','data/funding.csv']:
        assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==source[name]
    cases=[('direct4555_minute',Config(mode='direct',low=.45,high=.55,adjustment_fraction=.05,stop_on_completion=True))]
    for levels in [20,50]:
        base=Config(levels=levels,low=.45,high=.55,adjustment_fraction=.05,stop_on_completion=True)
        cases += [(f'grid{levels}_minute',base),(f'grid{levels}_OHLC',replace(base,path='OHLC')),
                  (f'grid{levels}_half_fill',replace(base,fill_fraction=.5)),(f'grid{levels}_fee2',replace(base,fee_multiplier=2)),
                  (f'grid{levels}_borrow5',replace(base,borrow_apr=.05)),(f'grid{levels}_borrow10',replace(base,borrow_apr=.10))]
    saved={}
    with ProcessPoolExecutor(max_workers=2, initializer=load_data) as pool:
        futures={pool.submit(execute_case,name,cfg):name for name,cfg in cases}
        for future in as_completed(futures):
            name=futures[future];saved[name]=future.result()
            (ROOT/'partial_results.json').write_text(json.dumps([x[0] for x in saved.values()],indent=2,allow_nan=False))
    results=[saved[name][0] for name,cfg in cases]
    audits=[saved[name][1] for name,cfg in cases]
    years=[row for name,cfg in cases for row in saved[name][2]]
    (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False))
    pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False)
    pd.DataFrame(years).to_csv(ROOT/'yearly.csv',index=False)
    (ROOT/'audit.json').write_text(json.dumps(dict(unit_checks=checks,scenarios=audits,passed=True),indent=2))
    refs=json.loads((PREVIOUS/'results.json').read_text())
    (ROOT/'reference_results.json').write_text(json.dumps(refs,indent=2))
    pd.json_normalize(refs+results).to_csv(ROOT/'comparison.csv',index=False)
    files=['engine.py','run.py','audit.py','test_engine.py','test_4060.py','test_variants.py','validate_export.py','data/manifest.json','data/bybit_1m.csv.gz','data/funding.csv','reference_results.json']
    (ROOT/'source_hashes.json').write_text(json.dumps({f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files},indent=2))
    print('ALL',len(results),'SCENARIOS SAVED',flush=True)

if __name__=='__main__':main()
