import unittest
import numpy as np
import pandas as pd
from engine import simulate
from features import MacroConfig,macro_features,controller,hourly_signals,past_percentile


def bars(n,interval=60000,low=99.,high=101.):
    t=1727827200000+np.arange(n)*interval
    return pd.DataFrame(dict(timestamp_ms=t,open=100.,high=high,low=low,close=100.,
        mark_open=100.,mark_high=high,mark_low=low,mark_close=100.))


class Acceptance(unittest.TestCase):
    def test_percentile_excludes_current_and_future(self):
        series=pd.Series([1.,2.,3.,4.,1.])
        out=past_percentile(series,3)
        self.assertEqual(out.iloc[3],1)
        self.assertEqual(out.iloc[4],0)

    def test_feature_and_controller_prefix_invariance(self):
        t=pd.date_range('2010-01-01',periods=800,tz='UTC')
        close=100*np.exp(np.arange(800)/1000+.4*np.sin(np.arange(800)/40))
        data=pd.DataFrame(dict(close=close,mvrv=1+np.arange(800)/1000),index=t)
        full=macro_features(data,trend_days=30,history_days=60)
        prefix=macro_features(data.iloc[:650],trend_days=30,history_days=60)
        pd.testing.assert_frame_equal(full.iloc[:650],prefix)
        changed=data.copy();changed.iloc[650:]*=10
        pd.testing.assert_frame_equal(full.iloc[:650],macro_features(changed,30,60).iloc[:650])
        for mode in ['percentile','peak']:
            config=MacroConfig(mode=mode,confirm_days=14)
            pd.testing.assert_frame_equal(controller(full,config).iloc[:650],controller(prefix,config))

    def test_chain_is_optional_and_day_decisions_are_delayed(self):
        index=pd.date_range('2010-01-01',periods=2000,tz='UTC')
        daily=pd.DataFrame(dict(close=100*np.exp(.001*np.arange(2000)+np.sin(np.arange(2000)/100)),mvrv=2.),index=index)
        frame=macro_features(daily,30,60)
        config=MacroConfig(mode='peak')
        a=controller(frame,config)
        frame['mvrv']=np.nan
        pd.testing.assert_frame_equal(a,controller(frame,config))
        self.assertEqual(a.state.iloc[0],0)

    def test_minute_and_hour_zero_grid_match_on_constant_bars(self):
        minute=bars(180)
        hourly=bars(3,interval=3600000)
        sig=pd.DataFrame(dict(state=[1,-1,0],target=[.35,-.35,0],
            anchor=[100.]*3,step=[.01]*3,available_ms=hourly.timestamp_ms))
        funding=pd.DataFrame(dict(timestamp_ms=[hourly.timestamp_ms.iloc[1]],rate=[.001]))
        a=simulate(minute,funding,sig,MacroConfig(),'test',grid=False,export=False)
        b=simulate(hourly,funding,sig,MacroConfig(),'test',grid=False,export=False)
        self.assertAlmostEqual(a['net_pnl'],b['net_pnl'],places=8)
        self.assertEqual(a['completed_cycles'],2)
        self.assertEqual(a['open_qty'],0)
        self.assertGreater(a['funding_paid'],0)

    def test_no_limit_fill_on_exact_touch(self):
        data=bars(120)
        sig=pd.DataFrame(dict(state=[1,1],target=[.35,.35],anchor=[100.,100.],step=[.01,.01],
            available_ms=[data.timestamp_ms.iloc[0],data.timestamp_ms.iloc[60]]))
        funding=pd.DataFrame(dict(timestamp_ms=[],rate=[]))
        s=simulate(data,funding,sig,MacroConfig(),'test',export=False)
        self.assertEqual(s['grid_fills'],0)


if __name__=='__main__':unittest.main()
