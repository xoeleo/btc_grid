"""Endowed BTC spot + short-only USDT perpetual. Isolated research, no orders."""
from dataclasses import dataclass,asdict
import math
import numpy as np
CAPITAL=500000.
MAKER=.00014;TAKER=.00035

@dataclass(frozen=True)
class Config:
 mode:str='grid'
 step:float=.005
 levels:int=5
 timeout_hours:int=24
 path:str='OLHC'
 fill_fraction:float=1.
 penetration:float=.0002
 fee_multiplier:float=1.
 slip:float=.0001
 funding_enabled:bool=True
 borrow_apr:float=0.
 low:float=.45
 high:float=.55
 guard_low:float=.40
 guard_high:float=.60
 collateral_haircut:float=.80
 maintenance_fraction:float=.01


def correction(spot_value,cash,q,mark,execution,fee,target=.5):
 """Signed fee-aware trade to target. Contract quantity may never become long."""
 equity=spot_value+cash+q*mark
 residual=target*equity-spot_value-q*mark
 if abs(residual)<1e-9:return 0.
 sign=1 if residual>0 else -1
 d=residual/(mark-target*(mark-execution)+target*sign*execution*fee)
 d=min(d,-q) if d>0 else d
 return math.copysign(math.floor((abs(d)+1e-12)*1000)/1000,d)


def simulate(bars,funding,config=Config(),name='case'):
 a=bars.to_numpy(dtype=float);cols={c:i for i,c in enumerate(bars.columns)}
 idx=lambda c:cols[c]
 fdict=dict(zip(funding.timestamp_ms.astype('int64'),funding.rate.astype(float)))
 dt=int(a[1,idx('timestamp_ms')]-a[0,idx('timestamp_ms')]);assert dt in [300000,3600000]
 spot_qty=CAPITAL/a[0,idx('spot_open')]
 # cash is the synthetic perpetual cash account; basis converts it to actual USDT ledger.
 q=0.;cash=0.;basis=0.;realized=0.;fees=0.;funding_net=0.;interest=0.
 peak=CAPITAL;dd=0.;ddusd=0.;min_eq=CAPITAL;min_adjusted=CAPITAL
 max_weight=0.;min_weight=1.;min_margin_ratio=float('inf');max_short=0.;max_debt=0.
 orders=[];active=None;active_sign=0;episode=0;trades=[];settlements=[];daily=[];episodes=[]
 fills=0;timeouts=0;guards=0;clipped=0;unreachable_bars=0;neg_cash_bars=0;outband_bars=0
 lastborrow=int(a[0,idx('timestamp_ms')]);last_ts=lastborrow

 def equity(s,m):return spot_qty*s+cash+q*m
 def weight(s,m):return (spot_qty*s+q*m)/equity(s,m)
 def observe(s,m):
  nonlocal peak,dd,ddusd,min_eq,min_adjusted,min_margin_ratio,max_short,max_debt,max_weight,min_weight
  e=equity(s,m);assert e>0,('economic insolvency',name,e)
  peak=max(peak,e);dd=max(dd,1-e/peak);ddusd=max(ddusd,peak-e);min_eq=min(min_eq,e)
  w=(spot_qty*s+q*m)/e;max_weight=max(max_weight,w);min_weight=min(min_weight,w)
  realcash=cash+basis;adjusted=e-(1-config.collateral_haircut)*spot_qty*s
  min_adjusted=min(min_adjusted,adjusted)
  maintenance=abs(q)*m*config.maintenance_fraction
  if maintenance:min_margin_ratio=min(min_margin_ratio,adjusted/maintenance)
  max_short=max(max_short,abs(q)*m/e);max_debt=max(max_debt,max(0,-realcash))
  return e

 def fill(ts,d,pe,s,m,kind):
  nonlocal q,cash,basis,realized,fees,fills,clipped
  rate=(MAKER if kind=='maker' else TAKER)*config.fee_multiplier
  if d>0:d=min(d,-q)
  d=math.copysign(math.floor((abs(d)+1e-12)*1000)/1000,d)
  if abs(d)<.001 or abs(d)*pe<5:return False
  assert q+d<=1e-9
  preq,precash,prebasis=q,cash,basis
  f=abs(d)*pe*rate
  if d<0:basis+=d*pe
  else:
   average=basis/q
   realized+=d*(average-pe)
   basis+=d*average
  cash-=d*pe+f;q+=d;fees+=f;realized-=f
  if abs(q)<1e-10:q=0.;basis=0.
  assert abs((cash+basis)-(realized+funding_net-interest))<1e-5
  trades.append(dict(timestamp_ms=int(ts),episode=episode,kind=kind,delta=d,execution=pe,spot=s,mark=m,fee=f,pre_q=preq,pre_cash=precash,pre_basis=prebasis,q=q,cash=cash,basis=basis,actual_usdt=cash+basis,spot_qty=spot_qty,equity=equity(s,m),weight=weight(s,m),realized=realized,funding=funding_net,interest=interest))
  fills+=1;return True

 def direct(ts,s,p,m,kind):
  res=.5*equity(s,m)-spot_qty*s-q*m
  pe=p*(1+config.slip if res>0 else 1-config.slip)
  rate=TAKER*config.fee_multiplier
  d=correction(spot_qty*s,cash,q,m,pe,rate)
  fill(ts,d,pe,s,m,kind)

 def finish(ts,reason,s,m):
  nonlocal active,orders,active_sign
  if active is not None:
   episodes[-1].update(end_ms=int(ts),reason=reason,end_weight=weight(s,m),end_equity=equity(s,m),remaining_orders=len(orders))
  active=None;orders=[];active_sign=0

 for i,row in enumerate(a):
  ts=int(row[idx('timestamp_ms')]);last_ts=ts
  s=row[idx('spot_open')];p=row[idx('linear_open')];m=row[idx('mark_open')]
  # Funding belongs to carried inventory, before any orders at this timestamp.
  if ts in fdict and i>0:
   rate=fdict[ts] if config.funding_enabled else 0.
   payment=-q*m*rate;cash+=payment;funding_net+=payment
   settlements.append(dict(timestamp_ms=ts,kind='funding',q=q,mark=m,rate=rate,payment=payment,cash=cash,basis=basis))
  if ts%3600000==0 and ts>lastborrow:
   hours=(ts-lastborrow)/3600000
   debt=max(0,-(cash+basis));charge=debt*config.borrow_apr*hours/(365.25*24)
   if charge:
    cash-=charge;interest+=charge;settlements.append(dict(timestamp_ms=ts,kind='interest',q=q,mark=m,rate=config.borrow_apr,payment=-charge,cash=cash,basis=basis))
   lastborrow=ts
  observe(s,m)
  if i==0 and config.mode!='spot':direct(ts,s,p,m,'initial')
  elif config.mode in ['direct','grid']:
   w=weight(s,m)
   if active is not None:
    gap=.5*equity(s,m)-spot_qty*s-q*m
    if gap*active_sign<=0:finish(ts,'target_crossed',s,m)
    elif ts-active>=config.timeout_hours*3600000:
     timeouts+=1;direct(ts,s,p,m,'timeout');finish(ts,'timeout',s,m)
    elif w<config.guard_low or w>config.guard_high:
     guards+=1;direct(ts,s,p,m,'guard');finish(ts,'guard',s,m)
   if active is not None:
    # Existing resting orders can be crossed by an opening gap. Conservative limit execution.
    crossed=[o for o in orders if ts>o['placed'] and ((o['sign']<0 and p>o['limit']*(1+config.penetration)) or (o['sign']>0 and p<o['limit']*(1-config.penetration)))]
    crossed.sort(key=lambda o:o['limit'],reverse=active_sign>0)
    for order in crossed:
     needed=correction(spot_qty*s,cash,q,m,order['limit'],MAKER*config.fee_multiplier)
     if needed*order['sign']<=0:finish(ts,'target_crossed',s,m);break
     fill(ts,order['sign']*min(order['qty']*config.fill_fraction,abs(needed)),order['limit'],s,m,'maker')
     orders.remove(order);observe(s,m)
     if abs(correction(spot_qty*s,cash,q,m,order['limit'],MAKER*config.fee_multiplier))<.001:
      finish(ts,'target_reached',s,m);break
    if active is not None and not orders:finish(ts,'layers_filled',s,m)
   if ts%3600000==0 and active is None and (weight(s,m)<config.low or weight(s,m)>config.high):
    if config.mode=='direct':direct(ts,s,p,m,'rebalance')
    else:
     gap=.5*equity(s,m)-spot_qty*s-q*m
     sign=1 if gap>0 else -1
     qty=abs(correction(spot_qty*s,cash,q,m,p,MAKER*config.fee_multiplier))
     if qty>=.001:
      episode+=1;active=ts;active_sign=sign;orders=[]
      for level in range(1,config.levels+1):
       # Buy below the market (reduce short); sell above (increase short).
       limit=p*(1-sign*config.step*level)
       limit=math.floor(limit*10)/10 if sign>0 else math.ceil(limit*10)/10
       orders.append(dict(sign=sign,qty=qty/config.levels,limit=limit,placed=ts,level=level))
      episodes.append(dict(episode=episode,start_ms=ts,start_weight=weight(s,m),start_equity=equity(s,m),direction='reduce_short' if sign>0 else 'increase_short',quantity=qty))
  observe(s,m)
  if spot_qty*s<.5*equity(s,m):unreachable_bars+=1
  if cash+basis<0:neg_cash_bars+=1
  if weight(s,m)<config.low or weight(s,m)>config.high:outband_bars+=1
  # Predeclared OHLC path; paired spot/mark phases, not observed tick chronology.
  letters=config.path
  prevp=p;prevs=s;prevm=m
  phase={'O':'open','L':'low','H':'high','C':'close'}
  for letter in letters[1:]:
   suffix=phase[letter];endp=row[idx('linear_'+suffix)];ends=row[idx('spot_'+suffix)];endm=row[idx('mark_'+suffix)]
   if active is not None:
    # No maker fill in the bar where orders were placed; strict penetration excludes touches.
    candidates=[]
    for order in orders:
     limit=order['limit'];sign=order['sign']
     if ts<=order['placed']:continue
     if sign<0 and endp>prevp and endp>limit*(1+config.penetration):
      alpha=max(0,min(1,(limit-prevp)/(endp-prevp)));candidates.append((alpha,order))
     elif sign>0 and endp<prevp and endp<limit*(1-config.penetration):
      alpha=max(0,min(1,(limit-prevp)/(endp-prevp)));candidates.append((alpha,order))
    candidates.sort(key=lambda pair:pair[0])
    for alpha,order in candidates:
     if order not in orders:continue
     fs=prevs+alpha*(ends-prevs);fm=prevm+alpha*(endm-prevm)
     sign=order['sign'];limit=order['limit']
     needed=correction(spot_qty*fs,cash,q,fm,limit,MAKER*config.fee_multiplier)
     if needed*sign<=0:finish(ts,'target_crossed',fs,fm);break
     amount=min(order['qty']*config.fill_fraction,abs(needed))
     fill(ts,sign*amount,limit,fs,fm,'maker');orders.remove(order)
     observe(fs,fm)
     if abs(correction(spot_qty*fs,cash,q,fm,limit,MAKER*config.fee_multiplier))<.001:
      finish(ts,'target_reached',fs,fm);break
    if active is not None and not orders:finish(ts,'layers_filled',ends,endm)
   observe(ends,endm);prevp,prevs,prevm=endp,ends,endm
  if i==len(a)-1 or (ts+dt)//86400000!=ts//86400000:
   closeeq=equity(prevs,prevm)
   daily.append(dict(timestamp_ms=ts+dt,equity=closeeq,spot_qty=spot_qty,short_qty=-q,spot=prevs,mark=prevm,cash=cash,basis=basis,actual_usdt=cash+basis,weight=weight(prevs,prevm),funding=funding_net,fees=fees,interest=interest,realized=realized,unrealized_perp=q*prevm-basis))
 if active is not None:finish(last_ts+dt,'open_at_end',prevs,prevm)
 final=equity(prevs,prevm);spotpnl=spot_qty*(prevs-a[0,idx('spot_open')]);unreal=q*prevm-basis
 assert abs(final-CAPITAL-(spotpnl+realized+unreal+funding_net-interest))<1e-5
 years=(last_ts+dt-int(a[0,idx('timestamp_ms')]))/(365.25*86400000)
 result=dict(id=name,config=asdict(config),start_ms=int(a[0,idx('timestamp_ms')]),end_exclusive_ms=last_ts+dt,interval_minutes=dt//60000,final_equity=final,total_return=final/CAPITAL-1,cagr=(final/CAPITAL)**(1/years)-1,max_drawdown=dd,max_drawdown_usdt=ddusd,min_equity=min_eq,spot_pnl=spotpnl,perp_realized_net_fees=realized,perp_unrealized=unreal,funding_net=funding_net,fees=fees,borrow_interest=interest,final_spot_qty=spot_qty,final_short_qty=-q,final_actual_usdt=cash+basis,final_weight=weight(prevs,prevm),maker_fills=sum(t['kind']=='maker' for t in trades),taker_fills=sum(t['kind']!='maker' for t in trades),episodes=episode,timeouts=timeouts,guards=guards,short_only_target_unreachable_hours=unreachable_bars*dt/3600000,outside_band_hours=outband_bars*dt/3600000,negative_usdt_hours=neg_cash_bars*dt/3600000,max_negative_usdt=max_debt,max_short_notional_to_equity=max_short,min_collateral_adjusted_equity=min_adjusted,min_adjusted_equity_to_assumed_maintenance=min_margin_ratio if math.isfinite(min_margin_ratio) else None,min_weight=min_weight,max_weight=max_weight)
 return result,trades,settlements,daily,episodes
