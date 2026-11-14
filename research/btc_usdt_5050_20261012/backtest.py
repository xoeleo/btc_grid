"""Causal spot 50/50 rebalancing comparison; no leverage or live connectivity."""
from pathlib import Path
import math,json,hashlib
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parent
CAPITAL=500000.
MODES=['hold50','daily','weekly','monthly','quarterly','band2.5','band5','band10','hold100']

def order(q,c,p,target,fee,slip):
    residual=target*(c+q*p)-q*p
    if abs(residual)<1e-8:return q,c,0.,0.,0.
    pe=p*(1+slip if residual>0 else 1-slip)
    d=residual/(p+target*(pe-p+(pe*fee if residual>0 else -pe*fee)))
    d=math.copysign(math.floor(abs(d)*100000)/100000,d)
    if abs(d)*pe<5:return q,c,0.,0.,0.
    f=abs(d)*pe*fee
    nq,nc=q+d,c-d*pe-f
    assert nq>=-1e-8 and nc>=-1e-8
    return nq,nc,d,pe,f

def due(mode,t,w,lastkey):
    if mode.startswith('band'):return abs(w-.5)>float(mode[4:])/100
    key={'daily':(t.year,t.dayofyear),'weekly':tuple(t.isocalendar()[:2]),'monthly':(t.year,t.month),'quarterly':(t.year,(t.month-1)//3)}.get(mode)
    return key is not None and key!=lastkey

def key_for(mode,t):
    return {'daily':(t.year,t.dayofyear),'weekly':tuple(t.isocalendar()[:2]),'monthly':(t.year,t.month),'quarterly':(t.year,(t.month-1)//3)}.get(mode)

def run(bars,mode,fee=.001,slip=.0002,export=True):
    q=0.;c=CAPITAL;basis=0.;realized=0.;fees=0.;turnover=0.;peak=CAPITAL;mdd=0.;trades=[];daily=[];lastkey=None
    target=1. if mode=='hold100' else .5
    times=pd.to_datetime(bars.timestamp_ms,unit='ms',utc=True).tolist()
    dates=[t.date() for t in times]
    for i,r in enumerate(bars.itertuples(index=False)):
        t=times[i];p=r.open
        pre=c+q*p;w=q*p/pre
        if i==0 or due(mode,t,w,lastkey):
            oldq,oldc=q,c
            q,c,d,pe,f=order(q,c,p,target,fee,slip)
            if d:
                if d>0:basis+=d*pe+f
                else:
                    removed=basis*(-d)/oldq;basis-=removed;realized+=(-d)*pe-f-removed
                fees+=f;turnover+=abs(d)*pe
                trades.append(dict(timestamp_ms=int(r.timestamp_ms),utc=t.isoformat(),quantity_change=d,quote=p,execution=pe,fee=f,btc=q,usdt=c,equity=c+q*p,weight=q*p/(c+q*p),realized_pnl=realized,cost_basis=basis,pre_btc=oldq,pre_usdt=oldc))
            lastkey=key_for(mode,t)
        # Both extrema observed; high-before-low is a conservative within-hour DD bound.
        for v in (pre,c+q*p,c+q*r.high,c+q*r.low,c+q*r.close):
            peak=max(peak,v);mdd=max(mdd,1-v/peak)
        if i==len(bars)-1 or dates[i+1]!=dates[i]:
            daily.append(dict(utc=pd.Timestamp(int(getattr(r,'close_timestamp_ms',r.timestamp_ms+3599999))+1,unit='ms',tz='UTC').isoformat(),equity=c+q*r.close,btc=q,usdt=c,price=r.close,weight=q*r.close/(c+q*r.close)))
    end=c+q*r.close;years=(int(r.timestamp_ms)+3600000-int(bars.timestamp_ms.iloc[0]))/(365.25*86400000)
    result=dict(mode=mode,start=pd.Timestamp(int(bars.timestamp_ms.iloc[0]),unit='ms',tz='UTC').isoformat(),end_exclusive=pd.Timestamp(int(r.timestamp_ms)+3600000,unit='ms',tz='UTC').isoformat(),initial=CAPITAL,final_equity=end,total_return=end/CAPITAL-1,cagr=(end/CAPITAL)**(1/years)-1,max_drawdown_bound=mdd,rebalance_count=len(trades)-1,fee_total=fees,turnover=turnover,final_btc=q,final_usdt=c,realized_pnl=realized,unrealized_pnl=q*r.close-basis,fee_rate=fee,slippage=slip)
    assert abs((realized+q*r.close-basis)-(end-CAPITAL))<1e-5
    return result,pd.DataFrame(trades),pd.DataFrame(daily)

def tests():
    q,c,d,pe,f=order(0,500000,60000,.5,.001,.0002)
    assert abs(q*60000/(c+q*60000)-.5)<2e-6 and d>0
    q2,c2,d2,_,_=order(q,c,80000,.5,.001,.0002)
    assert d2<0 and abs(q2*80000/(c2+q2*80000)-.5)<2e-6
    q3,c3,d3,_,_=order(q,c,40000,.5,.001,.0002)
    assert d3>0 and abs(q3*40000/(c3+q3*40000)-.5)<2e-6
    t=pd.Timestamp('2024-10-02',tz='UTC')
    assert not due('band5',t,.54,None) and due('band5',t,.56,None)
    assert not due('monthly',t,.5,(2024,10)) and due('monthly',t+pd.Timedelta(days=31),.5,(2024,10))
    b=pd.DataFrame(dict(timestamp_ms=[1727827200000,1727830800000,1727834400000],open=[60000,80000,40000],high=[61000,81000,41000],low=[59000,79000,39000],close=[60000,80000,40000]))
    _,a,_=run(b.iloc[:2],'band5');_,z,_=run(b,'band5')
    pd.testing.assert_frame_equal(a,z.iloc[:len(a)].reset_index(drop=True))
    return ['fee-aware initial allocation','sell on rise','buy on fall','threshold inactivity','calendar schedule','no future dependence','realized plus unrealized reconciles equity']

def audit(trades,daily,fee,slip):
    q=0.;c=CAPITAL
    for r in trades.itertuples():
        assert abs(r.pre_btc-q)<1e-8 and abs(r.pre_usdt-c)<1e-6
        expected=r.quote*(1+slip if r.quantity_change>0 else 1-slip)
        assert abs(r.execution-expected)<1e-8
        assert abs(r.fee-abs(r.quantity_change)*expected*fee)<1e-7
        q+=r.quantity_change;c-=r.quantity_change*expected+r.fee
        assert abs(r.btc-q)<1e-8 and abs(r.usdt-c)<1e-6
    timestamps=trades.timestamp_ms.to_numpy()
    for r in daily.itertuples():
        idx=np.searchsorted(timestamps,pd.Timestamp(r.utc).value//1000000,side='left')-1
        tr=trades.iloc[idx]
        assert abs(r.equity-(tr.usdt+tr.btc*r.price))<1e-5
    return dict(trades=len(trades),daily_equity_marks=len(daily),passed=True)

def main():
    tests_passed=tests();bars=pd.read_csv(ROOT/'data/spot_hourly.csv.gz');results=[];audits=[];yearly=[]
    (ROOT/'outputs').mkdir(exist_ok=True)
    for start in ['2018-01-01','2020-01-01','2024-10-02']:
        sub=bars[bars.timestamp_ms>=pd.Timestamp(start,tz='UTC').value//1000000].reset_index(drop=True)
        for stress in [False,True]:
            fee,slip=(.002,.0005) if stress else (.001,.0002)
            for mode in MODES:
                result,tr,day=run(sub,mode,fee,slip);name=f'{start}_{mode}_{"stress" if stress else "base"}'
                result['id']=name;results.append(result);audits.append(dict(id=name,**audit(tr,day,fee,slip)))
                tr.to_csv(ROOT/'outputs'/f'{name}_trades.csv',index=False);day.to_csv(ROOT/'outputs'/f'{name}_daily.csv',index=False)
                previous=CAPITAL
                for year,group in day.groupby((pd.to_datetime(day.utc,utc=True,format='ISO8601')-pd.Timedelta(nanoseconds=1)).dt.year):
                    final=float(group.equity.iloc[-1]);yearly.append(dict(id=name,year=int(year),return_pct=100*(final/previous-1)));previous=final
                if start=='2024-10-02' and not stress:
                    plt.plot(pd.to_datetime(day.utc,format='ISO8601'),day.equity,label=mode)
        print('completed',start,flush=True)
    plt.ylabel('Portfolio value (USDT)');plt.legend(ncol=3,fontsize=8);plt.grid(alpha=.3);plt.gcf().set_size_inches(12,6);plt.tight_layout();plt.savefig(ROOT/'equity.png',dpi=150);plt.close()
    pd.DataFrame(results).to_csv(ROOT/'results.csv',index=False);pd.DataFrame(yearly).to_csv(ROOT/'yearly.csv',index=False)
    (ROOT/'results.json').write_text(json.dumps(results,indent=2));(ROOT/'audit.json').write_text(json.dumps(dict(tests=tests_passed,scenarios=audits),indent=2))
    hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'backtest.py',ROOT/'fetch_data.py',ROOT/'data/spot_hourly.csv.gz',ROOT/'data/manifest.json']}
    (ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
    df=pd.DataFrame(results);view=df[['id','final_equity','total_return','cagr','max_drawdown_bound','rebalance_count','fee_total','final_btc','final_usdt']].copy()
    for col in ['total_return','cagr','max_drawdown_bound']:view[col]=view[col].map(lambda x:f'{100*x:.2f}%')
    for col in ['final_equity','fee_total','final_usdt']:view[col]=view[col].map(lambda x:f'{x:,.2f}')
    manifest=json.loads((ROOT/'data/manifest.json').read_text())
    notes='初始资金500,000 USDT，BTC现货/USDT目标各50%；hold100为全仓BTC对照。USDT按1美元、无利息，无杠杆。基础手续费单边0.1%+滑点2bp；压力成本0.2%+5bp。初始建仓成本已计入。每小时开盘观察占比，阈值越界后回到50%；日/周/月/季度按UTC周期首个可交易小时执行。数量步长0.00001 BTC、最小名义5 USDT为简化过滤器。结果含未卖出BTC的浮动损益，未计税费、托管和稳定币风险。回撤采用小时高低价保守边界，不代表精确成交逐笔回撤。参数为事前固定对比，未按历史最优参数筛选。'
    html='<!doctype html><meta charset="utf-8"><title>BTC/USDT 50/50再平衡</title><style>body{font-family:system-ui;margin:30px}table{border-collapse:collapse;font-size:13px}td,th{padding:7px;border:1px solid #ccc}img{max-width:100%}</style><h1>50万美元 BTC/USDT 再平衡研究</h1><p>'+notes+f'</p><p>数据：Binance BTCUSDT现货，{manifest["rows"]:,}根小时线；缺失{manifest["missing_hours"]}小时，未填价或模拟成交。54个场景，全部成交和日终净值通过独立账本重放核验。</p><img src="equity.png">'+view.to_html(index=False)+'<p>外部复核未执行：本会话没有可用内置浏览器或 ChatGPT Pro 工具。</p>'
    (ROOT/'report.html').write_text(html)
    (ROOT/'README.md').write_text(notes+'\n\n运行：python3 fetch_data.py，然后 python3 backtest.py。详情见report.html，完整成交和日终净值见outputs/。外部复核未执行：本会话没有可用内置浏览器或 ChatGPT Pro 工具。\n')
    print(df[(df.start.str.startswith('2024')) & (df.fee_rate==.001)][['mode','final_equity','total_return','cagr','max_drawdown_bound','rebalance_count','fee_total']].to_string(index=False))

if __name__=='__main__':main()
