"""Fast causal/accounting checks for the fixed-dollar price-band engine."""
import pandas as pd
import pytest
from engine import CAPITAL,Config,simulate,price_band_target,target_trade,TAKER
START=1727827200000

def bars(prices):
 rows=[]
 for i,p in enumerate(prices):
  r={'timestamp_ms':START+i*60000}
  for prefix in ['linear','spot','mark']:
   for suffix,value in [('open',p),('high',p+1),('low',p-1),('close',p)]:r[prefix+'_'+suffix]=value
  rows.append(r)
 return pd.DataFrame(rows)

def funding(rates=()):
 return pd.DataFrame(rates,columns=['timestamp_ms','rate'])

def direct_cfg(**kwargs):
 base=dict(mode='direct',target_mode='price_band',lower_price=60000.,upper_price=200000.,
           threshold=.05,allow_perp_long=False)
 base.update(kwargs)
 return Config(**base)

def test_defaults_and_fixed_band_endpoints_are_clamped():
 assert Config().mode=='direct'
 assert Config().lower_price==60000.
 assert Config().upper_price==200000.
 assert price_band_target(0)==1.
 assert price_band_target(60000)==1.
 assert price_band_target(130000)==pytest.approx(.5)
 assert price_band_target(200000)==0.
 assert price_band_target(999999)==0.
 with pytest.raises(ValueError):price_band_target(100,100,100)

def test_threshold_is_one_symmetric_case_parameter_for_both_directions():
 prices=[120000,115000,125000,100000,140000,80000,160000]
 case5=simulate(bars(prices),funding(),direct_cfg(threshold=.05),'case5')
 case1=simulate(bars(prices),funding(),direct_cfg(threshold=.01),'case1')
 assert case5[0]['config']['threshold']==.05
 assert case1[0]['config']['threshold']==.01
 # The same path contains both price rises and falls.  The tighter case
 # must react at least as often in each direction, rather than switching
 # thresholds based on whether it is buying or selling.
 up5=[x for x in case5[1] if x['delta']<0]
 up1=[x for x in case1[1] if x['delta']<0]
 down5=[x for x in case5[1] if x['delta']>0]
 down1=[x for x in case1[1] if x['delta']>0]
 assert len(up1)>=len(up5)
 assert len(down1)>=len(down5)
 assert len(up1)>len(up5) or len(down1)>len(down5)

def test_target_trade_hits_target_after_fee_and_execution_price():
 spot_value=CAPITAL
 mark=100000.;execution=100010.;target=.5;fee=TAKER
 delta=target_trade(spot_value,0.,0.,mark,execution,fee,target)
 post_cash=-delta*execution-abs(delta)*execution*fee
 post_exposure=spot_value+delta*mark
 post_equity=post_exposure+post_cash
 # LOT=.001 quantization leaves a bounded residual around the fee-aware target.
 assert post_exposure/post_equity==pytest.approx(target,abs=1e-4)

def test_short_only_main_group_crosses_prices_and_books_funding():
 b=bars([120000,90000,60000,180000,100000])
 f=funding([(START+60000,.0001),(START+120000,-.0002),(START+180000,.0003)])
 result,trades,settlements,daily,_,_=simulate(b,f,direct_cfg(),'short_only')
 assert result['final_short_qty']>=-1e-9
 assert all(t['q']<=1e-9 for t in trades)
 assert any(x['kind']=='funding' for x in settlements)
 assert result['funding_net']!=0
 assert abs(result['final_equity']-CAPITAL-(result['spot_pnl']+result['perp_realized_net_fees']+result['perp_unrealized']+result['funding_net']-result['borrow_interest']))<1e-5

def test_allow_perp_long_sensitivity_can_cross_net_long():
 b=bars([120000,60000,60000])
 result,trades,_,_,_,_=simulate(b,funding(),direct_cfg(allow_perp_long=True),'net_long_sensitivity')
 assert result['max_long_qty']>0
 assert any(t['q']>0 for t in trades)

def test_future_prices_or_funding_do_not_change_old_trades():
 cfg=direct_cfg()
 prices=[120000,90000,60000,180000]
 prefix=simulate(bars(prices),funding([(START+60000,.0001)]),cfg,'prefix')
 future_funding=funding([(START+60000,.0001),(START+600000,.25)])
 extended=simulate(bars(prices+[100000,200000,40000]),future_funding,cfg,'extended')
 assert extended[1][:len(prefix[1])]==prefix[1]

def test_spot_quantity_is_constant_through_rebalances():
 result,trades,_,daily,_,_=simulate(bars([120000,60000,180000,90000]),funding(),direct_cfg(),'spot_constant')
 expected=CAPITAL/120000.
 assert result['final_spot_qty']==pytest.approx(expected)
 assert all(row['spot_qty']==pytest.approx(expected) for row in daily)
 assert all(row['spot_qty']==pytest.approx(expected) for row in trades)


def test_independent_audit_with_distinct_spot_linear_and_mark_prices():
 from audit import audit
 from validate_export import policy_check
 b=bars([100000.,110000.,90000.])
 for suffix in ['open','high','low','close']:
  b['linear_'+suffix]+=25.
  b['mark_'+suffix]+=10.
 cfg=direct_cfg(threshold=.01)
 r,t,se,da,_,_=simulate(b,funding(),cfg,'distinct_quotes')
 assert t and t[0]['quote']!=t[0]['spot']
 assert audit(r,t,se,da)['passed']
 assert policy_check(r,t,se,b,funding())['all_triggers_verified']


def test_hold_without_any_trade_or_settlement_audits_cleanly():
 from audit import audit
 r,t,se,da,_,_=simulate(bars([100000.,110000.]),funding(),direct_cfg(mode='spot'),'hold_empty')
 assert not t and not se
 assert audit(r,t,se,da)['passed']
