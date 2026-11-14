from pathlib import Path
import json,hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties,fontManager
from matplotlib.ticker import FuncFormatter
ROOT=Path(__file__).resolve().parent
FONT='/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc';fontManager.addfont(FONT)
plt.rcParams['font.family']=FontProperties(fname=FONT).get_name();plt.rcParams['axes.unicode_minus']=False
LABEL={'frozen_grid50_10pct':'10%触发／调整：移动50份网格','frozen_direct_10pct':'10%触发：直接再平衡','frozen_grid50_5pct':'5%触发／调整：移动50份网格','frozen_direct_5pct':'5%触发：直接再平衡'}
ORDER=['frozen_grid50_5pct','frozen_grid50_10pct','frozen_direct_5pct','frozen_direct_10pct']
REASONS={'adjustment_completed_orders_cancelled':'全部份额成交、撤单完成','all_returned_inside_band':'全部回补且回到目标带内','unfilled_returned_inside_band':'未成交且回到目标带内撤单','open_at_end':'期末未完成'}
def read(name,suffix):
 try:return pd.read_csv(ROOT/'outputs'/f'{name}_{suffix}.csv')
 except pd.errors.EmptyDataError:return pd.DataFrame()
def render(df):return df.to_html(index=False,float_format=lambda v:f'{v:,.2f}')
def main():
 results=json.loads((ROOT/'results.json').read_text());r={x['id']:x for x in results};a=json.loads((ROOT/'audit.json').read_text());m=json.loads((ROOT/'period_manifest.json').read_text())
 assert len(a['export_replay'])==4 and all(x['config']['fill_fraction']==1. for x in results)
 rows=[];stats=[]
 for name in ORDER:
  x=r[name];d=read(name,'daily');b=read(name,'batches');t=read(name,'trades')
  rows.append({'方案':LABEL[name],'年化收益（%）':x['cagr']*100,'最大回撤（%）':x['max_drawdown']*100,'期末净资产（美元）':x['final_equity'],'累计收益（%）':x['total_return']*100,'资金费净收入（美元）':x['funding_net'],'手续费（美元）':x['fees'],'已配对网格净价差（美元）':x['matched_grid_profit']})
  stats.append({'方案':LABEL[name],'直接调仓次数':int((t.kind=='rebalance').sum()),'网格启动批次':len(b),'完整调整批次':x['batches_completed'],'全数回补退出':int((b.end_reason=='all_returned_inside_band').sum()) if len(b) else 0,'未成交撤单批次':int((b.end_reason=='unfilled_returned_inside_band').sum()) if len(b) else 0,'期末未完成批次':int((b.end_reason=='open_at_end').sum()) if len(b) else 0,'挂单成交笔数':x['maker_fills'],'平均日终BTC敞口（%）':d.weight.mean()*100,'平均日终绝对偏差（百分点）':(d.weight-d.target_weight).abs().mean()*100,'最大实际偏差（百分点）':x['max_target_error']*100,'最低实际BTC比例（%）':x['min_weight']*100,'最高实际BTC比例（%）':x['max_weight']*100,'期末实际BTC比例（%）':x['final_weight']*100,'期末目标BTC比例（%）':x['final_target']*100})
 pd.DataFrame(rows).to_csv(ROOT/'comparison.csv',index=False);pd.DataFrame(stats).to_csv(ROOT/'tracking_and_counts.csv',index=False)
 fig,ax=plt.subplots(figsize=(17,7))
 for name in ORDER:
  d=read(name,'daily');ax.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,label=LABEL[name])
 ax.set_title('固定波段目标比例：5%与10%调仓比较，全部按正常完整成交');ax.yaxis.set_major_formatter(FuncFormatter(lambda v,p:f'{v/10000:.0f}'));ax.set_ylabel('组合净资产（万美元）');ax.set_xlabel('日期（UTC）');ax.grid(alpha=.2);ax.legend();fig.tight_layout();fig.savefig(ROOT/'equity.png',dpi=145);plt.close(fig)
 name='frozen_grid50_10pct';d=read(name,'daily');b=read(name,'batches');t=read(name,'trades');dates=pd.to_datetime(d.timestamp_ms,unit='ms',utc=True)
 fig,axes=plt.subplots(2,1,figsize=(17,9),sharex=True)
 axes[0].plot(dates,d.weight*100,label='10%网格实际BTC敞口');axes[0].plot(dates,d.target_weight*100,label='动态目标BTC比例',color='black',alpha=.7);axes[0].set_ylabel('BTC比例（%）');axes[0].set_ylim(0,100)
 for n in ['frozen_grid50_5pct','frozen_grid50_10pct']:
  day=read(n,'daily');axes[1].plot(pd.to_datetime(day.timestamp_ms,unit='ms',utc=True),(day.weight-day.target_weight)*100,label=LABEL[n])
 for y in [-10,-5,0,5,10]:axes[1].axhline(y,ls='--',color='gray',lw=.6)
 axes[1].set_ylabel('实际比例减目标（百分点）');axes[1].set_xlabel('日期（UTC）');axes[0].set_title('目标仓位跟踪与5%／10%触发带')
 for ax in axes:ax.grid(alpha=.2);ax.legend()
 fig.tight_layout();fig.savefig(ROOT/'allocation.png',dpi=145);plt.close(fig)
 fig,axes=plt.subplots(2,1,figsize=(17,9),sharex=True)
 axes[0].plot(dates,d.equity,label=LABEL[name]);axes[1].plot(dates,d.weight*100,label='实际净BTC敞口');axes[1].plot(dates,d.target_weight*100,label='动态目标',color='black',alpha=.7)
 for direction,marker,color,label in [('sell_up_buy_back','v','red','降低BTC敞口启动'),('buy_down_sell_back','^','green','提高BTC敞口启动')]:
  sub=b[b.direction==direction];dt=pd.to_datetime(sub.start_ms,unit='ms',utc=True)
  axes[0].scatter(dt,sub.start_equity,marker=marker,c=color,s=30,alpha=.65,label=f'{label}（{len(sub)}批）');axes[1].scatter(dt,sub.start_weight*100,marker=marker,c=color,s=18,alpha=.6)
 complete=b[b.end_reason=='adjustment_completed_orders_cancelled']
 if len(complete):
  last=t[t.batch.isin(complete.batch)].groupby('batch').tail(1).set_index('batch').reindex(complete.batch)
  axes[0].scatter(pd.to_datetime(complete.end_ms,unit='ms',utc=True),last.equity,marker='s',facecolors='none',edgecolors='blue',s=40,label=f'全部成交撤单（{len(complete)}批）')
 axes[0].set_title('10%调整量、50份移动网格：启动与完成');axes[0].set_ylabel('组合净资产（万美元）');axes[0].yaxis.set_major_formatter(FuncFormatter(lambda v,p:f'{v/10000:.0f}'));axes[1].set_ylabel('BTC比例（%）');axes[1].set_xlabel('日期（UTC）')
 for ax in axes:ax.grid(alpha=.2);ax.legend()
 fig.tight_layout();fig.savefig(ROOT/'rebalance_events.png',dpi=145);plt.close(fig)
 detail=b[['batch','direction','start_ms','anchor','start_target','start_weight','planned_qty','actual_slots','held_units','total_units','last_fill_anchor','end_ms','end_reason','end_weight']].copy()
 for c in ['start_ms','end_ms']:detail[c]=pd.to_datetime(detail[c],unit='ms',utc=True).dt.strftime('%Y-%m-%d %H:%M UTC')
 for c in ['start_target','start_weight','end_weight']:detail[c]*=100
 detail.direction=detail.direction.map({'sell_up_buy_back':'上涨减敞口、回落恢复','buy_down_sell_back':'下跌增敞口、反弹恢复'});detail.end_reason=detail.end_reason.map(REASONS)
 detail.columns=['批次','方向','启动时间','初始网格中心价','启动目标BTC比例（%）','启动实际BTC比例（%）','冻结调整量（BTC）','份数','当前净调整单位','计划单位','当前网格中心价','结束时间','状态','结束实际BTC比例（%）']
 body='<h1>固定波段目标：10%调仓、50份移动网格，两年回测</h1><p><strong>本报告所有收益场景都采用正常完整成交，成交比例为100%。</strong>10%版本同时修改触发门槛和单次调整量：实际BTC比例偏离当时动态目标10个百分点后，冻结当时净资产10%折算的合约数量，分为50份。5%版本沿用已核验的正常完整成交基准；未引用半量成交结果。</p>'
 body+=f'<p>区间2024-10-02至2026-10-02，初始50万美元。建仓前已知历史最高价{m["initial_ath"]:,.0f}美元，固定低点{m["lower_price"]:,.0f}对应100% BTC，固定高点{m["upper_price"]:,.0f}对应0% BTC，两年内不更新。目标比例＝截断到0%–100%的（147,600－现货价）／110,700。首次目标BTC比例{m["initial_target"]*100:.2f}%。沿用固定BTC现货＋合约空单调仓。</p>'
 body+='<h2>收益比较</h2>'+render(pd.DataFrame(rows))+'<img src="equity.png"><h2>调仓次数与实际敞口</h2>'+render(pd.DataFrame(stats))+'<img src="allocation.png">'
 body+='<h2>执行规则</h2><p>格距保持0.1%。每次正常成交一份后，撤掉全部旧单，以成交价为中心重新挂最多一买一卖。回补只能恢复本批已调整的份额；无回补库存时只挂调整方向一单。净调整全部50份后撤单完成，保留调整结果。已全部回补且实际比例回到当前目标±10个百分点以内时结束本批；未成交批次回到带内也撤单。单批方向和预算冻结，不逐笔重算，只有一个活动批次，不加入超时吃单或硬敞口边界。10个百分点是启动门槛，不能保证全过程偏差都在10个百分点以内。</p>'
 body+='<p>直接再平衡使用相同动态目标及10个百分点触发门槛，触发后一次调整至当时目标；网格使用冻结10%预算，完成后不保证恰好到达目标。主方案不能建立合约净多；所需目标超过固定现货能力时只能平空单。启动批次包含零成交撤单和全数回补退出，不能等同于完整调仓次数。</p>'
 body+='<h2>10%网格调仓位置</h2><img src="rebalance_events.png"><p>红色倒三角：降低BTC敞口启动；绿色正三角：提高BTC敞口启动；蓝色空心方块：全部成交完成。三角标记代表启动挂单，不代表该批一定成交。</p><input id="search" placeholder="筛选日期、方向、状态"><div id="batches">'+render(detail)+'</div>'
 yearly=pd.read_csv(ROOT/'yearly.csv');yearly.id=yearly.id.map(LABEL);yearly.columns=['方案','年份','该年区间收益（%）'];body+='<h2>年度收益（首尾为部分年份）</h2>'+render(yearly)
 body+=f'<h2>数据、成本及核验</h2><p>复用冻结Bybit现货、永续成交价、标记价与历史资金费，{m["minute_rows"]:,}根完整分钟数据、{m["funding_rows"]:,}个8小时资金费时间点。历史最高价仅由建仓前数据确定。两年现货范围{m["period_min_spot"]:,.2f}至{m["period_max_spot"]:,.2f}美元，未触及目标0%／100%端点。</p>'
 body+='<p>包含历史资金费和VIP3研究手续费：Maker 0.014%、Taker 0.035%，未从签名账户接口核实历史费率或等级。初始建空及直接调仓为吃单，滑点1基点；网格穿透限价2基点才计成交，新单／撤单重挂后的单最早下一分钟生效，每分钟最多一次网格成交。数量步长0.001 BTC、价格0.1 USDT。基础结果未计融资利息；现货为初始存量，不计买入现货费用。没有逐笔盘口、队列、撤单竞态及历史UTA强平规则重建。配对净价差已含在总损益中，不得重复相加。</p>'
 body+=f'<p>{len(a["unit_checks"])}项规则检查、2个新10%场景以及2个已核验5%基准的保存账本回放通过。<a href="audit.json">核验记录</a> · <a href="reference_provenance.json">5%基准账本来源及哈希</a> · <a href="source_hashes.json">代码与数据哈希</a> · <a href="outputs/frozen_grid50_10pct_trades.csv">10%网格成交账本</a>。仅研究目录变更，无实盘修改。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>'
 script='<script>document.querySelector("#search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
 (ROOT/'report.html').write_text('<!doctype html><meta charset="utf-8"><title>10%调仓移动50份网格</title><style>body{font-family:system-ui;max-width:1750px;margin:25px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{border:1px solid #ddd;padding:5px}img{max-width:100%}input{padding:8px;min-width:320px}</style>'+body+script)
 (ROOT/'README.md').write_text('固定波段目标，偏离10个百分点触发，冻结净资产10%调整量，50份、0.1%移动网格，正常完整成交。两年2024-10-02至2026-10-02。\npython3 test_10pct.py\npython3 run.py\npython3 validate_export.py\npython3 build_report.py\n仅研究，无实盘变更。\n')
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2));print('REPORT COMPLETE')
if __name__=='__main__':main()
