"""Freeze period and pre-period reference; all warmup bars precede capital deployment."""
from pathlib import Path
import hashlib,json
import pandas as pd
import numpy as np
ROOT=Path(__file__).resolve().parent
START=1727827200000
END=1790899200000

def load_period():
 full=pd.read_csv(ROOT/'data/bybit_1m.csv.gz')
 warm=full[full.timestamp_ms<START]
 row=warm.loc[warm.spot_high.idxmax()]
 peak=float(row.spot_high)
 selected=full[(full.timestamp_ms>=START)&(full.timestamp_ms<END)].copy().reset_index(drop=True)
 funding=pd.read_csv(ROOT/'data/funding.csv');funding=funding[(funding.timestamp_ms>=START)&(funding.timestamp_ms<END)].reset_index(drop=True)
 return selected,funding,peak,int(row.timestamp_ms)

def main():
 original=json.loads((ROOT/'data/manifest.json').read_text())
 for filename in ['bybit_1m.csv.gz','funding.csv']:
  assert hashlib.sha256((ROOT/'data'/filename).read_bytes()).hexdigest()==original['files'][filename]['sha256']
 bars,funding,peak,peak_ms=load_period()
 expected=np.arange(START,END,60000,dtype='int64');assert np.array_equal(bars.timestamp_ms.to_numpy(),expected)
 assert not bars.isna().any().any();assert np.isfinite(bars.to_numpy()).all()
 for prefix in ['spot','linear','mark']:
  assert (bars[prefix+'_low']<=bars[[prefix+'_open',prefix+'_close']].min(axis=1)).all()
  assert (bars[prefix+'_high']>=bars[[prefix+'_open',prefix+'_close']].max(axis=1)).all()
 f_expected=np.arange(START,END,8*3600000,dtype='int64');assert np.array_equal(funding.timestamp_ms.to_numpy(),f_expected)
 manifest={'start_ms':START,'end_exclusive_ms':END,'minute_rows':len(bars),'gaps':0,'duplicates':0,'funding_rows':len(funding),'initial_ath':peak,'initial_ath_timestamp_ms':peak_ms,'ath_source':'Bybit spot highs; warmup 2021-10-02 <= t < 2024-10-02 only','lower_price':peak*.5,'upper_price':peak*2,'initial_spot_price':float(bars.spot_open.iloc[0]),'initial_target':(2*peak-float(bars.spot_open.iloc[0]))/(1.5*peak),'period_min_spot':float(bars.spot_low.min()),'period_max_spot':float(bars.spot_high.max()),'files':original['files'],'frozen_reference':True}
 (ROOT/'period_manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
