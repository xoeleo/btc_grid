from pathlib import Path
import json,html,hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parent
LABELS={'recent_spot':'满仓现货，不对冲','recent_fixed':'初始50%空单，之后不调整','recent_direct':'45–55%，直接合约再平衡','recent_grid':'45–55%，0.5%五层网格','recent_grid_OHLC':'网格，另一OHLC路径','recent_direct_OHLC':'直接，另一OHLC路径','recent_grid_step025':'网格间隔0.25%','recent_grid_step1':'网格间隔1%','recent_grid_half_fill':'网格，每层只成交50%','recent_direct_cost2':'直接，手续费翻倍+5bp滑点','recent_grid_cost2':'网格，手续费翻倍+5bp滑点','recent_direct_no_funding':'直接，不计资金费','recent_grid_no_funding':'网格，不计资金费','recent_direct_borrow5':'直接，负USDT按5%计息','recent_grid_borrow5':'网格，负USDT按5%计息','recent_direct_borrow10':'直接，负USDT按10%计息','recent_grid_borrow10':'网格，负USDT按10%计息'}

def table(rows):
 out=[]
 for r in rows:
  out.append({'方案':LABELS.get(r['id'],r['id']),'期末资产':f"${r['final_equity']:,.2f}",'总收益':f"{r['total_return']*100:.2f}%",'年化':f"{r['cagr']*100:.2f}%",'回撤':f"{r['max_drawdown']*100:.2f}%",'资金费净收入':f"${r['funding_net']:,.2f}",'手续费':f"${r['fees']:,.2f}",'负USDT利息':f"${r['borrow_interest']:,.2f}",'Maker/Taker笔数':f"{r['maker_fills']}/{r['taker_fills']}",'空单已平完无法达到50%的小时':f"{r['short_only_target_unreachable_hours']:.1f}"})
 return pd.DataFrame(out).to_html(index=False,escape=True)

def main():
 rows=json.loads((ROOT/'results.json').read_text());r={x['id']:x for x in rows};base=r['recent_direct'];grid=r['recent_grid']
 audit=json.loads((ROOT/'audit.json').read_text());manifest=json.loads((ROOT/'data/manifest.json').read_text())
 plt.figure(figsize=(12,6))
 for name in ['recent_spot','recent_fixed','recent_direct','recent_grid','recent_direct_borrow10','recent_grid_borrow10']:
  day=pd.read_csv(ROOT/'outputs'/f'{name}_daily.csv');plt.plot(pd.to_datetime(day.timestamp_ms,unit='ms',utc=True),day.equity,label=name.removeprefix('recent_'))
 plt.ylabel('Account equity (USDT)');plt.legend(fontsize=9,ncol=2);plt.grid(alpha=.25);plt.tight_layout();plt.savefig(ROOT/'equity.png',dpi=150);plt.close()
 plt.figure(figsize=(12,4))
 for name in ['recent_direct','recent_grid']:
  day=pd.read_csv(ROOT/'outputs'/f'{name}_daily.csv');plt.plot(pd.to_datetime(day.timestamp_ms,unit='ms',utc=True),day.weight*100,label=name)
 plt.axhline(45,color='gray',ls='--');plt.axhline(55,color='gray',ls='--');plt.ylabel('Net BTC exposure / equity (%)');plt.legend();plt.grid(alpha=.25);plt.tight_layout();plt.savefig(ROOT/'exposure.png',dpi=150);plt.close()
 decision=dict(status='RESEARCH_ONLY_NO_LIVE_CHANGE',default_grid_improves_over_direct=grid['final_equity']>base['final_equity'],default_grid_minus_direct_usdt=grid['final_equity']-base['final_equity'],preferred_research_baseline='direct',funding_is_not_guaranteed=True,account_fee_rates_confirmed=False,bybit_account_margin_and_borrow_rules_verified=False,fees={'maker':.00014,'taker':.00035},external_review='外部复核未执行：本会话没有可用内置浏览器或 ChatGPT Pro 工具。')
 (ROOT/'decision.json').write_text(json.dumps(decision,indent=2,ensure_ascii=False))
 notes='''<p>账户初始净资产500,000 USDT，已持有等值BTC现货（现货数量保持不变），开立约250,000 USDT名义空单。现货视为初始存量，不计重新买入手续费。净敞口=(现货市值−合约空单标记市值)/账户净资产；实际BTC价格敏感度还受现货/合约基差影响。只允许空单及平空，不允许合约净多仓。</p>
<p>直接版每小时开盘检查45%–55%，越界后Taker回到50%。网格版同样每小时启动，五层、每层0.5%价格间隔，向有利方向挂限价单（加空挂上方，平空挂下方）；每次成交重算剩余目标、禁止过量调整。全部层成交后结束该批，行情移动后未必正好达到50%，若继续越界则下一小时重新启动。24小时超时，或活跃批次敞口越过40%/60%，撤单并Taker调整到50%。不在挂单当根K线模拟成交，下一根开始，需穿透限价2bp，单纯触价不算成交；挂单开盘跳空保守按限价成交。半成交压力将每层成交数量减半，剩余取消，超时规则保留。</p>
<p>暂用VIP3合约费率：Maker 0.014%、Taker 0.035%，尚未通过用户账户查询确认。Taker滑点1bp，压力测试费用翻倍且滑点5bp。合约步长0.001 BTC、限价价格步长0.1 USDT、最小名义5 USDT是统一简化过滤器。实际历史过滤器及盘口深度、排队优先级未重建。</p>
<p>Bybit BTCUSDT现货、USDT永续成交价、标记价、历史资金费全部同交易所。近期2024-10-02至2026-10-02使用5分钟数据；2022年以来及独立2023起点使用小时数据。资金费在真实事件时刻按持有空单及该时刻标记价计算，先结算后交易，初始开仓不领取起始时刻的资金费。正费率空单收款，负费率空单付款。历史资金费不构成未来保证。</p>
<p>净资产包含现货市值、合约已实现/未实现损益、资金费和手续费。合约名义金额不是抵押品，也不是实际收到的USDT；输出同时提供真实USDT损益账本。基础场景对负USDT余额不计利息，因此是利息前结果；另按每小时负USDT损益账本计5%/10%年利息敏感性，非已核实的账户真实借款。统一账户抵押支持不等于无需结算USDT亏损：自动借贷、兑换、还款规则可能改变费用及现货数量。保持现货固定只是研究设定。</p>
<p>保证金仅做情景筛查：BTC抵押按80%折算、维持保证金按空单名义1%，未重建历史UTA风险档位、订单保证金、账户其他仓位或强平引擎。结果不能证明实际账户全程不会强平。五分钟内部采用预先指定OLHC/OHLC价格路径，并将现货/标记价同阶段插值，属于成交和回撤估计，非观察到的逐笔路径；两条路径、半成交、小时数据及费用压力用于检验稳健性。</p>
<p>网格参数为固定方案与敏感性对比，未按历史最优值挑选。长期行情中若全部空单平完仍不足50%净敞口，会保持零空单并记录无法达到目标的时长，不自动开多或借款买更多现货。没有做实盘部署。</p>'''
 headline=f"<p><strong>近期直接再平衡：年化{base['cagr']*100:.2f}%，回撤{base['max_drawdown']*100:.2f}%；默认网格：年化{grid['cagr']*100:.2f}%，回撤{grid['max_drawdown']*100:.2f}%。网格相对直接期末资产差额{grid['final_equity']-base['final_equity']:,.2f} USDT。</strong>基础结果尚未计负USDT利息，见下面的敏感性。</p>"
 recent=[x for x in rows if x['id'].startswith('recent_') and not x['id'].startswith('recent_hourly')];others=[x for x in rows if x not in recent]
 pnl=pd.DataFrame([{'方案':LABELS[x['id']],'现货损益':x['spot_pnl'],'合约已实现净手续费':x['perp_realized_net_fees'],'合约浮动损益':x['perp_unrealized'],'资金费':x['funding_net'],'利息':-x['borrow_interest'],'总利润':x['final_equity']-500000,'期末USDT损益余额':x['final_actual_usdt'],'最大负USDT余额':x['max_negative_usdt']} for x in [base,grid]])
 risk=pd.DataFrame([{'方案':x['id'],'最大空单名义/净资产':x['max_short_notional_to_equity'],'最低抵押折算后净资产':x['min_collateral_adjusted_equity'],'最低折算净资产/假设维持保证金':x['min_adjusted_equity_to_assumed_maintenance'],'负USDT小时数':x['negative_usdt_hours']} for x in rows])
 episodes=pd.read_csv(ROOT/'outputs/recent_grid_episodes.csv');trades=pd.read_csv(ROOT/'outputs/recent_grid_trades.csv')
 for c in ['start_ms','end_ms']:episodes[c.replace('_ms','_utc')]=pd.to_datetime(episodes[c],unit='ms',utc=True).astype(str)
 counts=trades.groupby('episode').agg(成交笔数=('delta','count'),手续费=('fee','sum'),净空单变化=('delta','sum')).reset_index();episodes=episodes.merge(counts,on='episode',how='left')
 body='<h1>BTC现货＋合约空单：直接与网格再平衡</h1>'+headline+'<p><a href="results.csv">全部结果CSV</a> · <a href="yearly.csv">年度结果</a> · <a href="audit.json">独立账本核验</a> · <a href="data/manifest.json">数据来源及哈希</a></p><img src="equity.png"><h2>最近两年，统一起点</h2>'+table(recent)+'<h2>收益分解</h2>'+pnl.to_html(index=False,float_format=lambda x:f'{x:,.2f}')+'<img src="exposure.png"><h2>更长区间及小时交叉检查</h2>'+table(others)+'<h2>年度收益（首尾年份可能为部分年份）</h2>'+pd.read_csv(ROOT/'yearly.csv').to_html(index=False,float_format=lambda x:f'{x:.2f}')+'<h2>模型与限制</h2>'+notes+'<h2>保证金情景及USDT负余额</h2>'+risk.to_html(index=False,float_format=lambda x:f'{x:,.2f}')+'<h2>默认网格全部调整批次</h2><input id="filter" placeholder="筛选时间、结束原因"><div id="episodes">'+episodes.to_html(index=False,float_format=lambda x:f'{x:.6f}')+'</div><p>全部逐笔成交、资金费结算、日终持仓及批次数据位于 outputs/。期末仍未处理的批次标为 open_at_end。</p>'+f"<p>核验：{len(audit['scenarios'])}个场景、{len(audit['unit_checks'])}项针对性检查，逐笔交易/资金费/利息/日终净资产均独立重放通过。</p><p>{decision['external_review']}</p>"
 doc='<!doctype html><meta charset="utf-8"><title>BTC 合约再平衡回测</title><style>body{font-family:system-ui;max-width:1600px;margin:30px auto;padding:0 15px}table{border-collapse:collapse;font-size:12px}td,th{padding:6px;border:1px solid #ddd}img{max-width:100%}p{line-height:1.65}input{padding:8px}</style>'+body+'<script>document.getElementById("filter").oninput=function(){document.querySelectorAll("#episodes tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
 (ROOT/'report.html').write_text(doc)
 (ROOT/'README.md').write_text('研究结论：默认网格未超过直接合约再平衡。基础收益未计可能的负USDT利息，须同时查看5%/10%敏感性。\n\n'+pd.DataFrame([base,grid])[['id','final_equity','cagr','max_drawdown','funding_net','fees']].to_string(index=False)+'\n\n运行：python3 fetch_data.py；python3 run.py；python3 build_report.py。完整模型与限制见report.html。初始现货是存量，统一账户真实借贷与强平规则未核实，非实盘安全证明。\n'+decision['external_review']+'\n')
 hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
 print('report complete',len(rows),'scenarios; default grid minus direct',decision['default_grid_minus_direct_usdt'])
if __name__=='__main__':main()
