"""Financing sensitivity after observed negative actual-USDT ledger balances."""
from pathlib import Path
import json,hashlib,time
import pandas as pd
from engine import Config,simulate
from audit import audit
ROOT=Path(__file__).resolve().parent

def main():
 results=json.loads((ROOT/'results.json').read_text());aud=json.loads((ROOT/'audit.json').read_text());yearly=pd.read_csv(ROOT/'yearly.csv').to_dict('records')
 b=pd.read_csv(ROOT/'data/bybit_1m.csv.gz');f=pd.read_csv(ROOT/'data/funding.csv')
 for apr in [.05,.10]:
  name=f'grid100_borrow{int(apr*100)}';print('START',name,flush=True)
  cfg=Config(borrow_apr=apr);r,t,s,d,batches,pairs=simulate(b,f,cfg,name);aud['scenarios'].append(audit(r,t,s,d))
  for suffix,rows in [('trades',t),('settlements',s),('daily',d),('batches',batches),('pairs',pairs)]:pd.DataFrame(rows).to_csv(ROOT/'outputs'/f'{name}_{suffix}.csv',index=False)
  results.append(r);day=pd.DataFrame(d);dt=pd.to_datetime(day.timestamp_ms-1,unit='ms',utc=True);prev=500000.
  for year,group in day.groupby(dt.dt.year):
   end=float(group.equity.iloc[-1]);yearly.append(dict(id=name,year=int(year),return_pct=100*(end/prev-1)));prev=end
  print('DONE',name,'equity',round(r['final_equity'],2),'CAGR',round(r['cagr']*100,3),'DD',round(r['max_drawdown']*100,3),'interest',round(r['borrow_interest'],2),'debt',round(r['max_negative_usdt'],2),flush=True)
 (ROOT/'results.json').write_text(json.dumps(results,indent=2,allow_nan=False));pd.json_normalize(results).to_csv(ROOT/'results.csv',index=False);pd.DataFrame(yearly).to_csv(ROOT/'yearly.csv',index=False);(ROOT/'audit.json').write_text(json.dumps(aud,indent=2))
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['extra_scenarios.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
if __name__=='__main__':main()
