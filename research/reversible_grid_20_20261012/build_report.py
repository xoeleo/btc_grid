from pathlib import Path
import json,hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parent;OLD=ROOT.parent/'reversible_grid_100_20261012'
LABEL={'grid20_minute':'20份，0.1%：默认','grid20_OHLC':'20份：另一OHLC路径','grid20_half_fill':'20份：半成交','grid20_fee2':'20份：手续费翻倍','grid20_borrow5':'20份：负USDT按5%计息','grid20_borrow10':'20份：负USDT按10%计息','grid100_minute':'100份，0.1%：原版','grid100_borrow5':'100份：负USDT按5%计息','grid100_borrow10':'100份：负USDT按10%计息','direct_minute':'直接再平衡：每分钟观察','direct_hourly_trigger':'直接再平衡：每小时观察'}

def table(rows):
 return pd.DataFrame([{'方案':LABEL[x['id']],'期末净资产':f"${x['final_equity']:,.2f}",'总收益':f"{x['total_return']*100:.2f}%",'年化':f"{x['cagr']*100:.2f}%",'最大回撤':f"{x['max_drawdown']*100:.2f}%",'资金费净收入':f"${x['funding_net']:,.2f}",'全部手续费':f"${x['fees']:,.2f}",'负USDT利息':f"${x['borrow_interest']:,.2f}",'配对往返':x['grid_pair_count'],'配对净价差':f"${x['matched_grid_profit']:,.2f}"} for x in rows]).to_html(index=False)

def main():
 new=json.loads((ROOT/'results.json').read_text());old=json.loads((ROOT/'reference_results.json').read_text());r={x['id']:x for x in old+new};a=r['grid20_minute'];b=r['grid100_minute'];direct=r['direct_minute']
 plt.figure(figsize=(12,6))
 for name in ['direct_minute','grid100_minute','grid20_minute','grid20_borrow5','grid20_borrow10']:
  base=ROOT if name.startswith('grid20') else OLD;d=pd.read_csv(base/'outputs'/f'{name}_daily.csv')
  plt.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,label=name)
 plt.ylabel('Account equity (USDT)');plt.legend();plt.grid(alpha=.25);plt.tight_layout();plt.savefig(ROOT/'equity.png',dpi=150);plt.close()
 stats=[]
 for name in ['direct_minute','grid100_minute','grid20_minute']:
  x=r[name];base=ROOT if name.startswith('grid20') else OLD;d=pd.read_csv(base/'outputs'/f'{name}_daily.csv')
  stats.append({'方案':LABEL[name],'平均日终净敞口%':d.weight.mean()*100,'最低净敞口%':x['min_weight']*100,'最高净敞口%':x['max_weight']*100,'最大模拟USDT负余额':x['max_negative_usdt'],'期末USDT损益余额':x['final_actual_usdt'],'批次数':x['batches'],'全部份额成交批次数':x['batches_completed'],'Maker笔数':x['maker_fills'],'Taker笔数':x['taker_fills']})
 batches=pd.read_csv(ROOT/'outputs/grid20_minute_batches.csv')
 for c in ['start_ms','first_completed_ms','end_ms']:batches[c.replace('_ms','_utc')]=pd.to_datetime(batches[c],unit='ms',utc=True).astype(str)
 audit=json.loads((ROOT/'audit.json').read_text())
 headline=f"<p><strong>20份年化{a['cagr']*100:.2f}%，回撤{a['max_drawdown']*100:.2f}%；100份年化{b['cagr']*100:.2f}%，回撤{b['max_drawdown']*100:.2f}%。20份相对100份期末净资产差额${a['final_equity']-b['final_equity']:,.2f}。</strong>默认均未计负USDT利息，另提供5%/10%敏感性。</p>"
 notes='''<p>本次唯一策略参数改动：初始5%待调整仓位分20份，原版100份；格距仍0.1%，初始50万美元BTC现货存量＋约25万美元合约空单，现货数量固定。使用原版完全相同的回测引擎及被冻结数据，并校验SHA256一致。100份与直接版结果复用原独立核验记录，不重新挑选起止日期。</p>
<p>上侧55%启动：按当时净资产5%折算合约BTC数量，冻结预算，分为20份；每上涨0.1%增加一份空单，跌回前一格买回该份；买回后可原格位再次卖出。买回量不超过此前对应份额卖出量，买回后不得超过55%。下侧45%镜像执行，跌一格平空，反弹一格加空，卖回后不得低于45%。不允许合约净多仓。份额按BTC数量划分，整数0.001 BTC单位均分、余数分配，报价按0.1 USDT向不利于立即成交方向取整。</p>
<p>锚点为每次启动价格，几何格位上侧P×1.001^k，下侧P×0.999^k；20格累计上涨约2.019%，原100格约10.512%。每份量约为原版5倍，因此更快调整完5%，每次往返金额更大。全部份额成交后标记调整完成，但保留返向买回/卖回单；行情继续越界可以启动新批次。某批全部份额回收且占比在45%–55%内时退休，未结束批次保留并标open_at_end。价格期间变化，所以完成全部份额不等同于占比精确50%。</p>
<p>无24小时超时吃单、无40/60强制调整、无逐笔重新调整到50%。每分钟观察阈值。默认Maker单边0.014%、初始Taker0.035%和1bp滑点，沿用VIP3研究参数，未签名查询用户账户实际费率。使用Bybit同交易所现货、合约成交价、标记价的1分钟数据，2024-10-02至2026-10-02，共1,051,200根，无补造价格；资金费2,190次，在事件时刻按已持有空单、标记价结算。正资金费空单收款，负资金费空单支付。</p>
<p>限价需穿透2bp，不按触价即成交；新挂单及反向单最早下一根分钟K线成交。不模拟同分钟新成交后马上往返。分钟内部OLHC预设路径；OHLC另一条路径、半成交、费用翻倍作压力测试。未重建逐笔、盘口及排队，结果仍属于研究模拟。手续费和利息会改变后续占比及批次触发，压力场景的仓位路径可能不同，所以费用变化的结果不保证单调，不能解释成增加费用能够提高收益。</p>
<p>配对净价差只统计明确对应份额的完整开/返向往返，扣除两笔Maker费用，不分摊资金费，已经包含在组合损益中，不可再次加到净利润。组合净资产含所有未平仓损益。实际USDT损益按合约平均开仓价结算，因此即使某份网格往返盈利，也可能因原有空单亏损被实现而出现USDT负余额。5%/10%年利率按每小时模拟负余额计息，属于融资成本情景，非已核实账户实际借款记录或利率。</p>
<p>BTC抵押支持由用户确认；统一账户历史抵押折扣、风险档位、借款、兑换与强平规则未重建。80%抵押折算、1%维持保证金仅作情景监控，不证明实盘不会强平。没有改动或部署实盘策略。</p>'''
 body='<h1>5%仓位分20份，0.1%可逆网格再平衡</h1>'+headline+'<p><a href="comparison.csv">全部比较CSV</a> · <a href="yearly.csv">20份年度收益</a> · <a href="audit.json">独立库存核验</a> · <a href="source_hashes.json">来源哈希</a></p><img src="equity.png"><h2>核心比较</h2>'+table([direct,b,a,r['grid100_borrow5'],r['grid20_borrow5'],r['grid100_borrow10'],r['grid20_borrow10']])+'<h2>成交压力测试</h2>'+table(new)+'<h2>仓位及USDT结算</h2>'+pd.DataFrame(stats).to_html(index=False,float_format=lambda x:f'{x:,.3f}')+'<h2>年度收益（首尾为部分年份）</h2>'+pd.read_csv(ROOT/'yearly.csv').to_html(index=False,float_format=lambda x:f'{x:.2f}')+'<h2>模型口径</h2>'+notes+'<h2>默认20份全部批次</h2><input id="search" placeholder="筛选日期、方向或状态"><div id="batches">'+batches.to_html(index=False,float_format=lambda x:f'{x:.6f}')+'</div>'+f"<p>{len(audit['unit_checks'])}项针对性检查；{len(audit['scenarios'])}个新场景通过账本核验。全部逐笔成交、往返配对、日终净资产和批次在outputs/。</p><p>外部复核未执行：本会话没有可用内置浏览器或ChatGPT Pro工具。</p>"
 doc='<!doctype html><meta charset="utf-8"><title>20份可逆网格回测</title><style>body{font-family:system-ui;max-width:1650px;margin:30px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{padding:6px;border:1px solid #ddd}img{max-width:100%}input{padding:8px}</style>'+body+'<script>document.getElementById("search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
 (ROOT/'report.html').write_text(doc)
 (ROOT/'decision.json').write_text(json.dumps({'research_only':True,'only_parameter_change':'levels 100 -> 20; step unchanged .001','same_engine_source':True,'same_frozen_input_data':True,'20_minus_100_equity':a['final_equity']-b['final_equity'],'20_minus_direct_minute_equity':a['final_equity']-direct['final_equity'],'20_beats_100':a['final_equity']>b['final_equity']},indent=2))
 (ROOT/'README.md').write_text('20份、0.1%可逆网格；只改份数，原引擎及冻结数据相同。\n\n运行：python3 test_engine.py；python3 run.py；python3 validate_export.py；python3 build_report.py。数据通过data/相对软链接共享原版，不重复下载或修改。\n\n报告见report.html；账本见outputs/。基础结果未计可能的USDT负余额利息，另附5%/10%成本情景。无实盘变更。外部复核未执行：本会话没有可用内置浏览器或ChatGPT Pro工具。\n')
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
 print('report complete',len(new),'new scenarios; equity difference 20 vs 100',a['final_equity']-b['final_equity'])
if __name__=='__main__':main()
