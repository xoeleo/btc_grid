import unittest
import numpy as np
import pandas as pd
from backtest import Config, signals, simulate


def bars(n, low=99., high=101.):
    t = np.arange(n)*60000+1727827200000
    return pd.DataFrame(dict(timestamp_ms=t, open=100., high=high, low=low,
                             close=100., mark_open=100., mark_high=high,
                             mark_low=low, mark_close=100.))


class Acceptance(unittest.TestCase):
    def test_future_mutation_and_prefix_do_not_change_prior_signals(self):
        data = bars(90*1440)
        trend = 100+np.arange(len(data))/10000
        for key in data.columns[1:]:
            data[key] = trend + (1 if 'high' in key else -1 if 'low' in key else 0)
        full = signals(data, Config())
        prefix = signals(data.iloc[:75*1440], Config())
        pd.testing.assert_frame_equal(full.iloc[:len(prefix)], prefix)
        mutated = data.copy()
        mutated.loc[mutated.index >= 75*1440, mutated.columns[1:]] *= 2
        pd.testing.assert_frame_equal(full.iloc[:len(prefix)],
                                      signals(mutated, Config()).iloc[:len(prefix)])
        self.assertTrue((full.state.iloc[:60*24] == -2).all())

    def test_touch_is_not_a_fill(self):
        data = bars(120)
        sig = pd.DataFrame(dict(state=[1, 1], anchor=[100.,100.], step=[.01,.01]))
        funding = pd.DataFrame(dict(timestamp_ms=[], rate=[]))
        result = simulate(data, funding, sig, Config(), 'test', export=False)
        self.assertEqual(result['grid_fills'], 0)
        self.assertGreater(result['open_qty'], 0)
        self.assertLess(result['reconciliation_error'], 1e-5)

    def test_switch_closes_old_cycle_before_opening_short(self):
        data = bars(120, low=98., high=102.)
        sig = pd.DataFrame(dict(state=[1,-1], anchor=[100.,100.], step=[.01,.01]))
        funding = pd.DataFrame(dict(timestamp_ms=[data.timestamp_ms.iloc[60]], rate=[.001]))
        s = simulate(data, funding, sig, Config(), 'test', export=False)
        self.assertLess(s['open_qty'], 0)
        self.assertEqual(s['completed_cycles'], 1)
        self.assertGreater(s['funding_paid'], 0)
        self.assertLess(s['peak_exposure_pct'], 100)
        self.assertAlmostEqual(s['closed_cycle_pnl']+s['open_cycle_pnl'], s['net_pnl'], places=7)

    def test_short_receives_positive_funding_and_can_buy_to_close(self):
        data = bars(120)
        sig = pd.DataFrame(dict(state=[-1,-2], anchor=[100.,100.], step=[.01,.01]))
        funding = pd.DataFrame(dict(timestamp_ms=[data.timestamp_ms.iloc[60]], rate=[.001]))
        s = simulate(data, funding, sig, Config(), 'test', export=False)
        self.assertLess(s['funding_paid'], 0)
        self.assertEqual(s['open_qty'], 0)
        self.assertEqual(s['completed_cycles'], 1)
        self.assertAlmostEqual(s['closed_cycle_pnl'], s['net_pnl'], places=7)

    def test_range_mode_disables_grid_in_clear_trend(self):
        data = bars(120, low=98., high=102.)
        sig = pd.DataFrame(dict(state=[1,1], anchor=[100.,100.], step=[.01,.01]))
        funding = pd.DataFrame(dict(timestamp_ms=[], rate=[]))
        range_result = simulate(data, funding, sig, Config(), 'test', export=False, grid_mode='range')
        all_result = simulate(data, funding, sig, Config(), 'test', export=False)
        self.assertEqual(range_result['grid_fills'], 0)
        self.assertGreater(all_result['grid_fills'], 0)


if __name__ == '__main__':
    unittest.main()
