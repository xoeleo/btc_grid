import math,importlib.util
from pathlib import Path
import pandas as pd
from engine import Config,simulate,quantize
from audit import audit
from test_engine import bars,START,run_tests

def run_checks():
 checks=run_tests()
 cfg=Config(levels=20,low=.40,high=.60,adjustment_fraction=.10)
 f=pd.DataFrame([dict(timestamp_ms=START,rate=.0001),dict(timestamp_ms=START+3*60000,rate=-.0001)])
 b=bars([100000,130000,130000,150000,150000,154000,154000,149000,149000,151000,151000,145000,145000])
 r,t,s,d,batches,pairs=simulate(b,f,cfg,'4060_up');audit(r,t,s,d)
 assert batches[0]['start_ms']==START+3*60000
 assert batches[0]['actual_slots']==20
 assert abs(batches[0]['planned_qty']-quantize(.10*batches[0]['start_equity']/150000))<1e-8
 assert batches[0]['first_completed_ms'] is not None and pairs
 assert all(x['weight']<=.600001 for x in t if x['leg']=='return' and x['delta']>0)
 assert all(x['kind']=='maker' for x in t[1:])
 prefix=simulate(b.iloc[:9],f,cfg,'prefix');assert prefix[1]==t[:len(prefix[1])]
 down=bars([100000,80000,80000,66000,66000,64000,64000,66000,66000,65000,65000])
 rr,tt,ss,dd,bb,pp=simulate(down,f,cfg,'4060_down');audit(rr,tt,ss,dd)
 assert bb[0]['start_ms']==START+3*60000 and bb[0]['direction']=='buy_down_sell_back'
 assert all(x['weight']>=.399999 for x in tt if x['leg']=='return' and x['delta']<0)
 # Re-parameterization must exactly preserve prior-default behavior, not only headline equity.
 path=Path(__file__).resolve().parent.parent/'reversible_grid_20_20261012/engine.py'
 spec=importlib.util.spec_from_file_location('old_engine',path);old=importlib.util.module_from_spec(spec)
 import sys
 sys.modules['old_engine']=old;spec.loader.exec_module(old)
 prior=old.simulate(b,f,old.Config(levels=20),'reference');same=simulate(b,f,Config(levels=20),'new_default')
 for i in range(1,6):assert prior[i]==same[i]
 for key in ['final_equity','max_drawdown','fees','funding_net','matched_grid_profit']:assert prior[0][key]==same[0][key]
 return checks+['40-60 does not trigger at former 45-55 boundary','fixed 10% budget allocated into 20 slots','60% upper return bound','40% lower return bound','40-60 no future dependence','exact old-default regression: trades settlements inventory pairs equity']
if __name__=='__main__':print(run_checks())
