"""Public Binance SPOT data, separate from derivative research."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed
import datetime as dt
import hashlib
import io
import json
import threading
import time
import zipfile
import numpy as np
import pandas as pd
import requests

ROOT=Path(__file__).resolve().parent
DATA=ROOT/'data';RAW=DATA/'raw'
BASE='https://data.binance.vision/data/spot'
START=pd.Timestamp('2018-01-01',tz='UTC');END=pd.Timestamp('2026-10-02',tz='UTC')
LOCAL=threading.local()


def get(url):
    if not hasattr(LOCAL,'session'):LOCAL.session=requests.Session()
    for attempt in range(4):
        try:
            r=LOCAL.session.get(url,timeout=25);r.raise_for_status();return r.content
        except requests.RequestException:
            if attempt==3:raise
            time.sleep(2**attempt)


def parse(payload):
    header=payload.splitlines()[0].split(b',')[0]
    df=pd.read_csv(io.BytesIO(payload),header=0 if header==b'open_time' else None).iloc[:,[0,1,2,3,4,6]]
    df.columns=['timestamp_ms','open','high','low','close','close_timestamp_ms'];df=df.apply(pd.to_numeric)
    df.timestamp_ms=np.where(df.timestamp_ms>=10**15,df.timestamp_ms//1000,df.timestamp_ms).astype('int64')
    df.close_timestamp_ms=np.where(df.close_timestamp_ms>=10**15,df.close_timestamp_ms//1000,df.close_timestamp_ms).astype('int64')
    return df


def archive(job):
    period,daily=job;name=f'BTCUSDT-1h-{period}.zip'
    url=BASE+('/daily/' if daily else '/monthly/')+f'klines/BTCUSDT/1h/{name}'
    path=RAW/name;checksum=path.with_suffix('.zip.CHECKSUM')
    if not checksum.exists():checksum.write_bytes(get(url+'.CHECKSUM'))
    expected=checksum.read_text().split()[0]
    if not path.exists():
        payload=get(url);assert hashlib.sha256(payload).hexdigest()==expected
        path.write_bytes(payload)
    assert hashlib.sha256(path.read_bytes()).hexdigest()==expected
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        df=parse(z.read(z.namelist()[0]))
    return df,dict(url=url,file=str(path.relative_to(ROOT)),sha256=expected)


def main():
    RAW.mkdir(parents=True,exist_ok=True)
    jobs=[(month.strftime('%Y-%m'),False) for month in pd.date_range(START,pd.Timestamp('2026-09-01',tz='UTC'),freq='MS')]
    jobs += [('2026-10-01',True)]
    parts,sources=[],[]
    with ThreadPoolExecutor(max_workers=8) as pool:
        for i,f in enumerate(as_completed([pool.submit(archive,j) for j in jobs]),1):
            df,source=f.result();parts.append(df);sources.append(source)
            if i%20==0 or i==len(jobs):print('spot archives verified',i,'/',len(jobs),flush=True)
    start,end=START.value//10**6,END.value//10**6
    bars=pd.concat(parts,ignore_index=True).sort_values('timestamp_ms')
    bars=bars[bars.timestamp_ms.between(start,end-1)].reset_index(drop=True)
    expected=np.arange(start,end,3600000)
    missing=np.setdiff1d(expected,bars.timestamp_ms)
    # Replace affected UTC days entirely, never mix overlapping monthly/daily bins.
    affected=np.concatenate([missing,bars.loc[bars.timestamp_ms%3600000!=0,'timestamp_ms'].to_numpy()])
    if len(affected):
        for day in pd.to_datetime(affected,unit='ms',utc=True).strftime('%Y-%m-%d').unique():
            patch,source=archive((day,True));sources.append(source)
            lo=pd.Timestamp(day,tz='UTC').value//10**6;hi=lo+86400000
            bars=bars[~bars.timestamp_ms.between(lo,hi-1)]
            bars=pd.concat([bars,patch[patch.timestamp_ms.between(lo,hi-1)]],ignore_index=True)
        bars=bars.sort_values('timestamp_ms').reset_index(drop=True)
    assert (bars.timestamp_ms.to_numpy()[1:]>bars.close_timestamp_ms.to_numpy()[:-1]).all()
    missing=np.setdiff1d(expected,bars.timestamp_ms)
    assert not bars.timestamp_ms.duplicated().any()
    assert (bars.low>0).all()
    assert (bars.high>=bars[['open','close']].max(axis=1)).all()
    assert (bars.low<=bars[['open','close']].min(axis=1)).all()
    assert bars.timestamp_ms.iloc[0]==start and bars.timestamp_ms.iloc[-1]+3600000==end
    # Preserve genuine no-candle hours; never manufacture prices or trades during gaps.
    gaps=[pd.Timestamp(int(t),unit='ms',tz='UTC').isoformat() for t in missing]
    path=DATA/'spot_hourly.csv.gz';bars.to_csv(path,index=False)
    manifest=dict(source='Binance BTCUSDT spot official public archives',start_ms=int(start),end_exclusive_ms=int(end),
        rows=len(bars),expected_rows=len(expected),missing_hours=len(missing),missing_hour_utc=gaps,
        note='No forward-filled bars or executions in missing hours; drawdown inside any remaining gap is unobserved.',
        downloaded_utc=dt.datetime.now(dt.timezone.utc).isoformat(),sources=sources,
        files={path.name:hashlib.sha256(path.read_bytes()).hexdigest()})
    (DATA/'manifest.json').write_text(json.dumps(manifest,indent=2))
    print('spot accepted',len(bars),'missing hours',len(missing),flush=True)


if __name__=='__main__':main()
