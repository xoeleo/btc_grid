"""Freeze the stage-two configuration and replay same-venue minute bars; no retuning."""
import hashlib
import json
import pandas as pd
from features import MacroConfig,load_data,macro_features,controller,hourly_signals
from engine import ROOT,simulate


def main():
    bundle=json.loads((ROOT/'results.json').read_text())
    config=MacroConfig(**bundle['selected_config'])
    manifest=json.loads((ROOT/'data/minute_manifest.json').read_text())
    for name,expected in manifest['files'].items():
        assert hashlib.sha256((ROOT/'data'/name).read_bytes()).hexdigest()==expected
    minute=pd.read_csv(ROOT/'data/recent_minutes.csv.gz')
    daily,hourly,funding,_=load_data()
    sig=hourly_signals(hourly,controller(macro_features(daily),config),config)
    cases=[('peak_minute',{}),('peak_minute_ohlc',{'path':'OHLC'}),('peak_minute_close',{'path':'CLOSE'}),
           ('peak_minute_cost',{'slippage_bps':5,'fee_multiplier':2}),
           ('peak_minute_half',{'fill_fraction':.5}),('peak_minute_no_grid',{'grid':False}),
           ('peak_minute_no_grid_cost',{'grid':False,'slippage_bps':5,'fee_multiplier':2})]
    for name,options in cases:
        s=simulate(minute,funding,sig,config,name,**options)
        bundle['results'][name]=s
        print(name,s['return_pct'],s['max_drawdown_pct'],s['fills'],flush=True)
    bundle['minute_manifest']=manifest
    (ROOT/'results.json').write_text(json.dumps(bundle,indent=2))


if __name__=='__main__':main()
