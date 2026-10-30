"""Download and strictly validate public Bybit BTCUSDT data; no account or orders API."""
import concurrent.futures as cf, datetime as dt, gzip, hashlib, json, pathlib, threading, time
import requests
ROOT=pathlib.Path(__file__).resolve().parent/'data'; ROOT.mkdir(exist_ok=True)
START=int(dt.datetime(2024,10,2,tzinfo=dt.timezone.utc).timestamp()*1000)
END=int(dt.datetime(2026,10,2,tzinfo=dt.timezone.utc).timestamp()*1000)
BASE='https://api.bybit.com'; local=threading.local()
def get(endpoint, params):
    if not hasattr(local,'session'): local.session=requests.Session()
    for retry in range(7):
        try:
            r=local.session.get(BASE+endpoint,params=params,timeout=30); r.raise_for_status(); j=r.json()
            if j['retCode']!=0: raise RuntimeError(str(j)[:300])
            return j['result']
        except Exception:
            if retry==6: raise
            time.sleep(min(16,2**retry))
def fetch(job):
    kind,start,end=job; f=ROOT/f'{kind}_{start}.json.gz'
    if f.exists():
        with gzip.open(f,'rt') as s: rows=json.load(s)
    else:
        rows=get('/v5/market/'+('kline' if kind=='trade' else 'mark-price-kline'),
                 {'category':'linear','symbol':'BTCUSDT','interval':'1','limit':1000,'start':start,'end':end-1})['list']
        rows=sorted(rows,key=lambda x:int(x[0]))
        assert [int(x[0]) for x in rows]==list(range(start,end,60000)),(kind,start,len(rows))
        with gzip.open(str(f)+'.tmp','wt') as s: json.dump(rows,s)
        pathlib.Path(str(f)+'.tmp').replace(f)
    assert [int(x[0]) for x in rows]==list(range(start,end,60000)),(kind,start,len(rows))
    return kind,start,rows
if __name__=='__main__':
    jobs=[(k,s,min(s+60000000,END)) for s in range(START,END,60000000) for k in ['trade','mark']]
    result={}; done=0
    with cf.ThreadPoolExecutor(max_workers=8) as pool:
        for kind,start,rows in pool.map(fetch,jobs):
            result[kind,start]=rows;done+=1
            if done%50==0:
                print(f'{done}/{len(jobs)} pages verified',flush=True)
                (ROOT/'progress.json').write_text(json.dumps({'done':done,'total':len(jobs)}))
    total=0
    with gzip.open(ROOT/'bars.csv.gz','wt') as f:
        f.write('timestamp_ms,open,high,low,close,mark_open,mark_high,mark_low,mark_close\n')
        for start in range(START,END,60000000):
            for trade,mark in zip(result['trade',start],result['mark',start]):
                assert trade[0]==mark[0]
                f.write(','.join(trade[:5]+mark[1:5])+'\n');total+=1
    funding={};cursor=END-1
    while cursor>=START:
        rows=get('/v5/market/funding/history',{'category':'linear','symbol':'BTCUSDT','limit':200,'endTime':cursor})['list']
        if not rows: raise RuntimeError('Missing funding history')
        for row in rows:
            t=int(row['fundingRateTimestamp'])
            if START<=t<END: funding[t]=row['fundingRate']
        oldest=min(int(x['fundingRateTimestamp']) for x in rows)
        if oldest>=cursor: raise RuntimeError('Funding pagination stalled')
        cursor=oldest-1
    with open(ROOT/'funding.csv','w') as f:
        f.write('timestamp_ms,rate\n')
        for t,rate in sorted(funding.items()):f.write(f'{t},{rate}\n')
    ts=sorted(funding)
    assert len(ts)==(END-START)//28800000 and ts==list(range(START,END,28800000)),(len(ts),ts[:1],ts[-1:])
    manifest={'source':'Bybit linear BTCUSDT public API','start_ms':START,'end_exclusive_ms':END,
              'rows':total,'funding_rows':len(ts),'interval_seconds':60,'gaps':0,'duplicates':0,
              'download_completed_utc':dt.datetime.now(dt.timezone.utc).isoformat(),
              'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'bars.csv.gz',ROOT/'funding.csv']}}
    (ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest),flush=True)
