from pathlib import Path
import json,hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parent
LABEL={'grid100_borrow5':'100份网格：负USDT按5%计息','grid100_borrow10':'100份网格：负USDT按10%计息','direct_minute':'直接再平衡：每分钟检查','grid100_minute':'100份可逆网格：默认','grid100_OHLC':'100份网格：另一OHLC路径','grid100_half_fill':'100份网格：半成交','grid100_fee2':'100份网格：手续费翻倍','grid100_penetration5bp':'100份网格：穿透5bp才成交','grid100_delay2bars':'100份网格：反向单延迟2根','grid100_no_funding':'100份网格：关闭资金费','direct_no_funding':'直接再平衡：关闭资金费','direct_hourly_trigger':'直接再平衡：每小时检查','grid100_5minute':'100份网格：5分钟粗粒度'}

def table(rows):
 records=[]
 for x in rows:
  records.append({'方案':LABEL.get(x['id'],x['id']),'期末资产':f"${x['final_equity']:,.2f}",'总收益':f"{x['total_return']*100:.2f}%",'年化':f"{x['cagr']*100:.2f}%",'最大回撤':f"{x['max_drawdown']*100:.2f}%",'资金费净收入':f"${x['funding_net']:,.2f}",'负USDT利息':f"${x['borrow_interest']:,.2f}",'手续费':f"${x['fees']:,.2f}",'Maker/Taker笔数':f"{x['maker_fills']}/{x['taker_fills']}",'已匹配往返数':x['grid_pair_count'],'已匹配网格净价差':f"${x['matched_grid_profit']:,.2f}"})
 return pd.DataFrame(records).to_html(index=False)

def main():
 rows=json.loads((ROOT/'results.json').read_text());cases={x['id']:x for x in rows};grid=cases['grid100_minute'];direct=cases['direct_minute']
 a=json.loads((ROOT/'audit.json').read_text())
 plt.figure(figsize=(12,6))
 for name in ['direct_minute','grid100_minute','grid100_half_fill','direct_hourly_trigger','grid100_no_funding','grid100_borrow10']:
  d=pd.read_csv(ROOT/'outputs'/f'{name}_daily.csv');plt.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,label=name)
 plt.ylabel('Account equity (USDT)');plt.grid(alpha=.25);plt.legend(fontsize=8);plt.tight_layout();plt.savefig(ROOT/'equity.png',dpi=150);plt.close()
 plt.figure(figsize=(12,5))
 for name in ['direct_minute','grid100_minute']:
  d=pd.read_csv(ROOT/'outputs'/f'{name}_daily.csv');plt.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.weight*100,label=name)
 plt.axhline(45,color='gray',ls='--');plt.axhline(55,color='gray',ls='--');plt.ylabel('Net BTC exposure / equity (%)');plt.grid(alpha=.25);plt.legend();plt.tight_layout();plt.savefig(ROOT/'exposure.png',dpi=150);plt.close()
 batches=pd.read_csv(ROOT/'outputs/grid100_minute_batches.csv')
 for key in ['start_ms','first_completed_ms','end_ms']:
  batches[key.replace('_ms','_utc')]=pd.to_datetime(batches[key],unit='ms',utc=True).astype(str)
 tr=pd.read_csv(ROOT/'outputs/grid100_minute_trades.csv');counts=tr[tr.batch>0].groupby('batch').agg(成交笔数=('delta','count'),累计手续费=('fee','sum'),合约净变化=('delta','sum')).reset_index();batches=batches.merge(counts,on='batch',how='left')
 income=pd.DataFrame([{'方案':LABEL[x['id']],'现货损益':x['spot_pnl'],'合约已实现净手续费':x['perp_realized_net_fees'],'合约未实现损益':x['perp_unrealized'],'资金费':x['funding_net'],'总利润':x['final_equity']-500000,'实际USDT损益余额':x['final_actual_usdt'],'最高占比':x['max_weight']*100,'最低占比':x['min_weight']*100,'带外小时数':x['outside_band_hours']} for x in [direct,grid]])
 notes='''<p>初始为500,000 USDT市值的BTC现货存量，现货数量固定不变；初始合约Taker建立约50%名义空单。净敞口=(现货市值−空单标记市值)/净资产，净资产包括合约已实现及未实现损益、历史资金费和手续费。初始现货不是再次购买，不计额外现货建仓费。仅空单和平空，不允许合约净多仓。</p>
<p><b>默认网格严格采用固定100份、0.1%格距，无24小时超时，无强制40/60调整，无每笔重新调至50%的规则。</b>每分钟开盘观察，BTC净敞口达到或超过55%，冻结当时净资产5%对应的BTC合约数量，分成100份；开盘价格为锚点，第k个卖出价为锚点×1.001^k，买回价为锚点×1.001^(k−1)。卖出增加空单，买回平掉这份空单，买回后可在原卖价再次卖出。100份同时全部卖出标记批次完成，但保留买回/再次卖出机制。份额是数量，不是每次重新确定的固定美元金额。</p>
<p>45%端镜像：固定5%数量，价格每下跌0.1%买入平空，反弹一格卖出加回；下跌格位按锚点×0.999^k，反向价为前一格。上侧买回不得将净敞口推至55%以上，下侧卖回不得推至45%以下；因敞口限制暂停的订单只有恢复为非可立即成交价格时才重新挂出，不将吃单冒充Maker。仍受空单不能平为净多仓约束。</p>
<p>同方向存在尚未完成的当前批次时，不重复开100份；完成100份后若价格继续导致越界，可以启动下一批。旧批次回购单仍保留，因此长期趋势后可能有多个批次。某批份额全部回收且占比在45%–55%内时退休并撤掉该批剩余重卖单；其余未结束批次标为open_at_end。批次完成表示100份已调整，价格在此期间变化，因此不等同于占比正好50%。</p>
<p>Bybit合约数量步长按0.001 BTC，100份采用整数单位均分并分配余数，总数量不超预算；不足100个最小数量单位则实际份数减少并记录。报价步长0.1 USDT；卖价向上取整、买价向下取整。格位不是固定线性10%宽度：连续100次上涨0.1%，累计约10.51%。</p>
<p>Maker暂用0.014%，Taker0.035%，沿用VIP3研究参数，未通过账户签名接口核实。只有初始建空单用Taker（滑点1bp），网格全部Maker。默认限价必须穿透2bp，触价不算成交；新挂单、反向订单最早下一根1分钟K线成交，因此不允许同一分钟新成交后立即又完成反向往返。半成交版本每次只成交剩余数量50%（至少一个最小单位）；剩余在后续K线保持可成交。压力版本延迟两根K线、穿透5bp以及Maker/Taker费用翻倍。未重建订单簿深度、排队及真实撤单延迟。</p>
<p>全部现货、合约成交价、合约标记价来自Bybit公共API，2024-10-02至2026-10-02，共1,051,200根连续分钟记录，资金费2,190次；初始时点未建空单不领取资金费。资金费按事件时刻已持有仓位和标记价估算，先结算再交易；负费率空单支付。1分钟内部用OLHC/OHLC预设路径，将三种价格按同阶段插值，并非观察到的逐笔路径；另报告5分钟对比，用于识别粗数据偏差。</p>
<p>公平直接对照每分钟观察45%–55%，越界后立即Taker回到50%；另保留每小时直接对照，以对应上一份报告的15.89%口径。相同价格和资金费下，触发频率改变也可能改变结果。所有场景从同一日期、同样本金独立开始，没有按历史最优参数筛选。</p>
<p><b>已匹配网格价差</b>只统计某份的完整开/回往返，扣除这两笔Maker手续费，不分摊资金费。它是现金流分解，不可再次加到组合净利润；未匹配份额仍承担浮动盈亏，组合净值包含全部未平仓合约。手续费翻倍等变化可能改变占比与后续触发，结果不保证单调。</p>
<p>基础场景未计负USDT借款利息、税费、交易所托管及稳定币风险。实际USDT损益账本出现阶段性负余额，因此额外按每小时负余额计5%/10%年利息敏感性；这些是融资成本情景，不是已核实的账户借款记录或实际利率。报告保留实际USDT损益余额及最负余额，不能把合约卖出的名义金额当作收到的USDT。统一账户允许BTC抵押已由用户确认；历史抵押折扣、风险档位、自动借贷/兑换及强平规则未重建。80%抵押折算、1%维持保证金仅作情景观察，不证明实盘不会强平。</p>'''
 summary=f"<p><strong>默认100份网格：期末${grid['final_equity']:,.2f}，年化{grid['cagr']*100:.2f}%，回撤{grid['max_drawdown']*100:.2f}%。每分钟直接对照：年化{direct['cagr']*100:.2f}%，回撤{direct['max_drawdown']*100:.2f}%。期末差额${grid['final_equity']-direct['final_equity']:,.2f}。</strong></p>"
 body='<h1>固定100份、0.1%可逆网格再平衡</h1>'+summary+'<p><a href="results.csv">结果CSV</a> · <a href="yearly.csv">年度收益</a> · <a href="audit.json">核验记录</a> · <a href="data/manifest.json">数据来源</a></p><img src="equity.png"><h2>全部对照及成交压力场景</h2>'+table(rows)+'<h2>组合损益及敞口</h2>'+income.to_html(index=False,float_format=lambda x:f'{x:,.2f}')+'<img src="exposure.png"><h2>年度收益（首尾为部分年份）</h2>'+pd.read_csv(ROOT/'yearly.csv').to_html(index=False,float_format=lambda x:f'{x:.2f}')+'<h2>规则与执行限制</h2>'+notes+'<h2>全部默认批次</h2><input id="search" placeholder="筛选日期、方向或结束状态"><div id="batches">'+batches.to_html(index=False,float_format=lambda x:f'{x:.6f}')+'</div><p>全部成交、资金费、日终持仓、逐份配对与批次见outputs/。未结束批次标记open_at_end。</p>'+f"<p>核验：{len(a['unit_checks'])}项针对性检查；{len(a['scenarios'])}个场景逐笔账本重放通过。</p><p>外部复核未执行：本会话没有可用内置浏览器或ChatGPT Pro工具。</p>"
 doc='<!doctype html><meta charset="utf-8"><title>100份可逆网格再平衡</title><style>body{font-family:system-ui;max-width:1700px;margin:30px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{padding:6px;border:1px solid #ddd}img{max-width:100%}input{padding:8px}</style>'+body+'<script>document.getElementById("search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
 (ROOT/'report.html').write_text(doc)
 decision=dict(research_only=True,default_grid_minus_direct_minute=grid['final_equity']-direct['final_equity'],default_grid_beats_direct_minute=grid['final_equity']>direct['final_equity'],rules={'slots':100,'step':.001,'timeout':False,'roundtrip_rearm':True,'fixed_five_percent':True},limitations=['OHLC execution assumptions, no order-book queue reconstruction','VIP3 account-specific rates not queried','historical unified-account liquidation/borrow rules not reconstructed'])
 (ROOT/'decision.json').write_text(json.dumps(decision,indent=2))
 (ROOT/'README.md').write_text('固定100份、0.1%可逆网格研究；现货数量固定，合约仅空与平空。\n\n运行：python3 fetch_data.py；python3 test_engine.py；python3 run.py；python3 build_report.py。完整规则、损益、配对与批次详见report.html及outputs/。\n\n默认结果：'+json.dumps({k:grid[k] for k in ['final_equity','cagr','max_drawdown','funding_net','matched_grid_profit','fees']},ensure_ascii=False)+'\n\n无实盘修改。外部复核未执行：本会话没有可用内置浏览器或ChatGPT Pro工具。\n')
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
 print('report complete',len(rows),'scenarios')
if __name__=='__main__':main()
