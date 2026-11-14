"""Causal ATH-dependent target allocation with moving-center reversible rebalancing."""
from dataclasses import dataclass,asdict
import math,heapq
from collections import deque
import numpy as np
CAPITAL=500000.;MAKER=.00014;TAKER=.00035;LOT=.001;TICK=.1
@dataclass(frozen=True)
class Config:
 mode:str='grid'
 step:float=.001
 levels:int=100
 low:float=.45
 high:float=.55
 adjustment_fraction:float=.05
 path:str='OLHC'
 penetration:float=.0002
 fill_fraction:float=1.
 fee_multiplier:float=1.
 slip:float=.0001
 funding_enabled:bool=True
 trigger_minutes:int=1
 borrow_apr:float=0.
 geometric:bool=True
 rearm_delay_bars:int=1
 fixed_five:bool=True
 stop_on_completion:bool=True
 target_mode:str='fixed'
 fixed_target:float=.5
 initial_ath:float=0.
 threshold:float=.05
 allow_perp_long:bool=False
 funding_overlay:bool=False
 funding_trigger:float=.0002
 funding_levels:int=100
 funding_fraction:float=.5
 funding_take_profit:float=.02
 funding_completion_net_zero:bool=False
 initial_known_funding:float=.0001
 initial_known_funding_ms:int=0


def quantize(quantity):return math.floor((max(0,quantity)+1e-12)/LOT)*LOT

def target_trade(value,cash,q,mark,execution,fee,target,allow_long=False):
 residual=target*(value+cash+q*mark)-value-q*mark
 if abs(residual)<1e-10:return 0.
 sign=1 if residual>0 else -1
 delta=residual/(mark-target*(mark-execution)+target*sign*execution*fee)
 if delta>0 and not allow_long:delta=min(delta,-q)
 return math.copysign(quantize(abs(delta)),delta)


def simulate(bars,funding,cfg=Config(),name='case'):
 a=bars.to_numpy(dtype=float);col={c:i for i,c in enumerate(bars.columns)}
 tscol=col['timestamp_ms'];phasecols={k:[col[x+'_'+k] for x in ['linear','spot','mark']] for k in ['open','low','high','close']}
 dt=int(a[1,tscol]-a[0,tscol]);assert dt in [60000,300000,3600000]
 rates=dict(zip(funding.timestamp_ms.astype('int64'),funding.rate.astype(float)))
 spot_qty=CAPITAL/a[0,col['spot_open']];q=0.;cash=0.;basis=0.;realized=0.;fundnet=0.;fees=0.;interest=0.
 peak=CAPITAL;dd=0.;min_eq=CAPITAL;min_weight=1.;max_weight=0.;min_margin=float('inf');max_debt=0.;min_adjusted=CAPITAL
 trades=[];settlements=[];daily=[];batches=[];slots=[];pairs=[];buyheap=[];sellheap=[];paused=set()
 current={-1:None,1:None};completed=0;grid_profit=0.;unreachable=0;outside=0;negcash=0
 lastborrow=int(a[0,tscol]);seq=0;lasttick=lastborrow
 overlay_q=0.;overlay_cash=0.;overlay_funding=0.;fund_current=None;waiting=set();fund_bids=[];known_rate=cfg.initial_known_funding;known_rate_ms=cfg.initial_known_funding_ms;blocked_small_funding=0
 ath=cfg.initial_ath;long_bars=0;max_long_qty=0.;max_target_error=0.;target_error_sum=0.
 assert cfg.target_mode in ['fixed','rolling_ath','frozen_ath']
 if cfg.target_mode!='fixed':assert ath>0

 def target(s):
  if cfg.target_mode=='fixed':return cfg.fixed_target
  reference=max(ath,s) if cfg.target_mode=='rolling_ath' else cfg.initial_ath
  return max(0.,min(1.,(2*reference-s)/(1.5*reference)))
 def core_equity(s,m):return equity(s,m)-(overlay_cash+overlay_q*m)
 def core_weight(s,m):return (spot_qty*s+(q-overlay_q)*m)/core_equity(s,m)
 def in_band(s,m,strict=False):
  w=core_weight(s,m)
  if cfg.target_mode=='fixed':return cfg.low<w<cfg.high if strict else cfg.low<=w<=cfg.high
  error=abs(w-target(s))
  return error<cfg.threshold if strict else error<=cfg.threshold


 def equity(s,m):return spot_qty*s+cash+q*m
 def weight(s,m):return (spot_qty*s+q*m)/equity(s,m)
 def observe(s,m,initial=False):
  nonlocal peak,dd,min_eq,min_weight,max_weight,min_margin,max_debt,min_adjusted,ath,max_long_qty,max_target_error
  if cfg.target_mode=='rolling_ath':ath=max(ath,s)
  max_long_qty=max(max_long_qty,max(0.,q))
  if not initial:max_target_error=max(max_target_error,abs(weight(s,m)-target(s)))
  e=equity(s,m);assert e>0,(name,'economic insolvency',e)
  peak=max(peak,e);dd=max(dd,1-e/peak);min_eq=min(min_eq,e)
  if not initial:
   w=weight(s,m);min_weight=min(min_weight,w);max_weight=max(max_weight,w)
  adjusted=e-.2*spot_qty*s;min_adjusted=min(min_adjusted,adjusted)
  if q:min_margin=min(min_margin,adjusted/(abs(q)*m*.01))
  max_debt=max(max_debt,max(0,-cash-basis));return e

 def execute(ts,delta,pe,s,m,kind,bid=0,sid=-1,leg='',component='base'):
  nonlocal q,cash,basis,realized,fees,overlay_q,overlay_cash
  if cfg.funding_overlay:
   if delta<0:delta=-min(abs(delta),quantize(spot_qty+q))
   elif component=='base' and not cfg.allow_perp_long:delta=min(delta,quantize(-(q-overlay_q)))
  if delta>0 and not cfg.allow_perp_long:delta=min(delta,-q)
  delta=math.copysign(quantize(abs(delta)),delta)
  if abs(delta)<LOT*.999 or abs(delta)*pe<5:return 0.
  rate=(MAKER if kind=='maker' else TAKER)*cfg.fee_multiplier
  preq,precash,prebasis=q,cash,basis;fee=abs(delta)*pe*rate
  if abs(q)<1e-9 or q*delta>0:
   basis+=delta*pe
  else:
   average=basis/q;closed=min(abs(q),abs(delta));closing_delta=math.copysign(closed,delta)
   realized+=closed*(pe-average)*math.copysign(1.,q)
   basis+=closing_delta*average
   remaining=delta-closing_delta
   basis+=remaining*pe
  cash-=delta*pe+fee;q+=delta;fees+=fee;realized-=fee
  if component=='funding':
   overlay_cash-=delta*pe+fee;overlay_q+=delta
   batches[bid-1]['cash_flow']-=delta*pe+fee
  if cfg.funding_overlay:assert spot_qty+q>=-1e-8
  if abs(q)<1e-9:q=0.;basis=0.
  assert (cfg.allow_perp_long or q<=1e-8) and abs(cash+basis-(realized+fundnet-interest))<1e-4
  trades.append(dict(timestamp_ms=int(ts),sequence=len(trades),batch=bid,slot=sid,leg=leg,kind=kind,delta=delta,execution=pe,spot=s,mark=m,fee=fee,pre_q=preq,pre_cash=precash,pre_basis=prebasis,q=q,cash=cash,basis=basis,actual_usdt=cash+basis,spot_qty=spot_qty,equity=equity(s,m),weight=weight(s,m),realized=realized,funding=fundnet,interest=interest,component=component,overlay_q=overlay_q,overlay_cash=overlay_cash,net_btc_qty=spot_qty+q,known_funding_rate=known_rate,known_funding_ms=known_rate_ms,phase_index=phase_index,target_weight=target(s),ath_reference=max(ath,s) if cfg.target_mode=='rolling_ath' else cfg.initial_ath))
  return abs(delta)

 def direct(ts,s,p,m,kind):
  gap=target(s)*core_equity(s,m)-spot_qty*s-(q-overlay_q)*m;pe=p*(1+cfg.slip if gap>0 else 1-cfg.slip)
  delta=target_trade(spot_qty*s,cash-overlay_cash,q-overlay_q,m,pe,TAKER*cfg.fee_multiplier,target(s),cfg.allow_perp_long)
  execute(ts,delta,pe,s,m,kind)

 def side_of(slot,leg):return slot['direction'] if leg=='open' else -slot['direction']
 def amount_of(slot,leg):return slot['units']-slot['held'] if leg=='open' else slot['held']
 def price_of(slot,leg):return slot['open_price'] if leg=='open' else slot['return_price']
 def arm(sid,leg,ready):
  nonlocal seq
  slot=slots[sid];key=(sid,leg);slot['version'][leg]+=1;version=slot['version'][leg]
  if batches[slot['batch']-1].get('retired') or batches[slot['batch']-1].get('grid_stopped') or amount_of(slot,leg)<=0:return
  price=price_of(slot,leg);side=side_of(slot,leg);seq+=1
  record=(price if side<0 else -price,seq,sid,leg,version,int(ready))
  heapq.heappush(sellheap if side<0 else buyheap,record)

 def create_batch(i,ts,s,p,m,direction,role='base'):
  nonlocal fund_current,blocked_small_funding
  value=(cfg.adjustment_fraction if cfg.fixed_five else abs(core_weight(s,m)-target(s)))*core_equity(s,m)
  quantity=quantize(value/m)
  if role=='funding':quantity=quantize(cfg.funding_fraction*max(0.,spot_qty+q))
  if cfg.funding_overlay and direction<0:quantity=min(quantity,quantize(spot_qty+q))
  if direction>0 and not cfg.allow_perp_long:quantity=min(quantity,quantize(-(q-overlay_q)))
  units=int(round(quantity/LOT))
  if units<=0:return
  levels=cfg.funding_levels if role=='funding' else cfg.levels
  if role=='funding' and units<levels:blocked_small_funding+=1;return
  bid=len(batches)+1;count=min(levels,units);each,extra=divmod(units,count)
  batch=dict(batch=bid,role=role,start_sequence=len(trades),grid_stopped=False,holding=False,cash_flow=0.,funding_received=0.,signal_funding_rate=known_rate,signal_funding_ms=known_rate_ms,start_net_btc_qty=spot_qty+q,direction='sell_up_buy_back' if direction<0 else 'buy_down_sell_back',start_ms=int(ts),anchor=p,start_equity=core_equity(s,m) if role=='base' else equity(s,m),start_weight=core_weight(s,m) if role=='base' else weight(s,m),planned_qty=units*LOT,requested_slots=levels,actual_slots=count,total_units=units,held_units=0,ever_traded=False,retired=False,completion_count=0,first_completed_ms=None,start_mark=m,start_spot=s,start_q=q,start_core_q=q-overlay_q,start_target=target(s),start_ath=max(ath,s) if cfg.target_mode=='rolling_ath' else cfg.initial_ath)
  batch['slot_start']=len(slots)
  batches.append(batch)
  if role=='funding':fund_current=bid;fund_bids.append(bid)
  else:current[direction]=bid
  for level in range(1,count+1):
   if cfg.geometric:
    opening=p*((1+cfg.step)**level if direction<0 else (1-cfg.step)**level)
    returning=p*((1+cfg.step)**(level-1) if direction<0 else (1-cfg.step)**(level-1))
   else:
    opening=p*(1-direction*cfg.step*level);returning=p*(1-direction*cfg.step*(level-1))
   op=math.ceil(opening/TICK)*TICK if direction<0 else math.floor(opening/TICK)*TICK
   rp=math.floor(returning/TICK)*TICK if direction<0 else math.ceil(returning/TICK)*TICK
   sid=len(slots)
   slot=dict(slot=sid,batch=bid,level=level,direction=direction,units=each+(1 if level<=extra else 0),held=0,open_price=op,return_price=rp,version={'open':0,'return':0},lots=deque())
   slots.append(slot)
  reanchor(batch,p,i)

 def reanchor(batch,price,i):
  # Exactly one global active batch; cancel both sides after any fill.
  group=slots[batch['slot_start']:batch['slot_start']+batch['actual_slots']]
  for slot in group:
   slot['version']['open']+=1;slot['version']['return']+=1
   paused.discard((slot['slot'],'open'));paused.discard((slot['slot'],'return'))
  batch['last_fill_anchor']=price
  if batch['retired'] or batch['grid_stopped']:return
  group=slots[batch['slot_start']:batch['slot_start']+batch['actual_slots']]
  opening=next((slot for slot in group if slot['held']<slot['units']),None)
  returning=next((slot for slot in reversed(group) if slot['held']>0),None)
  for slot,leg in [(opening,'open'),(returning,'return')]:
   if slot is None:continue
   side=side_of(slot,leg)
   raw=price*(1+cfg.step if side<0 else 1-cfg.step)
   px=math.ceil(raw/TICK)*TICK if side<0 else math.floor(raw/TICK)*TICK
   slot['open_price' if leg=='open' else 'return_price']=px
   arm(slot['slot'],leg,i+cfg.rearm_delay_bars)
  # One open and one return order per active batch; actors retain their own quotes.

 def valid(record):
  _,_,sid,leg,version,_=record;slot=slots[sid]
  return version==slot['version'][leg] and not batches[slot['batch']-1].get('retired') and not batches[slot['batch']-1].get('grid_stopped') and amount_of(slot,leg)>0

 def grid_fill(record,i,ts,s,m,p):
  nonlocal completed,grid_profit,fund_current
  _,_,sid,leg,_,_=record;slot=slots[sid];batch=batches[slot['batch']-1]
  side=side_of(slot,leg);price=price_of(slot,leg);units=amount_of(slot,leg)
  desired=max(1,math.floor(units*cfg.fill_fraction)) if cfg.fill_fraction>0 else 0
  delta=side*desired*LOT
  # Return only previously adjusted inventory. Exposure thresholds do not
  # suppress the counter-order of an active moving grid.
  if side>0 and not cfg.allow_perp_long:desired=min(desired,int(round(quantize(-q)/LOT)))
  if desired<=0:paused.add((sid,leg));return
  filled=execute(ts,side*desired*LOT,price,s,m,'maker',slot['batch'],sid,leg,batch['role'])
  got=int(round(filled/LOT))
  if not got:paused.add((sid,leg));return
  trades[-1].update(anchor_before=batch['last_fill_anchor'],planned_units=units)
  if not batch['ever_traded']:batch['exit_ready_i']=i+1
  batch['ever_traded']=True
  if leg=='open':
   slot['held']+=got;batch['held_units']+=got
   slot['lots'].append(dict(units=got,price=price,timestamp_ms=int(ts),fee=got*LOT*price*MAKER*cfg.fee_multiplier))
  else:
   remaining=got
   while remaining:
    opening=slot['lots'][0];used=min(remaining,opening['units']);qty=used*LOT
    openingfee=opening['fee']*used/opening['units'];closingfee=qty*price*MAKER*cfg.fee_multiplier
    gross=(-slot['direction'])*(opening['price']-price)*qty
    profit=gross-openingfee-closingfee;grid_profit+=profit
    pairs.append(dict(batch=slot['batch'],slot=sid,level=slot['level'],open_ms=opening['timestamp_ms'],close_ms=int(ts),quantity=qty,open_price=opening['price'],close_price=price,direction=slot['direction'],gross_profit=gross,open_fee=openingfee,close_fee=closingfee,net_grid_profit=profit))
    opening['units']-=used;opening['fee']-=openingfee;remaining-=used
    if not opening['units']:slot['lots'].popleft()
   slot['held']-=got;batch['held_units']-=got
  assert 0<=slot['held']<=slot['units']
  if batch['held_units']==batch['total_units'] and not (batch['role']=='funding' and cfg.funding_completion_net_zero):
   batch['completion_count']+=1
   if batch['first_completed_ms'] is None:
    batch['first_completed_ms']=int(ts);batch['first_completed_weight']=weight(s,m);completed+=1
    if batch['role']=='funding':
     batch.update(grid_stopped=True,holding=True,exit_ready_i=i+1)
     batch['held_vwap']=sum(lot['units']*lot['price'] for z in slots[batch['slot_start']:batch['slot_start']+batch['actual_slots']] for lot in z['lots'])/batch['held_units']
     batch['exit_price']=batch['held_vwap']*(1-cfg.funding_take_profit)
     waiting.add(batch['batch']);fund_current=None
    elif current[slot['direction']]==slot['batch']:current[slot['direction']]=None
    if cfg.stop_on_completion and batch['role']=='base':
     batch.update(retired=True,end_ms=int(ts),end_reason='adjustment_completed_orders_cancelled',end_weight=weight(s,m))
  if batch['role']=='base' and batch['held_units']==0 and batch['ever_traded'] and in_band(s,m):
   batch.update(retired=True,end_ms=int(ts),end_reason='all_returned_inside_band',end_weight=weight(s,m))
   if current[slot['direction']]==slot['batch']:current[slot['direction']]=None
  stop_funding_at_zero(i,ts,s,m)
  reanchor(batch,price,i)
  observe(s,m)

 def stop_funding_at_zero(i,ts,s,m):
  nonlocal fund_current,completed
  if not cfg.funding_completion_net_zero or fund_current is None or quantize(spot_qty+q)>0:return
  batch=batches[fund_current-1]
  if batch['held_units']<=0:return
  batch.update(grid_stopped=True,holding=True,exit_ready_i=i+1,completion_count=1,
   first_completed_ms=int(ts),first_completed_weight=weight(s,m),completion_reason='net_btc_zero',
   stop_sequence=len(trades)-1,stop_net_btc_qty=spot_qty+q,stop_held_units=batch['held_units'])
  batch['held_vwap']=sum(lot['units']*lot['price'] for z in slots[batch['slot_start']:batch['slot_start']+batch['actual_slots']] for lot in z['lots'])/batch['held_units']
  batch['exit_price']=batch['held_vwap']*(1-cfg.funding_take_profit)
  completed+=1;waiting.add(batch['batch']);fund_current=None
  reanchor(batch,batch['last_fill_anchor'],i)

 def close_funding(batch,i,ts,s,m,p,reason):
  nonlocal fund_current
  if batch['held_units']:
   qty=batch['held_units']*LOT
   got=execute(ts,qty,p*(1+cfg.slip),s,m,'taker',batch['batch'],-1,'exit','funding')
   assert abs(got-qty)<1e-8
  group=slots[batch['slot_start']:batch['slot_start']+batch['actual_slots']]
  for slot in group:slot['held']=0;slot['lots'].clear()
  batch.update(held_units=0,retired=True,holding=False,end_ms=int(ts),end_reason=reason,end_weight=weight(s,m))
  if fund_current==batch['batch']:fund_current=None
  waiting.discard(batch['batch']);reanchor(batch,p,i)
  observe(s,m)

 def exit_candidate(i,endp):
  candidates=[]
  active=[fund_current] if fund_current is not None else []
  for bid in list(waiting)+active:
   batch=batches[bid-1]
   if batch['retired'] or not batch['ever_traded'] or i<batch.get('exit_ready_i',i+1):continue
   price=batch['exit_price'] if batch['holding'] else batch['anchor']
   if endp<=price:candidates.append((price,bid))
  return max(candidates) if candidates else None

 def process(heap,i,ts,startp,endp,starts,ends,startm,endm,side,gap=False):
  deferred=[]
  while True:
   while heap and not valid(heap[0]):heapq.heappop(heap)
   record=None;price=None
   if heap:
    top=heap[0];px=top[0] if side<0 else -top[0]
    crossed=endp>px*(1+cfg.penetration) if side<0 else endp<px*(1-cfg.penetration)
    if crossed:
     if top[5]>i:deferred.append(heapq.heappop(heap));continue
     record=top;price=px
   candidate=exit_candidate(i,endp) if side>0 and cfg.funding_overlay else None
   do_exit=candidate is not None and (record is None or candidate[0]>=price)
   if record is None and not do_exit:break
   pe=candidate[0] if do_exit else price
   alpha=0. if gap or endp==startp else max(0.,min(1.,(pe-startp)/(endp-startp)))
   ss=starts+alpha*(ends-starts);mm=startm+alpha*(endm-startm)
   if do_exit:
    batch=batches[candidate[1]-1];actual=startp if gap else pe
    close_funding(batch,i,ts,ss,mm,actual,f'funding_{cfg.funding_take_profit*100:g}pct_take_profit' if batch['holding'] else 'funding_returned_to_anchor')
   else:
    heapq.heappop(heap);grid_fill(record,i,ts,ss,mm,price)
  for record in deferred:heapq.heappush(heap,record)

 for i,row in enumerate(a):
  phase_index=0
  ts=int(row[tscol]);lasttick=ts;p,s,m=(float(row[j]) for j in phasecols['open'])
  if ts in rates:known_rate=rates[ts];known_rate_ms=ts
  if ts in rates and i>0:
   rate=rates[ts] if cfg.funding_enabled else 0.;payment=-q*m*rate
   extra=-overlay_q*m*rate;overlay_cash+=extra;overlay_funding+=extra
   for bid in fund_bids:
    batch=batches[bid-1];batch['funding_received']+=batch['held_units']*LOT*m*rate
   cash+=payment;fundnet+=payment;settlements.append(dict(timestamp_ms=ts,kind='funding',overlay_q=overlay_q,overlay_payment=extra,q=q,mark=m,rate=rate,payment=payment,cash=cash,basis=basis))
  if ts%3600000==0 and ts>lastborrow:
   debt=max(0,-cash-basis);charge=debt*cfg.borrow_apr*(ts-lastborrow)/(365.25*86400000)
   if charge:
    cash-=charge;interest+=charge;settlements.append(dict(timestamp_ms=ts,kind='interest',q=q,mark=m,rate=cfg.borrow_apr,payment=-charge,cash=cash,basis=basis))
   lastborrow=ts
  observe(s,m,initial=i==0)
  if i==0 and cfg.mode!='spot':direct(ts,s,p,m,'initial')
  if cfg.mode=='grid':
   # An empty pending grid must not block future triggers after exposure returns inside.
   if cfg.stop_on_completion and in_band(s,m,strict=True):
    for direction,bid in list(current.items()):
     if bid is not None:
      batch=batches[bid-1]
      if not batch['retired'] and batch['held_units']==0:
       reason='all_returned_inside_band' if batch['ever_traded'] else 'unfilled_returned_inside_band'
       batch.update(retired=True,end_ms=int(ts),end_reason=reason,end_weight=weight(s,m))
       current[direction]=None
   # Reactivate exposure-blocked orders only when non-marketable; no fictitious maker fills.
   for key in list(paused):
    sid,leg=key;slot=slots[sid];price=price_of(slot,leg);side=side_of(slot,leg)
    if batches[slot['batch']-1].get('retired') or batches[slot['batch']-1].get('grid_stopped') or amount_of(slot,leg)<=0:paused.discard(key);continue
    if (side>0 and price<p) or (side<0 and price>p):
     arm(sid,leg,i+cfg.rearm_delay_bars);paused.discard(key)
   process(sellheap,i,ts,p,p,s,s,m,m,-1,True);process(buyheap,i,ts,p,p,s,s,m,m,1,True)
  if i>0 and cfg.mode in ['grid','direct'] and ts%(cfg.trigger_minutes*60000)==0:
   w=core_weight(s,m)
   tgt=target(s)
   crossed=(w>=cfg.high or w<=cfg.low) if cfg.target_mode=='fixed' else abs(w-tgt)>=cfg.threshold
   if crossed:
    if cfg.mode=='direct':direct(ts,s,p,m,'rebalance')
    else:
     direction=-1 if w>=tgt else 1
     if all(bid is None for bid in current.values()):create_batch(i,ts,s,p,m,direction)
  if i>0 and cfg.funding_overlay and known_rate>cfg.funding_trigger and fund_current is None:
   create_batch(i,ts,s,p,m,-1,'funding')
  observe(s,m)
  if not cfg.allow_perp_long and spot_qty*s<target(s)*equity(s,m):unreachable+=1
  error=abs(weight(s,m)-target(s));target_error_sum+=error
  if not in_band(s,m):outside+=1
  if q>1e-9:long_bars+=1
  if cash+basis<0:negcash+=1
  prevp,prevs,prevm=p,s,m
  for phase_index,letter in enumerate(cfg.path[1:],start=1):
   suffix={'L':'low','H':'high','C':'close'}[letter];endp,ends,endm=(float(row[j]) for j in phasecols[suffix])
   if cfg.mode=='grid':
    if endp>prevp:process(sellheap,i,ts,prevp,endp,prevs,ends,prevm,endm,-1)
    elif endp<prevp:process(buyheap,i,ts,prevp,endp,prevs,ends,prevm,endm,1)
   observe(ends,endm);prevp,prevs,prevm=endp,ends,endm
  if i==len(a)-1 or (ts+dt)//86400000!=ts//86400000:
   daily.append(dict(timestamp_ms=ts+dt,equity=equity(prevs,prevm),spot_qty=spot_qty,short_qty=-q,spot=prevs,mark=prevm,cash=cash,basis=basis,actual_usdt=cash+basis,weight=weight(prevs,prevm),funding=fundnet,fees=fees,interest=interest,realized=realized,unrealized_perp=q*prevm-basis,grid_profit=grid_profit,target_weight=target(prevs),ath_reference=max(ath,prevs) if cfg.target_mode=='rolling_ath' else cfg.initial_ath,long_qty=max(0.,q),overlay_q=overlay_q,overlay_cash=overlay_cash,overlay_pnl=overlay_cash+overlay_q*prevm,overlay_funding=overlay_funding,core_equity=core_equity(prevs,prevm),core_weight=core_weight(prevs,prevm),net_btc_qty=spot_qty+q))
 for batch in batches:
  if not batch.get('retired'):batch.update(end_ms=lasttick+dt,end_reason='funding_waiting_at_end' if batch.get('holding') else 'open_at_end',end_weight=weight(prevs,prevm))
 final=equity(prevs,prevm);spotpnl=spot_qty*(prevs-a[0,col['spot_open']]);unreal=q*prevm-basis
 assert abs(final-CAPITAL-(spotpnl+realized+unreal+fundnet-interest))<1e-4
 years=(lasttick+dt-int(a[0,tscol]))/(365.25*86400000)
 result=dict(id=name,config=asdict(cfg),start_ms=int(a[0,tscol]),end_exclusive_ms=lasttick+dt,interval_minutes=dt//60000,final_equity=final,total_return=final/CAPITAL-1,cagr=math.expm1(math.log(final/CAPITAL)/years) if abs(math.log(final/CAPITAL)/years)<700 else None,max_drawdown=dd,min_equity=min_eq,funding_net=fundnet,fees=fees,borrow_interest=interest,spot_pnl=spotpnl,perp_realized_net_fees=realized,perp_unrealized=unreal,final_actual_usdt=cash+basis,final_spot_qty=spot_qty,final_short_qty=-q,final_weight=weight(prevs,prevm),maker_fills=sum(x['kind']=='maker' for x in trades),taker_fills=sum(x['kind']!='maker' for x in trades),grid_pair_count=len(pairs),matched_grid_profit=grid_profit,batches=len(batches),batches_completed=completed,min_weight=min_weight,max_weight=max_weight,outside_band_hours=outside*dt/3600000,short_only_target_unreachable_hours=unreachable*dt/3600000,negative_usdt_hours=negcash*dt/3600000,max_negative_usdt=max_debt,min_collateral_adjusted_equity=min_adjusted,min_adjusted_equity_to_assumed_maintenance=min_margin if math.isfinite(min_margin) else None,final_target=target(prevs),final_ath=ath,max_long_qty=max_long_qty,long_position_hours=long_bars*dt/3600000,average_open_target_error=target_error_sum/len(a),max_target_error=max_target_error,overlay_q=overlay_q,overlay_final_pnl=overlay_cash+overlay_q*prevm,overlay_funding_received=overlay_funding,funding_batches=len(fund_bids),funding_grids_completed=sum(batches[bid-1]['completion_count'] for bid in fund_bids),funding_closed_batches=sum(batches[bid-1]['retired'] for bid in fund_bids),funding_waiting_batches=len(waiting),funding_min_lot_blocked_minutes=blocked_small_funding)
 return result,trades,settlements,daily,batches,pairs
