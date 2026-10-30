"""Same-venue, same-period minute stress replay for the last two years."""
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
import hashlib
import io
import json
import zipfile
import numpy as np
import pandas as pd
from fetch_data import get,BASE,ROOT,klines


def part(job):
    kind,period,daily=job
    name=f'BTCUSDT-1m-{period}.zip'
    url=BASE+('/daily/' if daily else '/monthly/')+f'{kind}/BTCUSDT/1m/'+name
    folder=ROOT/'data/raw'/('minute_'+kind);folder.mkdir(exist_ok=True)
    path=folder/name;checksum=path.with_suffix('.zip.CHECKSUM')
    if not checksum.exists():checksum.write_bytes(get(url+'.CHECKSUM'))
    expected=checksum.read_text().split()[0]
    if not path.exists():
        payload=get(url);assert hashlib.sha256(payload).hexdigest()==expected
        path.write_bytes(payload)
    assert hashlib.sha256(path.read_bytes()).hexdigest()==expected
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        frame=klines(z.read(z.namelist()[0]))
    return kind,frame,dict(url=url,sha256=expected,file=str(path.relative_to(ROOT)))


def main():
    start=pd.Timestamp('2024-10-02',tz='UTC').value//10**6
    end=pd.Timestamp('2026-10-02',tz='UTC').value//10**6
    jobs=[(kind,month.strftime('%Y-%m'),False)
          for month in pd.date_range('2024-10-01','2026-09-01',freq='MS')
          for kind in ['klines','markPriceKlines']]
    jobs += [(kind,'2026-10-01',True) for kind in ['klines','markPriceKlines']]
    parts={'klines':[],'markPriceKlines':[]};sources=[]
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures={pool.submit(part,j):j for j in jobs}
        for i,f in enumerate(as_completed(futures),1):
            kind,frame,source=f.result();parts[kind].append(frame);sources.append(source)
            if i%10==0 or i==len(jobs):print('minute archives',i,'/',len(jobs),flush=True)
    merged={}
    for kind in parts:
        df=pd.concat(parts[kind],ignore_index=True).sort_values('timestamp_ms')
        df=df[df.timestamp_ms.between(start,end-1)].reset_index(drop=True)
        missing=np.setdiff1d(np.arange(start,end,60000),df.timestamp_ms)
        if len(missing):
            days=pd.to_datetime(missing,unit='ms',utc=True).strftime('%Y-%m-%d').unique()
            for day in days:
                _,patch,source=part((kind,day,True));sources.append(source)
                df=pd.concat([df,patch[patch.timestamp_ms.isin(missing)]],ignore_index=True)
            df=df.sort_values('timestamp_ms').reset_index(drop=True)
        assert not df.timestamp_ms.duplicated().any()
        assert np.array_equal(df.timestamp_ms,np.arange(start,end,60000)),(kind,len(df))
        assert (df.high >= df[['open','close']].max(axis=1)).all()
        assert (df.low <= df[['open','close']].min(axis=1)).all()
        merged[kind]=df
    bars=merged['klines'].merge(merged['markPriceKlines'].rename(columns={c:'mark_'+c for c in ['open','high','low','close']}),on='timestamp_ms',validate='one_to_one')
    path=ROOT/'data/recent_minutes.csv.gz';bars.to_csv(path,index=False)
    (ROOT/'data/minute_manifest.json').write_text(json.dumps(dict(start_ms=int(start),end_exclusive_ms=int(end),rows=len(bars),
        files={path.name:hashlib.sha256(path.read_bytes()).hexdigest()},sources=sources),indent=2))
    print('minutes accepted',len(bars),flush=True)


if __name__=='__main__':main()
