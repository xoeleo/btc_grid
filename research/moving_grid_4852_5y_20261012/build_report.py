from pathlib import Path
import json,hashlib,html
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.dates as md
from matplotlib.font_manager import FontProperties, fontManager
from matplotlib.ticker import FuncFormatter
ROOT=Path(__file__).resolve().parent
FONT='/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc'
fontManager.addfont(FONT)
plt.rcParams['font.family']=FontProperties(fname=FONT).get_name()
plt.rcParams['axes.unicode_minus']=False
LABEL={'direct4852_minute':'48–52直接再平衡','grid20_minute':'48–52移动20格','grid50_minute':'48–52移动50格','grid20_4555':'45–55移动20格'}
for n in [20,50]:
 for suffix,label in [('OHLC','另一分钟内路径'),('half_fill','每次半量成交'),('borrow5','负USDT按年息5%')]:LABEL[f'grid{n}_{suffix}']=f'48–52移动{n}格：{label}'
def read(name,suffix):
 try:return pd.read_csv(ROOT/'outputs'/f'{name}_{suffix}.csv')
 except pd.errors.EmptyDataError:return pd.DataFrame()
def table(results):
 return pd.DataFrame([{'方案':LABEL[x['id']],'期末净资产（美元）':x['final_equity'],'年化收益（%）':100*x['cagr'],'最大回撤（%）':100*x['max_drawdown'],'资金费净收入（美元）':x['funding_net'],'手续费（美元）':x['fees'],'融资利息（美元）':x['borrow_interest'],'已配对网格净价差（美元）':x['matched_grid_profit'],'启动批次':x['batches'],'完成批次':x['batches_completed'],'最低净BTC敞口（%）':x['min_weight']*100,'最高净BTC敞口（%）':x['max_weight']*100} for x in results]).to_html(index=False,float_format=lambda v:f'{v:,.2f}')
def main():
 results=json.loads((ROOT/'results.json').read_text());r={x['id']:x for x in results};audit=json.loads((ROOT/'audit.json').read_text());assert len(audit['export_replay'])==len(results)
 names=['direct4852_minute','grid20_minute','grid50_minute'];fig,ax=plt.subplots(figsize=(17,7))
 for name in names:
  d=read(name,'daily');ax.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,label=LABEL[name])
 ax.yaxis.set_major_formatter(FuncFormatter(lambda v,p:f'{v/10000:.0f}'));ax.set_ylabel('组合净资产（万美元）');ax.set_xlabel('日期（UTC）');ax.set_title('五年回测：每次成交后撤单、以成交价重建一买一卖');ax.grid(alpha=.2);ax.legend();fig.tight_layout();fig.savefig(ROOT/'equity.png',dpi=145);plt.close(fig)
 stats=[];batch_html=''
 for n in [20,50]:
  name=f'grid{n}_minute';d=read(name,'daily');b=read(name,'batches');t=read(name,'trades');pairs=read(name,'pairs')
  starts=b.groupby('direction').size().to_dict();finished=b[b.end_reason=='adjustment_completed_orders_cancelled'];pending=b[b.end_reason=='open_at_end']
  stats.append({'方案':LABEL[name],'启动批次':len(b),'全部成交完成':len(finished),'全数回补后退出':sum(b.end_reason=='all_returned_inside_band'),'未成交回带内撤单':sum(b.end_reason=='unfilled_returned_inside_band'),'期末未完成':len(pending),'挂单成交笔数':r[name]['maker_fills'],'回补配对数量':r[name]['grid_pair_count'],'日终平均净BTC敞口（%）':d.weight.mean()*100})
  fig,axes=plt.subplots(2,1,figsize=(17,9),sharex=True,gridspec_kw={'height_ratios':[2,1]})
  dates=pd.to_datetime(d.timestamp_ms,unit='ms',utc=True);axes[0].plot(dates,d.equity,label=LABEL[name])
  axes[1].plot(dates,d.weight*100,label='日终净BTC敞口')
  for direction,marker,color,label in [('sell_up_buy_back','v','red','加空启动'),('buy_down_sell_back','^','green','减空启动')]:
   rows=b[b.direction==direction];dt=pd.to_datetime(rows.start_ms,unit='ms',utc=True)
   axes[0].scatter(dt,rows.start_equity,marker=marker,c=color,s=27,label=f'{label}（{len(rows)}批）',alpha=.65)
   axes[1].scatter(dt,rows.start_weight*100,marker=marker,c=color,s=18,alpha=.6)
  if len(finished):
   dt=pd.to_datetime(finished.end_ms,unit='ms',utc=True)
   # Completion equity is from actual last fills, not interpolated daily values.
   final=t[t.batch.isin(finished.batch)].groupby('batch').tail(1).set_index('batch').reindex(finished.batch)
   axes[0].scatter(dt,final.equity,marker='s',facecolors='none',edgecolors='blue',s=38,label=f'全部成交撤单（{len(finished)}批）')
  for y in [48,50,52]:axes[1].axhline(y,color='gray',ls='--',lw=.7)
  axes[0].yaxis.set_major_formatter(FuncFormatter(lambda v,p:f'{v/10000:.0f}'));axes[0].set_ylabel('组合净资产（万美元）');axes[1].set_ylabel('净BTC敞口（%）');axes[1].set_xlabel('日期（UTC）')
  for ax in axes:ax.grid(alpha=.2);ax.legend()
  axes[0].set_title(f'{n}格移动网格：启动、完成与持仓比例');fig.tight_layout();fig.savefig(ROOT/f'grid{n}_events.png',dpi=145);plt.close(fig)
  bb=b[['batch','direction','start_ms','anchor','planned_qty','actual_slots','held_units','total_units','last_fill_anchor','end_ms','end_reason','end_weight']].copy()
  for c in ['start_ms','end_ms']:bb[c]=pd.to_datetime(bb[c],unit='ms',utc=True).dt.strftime('%Y-%m-%d %H:%M UTC')
  bb.direction=bb.direction.map({'sell_up_buy_back':'上涨加空、回落减空','buy_down_sell_back':'下跌减空、反弹加空'})
  bb.end_reason=bb.end_reason.map({'adjustment_completed_orders_cancelled':'全部份额成交、撤单完成','all_returned_inside_band':'全部回补且回到触发带内','unfilled_returned_inside_band':'未成交且回到触发带内撤单','open_at_end':'期末未完成'})
  bb.end_weight*=100
  bb.columns=['批次','方向','启动时间','初始中心价','冻结调整量（BTC）','份数','当前净调整单位','计划单位','当前网格中心价','结束时间','状态','结束净BTC敞口（%）']
  batch_html+=f'<h2>{n}格调仓位置及批次</h2><img src="grid{n}_events.png"><p>三角标记是批次启动，蓝色方块是全部成交完成；每笔成交记录见<a href="outputs/{name}_trades.csv">成交账本</a>。单批单位为0.001 BTC。</p>'+bb.to_html(index=False,float_format=lambda v:f'{v:,.4f}')
 pd.DataFrame(stats).to_csv(ROOT/'rebalance_counts.csv',index=False)
 refs=json.loads((ROOT.parent/'reversible_grid_4852_5y_20261012/results.json').read_text());old={x['id']:x for x in refs}
 compare=[]
 for name in names:
  x=r[name];o=old[name];compare.append({'方案':LABEL[name],'固定锚点年化（%）':o['cagr']*100,'移动中心年化（%）':x['cagr']*100,'固定锚点最高敞口（%）':o['max_weight']*100,'移动中心最高敞口（%）':x['max_weight']*100})
 pd.DataFrame(compare).to_csv(ROOT/'old_model_comparison.csv',index=False)
 body='<h1>移动中心网格再平衡：五年48–52、20／50份</h1><p><strong>本报告按最新澄清重写执行模型：最多一买一卖；任何一笔成交后撤掉全部旧单，以实际成交价为新中心重挂上下0.1%的订单。此前固定锚点、多层挂单的报告不能代替本模型。</strong></p>'
 body+='<p>初始50万美元BTC现货存量，约25万美元永续空单。2021-10-02至2026-10-02。48%–52%触发时冻结当时净资产2%折算的合约数量，分成20或50份。45–55移动20格附加对照冻结5%。</p><img src="equity.png"><h2>基础场景</h2>'+table([r[n] for n in names])
 body+='<h2>执行规则</h2><p>只有一个活动批次。上侧先加空；有已加空份额后才挂减空回补单。下侧镜像。回补只恢复本批已调整库存，不能增加额外份额，也不再沿用旧模型的触发阈值回补限额。每次成交后撤单，两侧以该成交价重新报价，开仓取下一份，回补取最近尚未回补的份额。20／50份指冻结预算份数，并非同时挂20／50张订单。净调整量达到全部预算后立即撤单完成，已完成批次不再回补。若全数回补且敞口回到阈值带内，结束本批；零成交批次回带内也撤单。已部分成交而尚未结束时持续移动中心，不启动相反方向的第二批。冻结数量不随成交重新计算，所以完成后不保证精确50%。</p>'
 body+='<p><strong>时间与成交限制：</strong>历史数据为分钟OHLC，不含逐笔盘口；新单及撤单重挂后的两单最早下一分钟成交，同一分钟最多一次网格成交。穿透挂单价2基点才计成交。默认分钟路径OLHC，另测OHLC。该延迟是避免凭分钟数据虚构即时连续成交的研究假设；尚未模拟盘口队列、撤单竞态和真实撤单确认。</p><h2>调仓次数与实际持仓</h2>'+pd.DataFrame(stats).to_html(index=False,float_format=lambda v:f'{v:,.2f}')
 body+='<h2>与旧固定锚点模型对照</h2>'+pd.DataFrame(compare).to_html(index=False,float_format=lambda v:f'{v:,.2f}')+'<p>这里只用于量化执行模型差异。移动中心解决的是订单锚点不更新问题，是否严格控制敞口仍需看结果；没有加入超时吃单或敞口硬限制。</p>'
 body+='<h2>路径、部分成交及融资情景</h2>'+table(results)+'<p>成本和成交变化会改变以后触发路径，结果不保证单调；不能把情景差额解释成纯粹成本效应。</p>'+batch_html
 body+='<h2>数据、费用及验证</h2><p>复用同一冻结Bybit现货／永续成交价／标记价，2,629,440根分钟记录、5,478次历史资金费。初始现货为存量，不计现货买入费用。研究VIP3费率：Maker 0.014%、Taker 0.035%，尚未从签名账户接口核实历史等级和费率。初始建空及直接调仓滑点1基点，最小数量0.001 BTC、价格0.1 USDT。基础结果含资金费及手续费，融资利息为零；借款情景按实际模拟负USDT余额逐小时年息5%。配对净价差已含在组合损益中，不得重复相加。未重建历史UTA制度、抵押折扣及强平规则。</p>'
 body+=f'<p>{len(audit["unit_checks"])}项规则检查、{len(results)}个五年场景独立账本及移动报价／库存核验通过。<a href="audit.json">核验记录</a> · <a href="source_hashes.json">代码与数据哈希</a>。仅研究目录变更，无实盘修改。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>'
 (ROOT/'report.html').write_text('<!doctype html><meta charset="utf-8"><title>移动网格再平衡五年报告</title><style>body{font-family:system-ui;max-width:1700px;margin:25px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{border:1px solid #ddd;padding:5px}img{max-width:100%}</style>'+body)
 (ROOT/'README.md').write_text('最新澄清：单活动批次、最多一买一卖；每次成交撤单并以实际成交价重挂上下0.1%。无库存不挂回补单，净调整全部份额后撤单完成。\n\npython3 test_moving.py\npython3 run.py\npython3 validate_export.py\npython3 build_report.py\n\n分钟数据限制：重挂下一分钟生效，每分钟最多一次网格成交。基础情景未计融资利息。仅研究，无实盘变更。\n')
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2));print('report complete',len(results))
if __name__=='__main__':main()
