"""Audit both sides, causal Cycle entry selection, signed funding and 1x capital accounting."""
from pathlib import Path
import base64, hashlib, json
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager

ROOT=Path(__file__).resolve().parent
NAMES={'threshold_olhc':'10万分界多空 · 先跌后涨','threshold_ohlc':'10万分界多空 · 先涨后跌',
       'long_200k_olhc':'始终做多 · 先跌后涨','long_200k_ohlc':'始终做多 · 先涨后跌',
       'threshold_close':'10万分界多空 · 仅开收价','threshold_slip5':'10万分界多空 · 5 bp 滑点'}
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
    assert s['initial_capital']==200000 and tr.timestamp_ms.is_monotonic_increasing
    sign=np.where(tr.side.eq('BUY'),1.,-1.);tr['signed_qty']=tr.qty*sign;tr['signed_value']=tr.qty*tr.price*sign
    assert len(tr)==s['fills'] and len(cy)==s['completed_cycles']
    near(tr.fee.sum(),s['trading_fees']);near(tr.signed_qty.sum(),s['open_qty'],1e-9)
    assert np.max(abs(tr.groupby('cycle_id').signed_qty.cumsum()-tr.net_qty))<1e-9
    initial=tr.groupby('cycle_id').first()
    assert initial.kind.eq('initial').all() and initial.qty.eq(0.030).all()
    cycle_sign=initial.side.map({'BUY':1,'SELL':-1})
    assert (tr.net_qty*tr.cycle_id.map(cycle_sign)>=-1e-12).all()
    assert tr.net_qty.abs().max()<=5;near(tr.net_qty.abs().max(),s['peak_btc_qty'],1e-9)
    for row in initial.itertuples():
        p=entry_price(row.timestamp_ms,s['path'])
        expected='BUY' if name.startswith('long_') or p<100000 else 'SELL'
        assert row.side==expected and (not name.startswith('threshold_') or p!=100000),('entry signal',name,row.timestamp_ms,p,row.side)
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
        assert row.direction==('LONG' if cycle_sign[row.cycle_id]>0 else 'SHORT')
    near(cy.pnl.sum(),s['closed_cycle_pnl'])
    near(200000-tr.signed_value.sum()-tr.fee.sum()-fu.paid.sum()+s['open_qty']*eq.iloc[-1]['mark'],s['final_equity_marked'])
    near(200000+s['closed_cycle_pnl']+s['open_cycle_pnl'],s['final_equity_marked']);near(eq.iloc[-1].equity,s['final_equity_marked'])
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
    idx=pd.to_datetime(eq.timestamp_ms,unit='ms',utc=True)-pd.Timedelta(nanoseconds=1)
    monthly=eq.assign(month=idx.dt.strftime('%Y-%m')).groupby('month').equity.last()
    previous=200000.
    for month,value in monthly[monthly.index>='2024-10'].items():
        monthly_rows.append({'scenario':name,'month':month,'end_equity':value,'net_pnl':value-previous,'return_pct':(value/previous-1)*100});previous=value
    checks[name]={'passed':True,'fills_checked':len(tr),'closed_cycles_checked':len(cy),'funding_events_checked':len(fu),
                 'cycle_entry_direction_checked':True,'cycle_direction_locked_checked':True,'signed_accounting_checked':True,'one_times_exposure_cap_checked':True}

font=Path('/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc')
if font.exists():
    font_manager.fontManager.addfont(str(font));plt.rcParams['font.family']=font_manager.FontProperties(fname=str(font)).get_name()
plt.rcParams['axes.unicode_minus']=False
fig,axs=plt.subplots(3,1,figsize=(14,10),sharex=True,gridspec_kw={'height_ratios':[2,1,1]})
colors=['#1664ca','#35945f','#d97624','#905daf','#777','#b15367']
for (name,label),color in zip(NAMES.items(),colors):
    e=frames[name];t=pd.to_datetime(e.timestamp_ms,unit='ms',utc=True); ls='--' if name in ['threshold_close','threshold_slip5'] else '-'
    axs[0].plot(t,e.equity,label=label,color=color,lw=1,ls=ls)
    axs[1].plot(t,(e.equity/e.equity.cummax()-1)*100,color=color,lw=.8,ls=ls)
    if name.startswith('threshold_') and name not in ['threshold_close','threshold_slip5']:
        axs[2].plot(t,e.net_qty,color=color,lw=.7,alpha=.8)
axs[0].axhline(200000,color='#888',ls=':',lw=.7)
axs[0].set_title('20万 USDT · BTC 高于10万开空 / 低于10万开多 · 动态水位1%减半 / 2%清仓\n2024-10-02 — 2026-10-01（UTC）· 方向在 Cycle 开始时决定并保持')
axs[0].set_ylabel('资产净值 / USDT');axs[0].legend(ncol=2,fontsize=8,loc='upper left')
axs[1].set_ylabel('小时采样回撤 / %');axs[2].set_ylabel('净 BTC（正为多，负为空）');axs[2].axhline(0,color='#888',lw=.6)
for a in axs:a.grid(alpha=.18)
fig.tight_layout();fig.savefig(ROOT/'equity_drawdown.png',dpi=160);fig.savefig(ROOT/'equity_drawdown.svg');plt.close(fig)

parameters={'initial_capital_usdt':200000,'threshold_usdt':100000,'direction_selection':'new Cycle only; SHORT above, LONG below; wait at equality; hold direction until Cycle ends',
    'grid_qty_btc':0.006,'initial_cells':5,'initial_qty_btc':0.030,'spacing_pct':0.1,'water_half_pct':1,'water_full_pct':2,
    'maker_fee_pct':0.014,'taker_fee_pct':0.035,'poll_seconds':15,'order_timeout_minutes':15,'requote_shrink_factor':0.85,
    'leverage':'abs(q)*mark <= marked equity; reject unfunded adds; if necessary close minimal qty to restore cap',
    'restart':'next poll after terminal order/fill reconciliation; no price-trigger exit or asset-ratio exit'}
report={'parameters':parameters,'period':manifest,'results':results,'direction_pnl':directions,'audit':checks,
    'limitations':['minute price paths assumed, not actual tick order or rigorous return bounds','touch fills in full; no queue/depth/partial fills',
       'immediate simulated cancellation; fixed current fees throughout history','strict 1x exposure cover is research execution constraint, not deployed',
       'threshold chosen for this retrospective scenario, no independent forward period validation'],
    'external_review':'not performed; built-in browser unavailable'}
(ROOT/'results.json').write_text(json.dumps(report,indent=2,ensure_ascii=False));(ROOT/'audit.json').write_text(json.dumps(checks,indent=2))
pd.DataFrame(monthly_rows).to_csv(ROOT/'monthly_results.csv',index=False);pd.DataFrame(directions).to_csv(ROOT/'direction_pnl.csv',index=False)
rows=''
for name,label in NAMES.items():
    s=results[name]
    rows+=f'<tr><td>{label}</td><td>{s["final_equity_marked"]:,.2f}</td><td>{s["net_pnl"]:+,.2f}</td><td>{s["return_pct"]:+.2f}%</td><td>{s["max_drawdown_pct"]:.2f}%</td><td>{s["trading_fees"]:,.2f}</td><td>{s["funding_paid"]:,.2f}</td><td>{s["completed_long_cycles"]} / {s["completed_short_cycles"]}</td></tr>'
detail=''
for name,label in list(NAMES.items())[:4]:
    s=results[name]
    detail+=f'<tr><td>{label}</td><td>{s["open_direction"]}</td><td>{s["open_qty"]:.3f}</td><td>{s["open_cycle_pnl"]:,.2f}</td><td>{s["open_cycle_hours"]/24:.1f}</td><td>{s["max_completed_cycle_hours"]/24:.1f}</td><td>{s["average_exposure_pct"]:.1f}%</td><td>{s["peak_btc_qty"]:.3f}</td><td>{s["capital_blocked_add_attempts"]} / {s["margin_cover_events"]}</td></tr>'
links=''.join(f'<li>{label}：<a href="{name}/trades.csv">成交</a> / <a href="{name}/cycles.csv">Cycle</a> / <a href="{name}/equity.csv">净值</a> / <a href="{name}/funding_events.csv">资金费</a> / <a href="{name}/summary.json">结果</a></li>' for name,label in NAMES.items())
encoded=base64.b64encode((ROOT/'equity_drawdown.png').read_bytes()).decode()
direction_table=pd.DataFrame(directions).query('scenario in ["threshold_olhc","threshold_ohlc"]').replace({'scenario':NAMES}).round(2).to_html(index=False,float_format=lambda x:f'{x:,.2f}')
monthly_table=pd.DataFrame(monthly_rows).pivot(index='month',columns='scenario',values='net_pnl').rename(columns=NAMES).round(2).to_html(float_format=lambda x:f'{x:,.2f}')
body=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>20万 BTC 十万分界多空回测</title><style>body{{margin:0;background:#f5f7fb;color:#19263a;font:16px/1.65 system-ui,sans-serif}}main{{max-width:1250px;margin:auto;padding:30px}}section{{background:white;padding:24px;margin:20px 0;border-radius:12px}}table{{border-collapse:collapse;width:100%;font-size:14px}}td,th{{white-space:nowrap;padding:9px;border-bottom:1px solid #ddd;text-align:right}}td:first-child,th:first-child{{text-align:left}}.scroll{{overflow:auto}}img{{width:100%}}a{{color:#1464cc}}</style><main>
<h1>20万 USDT：高于10万开空、低于10万开多</h1><p>2024-10-02 00:00 UTC 至 2026-10-02 00:00 UTC（右端不含），730天。单格0.006 BTC、初始5格、0.1%间距、动态水位1%减半／2%清仓、不加杠杆。</p>
<section><h2>主要结果及同资金对照</h2><p><strong>两种分钟高低价顺序下，分界多空规则收益约23%，最大回撤约19%；同样20万、始终做多收益约7%～8%，最大回撤约32%。</strong>这是该历史样本的模拟结果，尚未做独立期间验证。</p><div class="scroll"><table><tr><th>规则 / 路径</th><th>期末净值</th><th>净利润</th><th>两年收益</th><th>最大回撤</th><th>交易手续费</th><th>资金费净支出</th><th>已结束多 / 空 Cycle</th></tr>{rows}</table></div><p>收益包含期末浮盈亏，已扣交易手续费和历史资金费。资金费负数为净收入。基准主动成交滑点1 bp，另测试5 bp；仅开收价路径忽略分钟极值，用于诊断触价成交的影响，并非收益下界。</p></section>
<section><h2>净值、回撤和多空持仓</h2><img src="data:image/png;base64,{encoded}" alt="两年多空净值回撤持仓曲线"><p>图为小时采样，结果表最大回撤来自全部回放节点和成交点。</p></section>
<section><h2>持仓与资金占用</h2><div class="scroll"><table><tr><th>场景</th><th>期末方向</th><th>净 BTC</th><th>期末浮盈亏</th><th>当前 Cycle 天数</th><th>最长已结束天数</th><th>平均敞口</th><th>峰值 BTC 绝对量</th><th>资金不足拒单 / 约束减仓次数</th></tr>{detail}</table></div><p>主多空场景中，没有资金不足拒单，也没有触发额外的1倍敞口约束减仓。平均敞口约为净值的21%；仍可能有约100天的单轮持仓和约19%的净值回撤。</p><div class="scroll">{direction_table}</div><p>方向收益含各方向期末浮盈亏；completed_cycles仅为已结束轮数，不能单凭已结束轮的胜率判断风险。</p></section>
<section><h2>规则口径</h2><ul><li>新 Cycle 开始时看当前交易价：高于100,000 USDT做空，低于100,000做多，等于则等待。方向锁定到该轮结束；跨过10万不会立即强制反手。结束后下个15秒轮询重新选方向。</li><li>盈利后撤单暂停网格；做多价格高于动态水位1%卖出一半、2%卖完；做空价格低于动态水位1%买回一半、2%买回全部。成交与资金费持续改变水位；回到亏损侧恢复网格，下次盈利波段可再次减半。</li><li>复用现有网格引擎、数量舍入补偿和动态水位控制器。0.006 BTC为基础单格量，跨格时单笔数量可增加。网格单笔上限0.1 BTC、仓位上限5 BTC；15分钟重报价、间距缩小系数0.85。</li><li>初始20万全部为USDT，不另加BTC；无追加资金。按绝对持仓名义价值不超过净值执行1倍资金约束，开仓/加仓不足则拒单，必要时最小数量平仓恢复约束；空头卖出名义金额不作为额外可用本金。主多空场景没有触发这些约束。</li><li>maker手续费0.014%、taker0.035%，两年固定。初始及水位退出按单边滑点1 bp加盘口价差0.1 USDT模拟；被动网格触价全额成交。资金费按结算时带符号净数量×标记价格×历史费率计入，多空方向相反。</li></ul></section>
<section><h2>数据、验收与限制</h2><p>复用已校验的Bybit BTCUSDT交易价及标记价格分钟线：1,051,200分钟、2,190次资金费，无缺口或重复，数据哈希再次核对。<a href="https://bybit-exchange.github.io/docs/v5/market/kline">交易K线</a> / <a href="https://bybit-exchange.github.io/docs/v5/market/mark-kline">标记价格K线</a> / <a href="https://bybit-exchange.github.io/docs/v5/market/history-fund-rate">历史资金费接口</a>。</p><p>验收包括严格10万分界、活动轮方向锁定、做空1%减半/2%清仓、下一轮改变方向、做空资金容量、价格上涨后的1倍约束和带符号资金费。六个场景逐笔累计数量、全部已结束Cycle盈亏、资金费结算数量、方向选择及账户盈亏恒等式均独立核对通过。</p><p>分钟线不能还原真实价格先后、盘口深度、排队与部分成交；两种OHLC路径不是收益上下界，标记价极值假设与交易价同步。假设触价全额成交、撤单即时确认，未重建历史费率档位或网络延迟。普通网格若先将数量平到零，现有服务也会结束Cycle；这不是2%盈利退出。10万阈值是本次指定假设，没有独立未来验证。外部复核未执行：浏览器通道不可用。线上服务未改动。</p></section>
<section><h2>月度净利润（USDT，包含浮盈亏）</h2><div class="scroll">{monthly_table}</div></section>
<section><h2>下载与复现</h2><p><a href="results.json">全部结果</a> · <a href="audit.json">账本验收</a> · <a href="monthly_results.csv">月度CSV</a> · <a href="direction_pnl.csv">多空收益CSV</a> · <a href="equity_drawdown.png">PNG曲线</a> · <a href="equity_drawdown.svg">SVG曲线</a></p><ul>{links}</ul><p>独立研究目录：ThresholdWaterBacktest.java、ThresholdAcceptance.java、build_report.py、run.sh；数据链接至上一轮研究的已校验数据目录。</p></section></main></html>'''
import sys
sys.path.insert(0, str(ROOT.parent))
from cycle_report import build_cycle_section
body = body.replace('<section><h2>月度净利润', build_cycle_section(ROOT, NAMES) + '<section><h2>月度净利润', 1)
(ROOT/'report.html').write_text(body)
hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'ThresholdWaterBacktest.java',ROOT/'ThresholdAcceptance.java',ROOT/'ReplayWaterExit.java',ROOT/'build_report.py']}
(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
print(json.dumps({'audit':checks,'results':{n:{k:s[k] for k in ['net_pnl','return_pct','max_drawdown_pct','open_cycle_pnl','margin_cover_events']} for n,s in results.items()}},indent=2))
