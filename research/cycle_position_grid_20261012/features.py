"""Causal macro position and entry/exit controller. No future cycle labels are inputs."""
from dataclasses import dataclass
import hashlib
import json
import numpy as np
import pandas as pd
from engine import Config, ROOT


@dataclass(frozen=True)
class MacroConfig(Config):
    mode: str = 'percentile'
    method: str = 'trend_z'
    zone: float = .20
    confirm_days: int = 14
    exposure: float = .35
    history_days: int = 1460
    trend_days: int = 730
    arm_days: int = 90
    stop_fraction: float = .20
    maximum_hold_days: int = 365
    confirm: bool = True
    chain: bool = False
    low_ratio: float = .45
    high_ratio: float = .80
    cooldown_days: int = 30


def load_data():
    data = ROOT/'data'
    manifest = json.loads((data/'manifest.json').read_text())
    for name, expected in manifest['files'].items():
        assert hashlib.sha256((data/name).read_bytes()).hexdigest()==expected,name
    daily = pd.read_csv(data/'daily.csv',parse_dates=['time']).set_index('time')
    daily.index = daily.index.tz_localize('UTC')
    hourly = pd.read_csv(data/'hourly.csv.gz')
    funding = pd.read_csv(data/'funding.csv')
    assert np.all(np.diff(hourly.timestamp_ms)==3600000)
    assert np.all(np.diff(funding.timestamp_ms)==28800000)
    return daily,hourly,funding,manifest


def past_percentile(series, days, minimum=None):
    """Rank today's observation against strictly earlier observations."""
    values = series.to_numpy(dtype=float)
    out = np.full(len(values),np.nan)
    minimum = days if minimum is None else minimum
    for i in range(len(values)):
        history = values[max(0,i-days):i]
        history = history[np.isfinite(history)]
        if np.isfinite(values[i]) and len(history)>=minimum:
            out[i] = (np.sum(history<values[i])+.5*np.sum(history==values[i]))/len(history)
    return pd.Series(out,index=series.index)


def macro_features(daily, trend_days=730, history_days=1460):
    log_price = np.log(daily.close.to_numpy())
    fitted, z = np.full(len(daily),np.nan),np.full(len(daily),np.nan)
    x = np.arange(trend_days,dtype=float)
    centered = x-x.mean()
    xx = centered@centered
    for i in range(trend_days,len(daily)):
        y = log_price[i-trend_days:i]  # Today's close is deliberately excluded from the fit.
        slope = centered@y/xx
        intercept = y.mean()-slope*x.mean()
        forecast = intercept+slope*trend_days
        residual = y-(intercept+slope*x)
        sd = np.std(residual)
        fitted[i] = np.exp(forecast)
        if sd>1e-8:
            z[i] = (log_price[i]-forecast)/sd
    frame = daily.copy()
    frame['trend_price'] = fitted
    frame['trend_z'] = z
    frame['trend_z_pct'] = past_percentile(frame.trend_z,history_days)
    frame['ma_multiple'] = frame.close/frame.close.rolling(trend_days,min_periods=trend_days).mean().shift(1)
    frame['ma_multiple_pct'] = past_percentile(frame.ma_multiple,history_days)
    frame['mvrv_pct'] = past_percentile(frame.mvrv,history_days)
    # Chain releases lag price. A two-day lag is only a conservative assumption, not vintage proof.
    frame['chain_position'] = (frame.trend_z_pct+frame.mvrv_pct.shift(1))/2
    frame['ath_drawdown'] = frame.close/frame.close.cummax()-1
    frame['four_year_peak'] = frame.close.rolling(history_days,min_periods=min(365,history_days)).max()
    frame['peak_ratio'] = frame.close/frame.four_year_peak
    frame['ema100'] = frame.close.ewm(span=100,adjust=False).mean()
    return frame


def controller(frame, config):
    if config.mode=='peak':
        return peak_controller(frame,config)
    position = frame[config.method+'_pct'] if not config.chain else frame.chain_position
    prior_high = frame.close.rolling(config.confirm_days,min_periods=config.confirm_days).max().shift(1)
    prior_low = frame.close.rolling(config.confirm_days,min_periods=config.confirm_days).min().shift(1)
    low_seen = position.rolling(config.arm_days,min_periods=1).min()<=config.zone
    high_seen = position.rolling(config.arm_days,min_periods=1).max()>=1-config.zone
    states, targets, reasons = [],[],[]
    side, entry, held = 0,0.,0
    for i in range(len(frame)):
        p, score = float(frame.close.iloc[i]),float(position.iloc[i])
        reason = 'wait'
        exit_today = False
        if side:
            held += 1
            unfavorable = p/entry-1 < -config.stop_fraction if side==1 else p/entry-1 > config.stop_fraction
            middle = score>=.55 if side==1 else score<=.45
            if unfavorable or middle or held>=config.maximum_hold_days or not np.isfinite(score):
                reason = 'stop' if unfavorable else 'middle' if middle else 'timeout_or_missing'
                side,held = 0,0
                exit_today = True
        if not side and not exit_today and np.isfinite(score):
            long_confirm = p>prior_high.iloc[i] if config.confirm else score<=config.zone
            short_confirm = p<prior_low.iloc[i] if config.confirm else score>=1-config.zone
            if low_seen.iloc[i] and long_confirm and score<.55:
                side,entry,held,reason = 1,p,0,'low_reversal'
            elif high_seen.iloc[i] and short_confirm and score>.45:
                side,entry,held,reason = -1,p,0,'high_reversal'
        states.append(side)
        targets.append(side*config.exposure)
        reasons.append(reason)
    out = pd.DataFrame(dict(position=position,state=states,target=targets,reason=reasons),index=frame.index)
    # A day-d close decision is not available until day d+1.
    delayed = out.shift(1)
    delayed[['state','target']] = delayed[['state','target']].fillna(0)
    delayed.reason = delayed.reason.fillna('warmup')
    return delayed


def peak_controller(frame,config):
    """Extreme-zone memory plus confirmed medium-term reversal; no future peak labels."""
    ratio = frame.peak_ratio
    low_seen = ratio.rolling(config.arm_days,min_periods=1).min()<=config.low_ratio
    high_seen = ratio.rolling(config.arm_days,min_periods=1).max()>=config.high_ratio
    prior_high = frame.close.rolling(config.confirm_days,min_periods=config.confirm_days).max().shift(1)
    prior_low = frame.close.rolling(config.confirm_days,min_periods=config.confirm_days).min().shift(1)
    up = (frame.close>frame.ema100)&(frame.ema100>frame.ema100.shift(5))
    down = (frame.close<frame.ema100)&(frame.ema100<frame.ema100.shift(5))
    lagged_mvrv = frame.mvrv.shift(1)
    chain_low = lagged_mvrv.rolling(config.arm_days,min_periods=1).min()<=1.2
    chain_high = lagged_mvrv.rolling(config.arm_days,min_periods=1).max()>=2.5
    states,targets,reasons = [],[],[]
    side,entry,held,cooldown = 0,0.,0,0
    for i in range(len(frame)):
        p,r = float(frame.close.iloc[i]),float(ratio.iloc[i])
        reason,exit_today = 'wait',False
        cooldown=max(0,cooldown-1)
        if side:
            held+=1
            unfavorable = p/entry-1 < -config.stop_fraction if side==1 else p/entry-1 > config.stop_fraction
            reached = r>=config.high_ratio if side==1 else r<=config.low_ratio
            reversed_again = down.iloc[i] if side==1 else up.iloc[i]
            if unfavorable or reached or reversed_again or held>=config.maximum_hold_days:
                reason='stop' if unfavorable else 'opposite_zone' if reached else 'trend_exit' if reversed_again else 'timeout'
                side,held,cooldown = 0,0,config.cooldown_days
                exit_today=True
        if not side and not exit_today and cooldown==0 and np.isfinite(r):
            long_confirm = up.iloc[i] and p>prior_high.iloc[i] if config.confirm else True
            short_confirm = down.iloc[i] and p<prior_low.iloc[i] if config.confirm else True
            chain_ok = not config.chain or np.isfinite(lagged_mvrv.iloc[i])
            if (low_seen.iloc[i] and long_confirm and r<config.high_ratio and chain_ok and
                    (not config.chain or chain_low.iloc[i])):
                side,entry,held,reason=1,p,0,'low_medium_reversal'
            elif (high_seen.iloc[i] and short_confirm and r>config.low_ratio and chain_ok and
                    (not config.chain or chain_high.iloc[i])):
                side,entry,held,reason=-1,p,0,'high_medium_reversal'
        states.append(side);targets.append(side*config.exposure);reasons.append(reason)
    out=pd.DataFrame(dict(position=ratio,state=states,target=targets,reason=reasons),index=frame.index).shift(1)
    out[['state','target']]=out[['state','target']].fillna(0)
    out.reason=out.reason.fillna('warmup')
    return out


def hourly_signals(hourly, decisions, config):
    index = pd.to_datetime(hourly.timestamp_ms,unit='ms',utc=True)
    mapped = decisions.reindex(index,method='ffill')
    close = pd.Series(hourly.close.to_numpy(),index=index)
    high = pd.Series(hourly.high.to_numpy(),index=index)
    low = pd.Series(hourly.low.to_numpy(),index=index)
    previous = close.shift(1)
    tr = pd.concat([high-low,abs(high-previous),abs(low-previous)],axis=1).max(axis=1)
    step = (tr.ewm(alpha=1/24,adjust=False).mean()/close*config.atr_scale).clip(config.minimum_step,config.maximum_step).shift(1)
    return pd.DataFrame(dict(state=mapped.state.to_numpy(),target=mapped.target.to_numpy(),
        anchor=previous.to_numpy(),step=step.to_numpy(),available_ms=hourly.timestamp_ms.to_numpy(),
        position=mapped.position.to_numpy()))
