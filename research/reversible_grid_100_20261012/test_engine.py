import pandas as pd
from dataclasses import replace
from engine import Config,simulate,MAKER
from audit import audit
START=1727827200000

def bars(prices):
 rows=[]
 for i,p in enumerate(prices):
  r={'timestamp_ms':START+i*60000}
  for prefix in ['linear','spot','mark']:
   for suffix,value in [('open',p),('high',p+1),('low',p-1),('close',p)]:r[prefix+'_'+suffix]=value
  rows.append(r)
 return pd.DataFrame(rows)

def run_tests():
 cfg=Config();b=bars([100000,123000,123000,136000,136000,126000,126000,130000,130000,123000,123000])
 f=pd.DataFrame([{'timestamp_ms':START,'rate':.0001},{'timestamp_ms':START+3*60000,'rate':-.0001}])
 r,t,s,d,batches,pairs=simulate(b,f,cfg,'unit_up');audit(r,t,s,d)
 assert batches and batches[0]['actual_slots']==100
 assert abs(sum(x['delta'] for x in t if x['batch']==1 and x['leg']=='open' and x['timestamp_ms']<=batches[0]['first_completed_ms'])+batches[0]['planned_qty'])<1e-8
 assert pairs and all(x['gross_profit']>0 for x in pairs)
 assert all(abs(x['net_grid_profit']-(x['gross_profit']-x['open_fee']-x['close_fee']))<1e-8 for x in pairs)
 assert all(x['kind']=='maker' for x in t[1:])
 assert all(x['timestamp_ms']>batches[x['batch']-1]['start_ms'] for x in t[1:])
 assert all(x['weight']<=.550001 for x in t if x['leg']=='return' and x['delta']>0)
 assert len(s)==1 and s[0]['payment']<0 and s[0]['q']==t[0]['q']
 prefix=simulate(b.iloc[:8],f,cfg,'prefix');assert prefix[1]==t[:len(prefix[1])]
 # At least one slot opens, returns, and opens again; not the former one-way batch.
 counts={}
 for x in t[1:]:counts.setdefault(x['slot'],[]).append(x['leg'])
 assert any('open,return,open' in ','.join(v) for v in counts.values())
 down=bars([100000,80000,80000,71000,71000,79000,79000,74000,74000])
 rr,tt,ss,dd,bb,pp=simulate(down,f,cfg,'unit_down');audit(rr,tt,ss,dd)
 assert bb[0]['direction']=='buy_down_sell_back' and pp
 assert all(x['weight']>=.449999 for x in tt if x['leg']=='return' and x['delta']<0)
 assert all(x['q']<=1e-9 for x in tt)
 return ['100 fixed slots and frozen total quantity','all 100 sold completes correction without timeout','positive matched grid spread after actual maker fees','explicit slot open-return-open reversibility','no grid taker exits','no fills in placement bar','upper buybacks stay <=55%','lower sellbacks stay >=45%','no perpetual net long','negative funding paid by carried short','no future dependence','independent account replay']
if __name__=='__main__':print(run_tests())
