"""Read-only Bybit public market data; no keys, account access or orders."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed
import hashlib,json,time,threading
import requests
import numpy as np
import pandas as pd
ROOT=Path(__file__).resolve().parent;RAW=ROOT/'data/raw';RAW.mkdir(parents=True,exist_ok=True)
BASE='https://api.bybit.com';END=pd.Timestamp('2026-10-02',tz='UTC').value//10**6
RECENT=pd.Timestamp('2024-10-02',tz='UTC').value//10**6
LONG=pd.Timestamp('2022-01-01',tz='UTC').value//10**6
LOCAL=threading.local()

def request(path,params,name):
 p=RAW/(name+'.json')
 if p.exists():return json.loads(p.read_text())
 if not hasattr(LOCAL,'session'):LOCAL.session=requests.Session()
 for attempt in range(6):
  try:
   r=LOCAL.session.get(BASE+path,params=params,timeout=30);r.raise_for_status();j=r.json()
   if j['retCode']!=0:raise ValueError(str(j)[:300])
   p.write_text(json.dumps(dict(path=path,params=params,response=j)))
   time.sleep(.12)
   return json.loads(p.read_text())
  except (ValueError,requests.RequestException):
   if attempt==5:raise
   time.sleep(min(2**attempt,15))

def chunk(job):
 label,interval,start,end=job
 path='/v5/market/mark-price-kline' if label=='mark' else '/v5/market/kline'
 params=dict(category='spot' if label=='spot' else 'linear',symbol='BTCUSDT',interval=str(interval),start=int(start),end=int(end-1),limit=1000)
 name=f'{label}_{interval}_{start}'
 j=request(path,params,name)
 a=j['response']['result']['list']
 b=pd.DataFrame([x[:5] for x in a],columns=['timestamp_ms','open','high','low','close']).apply(pd.to_numeric)
 b=b[b.timestamp_ms.between(start,end-1)].sort_values('timestamp_ms')
 assert len(b)==(end-start)//(interval*60000),(name,len(b),(end-start)//(interval*60000))
 return label,interval,b

def main():
 jobs=[]
 for interval,start in [(60,LONG),(5,RECENT)]:
  for label in ['spot','linear','mark']:
   step=interval*60000*1000
   for lo in range(start,END,step):jobs.append((label,interval,lo,min(lo+step,END)))
 parts={};
 with ThreadPoolExecutor(max_workers=6) as pool:
  for i,f in enumerate(as_completed([pool.submit(chunk,j) for j in jobs]),1):
   label,interval,b=f.result();parts.setdefault((label,interval),[]).append(b)
   if i%50==0 or i==len(jobs):print('Bybit price chunks',i,'/',len(jobs),flush=True)
 files={}
 for interval,start in [(60,LONG),(5,RECENT)]:
  merged=None
  for label in ['spot','linear','mark']:
   b=pd.concat(parts[(label,interval)],ignore_index=True).sort_values('timestamp_ms').reset_index(drop=True)
   assert not b.timestamp_ms.duplicated().any()
   assert np.array_equal(b.timestamp_ms.to_numpy(),np.arange(start,END,interval*60000))
   assert (b.low>0).all() and (b.high>=b[['open','close']].max(axis=1)).all() and (b.low<=b[['open','close']].min(axis=1)).all()
   b=b.rename(columns={c:label+'_'+c for c in ['open','high','low','close']})
   merged=b if merged is None else merged.merge(b,on='timestamp_ms',validate='one_to_one')
  p=ROOT/f'data/bybit_{interval}m.csv.gz';merged.to_csv(p,index=False);files[p.name]=dict(rows=len(merged),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
 funding=[];end=END-1
 while end>=LONG:
  j=request('/v5/market/funding/history',dict(category='linear',symbol='BTCUSDT',endTime=int(end),limit=200),f'funding_{end}')
  rows=j['response']['result']['list'];assert rows
  funding.extend(rows);end=min(int(x['fundingRateTimestamp']) for x in rows)-1
  print('funding events',len(funding),flush=True)
 f=pd.DataFrame([dict(timestamp_ms=int(x['fundingRateTimestamp']),rate=float(x['fundingRate'])) for x in funding])
 f=f[f.timestamp_ms.between(LONG,END-1)].sort_values('timestamp_ms').reset_index(drop=True)
 assert not f.timestamp_ms.duplicated().any()
 assert (f.timestamp_ms%(5*60000)==0).all()
 p=ROOT/'data/funding.csv';f.to_csv(p,index=False);files[p.name]=dict(rows=len(f),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
 manifest=dict(source='Bybit public v5 market API, BTCUSDT spot/USDT perpetual/mark/funding; same venue',long_start_ms=LONG,recent_start_ms=RECENT,end_exclusive_ms=END,downloaded_utc=pd.Timestamp.now(tz='UTC').isoformat(),files=files,raw_sources={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in RAW.glob('*.json')})
 (ROOT/'data/manifest.json').write_text(json.dumps(manifest,indent=2));print('accepted',files,flush=True)
if __name__=='__main__':main()
