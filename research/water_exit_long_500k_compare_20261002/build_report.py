"""Audit the paired 500k long runs and independently reconstruct every exit threshold."""
from pathlib import Path
import base64, hashlib, json
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager

ROOT=Path(__file__).resolve().parent
NAMES={'exit12_olhc':'1%减半 / 2%清仓 · 先跌后涨','exit12_ohlc':'1%减半 / 2%清仓 · 先涨后跌',
       'exit25_olhc':'2%减半 / 5%清仓 · 先跌后涨','exit25_ohlc':'2%减半 / 5%清仓 · 先涨后跌'}
manifest=json.loads((ROOT/'data/manifest.json').read_text())
for name,expected in manifest['files'].items():
    assert hashlib.sha256((ROOT/'data'/name).read_bytes()).hexdigest()==expected
bars=pd.read_csv(ROOT/'data/bars.csv.gz')
assert len(bars)==manifest['rows'] and bars.timestamp_ms.diff().iloc[1:].eq(60000).all()
results={}; checks={}; frames={}; monthly_rows=[]; directions=[]
def near(x,y,tol=0.0001):assert abs(float(x)-float(y))<tol,(x,y)
def entry_price(ts,path):
    index=(int(ts)-manifest['start_ms'])//60000; second=(int(ts)%60000)/1000
    a=bars.iloc[index]
    if path=='CLOSE':return a['open']+(a['close']-a['open'])*second/60
    seq=['open','high','low','close'] if path=='OHLC' else ['open','low','high','close']
    leg=int(second//20); frac=(second%20)/20
    return a[seq[leg]]+(a[seq[leg+1]]-a[seq[leg]])*frac
for name in NAMES:
    folder=ROOT/name; s=json.loads((folder/'summary.json').read_text()); results[name]=s
    tr=pd.read_csv(folder/'trades.csv'); cy=pd.read_csv(folder/'cycles.csv')
    fu=pd.read_csv(folder/'funding_events.csv'); eq=pd.read_csv(folder/'equity.csv'); frames[name]=eq
    assert s['minutes']==manifest['rows'] and s['start_ms']==manifest['start_ms'] and s['end_ms']==manifest['end_exclusive_ms']
    assert s['initial_capital']==500000 and tr.timestamp_ms.is_monotonic_increasing
    sign=np.where(tr.side.eq('BUY'),1.,-1.);tr['signed_qty']=tr.qty*sign;tr['signed_value']=tr.qty*tr.price*sign
    assert len(tr)==s['fills'] and len(cy)==s['completed_cycles']
    near(tr.fee.sum(),s['trading_fees']);near(tr.signed_qty.sum(),s['open_qty'],1e-9)
    assert np.max(abs(tr.groupby('cycle_id').signed_qty.cumsum()-tr.net_qty))<1e-9
    initial=tr.groupby('cycle_id').first()
    assert initial.kind.eq('initial').all() and initial.qty.eq(0.030).all()
    cycle_sign=initial.side.map({'BUY':1,'SELL':-1})
    assert (tr.net_qty*tr.cycle_id.map(cycle_sign)>=-1e-12).all()
    assert tr.net_qty.abs().max()<=5;near(tr.net_qty.abs().max(),s['peak_btc_qty'],1e-9)
    assert initial.side.eq('BUY').all() and s['completed_short_cycles']==0
    half,full=(0.01,0.02) if name.startswith('exit12') else (0.02,0.05)
    assert s['half_rate']==half and s['full_rate']==full
    fee_rate=tr.fee/(tr.qty*tr.price)
    assert np.minimum(abs(fee_rate-0.00014),abs(fee_rate-0.00035)).max()<1e-12
    assert (abs(fee_rate[tr.kind.ne('grid')]-0.00035)<1e-12).all()
    expected_funding=pd.read_csv(ROOT/'data/funding.csv')
    assert np.array_equal(fu.timestamp_ms,expected_funding.timestamp_ms) and len(fu)==2190
    assert abs(fu.rate-expected_funding.rate).max()<1e-14 and abs(fu.qty*fu.mark*fu.rate-fu.paid).max()<1e-9
    near(fu.paid.sum(),s['funding_paid'])
    times=tr.timestamp_ms.to_numpy(); net=tr.net_qty.to_numpy()
    for row in fu.itertuples():
        lo=np.searchsorted(times,row.timestamp_ms,'left');hi=np.searchsorted(times,row.timestamp_ms,'right')
        candidate=[net[lo-1] if lo else 0.]+list(net[lo:hi])
        assert min(abs(row.qty-x) for x in candidate)<1e-9,('settlement inventory',row.timestamp_ms,row.qty)
    grouped=tr.groupby('cycle_id').agg(qty=('signed_qty','sum'),value=('signed_value','sum'),fee=('fee','sum'))
    for row in cy.itertuples():
        g=grouped.loc[row.cycle_id];near(g.qty,0,1e-9);near(g.fee,row.trading_fee);near(-g.value-g.fee-row.funding_paid,row.pnl)
        assert row.direction=='LONG'
        assert row.reason==f'{int(full*100)}pct' or row.reason=='flat'
    near(cy.pnl.sum(),s['closed_cycle_pnl'])
    near(500000-tr.signed_value.sum()-tr.fee.sum()-fu.paid.sum()+s['open_qty']*eq.iloc[-1]['mark'],s['final_equity_marked'])
    near(500000+s['closed_cycle_pnl']+s['open_cycle_pnl'],s['final_equity_marked']);near(eq.iloc[-1].equity,s['final_equity_marked'])
    assert eq.free_margin.min()>=-0.01 and s['min_free_margin']>=-0.01
    assert abs(eq.net_notional-eq.net_qty.abs()*eq.mark).max()<1e-6
    assert (eq.net_notional/eq.equity).max()<=1.000001
    covers=tr[tr.kind.eq('margin_cover')];assert len(covers)==s['margin_cover_events'];near(covers.qty.sum(),s['margin_cover_qty'],1e-9)
    for direction in ['LONG','SHORT']:
        d=cy[cy.direction.eq(direction)]
        assert len(d)==s['completed_long_cycles' if direction=='LONG' else 'completed_short_cycles']
        directions.append({'scenario':name,'direction':direction,'completed_cycles':len(d),'closed_cycle_pnl':d.pnl.sum(),
            'open_cycle_pnl':s['open_cycle_pnl'] if s['open_direction']==direction else 0,
            'total_pnl':d.pnl.sum()+(s['open_cycle_pnl'] if s['open_direction']==direction else 0)})
    # Independently reconstruct pre-exit water from fills and funding, then check every threshold.
    qty_before=tr.groupby('cycle_id').signed_qty.cumsum()-tr.signed_qty
    value_before=tr.groupby('cycle_id').signed_value.cumsum()-tr.signed_value
    fee_before=tr.groupby('cycle_id').fee.cumsum()-tr.fee
    fu['cycle_id']=np.searchsorted(initial.timestamp_ms.to_numpy(),fu.timestamp_ms.to_numpy(),side='right')
    fu['cumulative_paid']=fu.groupby('cycle_id').paid.cumsum()
    water_checks=0
    for i,row in tr[tr.kind.eq('water')].iterrows():
        f=fu[fu.cycle_id.eq(row.cycle_id)]
        k=np.searchsorted(f.timestamp_ms.to_numpy(),row.timestamp_ms,side='right')
        funding_before=f.cumulative_paid.iloc[k-1] if k else 0.
        water=(value_before.iloc[i]+fee_before.iloc[i]+funding_before)/qty_before.iloc[i]
        bar=bars.iloc[(int(row.timestamp_ms)-manifest['start_ms'])//60000]
        second=(int(row.timestamp_ms)%60000)/1000
        seq=['mark_open','mark_high','mark_low','mark_close'] if s['path']=='OHLC' else ['mark_open','mark_low','mark_high','mark_close']
        leg=int(second//20);frac=(second%20)/20
        mark=bar[seq[leg]]+(bar[seq[leg+1]]-bar[seq[leg]])*frac
        rate=(mark-water)/water if water>0 else full
        is_full=abs(row.qty-qty_before.iloc[i])<1e-9
        assert rate+1e-10>=(full if is_full else half),('water exit below threshold',name,row.timestamp_ms,rate,full if is_full else half)
        if not is_full:near(row.qty,np.floor((qty_before.iloc[i]/2+1e-12)*1000)/1000,1e-9)
        water_checks+=1
    idx=pd.to_datetime(eq.timestamp_ms,unit='ms',utc=True)-pd.Timedelta(nanoseconds=1)
    monthly=eq.assign(month=idx.dt.strftime('%Y-%m')).groupby('month').equity.last()
    previous=500000.
    for month,value in monthly[monthly.index>='2024-10'].items():
        monthly_rows.append({'scenario':name,'month':month,'end_equity':value,'net_pnl':value-previous,'return_pct':(value/previous-1)*100});previous=value
    checks[name]={'passed':True,'fills_checked':len(tr),'closed_cycles_checked':len(cy),'funding_events_checked':len(fu),
                 'cycle_entry_direction_checked':True,'cycle_direction_locked_checked':True,'signed_accounting_checked':True,'one_times_exposure_cap_checked':True,'exit_threshold_fills_checked':water_checks}

font=Path('/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc')
if font.exists():
    font_manager.fontManager.addfont(str(font));plt.rcParams['font.family']=font_manager.FontProperties(fname=str(font)).get_name()
plt.rcParams['axes.unicode_minus']=False
fig,axs=plt.subplots(3,1,figsize=(14,10),sharex=True,gridspec_kw={'height_ratios':[2,1,1]})
for (name,label),color in zip(NAMES.items(),['#1664ca','#65a3df','#d97624','#efaf72']):
    e=frames[name];t=pd.to_datetime(e.timestamp_ms,unit='ms',utc=True)
    axs[0].plot(t,e.equity,label=label,color=color,lw=1)
    axs[1].plot(t,(e.equity/e.equity.cummax()-1)*100,color=color,lw=.8)
    axs[2].plot(t,e.net_notional/e.equity*100,color=color,lw=.7,alpha=.8)
axs[0].axhline(500000,color='#888',ls=':',lw=.7)
axs[0].set_title('50万 USDT · 始终做多 · 动态水位1%减半/2%清仓 vs 2%减半/5%清仓\n2024-10-02 — 2026-10-01（UTC）· 单格0.006 BTC · 起始5格')
axs[0].set_ylabel('资产净值 / USDT');axs[0].legend(ncol=2,fontsize=9,loc='upper left')
axs[1].set_ylabel('小时采样回撤 / %');axs[2].set_ylabel('持仓名义金额 / 净值 %')
for a in axs:a.grid(alpha=.18)
fig.tight_layout();fig.savefig(ROOT/'equity_drawdown.png',dpi=160);fig.savefig(ROOT/'equity_drawdown.svg');plt.close(fig)

parameters={'initial_capital_usdt':500000,'direction':'always LONG; next Cycle after completion',
    'exit_regimes':[[1,2],[2,5]],'grid_qty_btc':0.006,'initial_cells':5,'spacing_pct':0.1,
    'max_inventory_btc':5,'max_grid_order_btc':0.1,'maker_fee_pct':0.014,'taker_fee_pct':0.035,
    'slippage_bps':1,'poll_seconds':15,'order_timeout_minutes':15,'requote_shrink_factor':0.85,
    'capital_constraint':'abs(q)*mark <= marked equity; reject unfunded adds; minimal close if needed',
    'price_trigger':None,'asset_ratio_end':None}
report={'parameters':parameters,'period':manifest,'results':results,'audit':checks,
    'limitations':['minute price paths are assumed, not actual tick order or mathematical return bounds',
       'full fills at touch, immediate simulated cancel; no depth/queue/partial fills',
       'current fees fixed throughout history; no independent future-period validation'],
    'external_review':'not performed; built-in browser unavailable'}
(ROOT/'results.json').write_text(json.dumps(report,indent=2,ensure_ascii=False));(ROOT/'audit.json').write_text(json.dumps(checks,indent=2))
pd.DataFrame(monthly_rows).to_csv(ROOT/'monthly_results.csv',index=False)
rows='';details=''
for name,label in NAMES.items():
    s=results[name]
    rows+=f'<tr><td>{label}</td><td>{s["final_equity_marked"]:,.2f}</td><td>{s["net_pnl"]:+,.2f}</td><td>{s["return_pct"]:.2f}%</td><td>{s["max_drawdown_pct"]:.2f}%</td><td>{s["trading_fees"]:,.2f}</td><td>{s["funding_paid"]:,.2f}</td><td>{s["completed_cycles"]}</td></tr>'
    longest=max(s['max_completed_cycle_hours'],s['open_cycle_hours'])/24
    details+=f'<tr><td>{label}</td><td>{s["average_exposure_pct"]:.2f}%</td><td>{s["peak_btc_qty"]:.3f}</td><td>{s["min_free_margin"]:,.2f}</td><td>{s["capital_blocked_add_attempts"]}</td><td>{s["open_qty"]:.3f}</td><td>{s["open_cycle_pnl"]:,.2f}</td><td>{s["open_cycle_hours"]/24:.1f}</td><td>{longest:.1f}</td></tr>'
monthly=pd.DataFrame(monthly_rows).pivot(index='month',columns='scenario',values='net_pnl').rename(columns=NAMES).round(2).to_html(float_format=lambda x:f'{x:,.2f}')
image=base64.b64encode((ROOT/'equity_drawdown.png').read_bytes()).decode()
links=''.join(f'<li>{label}：<a href="{name}/trades.csv">成交</a> / <a href="{name}/cycles.csv">Cycle</a> / <a href="{name}/funding_events.csv">资金费</a> / <a href="{name}/equity.csv">净值</a> / <a href="{name}/summary.json">结果</a></li>' for name,label in NAMES.items())
body=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>50万只做多：退出阈值对比</title><style>body{{margin:0;background:#f4f7fb;color:#182639;font:16px/1.65 system-ui,sans-serif}}main{{max-width:1250px;margin:auto;padding:30px}}section{{background:white;padding:24px;margin:20px 0;border-radius:12px}}table{{border-collapse:collapse;width:100%;font-size:14px}}td,th{{white-space:nowrap;padding:9px;border-bottom:1px solid #ddd;text-align:right}}td:first-child,th:first-child{{text-align:left}}.scroll{{overflow:auto}}img{{width:100%}}a{{color:#1464cc}}</style><main>
<h1>50万 USDT · 始终做多：1%/2% 与 2%/5%</h1><p>2024-10-02 00:00 UTC 至 2026-10-02 00:00 UTC（右端不含），730天。初始50万USDT、不加杠杆，单格0.006 BTC、初始5格、0.1%网格间距，仓位上限5 BTC。</p>
<section><h2>结果</h2><p><strong>两种分钟高低价顺序下，两套规则收益均约6%、最大回撤约15%。2%减半／5%清仓没有一致的收益优势；它减少了已结束轮数及手续费，但期末保留了更多BTC。</strong></p><div class="scroll"><table><tr><th>退出规则 / 路径</th><th>期末净值</th><th>净利润</th><th>两年收益</th><th>最大回撤</th><th>交易手续费</th><th>资金费支出</th><th>已结束Cycle</th></tr>{rows}</table></div><p>收益已扣全部交易手续费和历史资金费，并包含期末浮盈亏。未在期末强制清仓；估算清仓后的净值见各场景JSON。两种分钟路径不是严格的收益上下界。</p></section>
<section><h2>净值、回撤与资金敞口</h2><img src="data:image/png;base64,{image}" alt="50万两套退出阈值的净值回撤和敞口"><p>曲线为小时采样；结果表的最大回撤使用全部回放节点与成交点。</p></section>
<section><h2>持仓、资金容量和长时间未结束的Cycle</h2><div class="scroll"><table><tr><th>规则 / 路径</th><th>平均资金敞口</th><th>峰值BTC</th><th>最低可用资金</th><th>资金不足拒单</th><th>期末BTC</th><th>期末浮盈亏</th><th>当前Cycle天数</th><th>最长Cycle天数（含当前）</th></tr>{details}</table></div><p>四组均无资金不足拒单，也没有触发额外资金约束减仓；持仓也未达到5 BTC上限。最低可用资金仍超过21万USDT。增加本金时，本次维持了原单格量，因此平均资金敞口约18%。</p><p>1%/2%在期末刚开始新一轮，但之前最长持仓仍超过328天。2%/5%当前未结束的一轮持续约340～362天；不能仅看已结束轮次的胜率或盈亏。累计总收益已纳入这些未结束仓位。</p></section>
<section><h2>相同参数与动态水位口径</h2><ul><li>始终做多，移除启动价格门槛及资产比例结束条件，结束并确认成交后下个15秒轮询开始新Cycle。</li><li>盈利后撤单暂停网格；价格回到亏损侧恢复。两套规则分别在当前动态水位上方1%或2%减半、2%或5%清仓。半仓成交和资金费会继续更新水位，后续清仓阈值以更新后的水位计算。</li><li>单格0.006 BTC、初始0.030 BTC；网格间距0.1%、15分钟重报价、缩小系数0.85；每笔网格上限0.1 BTC、仓位上限5 BTC。跨格和数量舍入补偿可能使单笔数量超过单格量。</li><li>maker手续费0.014%、taker0.035%，全期间固定；初始及水位退出单边滑点1 bp加0.1 USDT盘口价差。资金费使用历史费率与当时持仓、标记价格结算。严格资金约束相同，本次没有触发。</li><li>复用已有网格引擎及水位控制器；研究副本仅将水位阈值改为构造参数、关闭磁盘写入并修正可配置阈值的文字标签。线上策略未修改。</li></ul></section>
<section><h2>数据和验收</h2><p>复用Bybit BTCUSDT交易价和标记价分钟线：1,051,200分钟、2,190次资金费。无分钟缺口或重复，数据哈希重新校验。<a href="https://bybit-exchange.github.io/docs/v5/market/kline">交易K线接口</a> / <a href="https://bybit-exchange.github.io/docs/v5/market/history-fund-rate">历史资金费接口</a>。</p><p>验收包括两套阈值分别生效、动态水位更新、清仓成交核对、下一轮只做多。四组逐笔累计数量、每个已结束Cycle盈亏、所有资金费以及账户净值恒等式均独立核对；另从成交及资金费重建每笔水位退出前的水位，确认减半与全平成交均达到对应阈值。</p><p>分钟内价格顺序、交易价/标记价极值时间采用路径假设；触价全额成交、撤单即时确认，未模拟盘口深度、排队、部分成交、网络延迟和历史费率变化。未做独立未来期间验证。外部复核未执行：浏览器通道不可用。</p></section>
<section><h2>月度净利润（USDT，含浮盈亏）</h2><div class="scroll">{monthly}</div></section>
<section><h2>下载与复现</h2><p><a href="results.json">完整结果</a> · <a href="audit.json">账本校验</a> · <a href="monthly_results.csv">月度CSV</a> · <a href="equity_drawdown.png">PNG曲线</a> · <a href="equity_drawdown.svg">SVG曲线</a></p><ul>{links}</ul><p>独立研究源代码：LongCapitalBacktest.java、ConfiguredWaterExit.java、ComparisonAcceptance.java、build_report.py；run.sh可在本仓库复现。原研究目录与线上服务未修改。</p></section></main></html>'''
import sys
sys.path.insert(0, str(ROOT.parent))
from cycle_report import build_cycle_section
body = body.replace('<section><h2>月度净利润', build_cycle_section(ROOT, NAMES) + '<section><h2>月度净利润', 1)
(ROOT/'report.html').write_text(body)
sources=[ROOT/'LongCapitalBacktest.java',ROOT/'ConfiguredWaterExit.java',ROOT/'ComparisonAcceptance.java',ROOT/'build_report.py']
(ROOT/'source_hashes.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},indent=2))
print(json.dumps({'audit':checks,'results':{name:{k:s[k] for k in ['net_pnl','return_pct','max_drawdown_pct','average_exposure_pct','min_free_margin']} for name,s in results.items()}},indent=2))
