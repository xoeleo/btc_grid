"""Retrospective structural refinement; preserve original results without parameter retuning."""
import json
import numpy as np
import pandas as pd
from backtest import ROOT, Config, load_data, signals, simulate


def main():
    bundle = json.loads((ROOT/'results.json').read_text())
    config = Config(**bundle['selected_config'])
    bars, funding, _ = load_data()
    sig = signals(bars, config)
    split = int(np.searchsorted(bars.timestamp_ms, pd.Timestamp('2025-10-02',tz='UTC').value//10**6))
    cases = [('range_train',{'end':split}), ('range_olhc',{}),
             ('range_ohlc',{'path':'OHLC'}),
             ('range_cost',{'slippage_bps':5,'fee_multiplier':2}),
             ('range_holdout',{'start':split}),
             ('range_holdout_ohlc',{'start':split,'path':'OHLC'}),
             ('range_holdout_cost',{'start':split,'slippage_bps':5,'fee_multiplier':2}),
             ('range_half_fill',{'fill_fraction':.5}),
             ('range_close',{'path':'CLOSE'})]
    for name, options in cases:
        if name in bundle['results']:
            continue
        s = simulate(bars, funding, sig, config, name, grid_mode='range', **options)
        bundle['results'][name] = s
        print(name, s['return_pct'], s['max_drawdown_pct'], s['fills'], flush=True)
    bundle['refinement'] = dict(rule='Enable grid only in neutral regime; preserve original selected parameters',
        provenance='Introduced after observing full-period grid ablation. All refinement results are retrospective; not fresh holdout.')
    (ROOT/'results.json').write_text(json.dumps(bundle,indent=2))


if __name__ == '__main__':
    main()
