"""Read-only reuse of frozen Bybit minute archives; no live endpoints or credentials."""
from pathlib import Path
import hashlib
import json
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent
SOURCE = Path('/mnt/f/home/btc_grid/research/ath_target_moving50_2y_20261014/data')
START = 1727827200000
END = 1790899200000


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def load_period():
    source_manifest = json.loads((SOURCE / 'manifest.json').read_text())
    hashes = {}
    for name in ('bybit_1m.csv.gz', 'funding.csv'):
        path = SOURCE / name
        actual = sha256(path)
        expected = source_manifest['files'][name]['sha256']
        if actual != expected:
            raise ValueError(f'Frozen data hash mismatch: {name}')
        hashes[name] = {'path': str(path.resolve()), 'sha256': actual}
    parts = []
    for chunk in pd.read_csv(SOURCE / 'bybit_1m.csv.gz', chunksize=250_000):
        subset = chunk.loc[(chunk.timestamp_ms >= START) & (chunk.timestamp_ms < END)]
        if not subset.empty:
            parts.append(subset.copy())
    bars = pd.concat(parts, ignore_index=True)
    funding = pd.read_csv(SOURCE / 'funding.csv')
    funding = funding.loc[(funding.timestamp_ms >= START) & (funding.timestamp_ms < END)].reset_index(drop=True)
    expected = np.arange(START, END, 60_000, dtype='int64')
    if not np.array_equal(bars.timestamp_ms.to_numpy(), expected):
        raise ValueError('Minute bars are missing, duplicated, unsorted, or outside the exact interval')
    if not np.isfinite(bars.to_numpy()).all():
        raise ValueError('Non-finite data')
    for prefix in ('spot', 'linear', 'mark'):
        low = bars[f'{prefix}_low']
        high = bars[f'{prefix}_high']
        if not ((low > 0) & (low <= bars[[prefix+'_open', prefix+'_close']].min(axis=1))
                & (high >= bars[[prefix+'_open', prefix+'_close']].max(axis=1))).all():
            raise ValueError(f'Invalid {prefix} OHLC')
    if not np.array_equal(funding.timestamp_ms.to_numpy(), np.arange(START, END, 8*3_600_000)):
        raise ValueError('Incomplete or duplicated funding timestamps')
    if not np.isfinite(funding.rate.to_numpy()).all():
        raise ValueError('Invalid funding rate')
    manifest = {
        'source': source_manifest['source'], 'source_manifest_path': str((SOURCE/'manifest.json').resolve()),
        'source_manifest_sha256': sha256(SOURCE/'manifest.json'), 'files': hashes,
        'start_ms': START, 'end_exclusive_ms': END, 'minute_rows': len(bars),
        'gaps': 0, 'duplicates': 0, 'funding_rows': len(funding),
        'initial_spot_price': float(bars.spot_open.iloc[0]),
        'last_spot_price': float(bars.spot_close.iloc[-1]),
        'minimum_spot_price': float(bars.spot_low.min()), 'maximum_spot_price': float(bars.spot_high.max()),
        'lower_price': 60000, 'upper_price': 200000, 'lower_target': 1, 'upper_target': 0,
        'thresholds_percentage_points': [5, 2, 1], 'no_forward_fill': True,
        'data_cutoff_note': 'Frozen comparison ends 2026-10-02 00:00 UTC; not updated through the current date.',
    }
    (ROOT/'source_manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False)+'\n')
    return bars, funding


if __name__ == '__main__':
    b, f = load_period()
    print(f'Verified {len(b):,} minute bars and {len(f):,} funding records')
