from pathlib import Path
import json,hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
FONT_PATH=Path("/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc")
if FONT_PATH.exists():
 matplotlib.font_manager.fontManager.addfont(str(FONT_PATH))
 plt.rcParams["font.family"]=FontProperties(fname=str(FONT_PATH)).get_name()
plt.rcParams["axes.unicode_minus"]=False
ROOT=Path(__file__).resolve().parent;OLD=ROOT.parent/'reversible_grid_20_20261012'
LABEL={'grid20_minute':'旧版45–55：完成后仍回补','grid20_borrow5':'旧版：负USDT按5%计息','grid20_borrow10':'旧版：负USDT按10%计息','direct4060_minute':'40–60触发：直接回到50%','grid4060_minute':'40–60网格：20份完成即停','grid4060_OHLC':'40–60网格：另一OHLC路径','grid4060_half_fill':'40–60网格：半成交','grid4060_fee2':'40–60网格：手续费翻倍','grid4060_borrow5':'40–60网格：负USDT按5%计息','grid4060_borrow10':'40–60网格：负USDT按10%计息'}

def table(rows):
 return pd.DataFrame([{'方案':LABEL[x['id']],'期末净资产':f"${x['final_equity']:,.2f}",'总收益':f"{x['total_return']*100:.2f}%",'年化':f"{x['cagr']*100:.2f}%",'最大回撤':f"{x['max_drawdown']*100:.2f}%",'资金费净收入':f"${x['funding_net']:,.2f}",'手续费':f"${x['fees']:,.2f}",'负USDT利息':f"${x['borrow_interest']:,.2f}",'配对往返':x['grid_pair_count'],'配对净价差':f"${x['matched_grid_profit']:,.2f}"} for x in rows]).to_html(index=False)

def main():
 new=json.loads((ROOT/'results.json').read_text());old=json.loads((ROOT/'reference_results.json').read_text());r={x['id']:x for x in old+new};a=r['grid4060_minute'];b=r['grid20_minute'];direct=r['direct4060_minute']
 plt.figure(figsize=(14,7))
 for name in ['grid20_minute','grid4060_minute','direct4060_minute','grid4060_borrow5','grid4060_borrow10']:
  base=OLD if name.startswith('grid20') else ROOT;d=pd.read_csv(base/'outputs'/f'{name}_daily.csv');plt.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,label=LABEL[name])
 batches_raw=pd.read_csv(ROOT/'outputs/grid4060_minute_batches.csv')
 for direction,marker,color,label in [('sell_up_buy_back','v','#b91c1c','网格启动：加空、降低BTC敞口'),('buy_down_sell_back','^','#15803d','网格启动：减空、提高BTC敞口')]:
  points=batches_raw[batches_raw.direction==direction]
  if len(points):
   plt.scatter(pd.to_datetime(points.start_ms,unit='ms',utc=True),points.start_equity,marker=marker,color=color,s=38,alpha=.65,zorder=5,label=f'{label}（{len(points)}批）')
 complete_points=batches_raw[batches_raw.end_reason=='adjustment_completed_orders_cancelled']
 grid_trades=pd.read_csv(ROOT/'outputs/grid4060_minute_trades.csv')
 completed_fills=grid_trades[grid_trades.batch.isin(complete_points.batch)].groupby('batch',sort=False).tail(1)
 if len(completed_fills):plt.scatter(pd.to_datetime(completed_fills.timestamp_ms,unit='ms',utc=True),completed_fills.equity,marker='s',facecolors='none',edgecolors='#1d4ed8',s=60,zorder=6,label=f'网格20份全部成交、撤单结束（{len(completed_fills)}批）')
 direct_trades=pd.read_csv(ROOT/'outputs/direct4060_minute_trades.csv')
 direct_events=direct_trades[direct_trades.kind=='rebalance'].copy()
 for increasing,marker,label in [(False,'v','直接再平衡：降低BTC敞口'),(True,'^','直接再平衡：提高BTC敞口')]:
  points=direct_events[(direct_events.delta>0)==increasing]
  if len(points):
   plt.scatter(pd.to_datetime(points.timestamp_ms,unit='ms',utc=True),points.equity,marker=marker,facecolors='none',edgecolors='#111827',linewidths=1.8,s=120,zorder=6,label=label)
 plt.ylabel('组合净资产（USDT）');plt.xlabel('日期（UTC）');plt.title('净资产曲线及调仓位置')
 plt.legend(fontsize=9,ncol=2);plt.grid(alpha=.25);plt.tight_layout();plt.savefig(ROOT/'equity.png',dpi=150);plt.close()
 chart_note=f'<p>实心红色倒三角：新版网格加空批次启动（{len(batches_raw[batches_raw.direction=="sell_up_buy_back"])}批）；实心绿色正三角：网格减空批次启动（{len(batches_raw[batches_raw.direction=="buy_down_sell_back"])}批）。蓝色空心方块：网格20份全部成交并撤掉本批剩余挂单。空心黑色三角：直接再平衡实际成交，倒三角降低BTC敞口，正三角提高BTC敞口。标记按真实事件时间及净资产绘制；网格标记表示启动挂单，不代表当时已经成交，不逐笔标注格内往返成交。时间均为UTC。</p>'
 stats=[]
 for name in ['grid20_minute','grid4060_minute','direct4060_minute']:
  x=r[name];base=OLD if name.startswith('grid20') else ROOT;d=pd.read_csv(base/'outputs'/f'{name}_daily.csv')
  stats.append({'方案':LABEL[name],'日终平均净敞口%':d.weight.mean()*100,'最低净敞口%':x['min_weight']*100,'最高净敞口%':x['max_weight']*100,'最大模拟USDT负余额':x['max_negative_usdt'],'期末USDT损益余额':x['final_actual_usdt'],'批次数':x['batches'],'全部份额成交批次':x['batches_completed'],'挂单成交笔数':x['maker_fills'],'吃单成交笔数':x['taker_fills']})
 batches=pd.read_csv(ROOT/'outputs/grid4060_minute_batches.csv')
 for c in ['start_ms','first_completed_ms','end_ms']:batches[c.replace('_ms','_utc')]=pd.to_datetime(batches[c],unit='ms',utc=True).astype(str)
 batches=batches.drop(columns=['start_ms','first_completed_ms','end_ms'])
 batches['direction']=batches.direction.map({'sell_up_buy_back':'上涨加空／回落平空','buy_down_sell_back':'下跌平空／反弹加空'})
 batches['end_reason']=batches.end_reason.replace({'adjustment_completed_orders_cancelled':'20份全部成交，撤单并保留调整后仓位','all_returned_inside_band':'全部回补且敞口回到触发带内','open_at_end':'期末仍未结束'})
 for c in ['ever_traded','retired']:batches[c]=batches[c].map({True:'是',False:'否'})
 for c in ['start_utc','first_completed_utc','end_utc']:batches[c]=batches[c].replace('NaT','—')
 headers={'batch':'批次编号','direction':'调仓方向','anchor':'启动合约价格（USDT）','start_equity':'启动净资产（USDT）','start_weight':'启动净敞口比例','planned_qty':'计划调整数量（BTC）','requested_slots':'计划格数','actual_slots':'实际格数','total_units':'计划数量单位数（每单位0.001 BTC）','held_units':'尚未回补数量单位数','ever_traded':'是否曾成交','retired':'是否已结束','completion_count':'全格成交完成次数','end_reason':'结束状态','end_weight':'结束净敞口比例','first_completed_weight':'首次全格成交净敞口比例','start_utc':'启动时间（UTC）','first_completed_utc':'首次全格成交时间（UTC）','end_utc':'结束时间（UTC）'}
 assert set(batches.columns)<=set(headers),set(batches.columns)-set(headers)
 batches=batches.rename(columns=headers)
 yearly=pd.read_csv(ROOT/'yearly.csv');yearly['id']=yearly.id.map(LABEL);yearly=yearly.rename(columns={'id':'方案','year':'年份','return_pct':'年度收益（%）'})
 audit=json.loads((ROOT/'audit.json').read_text())
 headline=f"<p><strong>40–60触发、10%分20份：期末${a['final_equity']:,.2f}，年化{a['cagr']*100:.2f}%，回撤{a['max_drawdown']*100:.2f}%。旧45–55/5%/20份：年化{b['cagr']*100:.2f}%，回撤{b['max_drawdown']*100:.2f}%。同40–60触发直接版：年化{direct['cagr']*100:.2f}%，回撤{direct['max_drawdown']*100:.2f}%。</strong>基础值均未计潜在USDT负余额利息。</p>"
 headline+='<p>规则修正：此前40–60报告在20份全部成交后仍允许回补，年化14.69%、最大回撤32.23%，共141次上侧启动、没有下侧启动。该版本与用户最终确认的完成条件不一致。本报告改为20份成交后立即撤单、保留调整；旧数据仅用于追溯。</p>'
 notes='''<p>用户明确选择40%–60%触发，单次冻结10%待调整仓位，分20份、0.1%格距，以向50%附近回归。初始为500,000 USDT市值的BTC现货存量＋约50%名义合约空单，现货数量固定。净敞口=(现货市值−空单标记市值)/组合净资产。买回和卖回边界也同步改成60%及40%。初始现货视为存量，不另计现货购买手续费。</p>
<p>60%端：按当时净资产10%折算BTC合约数量，固定预算分20份，每涨一格加一份空单，回落至前一格平掉对应份额，之后原位可再次加空。40%端镜像：每跌一格平空，反弹一格加回空单。每份必须先开/调整，才能返向回收，返向量不超过该份已调整量。上侧买回后不超过60%，下侧卖回后不低于40%；禁止合约净多仓。</p>
<p>格位是启动价格×1.001^k或×0.999^k，总20格宽度累计上涨约2.02%，下跌约1.98%。每份约0.5%净资产的合约数量，是旧5%/20份版的两倍。20份全部成交即结束本批，立即撤掉本批全部剩余挂单及返向回补单，永久保留本批调整结果；之后只有敞口再次达到40%或60%才启动新批次。因成交期间价格持续变化，完成后净敞口接近但不保证恰为50%。全部回收且占比在40%–60%内时退休；未结束批次标open_at_end。</p>
<p>无24小时吃单退出、无40/60强制风控调整（40/60就是启动带）、无逐笔重新对齐50%。同方向存在尚未完成的当前批次时不重复创建。单次10%预算冻结于启动时刻，不随后续成交重新计算。只初始建空单用Taker，网格全为Maker。</p>
<p>全部Bybit同交易所现货、永续成交价、标记价与历史资金费，2024-10-02至2026-10-02，连续1,051,200根1分钟数据及2,190次资金费。在实际资金费时间点先对已持仓结算后交易，正费率空单收款，负费率付款，初始未建仓时不领取起始资金费。沿用VIP3研究参数Maker0.014%、Taker0.035%，初始吃单滑点1bp；未通过账户签名接口核实实际费率。</p>
<p>数量步长0.001 BTC、价格步长0.1 USDT；限价须穿透2bp，触价不算成交，新挂单和返向单最早下一根分钟K线成交。不得同分钟新成交后立即又完成返向。分钟内OLHC模拟，与另一OHLC路径、半成交及费用翻倍作压力对照；未重建真实逐笔、订单簿排队和撤单延迟。压力费用会改变净资产与触发，结果不保证单调。</p>
<p>公平直接对照也是40–60触发，每分钟观察越界后Taker回到50%，没有往返恢复库存逻辑；旧45–55/5%/20份为完成后仍回补的旧模型，保留作历史对照，不是用户最终确认的规则。当前已修正为20份完成即停。</p>
<p>组合净资产含所有未平仓合约损益。配对网格净价差扣除对应两笔Maker费，不分摊资金费，已包含在组合损益里，不得重复加到总利润。合约按加权平均开仓价结算实际USDT损益，因此配对盈利与实际USDT负余额可以同时出现。5%/10%融资情景按每小时模拟USDT负余额计息，不代表已经核实的账户借款金额或利率。BTC抵押支持已由用户确认；历史折扣、风险档位、自动借款/兑换及强平规则仍未重建。没有部署或修改实盘。</p>'''
 body='<h1>修正版：40–60触发，10%分20份，全部成交后撤单结束</h1>'+headline+'<p><a href="comparison.csv">比较CSV</a> · <a href="yearly.csv">年度收益</a> · <a href="audit.json">独立核验</a> · <a href="source_hashes.json">数据及源代码哈希</a></p><img src="equity.png">'+chart_note+'<h2>核心比较及融资情景</h2>'+table([b,a,direct,r['grid20_borrow5'],r['grid4060_borrow5'],r['grid20_borrow10'],r['grid4060_borrow10']])+'<h2>全部新场景</h2>'+table(new)+'<h2>敞口与USDT结算</h2>'+pd.DataFrame(stats).to_html(index=False,float_format=lambda x:f'{x:,.3f}')+'<h2>年度收益（首尾为部分年份）</h2>'+yearly.to_html(index=False,float_format=lambda x:f'{x:.2f}')+'<h2>执行规则及限制</h2>'+notes+'<h2>默认全部批次</h2><input id="search" placeholder="筛选日期、方向、状态"><div id="batches">'+batches.to_html(index=False,float_format=lambda x:f'{x:.6f}')+'</div>'+f"<p>核验：{len(audit['unit_checks'])}项针对性检查，{len(audit['scenarios'])}个新场景。全部逐笔成交、份额配对、日终净资产和批次见outputs/。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>"
 doc='<!doctype html><meta charset="utf-8"><title>40–60网格完成即停回测</title><style>body{font-family:system-ui;max-width:1700px;margin:30px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{padding:6px;border:1px solid #ddd}img{max-width:100%}input{padding:8px}</style>'+body+'<script>document.getElementById("search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
 (ROOT/'report.html').write_text(doc)
 (ROOT/'decision.json').write_text(json.dumps({'research_only':True,'stop_on_completion':True,'user_confirmed_adjustment_fraction':.10,'low':.40,'high':.60,'levels':20,'step':.001,'new_minus_old_grid_equity':a['final_equity']-b['final_equity'],'new_minus_same_band_direct_equity':a['final_equity']-direct['final_equity'],'supersedes_continuous_return_report':'../reversible_grid_4060_20261012/report_continuous_returns.html'},indent=2))
 (ROOT/'README.md').write_text('40–60触发，用户确认单次调整10%，分20份，0.1%格距，完成前可逆往返，20份全成交后撤单结束并保留调整，无超时吃单。\n\n运行：python3 test_4060.py；python3 run.py；python3 validate_export.py；python3 build_report.py。数据通过data/相对软链接共享已核验分钟数据。参数化引擎在旧参数下逐笔精确复现上一版。\n\n报告见report.html及outputs/；基础结果未计潜在负USDT利息，另提供5%/10%情景。没有实盘变更。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。\n')
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
 print('report complete',len(new),'scenarios; new grid minus old grid',a['final_equity']-b['final_equity'])
if __name__=='__main__':main()
