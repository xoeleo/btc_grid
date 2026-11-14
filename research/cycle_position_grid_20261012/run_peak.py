"""Retrospective stage two, retaining every failed stage-one result."""
from dataclasses import asdict
import json
import numpy as np
import pandas as pd
from features import MacroConfig,load_data,macro_features,controller,hourly_signals
from engine import ROOT,simulate


def main():
    old=json.loads((ROOT/'results.json').read_text())
    (ROOT/'results_phase1.json').write_text(json.dumps(old,indent=2))
    daily,hourly,funding,manifest=load_data()
    frame=macro_features(daily)
    frame.to_csv(ROOT/'macro_features.csv',index_label='day_utc')
    split=int(np.searchsorted(hourly.timestamp_ms,pd.Timestamp('2023-01-01',tz='UTC').value//10**6))
    recent=int(np.searchsorted(hourly.timestamp_ms,pd.Timestamp('2024-10-02',tz='UTC').value//10**6))
    configs=[MacroConfig(mode='peak',low_ratio=low,confirm_days=confirm,arm_days=180,maximum_hold_days=730)
             for low in [.45,.55] for confirm in [28,42]]
    training,signals=[],[]
    results=old['results']
    for i,c in enumerate(configs):
        decisions=controller(frame,c)
        decisions.to_csv(ROOT/f'peak_candidate_{i}_daily.csv',index_label='available_day_utc')
        sig=hourly_signals(hourly,decisions,c);signals.append(sig)
        s=simulate(hourly,funding,sig,c,f'peak_train_{i}',end=split)
        s['selection_score']=s['return_pct']-s['max_drawdown_pct']
        results[f'peak_train_{i}']=s;training.append(s)
        print('peak train',i,c.low_ratio,c.confirm_days,s['return_pct'],s['max_drawdown_pct'],flush=True)
    selected=max(range(len(configs)),key=lambda i:training[i]['selection_score'])
    c,sig=configs[selected],signals[selected]
    sig.to_csv(ROOT/'peak_selected_signals.csv',index=False)
    cases=[('peak_full',{}),('peak_ohlc',{'path':'OHLC'}),('peak_close',{'path':'CLOSE'}),
           ('peak_cost',{'slippage_bps':5,'fee_multiplier':2}),('peak_half',{'fill_fraction':.5}),
           ('peak_no_grid',{'grid':False}),('peak_validation',{'start':split}),
           ('peak_validation_cost',{'start':split,'slippage_bps':5,'fee_multiplier':2}),
           ('peak_validation_no_grid',{'start':split,'grid':False}),
           ('peak_recent_hourly',{'start':recent}),('peak_recent_no_grid',{'start':recent,'grid':False})]
    for name,options in cases:
        s=simulate(hourly,funding,sig,c,name,**options);results[name]=s
        print(name,s['return_pct'],s['max_drawdown_pct'],s['fills'],flush=True)
    for name,alternative in [('peak_no_confirmation',MacroConfig(**{**asdict(c),'confirm':False})),
                             ('peak_chain',MacroConfig(**{**asdict(c),'chain':True}))]:
        a_sig=hourly_signals(hourly,controller(frame,alternative),alternative)
        results[name]=simulate(hourly,funding,a_sig,alternative,name)
        print(name,results[name]['return_pct'],results[name]['max_drawdown_pct'],flush=True)
    for i,alternative in enumerate(configs):
        results[f'peak_candidate_{i}_validation']=simulate(hourly,funding,signals[i],alternative,
            f'peak_candidate_{i}_validation',start=split)
    bundle=dict(manifest=manifest,phase1=dict(selected_index=old['selected_index'],training=old['training']),
        training=training,selected_index=selected,selected_config=asdict(c),
        selection_rule='Stage-two four candidates: select using 2020-2022 return minus drawdown; freeze thereafter',
        validation_start_utc='2023-01-01',results=results,
        warnings=old['warnings']+['Stage two was introduced after examining stage-one full-period results; all results are retrospective'])
    (ROOT/'results.json').write_text(json.dumps(bundle,indent=2))


if __name__=='__main__':main()
