"""Public latest completed-day snapshot, separate from frozen research/selection data."""
from dataclasses import asdict
import datetime as dt
import hashlib
import json
import pandas as pd
from fetch_data import get
from features import MacroConfig,load_data,macro_features,controller
from engine import ROOT


def main():
    bundle=json.loads((ROOT/'results.json').read_text())
    config=MacroConfig(**bundle['selected_config'])
    daily,_,_,_=load_data()
    today=pd.Timestamp.now(tz='UTC').normalize()
    start=(daily.index[-1]+pd.Timedelta(days=1)).value//10**6
    end=today.value//10**6
    price_url=f'https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT&interval=1d&startTime={start}&endTime={end-1}&limit=1000'
    chain_url=('https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=PriceUSD,CapMVRVCur&frequency=1d&'
               f'start_time={pd.Timestamp(start,unit="ms",tz="UTC").strftime("%Y-%m-%d")}&end_time={today.strftime("%Y-%m-%d")}&page_size=1000')
    price_payload=get(price_url);chain_payload=get(chain_url)
    price_path=ROOT/'data/raw/latest_daily.json';chain_path=ROOT/'data/raw/latest_chain.json'
    price_path.write_bytes(price_payload);chain_path.write_bytes(chain_payload)
    rows=json.loads(price_payload);chain=json.loads(chain_payload)
    assert isinstance(rows,list) and not chain.get('next_page_url')
    extra=pd.DataFrame([dict(time=pd.Timestamp(int(r[0]),unit='ms',tz='UTC'),close=float(r[4])) for r in rows if int(r[0])<end]).set_index('time')
    chain_values={pd.Timestamp(r['time']):float(r['CapMVRVCur']) for r in chain['data'] if r.get('CapMVRVCur') is not None}
    extra['mvrv']=[chain_values.get(i,float('nan')) for i in extra.index]
    combined=pd.concat([daily,extra]).sort_index()
    assert not combined.index.duplicated().any()
    frame=macro_features(combined)
    last=frame.iloc[-1]
    # Extend only the availability index: the last signal belongs to the previous completed day.
    placeholder=frame.iloc[[-1]].copy();placeholder.index=placeholder.index+pd.Timedelta(days=1)
    available=controller(pd.concat([frame,placeholder]),config).iloc[-1]
    snapshot=dict(observed_utc=dt.datetime.now(dt.timezone.utc).isoformat(),
        completed_day_utc=frame.index[-1].strftime('%Y-%m-%d'),
        signal_available_day_utc=(frame.index[-1]+pd.Timedelta(days=1)).strftime('%Y-%m-%d'),
        close=float(last.close),four_year_peak=float(last.four_year_peak),peak_ratio=float(last.peak_ratio),
        low_zone_price=float(last.four_year_peak*config.low_ratio),high_zone_price=float(last.four_year_peak*config.high_ratio),
        mvrv=None if pd.isna(last.mvrv) else float(last.mvrv),ema100=float(last.ema100),
        state=int(available.state),target=float(available.target),
        region='low' if last.peak_ratio<=config.low_ratio else 'high' if last.peak_ratio>=config.high_ratio else 'middle',
        state_note='Current controller state may retain a position opened after a past extreme; current region alone is not an entry instruction.',
        sources=[dict(url=price_url,file=str(price_path.relative_to(ROOT)),sha256=hashlib.sha256(price_payload).hexdigest()),
                 dict(url=chain_url,file=str(chain_path.relative_to(ROOT)),sha256=hashlib.sha256(chain_payload).hexdigest())])
    (ROOT/'latest_snapshot.json').write_text(json.dumps(snapshot,indent=2))
    print(json.dumps(snapshot,ensure_ascii=False),flush=True)


if __name__=='__main__':main()
