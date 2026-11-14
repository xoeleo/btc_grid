"""Synthetic one-minute bars used by test_moving.py."""
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
