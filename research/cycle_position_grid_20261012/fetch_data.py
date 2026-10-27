"""Download public historical data only; verify archive checksums and time coverage."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
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

ROOT = Path(__file__).resolve().parent
DATA = ROOT/'data'
RAW = DATA/'raw'
BASE = 'https://data.binance.vision/data/futures/um'
START = pd.Timestamp('2020-01-01',tz='UTC')
END = pd.Timestamp('2026-10-02',tz='UTC')
LOCAL = threading.local()


def get(url):
    if not hasattr(LOCAL, 'session'):
        LOCAL.session = requests.Session()
    for attempt in range(4):
        try:
            response = LOCAL.session.get(url, timeout=25)
            response.raise_for_status()
            return response.content
        except requests.RequestException:
            if attempt == 3:
                raise
            time.sleep(2**attempt)


def archive(job):
    kind, period, daily = job
    name = f'BTCUSDT-fundingRate-{period}.zip' if kind=='fundingRate' else f'BTCUSDT-1h-{period}.zip'
    suffix = f'{kind}/BTCUSDT/' + ('' if kind=='fundingRate' else '1h/') + name
    url = BASE + ('/daily/' if daily else '/monthly/') + suffix
    folder = RAW/kind
    folder.mkdir(exist_ok=True)
    path = folder/name
    checksum = path.with_suffix('.zip.CHECKSUM')
    if not checksum.exists():
        checksum.write_bytes(get(url+'.CHECKSUM'))
    expected = checksum.read_text().split()[0]
    if not path.exists():
        data = get(url)
        assert hashlib.sha256(data).hexdigest() == expected, url
        path.write_bytes(data)
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, path
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None, path
        names = [n for n in z.namelist() if n.endswith('.csv')]
        assert len(names)==1
        payload = z.read(names[0])
    return kind, period, payload, dict(url=url, sha256=expected, file=str(path.relative_to(ROOT)))


def klines(payload):
    header = payload.splitlines()[0].split(b',')[0]
    df = pd.read_csv(io.BytesIO(payload), header=0 if header in [b'open_time',b'Open time'] else None)
    out = df.iloc[:,:5].copy()
    out.columns = ['timestamp_ms','open','high','low','close']
    out = out.apply(pd.to_numeric)
    out.timestamp_ms = np.where(out.timestamp_ms>=10**15, out.timestamp_ms//1000, out.timestamp_ms).astype('int64')
    return out


def main():
    RAW.mkdir(parents=True,exist_ok=True)
    cm = RAW/'coinmetrics_btc.csv'
    cm_url = 'https://raw.githubusercontent.com/coinmetrics/data/master/csv/btc.csv'
    if not cm.exists():
        cm.write_bytes(get(cm_url))
    jobs = [(kind, month.strftime('%Y-%m'), False)
            for month in pd.date_range(START, END-pd.Timedelta(days=2),freq='MS')
            for kind in ['klines','markPriceKlines','fundingRate']]
    # The latest unfinished archive month is obtained through daily bars and public funding REST.
    jobs = [j for j in jobs if j[1] < END.strftime('%Y-%m')]
    jobs += [(kind,'2026-10-01',True) for kind in ['klines','markPriceKlines']]
    parts = {'klines':[], 'markPriceKlines':[], 'fundingRate':[]}
    sources, failures = [], []
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(archive,j):j for j in jobs}
        for i,future in enumerate(as_completed(futures),1):
            try:
                kind, period, payload, source = future.result()
                sources.append(source)
                if kind=='fundingRate':
                    df = pd.read_csv(io.BytesIO(payload))
                    assert {'calc_time','last_funding_rate'}.issubset(df.columns)
                    df = df.rename(columns={'calc_time':'timestamp_ms','last_funding_rate':'rate'})[['timestamp_ms','rate']]
                else:
                    df = klines(payload)
                parts[kind].append(df)
            except Exception as exc:
                failures.append(dict(job=futures[future],error=str(exc)))
            if i % 30==0 or i==len(jobs):
                print('archives verified',i,'/',len(jobs),'errors',len(failures),flush=True)
                (DATA/'progress.json').write_text(json.dumps(dict(done=i,total=len(jobs),errors=failures),indent=2))
    if failures:
        raise RuntimeError(f'Incomplete archive download: {failures[:5]}')
    start_ms, end_ms = START.value//10**6, END.value//10**6
    first_partial = pd.Timestamp('2026-10-01',tz='UTC').value//10**6
    funding_url = f'https://fapi.binance.com/fapi/v1/fundingRate?symbol=BTCUSDT&startTime={first_partial}&endTime={end_ms-1}&limit=1000'
    funding_file = RAW/'funding_2026-10-01.json'
    if not funding_file.exists():
        funding_file.write_bytes(get(funding_url))
    rows = json.loads(funding_file.read_text())
    assert isinstance(rows,list)
    parts['fundingRate'].append(pd.DataFrame([dict(timestamp_ms=int(r['fundingTime']),rate=float(r['fundingRate'])) for r in rows]))
    expected_hours = np.arange(start_ms,end_ms,3600000)
    merged = {}
    for kind in ['klines','markPriceKlines']:
        df = pd.concat(parts[kind],ignore_index=True).sort_values('timestamp_ms').reset_index(drop=True)
        df = df[df.timestamp_ms.between(start_ms,end_ms-1)].reset_index(drop=True)
        missing = np.setdiff1d(expected_hours,df.timestamp_ms)
        if len(missing):
            # Some official monthly mark archives omit whole days. Repair from daily archives.
            days = pd.to_datetime(missing,unit='ms',utc=True).strftime('%Y-%m-%d').unique()
            for day in days:
                _, _, payload, source = archive((kind,day,True))
                sources.append(source)
                patch = klines(payload)
                df = pd.concat([df,patch[patch.timestamp_ms.isin(missing)]],ignore_index=True)
            df = df.sort_values('timestamp_ms').reset_index(drop=True)
            print('repaired',kind,len(missing),'hours from',len(days),'daily archives',flush=True)
        assert not df.timestamp_ms.duplicated().any(),kind
        assert np.array_equal(df.timestamp_ms,expected_hours),(kind,len(df),len(expected_hours))
        assert (df.low > 0).all()
        assert (df.high >= df[['open','close']].max(axis=1)).all()
        assert (df.low <= df[['open','close']].min(axis=1)).all()
        merged[kind] = df
    bars = merged['klines'].merge(merged['markPriceKlines'].rename(columns={c:'mark_'+c for c in ['open','high','low','close']}),on='timestamp_ms',validate='one_to_one')
    fu = pd.concat(parts['fundingRate'],ignore_index=True).sort_values('timestamp_ms').reset_index(drop=True)
    fu = fu[fu.timestamp_ms.between(start_ms,end_ms-1)].reset_index(drop=True)
    # Archives report millisecond jitter around settlement boundaries. Retain exact source time.
    fu['actual_timestamp_ms'] = fu.timestamp_ms.astype('int64')
    fu['timestamp_ms'] = (fu.actual_timestamp_ms//3600000)*3600000
    assert (fu.actual_timestamp_ms-fu.timestamp_ms).between(0,999).all(), 'unexpected funding event offset'
    assert not fu.timestamp_ms.duplicated().any()
    # Fail closed: BTC archives in this sample must include each 8-hour settlement.
    assert np.array_equal(fu.timestamp_ms,np.arange(start_ms,end_ms,28800000)), ('funding coverage',len(fu))
    cm_df = pd.read_csv(cm)
    # The GitHub snapshot is stale; supplement through the public community endpoint.
    supplement_url = ('https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&'
                      'metrics=PriceUSD,CapMVRVCur&frequency=1d&start_time=2026-05-01&end_time=2026-10-02&page_size=1000')
    supplement = RAW/'coinmetrics_2026_supplement.json'
    if not supplement.exists():
        supplement.write_bytes(get(supplement_url))
    payload = json.loads(supplement.read_text())
    assert not payload.get('next_page_url'), 'unhandled Coin Metrics pagination'
    extra = pd.DataFrame(payload['data'])
    extra['time'] = extra.time.str[:10]
    for column in ['PriceUSD','CapMVRVCur']:
        extra[column] = pd.to_numeric(extra[column],errors='coerce')
    cm_df = pd.concat([cm_df,extra],ignore_index=True).drop_duplicates('time',keep='last')
    daily = bars.assign(time=pd.to_datetime(bars.timestamp_ms,unit='ms',utc=True).dt.strftime('%Y-%m-%d')).groupby('time').close.last()
    reference = cm_df[['time','PriceUSD','CapMVRVCur']].rename(columns={'PriceUSD':'close','CapMVRVCur':'mvrv'})
    before = reference[reference.time<'2020-01-01'].dropna(subset=['close'])
    recent = daily.rename('close').reset_index().merge(reference[['time','mvrv']],on='time',how='left')
    macro = pd.concat([before,recent],ignore_index=True).sort_values('time').reset_index(drop=True)
    assert not macro.time.duplicated().any()
    assert (macro.close>0).all()
    assert np.array_equal(pd.to_datetime(macro.time).to_numpy(), pd.date_range(macro.time.iloc[0],macro.time.iloc[-1],freq='D').to_numpy())
    bars.to_csv(DATA/'hourly.csv.gz',index=False)
    fu.to_csv(DATA/'funding.csv',index=False)
    macro.to_csv(DATA/'daily.csv',index=False)
    manifest = dict(start_ms=int(start_ms),end_exclusive_ms=int(end_ms),hourly_rows=len(bars),funding_rows=len(fu),
        daily_start=macro.time.iloc[0],daily_end=macro.time.iloc[-1],daily_rows=len(macro),
        mvrv_last_valid=macro.loc[macro.mvrv.notna(),'time'].iloc[-1],
        note='Daily price uses Coin Metrics before 2020, Binance perpetual closes thereafter. MVRV is revised historical data; missing values remain missing. Funding original millisecond timestamps retained; model charges carried position at containing hour before orders.',
        downloaded_utc=dt.datetime.now(dt.timezone.utc).isoformat(),sources=sources,
        additional_sources=[dict(url=cm_url,file=str(cm.relative_to(ROOT)),sha256=hashlib.sha256(cm.read_bytes()).hexdigest()),
                            dict(url=funding_url,file=str(funding_file.relative_to(ROOT)),sha256=hashlib.sha256(funding_file.read_bytes()).hexdigest()),
                            dict(url=supplement_url,file=str(supplement.relative_to(ROOT)),sha256=hashlib.sha256(supplement.read_bytes()).hexdigest())],
        files={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [DATA/'hourly.csv.gz',DATA/'funding.csv',DATA/'daily.csv']})
    (DATA/'manifest.json').write_text(json.dumps(manifest,indent=2))
    print('data accepted',manifest['hourly_rows'],manifest['daily_rows'],'MVRV through',manifest['mvrv_last_valid'],flush=True)


if __name__=='__main__':
    main()
