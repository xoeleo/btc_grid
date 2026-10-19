"""Chinese report, actual allocations and every batch; consistent frozen-price comparison."""
from pathlib import Path
import json,hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties,fontManager
from matplotlib.ticker import FuncFormatter
ROOT=Path(__file__).resolve().parent
FONT='/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc'
fontManager.addfont(FONT);plt.rcParams['font.family']=FontProperties(fname=FONT).get_name();plt.rcParams['axes.unicode_minus']=False
LABEL={'frozen_grid50':'固定波段比例＋移动50份网格','frozen_direct':'固定波段比例＋直接再平衡','fixed50_grid50':'固定50%比例＋移动50份网格','fixed50_direct':'固定50%比例＋直接再平衡','spot_hold':'BTC现货持有','frozen_grid50_OHLC':'波段网格：另一分钟内路径','frozen_grid50_half':'波段网格：每次半量成交','frozen_grid50_fee2':'波段网格：手续费翻倍','frozen_grid50_borrow5':'波段网格：负USDT年息5%','frozen_grid50_allow_long':'波段网格：允许合约净多对照'}
MAIN=['frozen_grid50','frozen_direct','fixed50_grid50','fixed50_direct','spot_hold']
REASONS={'adjustment_completed_orders_cancelled':'全部份额成交、撤单完成','all_returned_inside_band':'全部回补且回到目标带内','unfilled_returned_inside_band':'未成交且回到目标带内撤单','open_at_end':'期末未完成'}
def read(name,suffix):
 try:return pd.read_csv(ROOT/'outputs'/f'{name}_{suffix}.csv')
 except pd.errors.EmptyDataError:return pd.DataFrame()
def financial(results):
 rows=[{'方案':LABEL[x['id']],'期末净资产（美元）':x['final_equity'],'累计收益（%）':100*x['total_return'],'年化收益（%）':100*x['cagr'],'最大回撤（%）':100*x['max_drawdown'],'资金费净收入（美元）':x['funding_net'],'手续费（美元）':x['fees'],'融资利息（美元）':x['borrow_interest'],'已配对网格净价差（美元）':x['matched_grid_profit']} for x in results]
 return pd.DataFrame(rows)
def render(df):return df.to_html(index=False,float_format=lambda v:f'{v:,.2f}')
def main():
 results=json.loads((ROOT/'results.json').read_text());r={x['id']:x for x in results};manifest=json.loads((ROOT/'period_manifest.json').read_text());audit=json.loads((ROOT/'audit.json').read_text())
 assert len(audit['export_replay'])==len(results)
 table=financial([r[n] for n in MAIN]);table.to_csv(ROOT/'comparison.csv',index=False)
 fig,ax=plt.subplots(figsize=(17,7))
 for name in MAIN:
  d=read(name,'daily');ax.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,label=LABEL[name])
 ax.set_title('两年回测：建仓时固定波段区间，按价格调整目标BTC比例');ax.set_ylabel('组合净资产（万美元）');ax.set_xlabel('日期（UTC）');ax.yaxis.set_major_formatter(FuncFormatter(lambda v,p:f'{v/10000:.0f}'));ax.grid(alpha=.2);ax.legend();fig.tight_layout();fig.savefig(ROOT/'equity.png',dpi=145);plt.close(fig)
 name='frozen_grid50';d=read(name,'daily');b=read(name,'batches');t=read(name,'trades');dates=pd.to_datetime(d.timestamp_ms,unit='ms',utc=True)
 fig,axes=plt.subplots(3,1,figsize=(17,12),sharex=True)
 axes[0].plot(dates,d.spot,label='BTC现货日终价格')
 for price,label in [(manifest['lower_price'],'固定低点：100% BTC'),(manifest['initial_ath'],'建仓前历史最高价'),(manifest['upper_price'],'固定高点：0% BTC')]:axes[0].axhline(price,ls='--',lw=1,label=f'{label}（{price:,.0f}）')
 axes[0].set_ylabel('现货价格（美元）');axes[0].set_title('固定区间、动态目标与实际BTC敞口')
 axes[1].plot(dates,d.target_weight*100,label='按价格计算的目标BTC比例',color='black',lw=1.5)
 axes[1].plot(dates,d.weight*100,label='移动网格实际净BTC敞口',alpha=.85)
 direct=read('frozen_direct','daily');axes[1].plot(pd.to_datetime(direct.timestamp_ms,unit='ms',utc=True),direct.weight*100,label='直接再平衡实际净BTC敞口',alpha=.65)
 axes[1].set_ylabel('BTC比例（%）');axes[1].set_ylim(-3,103)
 axes[2].plot(dates,(d.weight-d.target_weight)*100,label='网格实际比例减目标比例')
 for y in [-5,0,5]:axes[2].axhline(y,ls='--',color='gray',lw=.7)
 axes[2].set_ylabel('偏离目标（百分点）');axes[2].set_xlabel('日期（UTC）')
 for ax in axes:ax.grid(alpha=.2);ax.legend()
 fig.tight_layout();fig.savefig(ROOT/'allocation.png',dpi=145);plt.close(fig)
 fig,axes=plt.subplots(2,1,figsize=(17,9),sharex=True,gridspec_kw={'height_ratios':[2,1]})
 axes[0].plot(dates,d.equity,label=LABEL[name]);axes[1].plot(dates,d.weight*100,label='实际净BTC敞口');axes[1].plot(dates,d.target_weight*100,label='目标BTC比例',color='black',alpha=.7)
 for direction,marker,color,label in [('sell_up_buy_back','v','red','降低BTC敞口启动'),('buy_down_sell_back','^','green','提高BTC敞口启动')]:
  rows=b[b.direction==direction];dt=pd.to_datetime(rows.start_ms,unit='ms',utc=True)
  axes[0].scatter(dt,rows.start_equity,marker=marker,c=color,s=27,alpha=.65,label=f'{label}（{len(rows)}批）')
  axes[1].scatter(dt,rows.start_weight*100,marker=marker,c=color,s=16,alpha=.6)
 finished=b[b.end_reason=='adjustment_completed_orders_cancelled']
 if len(finished):
  last=t[t.batch.isin(finished.batch)].groupby('batch').tail(1).set_index('batch').reindex(finished.batch)
  axes[0].scatter(pd.to_datetime(finished.end_ms,unit='ms',utc=True),last.equity,marker='s',facecolors='none',edgecolors='blue',s=40,label=f'全部成交、撤单完成（{len(finished)}批）')
 axes[0].set_title('波段比例移动50份网格：调仓启动与完成位置');axes[0].set_ylabel('组合净资产（万美元）');axes[0].yaxis.set_major_formatter(FuncFormatter(lambda v,p:f'{v/10000:.0f}'));axes[1].set_ylabel('BTC比例（%）');axes[1].set_xlabel('日期（UTC）')
 for ax in axes:ax.grid(alpha=.2);ax.legend()
 fig.tight_layout();fig.savefig(ROOT/'rebalance_events.png',dpi=145);plt.close(fig)
 stats=[]
 for name in MAIN[:-1]:
  x=r[name];dd=read(name,'daily');bb=read(name,'batches');tt=read(name,'trades')
  stats.append({'方案':LABEL[name],'实际再平衡成交次数':int((tt.kind=='rebalance').sum()) if len(tt) else 0,'网格启动批次':len(bb),'全部调整完成批次':x['batches_completed'],'全数回补后退出':int((bb.end_reason=='all_returned_inside_band').sum()) if len(bb) else 0,'未成交回带内撤单':int((bb.end_reason=='unfilled_returned_inside_band').sum()) if len(bb) else 0,'期末未完成批次':int((bb.end_reason=='open_at_end').sum()) if len(bb) else 0,'挂单成交笔数':x['maker_fills'],'日终平均实际BTC比例（%）':dd.weight.mean()*100,'最低实际BTC比例（%）':x['min_weight']*100,'最高实际BTC比例（%）':x['max_weight']*100,'期末实际BTC比例（%）':x['final_weight']*100,'期末目标BTC比例（%）':x['final_target']*100,'超出目标带小时':x['outside_band_hours'],'目标超过现货能力小时':x['short_only_target_unreachable_hours']})
 pd.DataFrame(stats).to_csv(ROOT/'tracking_and_counts.csv',index=False)
 detail=b[['batch','direction','start_ms','anchor','start_target','start_weight','planned_qty','actual_slots','held_units','total_units','last_fill_anchor','end_ms','end_reason','end_weight']].copy()
 for col in ['start_ms','end_ms']:detail[col]=pd.to_datetime(detail[col],unit='ms',utc=True).dt.strftime('%Y-%m-%d %H:%M UTC')
 for col in ['start_target','start_weight','end_weight']:detail[col]*=100
 detail.direction=detail.direction.map({'sell_up_buy_back':'上涨降低BTC敞口、回落恢复','buy_down_sell_back':'下跌提高BTC敞口、反弹恢复'});detail.end_reason=detail.end_reason.map(REASONS)
 detail.columns=['批次','方向','启动时间','初始网格中心价','启动目标BTC比例（%）','启动实际BTC比例（%）','冻结调整量（BTC）','份数','当前净调整单位','计划单位','当前网格中心价','结束时间','结束原因','结束实际BTC比例（%）']
 init_trade=t[t.kind=='initial'].iloc[0]
 high=manifest['upper_price'];low=manifest['lower_price'];anchor=manifest['initial_ath']
 examples=[low,manifest['initial_spot_price'],anchor,100000.,manifest['period_max_spot'],high]
 curve=pd.DataFrame([{'BTC现货价格（美元）':p,'目标BTC比例（%）':max(0.,min(1.,(high-p)/(high-low)))*100} for p in examples])
 target_min=max(0.,min(1.,(high-manifest['period_max_spot'])/(high-low)))*100;target_max=max(0.,min(1.,(high-manifest['period_min_spot'])/(high-low)))*100
 grid=r['frozen_grid50'];direct=r['frozen_direct'];fixed=r['fixed50_grid50']
 body=f'<h1>两年回测：固定波段高低点＋动态BTC比例＋移动50份网格</h1><p><strong>最终确认规则：建仓时固定高低点，2024-10-02至2026-10-02期间不随创新高更新。</strong>建仓前历史最高价采用当时已知的Bybit现货高点{anchor:,.0f}美元，低点{low:,.0f}对应100% BTC，高点{high:,.0f}对应0% BTC。首次按当前价位目标建仓。</p>'
 body+=f'<p>初始净资产50万美元，BTC现货为存量，现货数量固定；主方案用永续空单调整净BTC敞口。初始现货价{manifest["initial_spot_price"]:,.2f}美元，目标BTC比例{manifest["initial_target"]*100:.2f}%，初始空单{abs(init_trade.q):,.3f} BTC，成交后实际比例{init_trade.weight*100:.2f}%。没有把空单名义卖出金额当作可支配USDT。</p>'
 body+='<h2>核心结果</h2>'+render(table)+'<img src="equity.png">'
 body+=f'<p>波段网格年化{grid["cagr"]*100:.2f}%，同仓位曲线直接再平衡年化{direct["cagr"]*100:.2f}%，差额{(grid["cagr"]-direct["cagr"])*100:.2f}个百分点。波段网格相对固定50%网格的年化差额{(grid["cagr"]-fixed["cagr"])*100:.2f}个百分点。配对网格净价差已在总损益内，不得再次加到期末资产上。比较不同目标曲线时，初始仓位、后续敞口及资金费都不同；收益差额不等于纯粹网格收益。</p>'
 body+='<h2>仓位公式与执行规则</h2><p>目标BTC比例＝截断到0%–100%的（固定高点－当前BTC现货价）／（固定高点－固定低点）。净BTC敞口＝（现货市值＋合约带符号的标记市值）／组合净资产。每分钟检查一次实际比例与当时目标的偏差；达到5个百分点后，冻结当时净资产5%折算的合约数量，分成50份。预算不会逐笔重算。</p>'
 body+='<p>仅一个活动批次，最多一买一卖。上涨减敞口批次先卖合约，有已调整份额后才挂买单恢复；下跌增敞口批次镜像。每笔成交后撤掉两侧旧单，围绕实际成交价上下各0.1%重挂；回补数量不能超过本批已调整份额。净调整量达到全部预算后撤单结束，保留调整结果。全数回补且回到当前目标±5个百分点以内时结束本批；未成交批次回到带内也撤单。没有24小时吃单、超时强制完成、完成后永久回补或相反方向第二活动批次。活动批次的方向与总预算保持冻结，因此目标变化途中可能暂时偏离目标带。</p>'
 body+=render(curve)+'<img src="allocation.png">'
 body+=f'<p><strong>样本边界：</strong>两年现货最低{manifest["period_min_spot"]:,.2f}、最高{manifest["period_max_spot"]:,.2f}美元，均未触及固定低点或高点；目标实际范围约{target_min:.2f}%–{target_max:.2f}%。本样本没有验证真实行情触及0%／100%端点的表现，端点仅有合成数据规则检查。冻结区间超出本轮波段后如何更新，尚未加入策略。</p>'
 body+='<h2>实际敞口、跟踪偏差与调仓次数</h2>'+render(pd.DataFrame(stats))+'<p>纯BTC现货持有基准固定100%敞口，不设置再平衡目标。启动批次含未成交撤单和全数回补退出；不能把启动次数或每份成交次数当作整批调仓完成次数。主方案只能平空单增加敞口，若目标所需BTC市值超过固定现货持仓，则会报告不可达时长；允许合约净多只作为额外研究对照。</p>'
 body+='<h2>调仓位置及每批明细</h2><img src="rebalance_events.png"><p>红色倒三角：降低BTC敞口启动；绿色正三角：提高BTC敞口启动；蓝色空心方块：全部份额成交完成。三角是挂单批次启动，不代表该批一定成交。</p><input id="search" placeholder="筛选时间、方向、状态"><div id="batches">'+render(detail)+'</div>'
 body+='<h2>成交路径、部分成交及成本对照</h2>'+render(financial(results))+'<p>默认分钟内路径OLHC，另测OHLC；每次半量成交场景会改变完成时点与后续持仓。手续费、利息也会改变以后触发路径，因此情景结果可能不单调。允许合约净多场景与主方案的区别仅在是否允许买入跨过零仓位，不代表实盘授权。</p>'
 signed=pd.DataFrame([{'方案':LABEL[x['id']],'最大合约净多数量（BTC）':x['max_long_qty'],'持有合约净多小时':x['long_position_hours'],'最大模拟USDT负余额（美元）':x['max_negative_usdt'],'融资利息（美元）':x['borrow_interest']} for x in results]);body+=render(signed)
 yearly=pd.read_csv(ROOT/'yearly.csv');yearly['id']=yearly['id'].map(LABEL);yearly.columns=['方案','年份','该年区间收益（%）'];body+='<h2>年度收益（首尾为部分年份）</h2>'+render(yearly)
 body+=f'<h2>数据与费用假设</h2><p>同一冻结Bybit现货、永续成交价、标记价数据。两年{manifest["minute_rows"]:,}根完整分钟数据，无缺口、无重复；{manifest["funding_rows"]:,}个完整8小时资金费时间点。历史最高价预热仅使用2021-10-02至建仓前的现货高点，确认值发生于2024-03-14；未用两年回测中的最高价倒推区间。初始无合约仓位，不领取建仓时点的资金费；之后使用实际历史费率对带符号持仓结算。</p>'
 body+='<p>VIP3研究费率：Maker 0.014%、Taker 0.035%，尚未从签名账户接口核实历史VIP等级或费率变化。初始建空和直接调仓为吃单，滑点1基点；网格穿透限价2基点才计成交。数量步长0.001 BTC、价格步长0.1 USDT。新单及撤单重挂后的订单最早下一分钟生效，同一分钟最多一次网格成交。没有真实逐笔盘口、队列顺序、撤单竞态、历史UTA抵押折扣及强平规则重建。现货为初始存量，未计买入现货手续费；基础情景包含合约手续费与资金费，未计融资利息，另按实际模拟USDT负余额逐小时计年息5%。</p>'
 body+=f'<p>{len(audit["unit_checks"])}项规则检查、{len(results)}个场景独立账本及移动报价／库存／固定目标验证通过。<a href="audit.json">核验记录</a> · <a href="period_manifest.json">区间与历史最高价证明</a> · <a href="source_hashes.json">代码与数据哈希</a> · <a href="outputs/frozen_grid50_trades.csv">主方案成交账本</a>。仅研究目录变更，无实盘修改。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>'
 script='<script>document.querySelector("#search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
 (ROOT/'report.html').write_text('<!doctype html><meta charset="utf-8"><title>固定波段目标比例两年回测</title><style>body{font-family:system-ui;max-width:1750px;margin:25px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{border:1px solid #ddd;padding:5px}img{max-width:100%}input{padding:8px;min-width:320px}</style>'+body+script)
 (ROOT/'decision.json').write_text(json.dumps({'research_only':True,'frozen_reference':True,'reference_ath':anchor,'low_price':low,'high_price':high,'threshold':.05,'adjustment_fraction':.05,'levels':50,'step':.001,'allow_perp_long_main':False,'main_result':grid,'direct_result':direct},indent=2))
 (ROOT/'README.md').write_text('建仓时固定历史最高价参考，不随创新高更新。低点ATH/2→100% BTC，高点ATH×2→0%；价格线性计算目标比例，偏离目标5个百分点触发冻结净资产5%调整量，50份、0.1%移动网格。主方案固定BTC现货＋合约空单，净多仅作对照。\n\n区间2024-10-02至2026-10-02。\npython3 prepare_data.py\npython3 test_target.py\npython3 run.py\npython3 validate_export.py\npython3 build_report.py\n\n分钟数据中新挂单下一分钟生效，每分钟最多一次网格成交。无实盘变更。\n')
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2));print('REPORT COMPLETE',len(results))
if __name__=='__main__':main()
