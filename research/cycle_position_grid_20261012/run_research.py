"""Prespecified small candidate family; select on 2020-22, report 2023+ without retuning."""
from dataclasses import asdict
import json
import numpy as np
import pandas as pd
from features import MacroConfig, load_data, macro_features, controller, hourly_signals
from engine import ROOT, simulate


def diagnostics(frame):
    rows = []
    for metric in ['trend_z_pct','ma_multiple_pct','mvrv_pct']:
        for horizon in [90,180,365]:
            forward = frame.close.shift(-horizon)/frame.close-1
            for label, condition in [('low',frame[metric]<=.20),('middle',frame[metric].between(.20,.80,inclusive='neither')),
                                     ('high',frame[metric]>=.80)]:
                selected = frame[condition & forward.notna()]
                # Describe overlapping observations and separate, >=30-day spaced events.
                indices = np.flatnonzero((condition & forward.notna()).to_numpy())
                events,last = [],-100000
                for i in indices:
                    if i-last>=30:
                        events.append(i);last=i
                r = forward.iloc[events]
                rows.append(dict(metric=metric,zone=label,horizon_days=horizon,
                    overlapping_days=len(selected),spaced_events=len(events),
                    event_mean_return_pct=r.mean()*100,event_median_return_pct=r.median()*100,
                    positive_fraction=float((r>0).mean()) if len(r) else None))
    pd.DataFrame(rows).to_csv(ROOT/'position_diagnostics.csv',index=False)


def main():
    daily,hourly,funding,manifest = load_data()
    frame = macro_features(daily)
    frame.to_csv(ROOT/'macro_features.csv',index_label='day_utc')
    diagnostics(frame)
    split = int(np.searchsorted(hourly.timestamp_ms,pd.Timestamp('2023-01-01',tz='UTC').value//10**6))
    recent_start = int(np.searchsorted(hourly.timestamp_ms,pd.Timestamp('2024-10-02',tz='UTC').value//10**6))
    configs = [MacroConfig(method=method,zone=zone) for method in ['trend_z','ma_multiple'] for zone in [.15,.25]]
    training = []
    all_sig = []
    for j,config in enumerate(configs):
        decisions = controller(frame,config)
        decisions.to_csv(ROOT/f'candidate_{j}_daily.csv',index_label='available_day_utc')
        sig = hourly_signals(hourly,decisions,config)
        all_sig.append(sig)
        result = simulate(hourly,funding,sig,config,f'train_{j}',end=split)
        result['selection_score'] = result['return_pct']-result['max_drawdown_pct']
        training.append(result)
        print('train',j,config.method,config.zone,result['return_pct'],result['max_drawdown_pct'],flush=True)
    selected = max(range(len(configs)),key=lambda j:training[j]['selection_score'])
    config,sig = configs[selected],all_sig[selected]
    sig.to_csv(ROOT/'selected_signals.csv',index=False)
    results = {f'train_{i}':r for i,r in enumerate(training)}
    cases = [('full_olhc',{}),('full_ohlc',{'path':'OHLC'}),('full_close',{'path':'CLOSE'}),
             ('full_cost',{'slippage_bps':5,'fee_multiplier':2}),('full_half',{'fill_fraction':.5}),
             ('full_no_grid',{'grid':False}),('validation_olhc',{'start':split}),
             ('validation_cost',{'start':split,'slippage_bps':5,'fee_multiplier':2}),
             ('validation_no_grid',{'start':split,'grid':False}),
             ('recent_hourly',{'start':recent_start}),('recent_hourly_no_grid',{'start':recent_start,'grid':False})]
    for name,options in cases:
        result = simulate(hourly,funding,sig,config,name,**options)
        results[name]=result
        print(name,result['return_pct'],result['max_drawdown_pct'],result['fills'],flush=True)
    # Explicit ablations, not replacements of the selected candidate.
    for name,alternative in [('no_confirmation',MacroConfig(**{**asdict(config),'confirm':False})),
                             ('chain_confirmation',MacroConfig(**{**asdict(config),'chain':True}))]:
        alternative_sig = hourly_signals(hourly,controller(frame,alternative),alternative)
        results[name] = simulate(hourly,funding,alternative_sig,alternative,name)
        print(name,results[name]['return_pct'],results[name]['max_drawdown_pct'],flush=True)
    # All frozen candidate validations are disclosed, not just the winning train score.
    for j,c in enumerate(configs):
        name=f'candidate_{j}_validation'
        results[name] = simulate(hourly,funding,all_sig[j],c,name,start=split)
    bundle = dict(manifest=manifest,training=training,selected_index=selected,selected_config=asdict(config),
        selection_rule='2020-2022 return_pct minus max_drawdown_pct; no 2023+ retuning',
        validation_start_utc='2023-01-01',results=results,
        warnings=['Retrospective research: earlier 2024-26 reports have been seen',
                  'MVRV is revised current-vintage history, not point-in-time snapshots',
                  'Hourly paths do not prove minute or order-book execution'])
    (ROOT/'results.json').write_text(json.dumps(bundle,indent=2))


if __name__=='__main__':
    main()
