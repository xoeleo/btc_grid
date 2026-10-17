"""Resume-safe public Bybit downloads, extending frozen recent minute data to five years."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib, json, threading, time
import requests
import numpy as np
import pandas as pd

ROOT=Path(__file__).resolve().parent
RAW=ROOT/'data/raw'
RECENT_ROOT=ROOT.parent/'reversible_grid_100_20261012/data'
FUND_ROOT=ROOT.parent/'spot_short_rebalance_20261012/data'
START=int(pd.Timestamp('2021-10-02',tz='UTC').timestamp()*1000)
RECENT=int(pd.Timestamp('2024-10-02',tz='UTC').timestamp()*1000)
END=int(pd.Timestamp('2026-10-02',tz='UTC').timestamp()*1000)
BASE='https://api.bybit.com'
LOCAL=threading.local()

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def request(path,params,name):
    dest=RAW/(name+'.json')
    if dest.exists():
        j=json.loads(dest.read_text());assert j['path']==path and j['params']==params
        assert j['response']['retCode']==0
        return j
    if not hasattr(LOCAL,'session'):LOCAL.session=requests.Session()
    for attempt in range(8):
        try:
            r=LOCAL.session.get(BASE+path,params=params,timeout=25);r.raise_for_status();j=r.json()
            if j.get('retCode')!=0:raise ValueError(str(j)[:300])
            record=dict(path=path,params=params,response=j)
            tmp=dest.with_suffix('.tmp');tmp.write_text(json.dumps(record));tmp.replace(dest)
            time.sleep(.1)
            return record
        except (requests.RequestException,ValueError):
            if attempt==7:raise
            time.sleep(min(2**attempt,15))

def chunk(job):
    label,start,end=job
    path='/v5/market/mark-price-kline' if label=='mark' else '/v5/market/kline'
    params=dict(category='spot' if label=='spot' else 'linear',symbol='BTCUSDT',interval='1',start=start,end=end-1,limit=1000)
    name=f'{label}_1_{start}'
    record=request(path,params,name)
    rows=record['response']['result']['list']
    frame=pd.DataFrame([x[:5] for x in rows],columns=['timestamp_ms','open','high','low','close']).apply(pd.to_numeric)
    frame=frame[frame.timestamp_ms.between(start,end-1)].sort_values('timestamp_ms')
    assert np.array_equal(frame.timestamp_ms.to_numpy(),np.arange(start,end,60000)),(name,len(frame),(end-start)//60000)
    assert (frame.low>0).all() and (frame.high>=frame[['open','close']].max(axis=1)).all() and (frame.low<=frame[['open','close']].min(axis=1)).all()
    return label,frame

def main():
    RAW.mkdir(parents=True,exist_ok=True)
    previous=json.loads((RECENT_ROOT/'manifest.json').read_text())
    assert sha(RECENT_ROOT/'bybit_1m.csv.gz')==previous['files']['bybit_1m.csv.gz']['sha256']
    fund_manifest=json.loads((FUND_ROOT/'manifest.json').read_text())
    assert sha(FUND_ROOT/'funding.csv')==fund_manifest['files']['funding.csv']['sha256']
    jobs=[(label,lo,min(lo+60000000,RECENT)) for label in ['spot','linear','mark'] for lo in range(START,RECENT,60000000)]
    parts={label:[] for label in ['spot','linear','mark']}
    print('missing historical price chunks',len(jobs),flush=True)
    with ThreadPoolExecutor(max_workers=16) as pool:
        futures=[pool.submit(chunk,j) for j in jobs]
        for i,f in enumerate(as_completed(futures),1):
            label,frame=f.result();parts[label].append(frame)
            if i%100==0 or i==len(jobs):print('accepted price chunks',i,'/',len(jobs),flush=True)
    merged=None
    for label in ['spot','linear','mark']:
        frame=pd.concat(parts[label],ignore_index=True).sort_values('timestamp_ms').reset_index(drop=True)
        assert np.array_equal(frame.timestamp_ms,np.arange(START,RECENT,60000))
        frame=frame.rename(columns={c:label+'_'+c for c in ['open','high','low','close']})
        merged=frame if merged is None else merged.merge(frame,on='timestamp_ms',validate='one_to_one')
    recent=pd.read_csv(RECENT_ROOT/'bybit_1m.csv.gz')
    assert np.array_equal(recent.timestamp_ms,np.arange(RECENT,END,60000))
    merged=pd.concat([merged,recent],ignore_index=True).sort_values('timestamp_ms').reset_index(drop=True)
    assert np.array_equal(merged.timestamp_ms,np.arange(START,END,60000))
    assert not merged.isna().any().any()
    for label in ['spot','linear','mark']:
        assert (merged[label+'_high']>=merged[[label+'_open',label+'_close']].max(axis=1)).all()
        assert (merged[label+'_low']<=merged[[label+'_open',label+'_close']].min(axis=1)).all()
    bars_path=ROOT/'data/bybit_1m.csv.gz'
    merged.to_csv(bars_path,index=False)
    old_fund=pd.read_csv(FUND_ROOT/'funding.csv')
    boundary=int(old_fund.timestamp_ms.min());end=boundary-1;funding=[]
    while end>=START:
        record=request('/v5/market/funding/history',dict(category='linear',symbol='BTCUSDT',endTime=end,limit=200),f'funding_{end}')
        rows=record['response']['result']['list'];assert rows
        funding.extend(rows);end=min(int(x['fundingRateTimestamp']) for x in rows)-1
        print('new historical funding events downloaded',len(funding),flush=True)
    early=pd.DataFrame([dict(timestamp_ms=int(x['fundingRateTimestamp']),rate=float(x['fundingRate'])) for x in funding])
    early=early[early.timestamp_ms.between(START,boundary-1)]
    fund=pd.concat([early,old_fund],ignore_index=True).sort_values('timestamp_ms').reset_index(drop=True)
    fund=fund[fund.timestamp_ms.between(START,END-1)].reset_index(drop=True)
    assert np.array_equal(fund.timestamp_ms,np.arange(START,END,8*3600000)),('funding gaps',len(fund))
    fund_path=ROOT/'data/funding.csv';fund.to_csv(fund_path,index=False)
    manifest=dict(source='Bybit public v5 market API; same venue spot/perpetual/mark/funding',start_ms=START,end_exclusive_ms=END,interval_minutes=1,rows=len(merged),gaps=0,duplicates=0,funding_rows=len(fund),downloaded_utc=pd.Timestamp.now(tz='UTC').isoformat(),files={'bybit_1m.csv.gz':dict(rows=len(merged),sha256=sha(bars_path)),'funding.csv':dict(rows=len(fund),sha256=sha(fund_path))},reused_sources={'recent_minutes':dict(path=str(RECENT_ROOT/'bybit_1m.csv.gz'),sha256=sha(RECENT_ROOT/'bybit_1m.csv.gz')),'funding_since_2022':dict(path=str(FUND_ROOT/'funding.csv'),sha256=sha(FUND_ROOT/'funding.csv'))},raw_sources={p.name:sha(p) for p in RAW.glob('*.json')})
    (ROOT/'data/manifest.json').write_text(json.dumps(manifest,indent=2))
    print('FIVE YEARS ACCEPTED',len(merged),'minute rows',len(fund),'funding events',flush=True)

if __name__=='__main__':main()
