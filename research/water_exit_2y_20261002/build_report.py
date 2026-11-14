"""Independently audit exported cash/quantity ledgers and build a standalone research report."""
from pathlib import Path
import base64, hashlib, html, json
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager

ROOT=Path(__file__).resolve().parent
NAMES={'current_fee_olhc':'先跌后涨 · 1 bp','current_fee_ohlc':'先涨后跌 · 1 bp',
       'close_only':'仅开收价路径 · 1 bp','slippage_5bp_olhc':'先跌后涨 · 5 bp'}
manifest=json.loads((ROOT/'data/manifest.json').read_text())
for file_name,expected_hash in manifest['files'].items():
    assert hashlib.sha256((ROOT/'data'/file_name).read_bytes()).hexdigest()==expected_hash
controller_meta=json.loads((ROOT/'controller_source.json').read_text())
assert hashlib.sha256(Path(controller_meta['source']).read_bytes()).hexdigest()==controller_meta['sha256']
checks={}; frames={}; results={}; months=[]; audit_details={}
def near(a,b,tol=0.0001):
    assert abs(float(a)-float(b))<tol,(a,b)
for name,label in NAMES.items():
    folder=ROOT/name
    s=json.loads((folder/'summary.json').read_text()); results[name]=s
    tr=pd.read_csv(folder/'trades.csv'); cy=pd.read_csv(folder/'cycles.csv')
    fu=pd.read_csv(folder/'funding_events.csv'); eq=pd.read_csv(folder/'equity.csv'); frames[name]=eq
    assert s['minutes']==manifest['rows'] and s['start_ms']==manifest['start_ms'] and s['end_ms']==manifest['end_exclusive_ms']
    assert tr.timestamp_ms.is_monotonic_increasing and tr.timestamp_ms.between(s['start_ms'],s['end_ms']).all()
    assert len(tr)==s['fills'] and len(cy)==s['completed_cycles']
    sign=np.where(tr.side.eq('BUY'),1.,-1.)
    tr['signed_qty']=tr.qty*sign; tr['signed_value']=tr.qty*tr.price*sign
    near(tr.fee.sum(),s['trading_fees'])
    fee_rates=tr.fee/(tr.qty*tr.price)
    assert np.minimum(abs(fee_rates-0.00014),abs(fee_rates-0.00035)).max()<1e-12
    assert (abs(fee_rates[tr.kind.ne('grid')]-0.00035)<1e-12).all()
    near(tr.signed_qty.sum(),s['open_qty'],1e-9)
    assert np.max(np.abs(tr.groupby('cycle_id').signed_qty.cumsum()-tr.net_qty))<1e-9
    assert tr.net_qty.min()>=-1e-12 and tr.net_qty.max()<=5
    near(tr.net_qty.max(),s['peak_btc_qty'],1e-9)
    expected_funding=pd.read_csv(ROOT/'data/funding.csv')
    assert len(fu)==manifest['funding_rows']
    assert np.array_equal(fu.timestamp_ms,expected_funding.timestamp_ms)
    assert np.max(np.abs(fu.rate-expected_funding.rate))<1e-14
    assert np.max(np.abs(fu.qty*fu.mark*fu.rate-fu.paid))<1e-9
    near(fu.paid.sum(),s['funding_paid'])
    # Funding is settled before polling at that timestamp. A previous minute's boundary fill
    # can share the timestamp: validate against inventory states in their chronological order.
    times=tr.timestamp_ms.to_numpy(); net=tr.net_qty.to_numpy()
    for row in fu.itertuples():
        lo=np.searchsorted(times,row.timestamp_ms,'left'); hi=np.searchsorted(times,row.timestamp_ms,'right')
        candidates=[net[lo-1] if lo else 0.]+list(net[lo:hi])
        assert min(abs(row.qty-x) for x in candidates)<1e-9,('funding inventory',row.timestamp_ms,row.qty,candidates)
    trade_by_cycle=tr.groupby('cycle_id').agg(qty=('signed_qty','sum'),value=('signed_value','sum'),fee=('fee','sum'))
    for row in cy.itertuples():
        g=trade_by_cycle.loc[row.cycle_id]
        near(g.qty,0,1e-9); near(g.fee,row.trading_fee)
        near(-g.value-g.fee-row.funding_paid,row.pnl)
    near(cy.pnl.sum(),s['closed_cycle_pnl'])
    near(100000-tr.signed_value.sum()-tr.fee.sum()-fu.paid.sum()+s['open_qty']*eq.iloc[-1]['mark'],s['final_equity_marked'])
    near(100000+s['closed_cycle_pnl']+s['open_cycle_pnl'],s['final_equity_marked'])
    near(eq.iloc[-1].equity,s['final_equity_marked'])
    assert (eq.equity-eq.net_notional).min()>=-0.01 and s['min_free_cash']>=-0.01
    assert (eq.net_notional/eq.equity).max()<=1.000001
    cover=tr[tr.kind.eq('funding_cash_cover')]
    assert len(cover)==s['funding_cash_cover_events']; near(cover.qty.sum(),s['funding_cash_cover_qty'],1e-9)
    # Shift closing observations to the month they close, avoiding a phantom final October.
    idx=pd.to_datetime(eq.timestamp_ms,unit='ms',utc=True)-pd.Timedelta(nanoseconds=1)
    monthly=eq.assign(month=idx.dt.strftime('%Y-%m')).groupby('month').equity.last()
    monthly=monthly[monthly.index>='2024-10']; previous=100000.
    for month,value in monthly.items():
        months.append({'scenario':name,'month':month,'end_equity':value,'net_pnl':value-previous,'return_pct':(value/previous-1)*100});previous=value
    checks[name]={'passed':True,'quantity_rows':len(tr),'completed_cycles_checked':len(cy),'funding_events_checked':len(fu),'cash_identity_checked':True,'no_borrowing_checked':True}
    worst=cy.sort_values('pnl').iloc[0]
    audit_details[name]={'worst_closed_cycle':worst.to_dict(),'open_cycle_days':s['open_cycle_hours']/24,
                        'flat_ending_cycles':int(cy.reason.eq('flat').sum()),
                        'closed_win_rate_pct':float(cy.pnl.gt(0).mean()*100)}

bars=pd.read_csv(ROOT/'data/bars.csv.gz',usecols=['timestamp_ms','open','close','mark_close'])
start_price=bars.iloc[0]['open']; end_price=bars.iloc[-1]['close']
gross_bh=(end_price/start_price-1)*100
# Fully invested spot comparison, fractional BTC, same assumed taker fee at entry, no terminal sale.
hold_qty=100000/(start_price*(1+0.00035)); hold_final=hold_qty*end_price
benchmark={'type':'fully invested BTC spot, fractional quantity, entry fee 0.035%, mark at last trade close, no funding, no exit fee',
           'start_price':start_price,'end_price':end_price,'btc_gross_return_pct':gross_bh,
           'final_equity':hold_final,'return_pct':(hold_final/100000-1)*100,'qty':hold_qty}
report_json={'period':manifest,'parameters':{'capital_usdt':100000,'leverage':'none; buy notional fully funded',
    'direction':'always LONG','grid_qty_btc':0.006,'initial_cells':5,'grid_spacing_pct':0.1,
    'half_water_rate_pct':1,'full_water_rate_pct':2,'maker_fee_pct':0.014,'taker_fee_pct':0.035,
    'poll_seconds':15,'order_timeout_minutes':15,'requote_shrink_factor':0.85,'max_inventory_btc':5,
    'max_grid_order_btc':0.1,'restart':'next 15 second poll after completion',
    'funding_cash_constraint':'reject buys without cash; minimum-quantity sale covers a funding cash deficit'},
    'results':results,'audit':checks,'cycle_details':audit_details,'benchmark':benchmark,
    'limitations':['Minute OHLC cannot reconstruct tick order, bid/ask, queue position or partial fills.',
       'OHLC/OLHC are assumed paths, not mathematical return bounds; trade and mark extremes share assumed timing.',
       'Fees and current strategy parameters fixed throughout history; no historical account fee tier reconstruction.',
       'Grid limits fill in full on price touch; immediate simulated cancellation and terminal exchange evidence.',
       'No leverage funding cash cover is a research execution constraint added to the strategy.',
       'Neutral indicators used: manual LONG start and classic sizing do not use indicator entry or reduce boosts.',
       'Native grid may sell remaining quantity to zero while underwater; current service then ends that Cycle.',
       'Completed cycle win rate omits open-cycle floating loss; total marked equity includes it.'],
    'sources':['https://bybit-exchange.github.io/docs/v5/market/kline','https://bybit-exchange.github.io/docs/v5/market/mark-kline','https://bybit-exchange.github.io/docs/v5/market/history-fund-rate'],
    'external_review':'not performed; built-in browser unavailable'}
pd.DataFrame(months).to_csv(ROOT/'monthly_results.csv',index=False)
(ROOT/'audit.json').write_text(json.dumps(checks,indent=2))
(ROOT/'results.json').write_text(json.dumps(report_json,indent=2,ensure_ascii=False))

chart_font=Path('/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc')
if chart_font.exists():
    font_manager.fontManager.addfont(str(chart_font))
    plt.rcParams['font.family']=font_manager.FontProperties(fname=str(chart_font)).get_name()
else:
    plt.rcParams['font.family']='DejaVu Sans'
plt.rcParams['axes.unicode_minus']=False
fig,axs=plt.subplots(3,1,figsize=(14,11),sharex=True,gridspec_kw={'height_ratios':[2,1,1]})
colors=['#1464cc','#d86619','#787e86','#6b43ae']
for (name,label),color in zip(NAMES.items(),colors):
    e=frames[name]; t=pd.to_datetime(e.timestamp_ms,unit='ms',utc=True)
    axs[0].plot(t,e.equity,label=label,color=color,linewidth=1)
    axs[1].plot(t,(e.equity/e.equity.cummax()-1)*100,color=color,linewidth=.9)
    axs[2].plot(t,e.net_notional/e.equity*100,color=color,linewidth=.7,alpha=.7)
sample=bars.iloc[::60]; axs[0].plot(pd.to_datetime(sample.timestamp_ms,unit='ms',utc=True),hold_qty*sample.close,
    color='#35905b',alpha=.8,linewidth=1,label='满仓 BTC 现货持有（入场费后）')
axs[0].axhline(100000,color='#777',ls='--',lw=.6)
axs[0].set_ylabel('资产净值 / USDT');axs[0].legend(ncol=2,loc='upper left',fontsize=9)
axs[0].set_title('BTC 动态水位 1% 减半 / 2% 清仓 · 100,000 USDT · 始终做多\n2024-10-02 — 2026-10-01（UTC）· 分钟路径近似，非逐笔成交回放')
axs[1].set_ylabel('小时采样回撤 / %');axs[2].set_ylabel('BTC 敞口 / 净值 %')
for a in axs:a.grid(alpha=.18)
fig.tight_layout(); fig.savefig(ROOT/'equity_drawdown.png',dpi=160);fig.savefig(ROOT/'equity_drawdown.svg');plt.close(fig)

def fmt(x):return f'{x:,.2f}'
table_rows=''
for name,label in NAMES.items():
    s=results[name]
    table_rows+=f'<tr><td>{label}</td><td>{fmt(s["final_equity_marked"])}</td><td>{fmt(s["net_pnl"])}</td><td>{s["return_pct"]:+.2f}%</td><td>{s["max_drawdown_pct"]:.2f}%</td><td>{fmt(s["trading_fees"])}</td><td>{fmt(s["funding_paid"])}</td><td>{s["completed_cycles"]}</td></tr>'
detail_rows=''
for name,label in NAMES.items():
    s=results[name]; w=audit_details[name]['worst_closed_cycle']
    detail_rows+=f'<tr><td>{label}</td><td>{fmt(s["closed_cycle_pnl"])}</td><td>{fmt(s["open_cycle_pnl"])}</td><td>{s["open_qty"]:.3f}</td><td>{s["open_cycle_hours"]/24:.1f}</td><td>{s["max_completed_cycle_hours"]/24:.1f}</td><td>{s["average_exposure_pct"]:.1f}%</td><td>{s["peak_btc_qty"]:.3f}</td><td>{w["pnl"]:,.2f} / {w["reason"]}</td></tr>'
image=base64.b64encode((ROOT/'equity_drawdown.png').read_bytes()).decode()
links=''.join(f'<li>{html.escape(label)}：<a href="{name}/trades.csv">成交</a> / <a href="{name}/cycles.csv">Cycle</a> / <a href="{name}/funding_events.csv">资金费</a> / <a href="{name}/equity.csv">小时净值</a> / <a href="{name}/summary.json">结果</a></li>' for name,label in NAMES.items())
monthly=pd.DataFrame(months).pivot(index='month',columns='scenario',values='net_pnl').rename(columns=NAMES).round(2).to_html(classes='data',float_format=lambda x:f'{x:,.2f}')
body=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>BTC 动态水位两年回测</title>
<style>body{{font:16px/1.65 system-ui,sans-serif;color:#172335;background:#f4f7fb;margin:0}}main{{max-width:1200px;margin:auto;padding:32px}}section{{background:white;padding:24px;margin:20px 0;border-radius:12px}}h1,h2{{line-height:1.3}}table{{border-collapse:collapse;width:100%;font-size:14px}}th,td{{padding:10px;border-bottom:1px solid #ddd;text-align:right;white-space:nowrap}}th:first-child,td:first-child{{text-align:left}}.scroll{{overflow:auto}}img{{width:100%}}a{{color:#1464cc}}.note{{color:#556274}}</style><main>
<h1>BTC 动态水位：1% 减半 / 2% 清仓</h1>
<p>2024-10-02 00:00 UTC 至 2026-10-02 00:00 UTC（右端不含；北京时间 08:00 至 08:00），730 天。初始 100,000 USDT、不加杠杆、始终做多。</p>
<section><h2>结果</h2><p><strong>价格路径会改变收益正负；各场景最大回撤均较大。分钟线回测不足以证明这套策略能稳定盈利。</strong></p>
<div class="scroll"><table><tr><th>假设路径 / 主动滑点</th><th>期末净值</th><th>净利润</th><th>两年收益</th><th>最大回撤</th><th>交易手续费</th><th>资金费净支出</th><th>已结束 Cycle</th></tr>{table_rows}</table></div>
<p>收益已扣交易手续费和历史资金费，包含期末未平仓盈亏。手续费支出不是额外待扣金额。期末不强制平仓；估算平仓净值见 JSON。负资金费表示净收入。</p>
<p>满仓 BTC 现货持有对照：BTC 价格 {start_price:,.1f} → {end_price:,.1f}，价格涨幅 {gross_bh:.2f}%；按入场手续费 0.035% 后净值 {hold_final:,.2f} USDT（{benchmark['return_pct']:.2f}%）。此对照一直满仓，网格的平均敞口仅约半仓，风险和投入比例不同。</p></section>
<section><h2>净值、回撤和资金占用</h2><img src="data:image/png;base64,{image}" alt="两年净值回撤敞口曲线"><p class="note">图为小时采样；结果表的最大回撤来自全部回放节点和成交点，通常比小时曲线更深。</p></section>
<section><h2>为何高胜率仍可能亏损</h2><p>没有亏损止损：下跌时持续买入，耗尽现金后拒绝新买单，旧 Cycle 可能持续数月。上涨时的许多小盈利 Cycle，不能抵消一次长时间持仓的亏损。普通网格卖单也可能先把数量卖到零，此时当前服务会结束 Cycle，即使尚未恢复总盈亏水位；这是保留的现有逻辑，并非触发了 2% 盈利清仓。</p>
<div class="scroll"><table><tr><th>场景</th><th>已结束累计盈亏</th><th>未结束浮盈亏</th><th>未平 BTC</th><th>未结束天数</th><th>最长已结束天数</th><th>平均敞口</th><th>峰值 BTC</th><th>最差已结束 Cycle / 原因</th></tr>{detail_rows}</table></div></section>
<section><h2>参数与执行规则</h2><ul><li>单格 0.006 BTC，初始 5 格 = 0.030 BTC；网格间距 0.1%，15 分钟超时重报价，间距缩小系数 0.85；单笔网格上限 0.1 BTC、仓位上限 5 BTC，另受现金限制。</li>
<li>复用本仓库 GridStrategyEngine、GridOrderPlanner、QuantityRoundingState 和动态水位控制器。控制器副本仅更名、跳过空路径读取并关闭磁盘写入。实际网格单量可因跨格和数量舍入补偿超过 0.006 BTC。</li>
<li>盈利后撤单暂停；超过动态水位 1% 减半、2% 清仓；半仓成交及资金费会继续改变水位；返回亏损侧恢复网格并重新允许减半。按 15 秒轮询确认；Cycle 结束后的下一个轮询开启下一轮。移除启动价格门槛及资产比例结束条件。</li>
<li>沿用当前账本核实费率：maker 0.014%、taker 0.035%，两年固定；初始及水位退出按盘口价差 0.1 USDT 加单边 1 bp 滑点，另测试 5 bp。主动成交的网格限价单按 taker 收费；其他网格单按 maker 收费。</li>
<li>不允许借款或追加资金：买入金额及费用超过现金则拒绝。资金费耗尽现金时，额外卖出最小可交易数量补足缺口；这一执行补充仅用于回测，没有改动线上策略。各场景次数及数量在 JSON 和成交 CSV 的 funding_cash_cover 中单列。</li></ul></section>
<section><h2>可信范围与数据校验</h2><p>数据来自 Bybit BTCUSDT 线性合约公开接口：1,051,200 根交易分钟线、对应标记价格分钟线、2,190 次历史资金费，无分钟缺口和重复。<a href="https://bybit-exchange.github.io/docs/v5/market/kline">交易 K 线</a> / <a href="https://bybit-exchange.github.io/docs/v5/market/mark-kline">标记价格 K 线</a> / <a href="https://bybit-exchange.github.io/docs/v5/market/history-fund-rate">资金费</a>。</p>
<p>每分钟假设 O→L→H→C 或 O→H→L→C，每段 20 秒；另用开收价线性路径诊断极值成交影响。开收路径忽略高低价，不是另一种同等真实路径，也不是收益下限。交易价和标记价格极值假设同步，不能恢复真实顺序。触价视为全部成交、撤单即时确认，未模拟盘口深度、排队、部分成交、网络延迟、交易所拒绝或历史费率变化；不会使用未来指标开单。两种 OHLC 路径也不是严格收益上下界。</p>
<p>验收覆盖启动数量、盈利撤单暂停、亏损恢复、资金不足拒单、1% 减半后动态水位触发 2% 全平、最终成交确认、下一轮启动和资金费改变水位。另独立核对每笔累计数量、每个已结束 Cycle 的盈亏、2190 次资金费及账户现金恒等式，全部通过。外部复核未执行：内置浏览器通道不可用。</p></section>
<section><h2>月度净利润（USDT）</h2><p>首尾为实际覆盖月份；每月净值差包含持仓浮盈亏。</p><div class="scroll">{monthly}</div></section>
<section><h2>下载与复现</h2><p><a href="results.json">全部参数与结果 JSON</a> · <a href="monthly_results.csv">月度 CSV</a> · <a href="audit.json">独立账本校验</a> · <a href="equity_drawdown.png">PNG 曲线</a> · <a href="equity_drawdown.svg">SVG 曲线</a> · <a href="data/manifest.json">数据清单及哈希</a></p><ul>{links}</ul><p>源代码：WaterGridBacktest.java、ReplayAcceptance.java、generate_controller.py、fetch_data.py、build_report.py。run.sh 可在本仓库重放。所有操作均在独立 research 目录，线上服务未修改或部署。</p></section></main></html>'''
import sys
sys.path.insert(0, str(ROOT.parent))
from cycle_report import build_cycle_section
body = body.replace('<section><h2>月度净利润', build_cycle_section(ROOT, NAMES) + '<section><h2>月度净利润', 1)
(ROOT/'report.html').write_text(body)
files=[ROOT/'WaterGridBacktest.java',ROOT/'ReplayWaterExit.java',ROOT/'ReplayAcceptance.java',ROOT/'build_report.py']
for module,path in [('grid-strategy-core','com/btcgrid/strategy/service/GridOrderPlanner'),('grid-strategy-core','com/btcgrid/strategy/service/GridStrategyEngine'),('grid-ledger','com/btcgrid/ledger/service/InMemoryStrategyLedger')]:
    files.append(ROOT.parents[1]/module/'target/classes'/f'{path}.class')
(ROOT/'replay_hashes.json').write_text(json.dumps({str(p.relative_to(ROOT.parents[1])):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2))
print(json.dumps({'audit':checks,'returns':{n:s['return_pct'] for n,s in results.items()},'benchmark':benchmark},indent=2,ensure_ascii=False))
