"""Match exported known-rate fields against the frozen actual funding history."""
from pathlib import Path
import json
import pandas as pd

ROOT = Path(__file__).resolve().parent

def main():
    funding = pd.read_csv(ROOT / 'data/funding.csv').rename(columns={'timestamp_ms': 'actual_ms', 'rate': 'actual_rate'})
    checks = []
    for result in json.loads((ROOT / 'results.json').read_text()):
        name = result['id']
        trades = pd.read_csv(ROOT / 'outputs' / f'{name}_trades.csv')
        joined = pd.merge_asof(trades.sort_values('timestamp_ms'), funding, left_on='timestamp_ms', right_on='actual_ms')
        assert (joined.known_funding_ms == joined.actual_ms).all()
        assert ((joined.known_funding_rate - joined.actual_rate).abs() < 1e-14).all()
        batches = pd.read_csv(ROOT / 'outputs' / f'{name}_batches.csv')
        batches = batches[batches.role == 'funding'].copy()
        if len(batches):
            batches['start_ms'] = batches.start_ms.astype('int64')
            joined = pd.merge_asof(batches.sort_values('start_ms'), funding, left_on='start_ms', right_on='actual_ms')
            assert (joined.signal_funding_ms == joined.actual_ms).all()
            assert ((joined.signal_funding_rate - joined.actual_rate).abs() < 1e-14).all()
            assert (joined.actual_rate > result['config']['funding_trigger']).all()
        checks.append({'id': name, 'passed': True, 'trades': len(trades), 'funding_batches': len(batches)})
    audit = json.loads((ROOT / 'audit.json').read_text())
    audit['actual_history_signal_check'] = checks
    (ROOT / 'audit.json').write_text(json.dumps(audit, indent=2))
    print('PASS actual historical funding signal', checks)

if __name__ == '__main__':
    main()
