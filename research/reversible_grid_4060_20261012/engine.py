"""Fixed 100-slot reversible rebalancing. No timeout, no forced grid liquidation."""
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


def quantize(quantity):return math.floor((max(0,quantity)+1e-12)/LOT)*LOT

def target_trade(value,cash,q,mark,execution,fee,target):
 residual=target*(value+cash+q*mark)-value-q*mark
 if abs(residual)<1e-10:return 0.
 sign=1 if residual>0 else -1
 delta=residual/(mark-target*(mark-execution)+target*sign*execution*fee)
 if delta>0:delta=min(delta,-q)
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

 def equity(s,m):return spot_qty*s+cash+q*m
 def weight(s,m):return (spot_qty*s+q*m)/equity(s,m)
 def observe(s,m,initial=False):
  nonlocal peak,dd,min_eq,min_weight,max_weight,min_margin,max_debt,min_adjusted
  e=equity(s,m);assert e>0,(name,'economic insolvency',e)
  peak=max(peak,e);dd=max(dd,1-e/peak);min_eq=min(min_eq,e)
  if not initial:
   w=weight(s,m);min_weight=min(min_weight,w);max_weight=max(max_weight,w)
  adjusted=e-.2*spot_qty*s;min_adjusted=min(min_adjusted,adjusted)
  if q:min_margin=min(min_margin,adjusted/(abs(q)*m*.01))
  max_debt=max(max_debt,max(0,-cash-basis));return e

 def execute(ts,delta,pe,s,m,kind,bid=0,sid=-1,leg=''):
  nonlocal q,cash,basis,realized,fees
  if delta>0:delta=min(delta,-q)
  delta=math.copysign(quantize(abs(delta)),delta)
  if abs(delta)<LOT*.999 or abs(delta)*pe<5:return 0.
  rate=(MAKER if kind=='maker' else TAKER)*cfg.fee_multiplier
  preq,precash,prebasis=q,cash,basis;fee=abs(delta)*pe*rate
  if delta<0:basis+=delta*pe
  else:
   average=basis/q;realized+=delta*(average-pe);basis+=delta*average
  cash-=delta*pe+fee;q+=delta;fees+=fee;realized-=fee
  if abs(q)<1e-9:q=0.;basis=0.
  assert q<=1e-8 and abs(cash+basis-(realized+fundnet-interest))<1e-4
  trades.append(dict(timestamp_ms=int(ts),sequence=len(trades),batch=bid,slot=sid,leg=leg,kind=kind,delta=delta,execution=pe,spot=s,mark=m,fee=fee,pre_q=preq,pre_cash=precash,pre_basis=prebasis,q=q,cash=cash,basis=basis,actual_usdt=cash+basis,spot_qty=spot_qty,equity=equity(s,m),weight=weight(s,m),realized=realized,funding=fundnet,interest=interest))
  return abs(delta)

 def direct(ts,s,p,m,kind):
  gap=.5*equity(s,m)-spot_qty*s-q*m;pe=p*(1+cfg.slip if gap>0 else 1-cfg.slip)
  delta=target_trade(spot_qty*s,cash,q,m,pe,TAKER*cfg.fee_multiplier,.5)
  execute(ts,delta,pe,s,m,kind)

 def side_of(slot,leg):return slot['direction'] if leg=='open' else -slot['direction']
 def amount_of(slot,leg):return slot['units']-slot['held'] if leg=='open' else slot['held']
 def price_of(slot,leg):return slot['open_price'] if leg=='open' else slot['return_price']
 def arm(sid,leg,ready):
  nonlocal seq
  slot=slots[sid];key=(sid,leg);slot['version'][leg]+=1;version=slot['version'][leg]
  if batches[slot['batch']-1].get('retired') or amount_of(slot,leg)<=0:return
  price=price_of(slot,leg);side=side_of(slot,leg);seq+=1
  record=(price if side<0 else -price,seq,sid,leg,version,int(ready))
  heapq.heappush(sellheap if side<0 else buyheap,record)

 def create_batch(i,ts,s,p,m,direction):
  value=(cfg.adjustment_fraction if cfg.fixed_five else abs(weight(s,m)-.5))*equity(s,m)
  quantity=quantize(value/m)
  if direction>0:quantity=min(quantity,quantize(-q))
  units=int(round(quantity/LOT))
  if units<=0:return
  bid=len(batches)+1;count=min(cfg.levels,units);each,extra=divmod(units,count)
  batch=dict(batch=bid,direction='sell_up_buy_back' if direction<0 else 'buy_down_sell_back',start_ms=int(ts),anchor=p,start_equity=equity(s,m),start_weight=weight(s,m),planned_qty=units*LOT,requested_slots=cfg.levels,actual_slots=count,total_units=units,held_units=0,ever_traded=False,retired=False,completion_count=0,first_completed_ms=None)
  batches.append(batch);current[direction]=bid
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
   slots.append(slot);arm(sid,'open',i+cfg.rearm_delay_bars)

 def valid(record):
  _,_,sid,leg,version,_=record;slot=slots[sid]
  return version==slot['version'][leg] and not batches[slot['batch']-1].get('retired') and amount_of(slot,leg)>0

 def grid_fill(record,i,ts,s,m,p):
  nonlocal completed,grid_profit
  _,_,sid,leg,_,_=record;slot=slots[sid];batch=batches[slot['batch']-1]
  side=side_of(slot,leg);price=price_of(slot,leg);units=amount_of(slot,leg)
  desired=max(1,math.floor(units*cfg.fill_fraction)) if cfg.fill_fraction>0 else 0
  delta=side*desired*LOT
  # Returned upper-grid inventory must stay <=55%; lower-grid return must stay >=45%.
  # Opening quantities remain frozen and are NOT recomputed to 50% after fills.
  if leg=='return':
   bound=cfg.high if side>0 else cfg.low
   cap=target_trade(spot_qty*s,cash,q,m,price,MAKER*cfg.fee_multiplier,bound)
   if cap*side<=0:paused.add((sid,leg));return
   desired=min(desired,int(round(abs(cap)/LOT)))
  if side>0:desired=min(desired,int(round(quantize(-q)/LOT)))
  if desired<=0:paused.add((sid,leg));return
  filled=execute(ts,side*desired*LOT,price,s,m,'maker',slot['batch'],sid,leg)
  got=int(round(filled/LOT))
  if not got:paused.add((sid,leg));return
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
  # Resting unfilled remainder remains live; newly reversed leg waits until next bar.
  arm(sid,leg,i+1)
  arm(sid,'return' if leg=='open' else 'open',i+cfg.rearm_delay_bars)
  paused.discard((sid,leg))
  if batch['held_units']==batch['total_units']:
   batch['completion_count']+=1
   if batch['first_completed_ms'] is None:
    batch['first_completed_ms']=int(ts);batch['first_completed_weight']=weight(s,m);completed+=1
    if current[slot['direction']]==slot['batch']:current[slot['direction']]=None
  if batch['held_units']==0 and batch['ever_traded'] and cfg.low<=weight(s,m)<=cfg.high:
   batch.update(retired=True,end_ms=int(ts),end_reason='all_returned_inside_band',end_weight=weight(s,m))
   if current[slot['direction']]==slot['batch']:current[slot['direction']]=None
  observe(s,m)

 def process(heap,i,ts,startp,endp,starts,ends,startm,endm,side,gap=False):
  deferred=[]
  while heap:
   top=heap[0];price=top[0] if side<0 else -top[0]
   crossed=endp>price*(1+cfg.penetration) if side<0 else endp<price*(1-cfg.penetration)
   if not crossed:break
   record=heapq.heappop(heap)
   if not valid(record):continue
   if record[5]>i:deferred.append(record);continue
   alpha=0. if gap or endp==startp else max(0.,min(1.,(price-startp)/(endp-startp)))
   s=starts+alpha*(ends-starts);m=startm+alpha*(endm-startm)
   grid_fill(record,i,ts,s,m,price)
  for record in deferred:heapq.heappush(heap,record)

 for i,row in enumerate(a):
  ts=int(row[tscol]);lasttick=ts;p,s,m=(float(row[j]) for j in phasecols['open'])
  if ts in rates and i>0:
   rate=rates[ts] if cfg.funding_enabled else 0.;payment=-q*m*rate
   cash+=payment;fundnet+=payment;settlements.append(dict(timestamp_ms=ts,kind='funding',q=q,mark=m,rate=rate,payment=payment,cash=cash,basis=basis))
  if ts%3600000==0 and ts>lastborrow:
   debt=max(0,-cash-basis);charge=debt*cfg.borrow_apr*(ts-lastborrow)/(365.25*86400000)
   if charge:
    cash-=charge;interest+=charge;settlements.append(dict(timestamp_ms=ts,kind='interest',q=q,mark=m,rate=cfg.borrow_apr,payment=-charge,cash=cash,basis=basis))
   lastborrow=ts
  observe(s,m,initial=i==0)
  if i==0 and cfg.mode!='spot':direct(ts,s,p,m,'initial')
  if cfg.mode=='grid':
   # Reactivate exposure-blocked orders only when non-marketable; no fictitious maker fills.
   for key in list(paused):
    sid,leg=key;slot=slots[sid];price=price_of(slot,leg);side=side_of(slot,leg)
    if batches[slot['batch']-1].get('retired') or amount_of(slot,leg)<=0:paused.discard(key);continue
    if (side>0 and price<p) or (side<0 and price>p):
     arm(sid,leg,i+cfg.rearm_delay_bars);paused.discard(key)
   process(sellheap,i,ts,p,p,s,s,m,m,-1,True);process(buyheap,i,ts,p,p,s,s,m,m,1,True)
  if i>0 and cfg.mode in ['grid','direct'] and ts%(cfg.trigger_minutes*60000)==0:
   w=weight(s,m)
   if w>=cfg.high or w<=cfg.low:
    if cfg.mode=='direct':direct(ts,s,p,m,'rebalance')
    else:
     direction=-1 if w>=cfg.high else 1
     if current[direction] is None:create_batch(i,ts,s,p,m,direction)
  observe(s,m)
  if spot_qty*s<.5*equity(s,m):unreachable+=1
  if weight(s,m)<cfg.low or weight(s,m)>cfg.high:outside+=1
  if cash+basis<0:negcash+=1
  prevp,prevs,prevm=p,s,m
  for letter in cfg.path[1:]:
   suffix={'L':'low','H':'high','C':'close'}[letter];endp,ends,endm=(float(row[j]) for j in phasecols[suffix])
   if cfg.mode=='grid':
    if endp>prevp:process(sellheap,i,ts,prevp,endp,prevs,ends,prevm,endm,-1)
    elif endp<prevp:process(buyheap,i,ts,prevp,endp,prevs,ends,prevm,endm,1)
   observe(ends,endm);prevp,prevs,prevm=endp,ends,endm
  if i==len(a)-1 or (ts+dt)//86400000!=ts//86400000:
   daily.append(dict(timestamp_ms=ts+dt,equity=equity(prevs,prevm),spot_qty=spot_qty,short_qty=-q,spot=prevs,mark=prevm,cash=cash,basis=basis,actual_usdt=cash+basis,weight=weight(prevs,prevm),funding=fundnet,fees=fees,interest=interest,realized=realized,unrealized_perp=q*prevm-basis,grid_profit=grid_profit))
 for batch in batches:
  if not batch.get('retired'):batch.update(end_ms=lasttick+dt,end_reason='open_at_end',end_weight=weight(prevs,prevm))
 final=equity(prevs,prevm);spotpnl=spot_qty*(prevs-a[0,col['spot_open']]);unreal=q*prevm-basis
 assert abs(final-CAPITAL-(spotpnl+realized+unreal+fundnet-interest))<1e-4
 years=(lasttick+dt-int(a[0,tscol]))/(365.25*86400000)
 result=dict(id=name,config=asdict(cfg),start_ms=int(a[0,tscol]),end_exclusive_ms=lasttick+dt,interval_minutes=dt//60000,final_equity=final,total_return=final/CAPITAL-1,cagr=math.expm1(math.log(final/CAPITAL)/years) if abs(math.log(final/CAPITAL)/years)<700 else None,max_drawdown=dd,min_equity=min_eq,funding_net=fundnet,fees=fees,borrow_interest=interest,spot_pnl=spotpnl,perp_realized_net_fees=realized,perp_unrealized=unreal,final_actual_usdt=cash+basis,final_spot_qty=spot_qty,final_short_qty=-q,final_weight=weight(prevs,prevm),maker_fills=sum(x['kind']=='maker' for x in trades),taker_fills=sum(x['kind']!='maker' for x in trades),grid_pair_count=len(pairs),matched_grid_profit=grid_profit,batches=len(batches),batches_completed=completed,min_weight=min_weight,max_weight=max_weight,outside_band_hours=outside*dt/3600000,short_only_target_unreachable_hours=unreachable*dt/3600000,negative_usdt_hours=negcash*dt/3600000,max_negative_usdt=max_debt,min_collateral_adjusted_equity=min_adjusted,min_adjusted_equity_to_assumed_maintenance=min_margin if math.isfinite(min_margin) else None)
 return result,trades,settlements,daily,batches,pairs
