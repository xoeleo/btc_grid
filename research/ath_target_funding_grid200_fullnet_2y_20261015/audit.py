from engine import MAKER,TAKER
def audit(result,trades,settlements,daily):
 """Independent replay exported ledger, including actual USDT and daily inventory marks."""
 q=0.;cash=0.;basis=0.;realized=0.;funding=0.;interest=0.;fees=0.
 events=[]
 for x in settlements:events.append((x['timestamp_ms'],0 if x['kind']=='funding' else 1,'settlement',x))
 for i,x in enumerate(trades):events.append((x['timestamp_ms'],2+i/1000000,'trade',x))
 for x in daily:events.append((x['timestamp_ms'],-1,'day',x))
 for ts,_,kind,x in sorted(events,key=lambda z:(z[0],z[1])):
  if kind=='settlement':
   assert abs(q-x['q'])<1e-7
   if x['kind']=='funding':
    expected=-q*x['mark']*x['rate'];assert abs(expected-x['payment'])<1e-5;funding+=expected
   else:interest-=x['payment']
   cash+=x['payment'];assert abs(cash-x['cash'])<1e-5
  elif kind=='trade':
   assert abs(q-x['pre_q'])<1e-7 and abs(cash-x['pre_cash'])<1e-5
   d=x['delta'];fee=abs(d)*x['execution']*(MAKER if x['kind']=='maker' else TAKER)*result['config']['fee_multiplier']
   assert abs(fee-x['fee'])<1e-7
   if abs(q)<1e-9 or q*d>0:basis+=d*x['execution']
   else:
    old_average=basis/q
    closed=min(abs(q),abs(d))
    closing_delta=closed if d>0 else -closed
    realized+=closing_delta*(old_average-x['execution'])
    basis+=closing_delta*old_average+(d-closing_delta)*x['execution']
   realized-=fee;fees+=fee;cash-=d*x['execution']+fee;q+=d
   if abs(q)<1e-9:q=0.;basis=0.
   assert (result['config'].get('allow_perp_long') or q<=1e-8) and abs(q-x['q'])<1e-7
   assert abs(basis-x['basis'])<1e-5
   assert abs(cash+basis-(realized+funding-interest))<1e-5
  else:
   expected=x['spot_qty']*x['spot']+cash+q*x['mark']
   assert abs(expected-x['equity'])<1e-5,(result['id'],ts,expected,x['equity'])
   assert abs(-q-x['short_qty'])<1e-7 and abs(cash+basis-x['actual_usdt'])<1e-5
 last=daily[-1]
 assert abs(last['equity']-result['final_equity'])<1e-5
 assert abs(funding-result['funding_net'])<1e-5 and abs(fees-result['fees'])<1e-5
 assert abs(realized-result['perp_realized_net_fees'])<1e-5
 assert abs(interest-result['borrow_interest'])<1e-5
 return dict(id=result['id'],passed=True,trades=len(trades),settlements=len(settlements),daily_marks=len(daily))
