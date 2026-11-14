from pathlib import Path
import hashlib
import json
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties, fontManager

ROOT = Path(__file__).resolve().parent
FONT = '/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc'
fontManager.addfont(FONT)
plt.rcParams['font.family'] = FontProperties(fname=FONT).get_name()
plt.rcParams['axes.unicode_minus'] = False
LABEL = {'base_10pct': '原10%波段网格', 'netzero_tp5': '修正：净敞口归零停网格／5%清仓', 'netzero_tp5_OHLC': '叠加网格：分钟内先高后低'}

def read(name, suffix):
    return pd.read_csv(ROOT / 'outputs' / f'{name}_{suffix}.csv')

def dates(values):
    return pd.to_datetime(values, unit='ms', utc=True)

def render(frame, ident=None):
    return frame.to_html(index=False, table_id=ident, float_format=lambda v: f'{v:,.2f}')

def main():
    results = json.loads((ROOT / 'results.json').read_text())
    r = {x['id']: x for x in results}
    audit = json.loads((ROOT / 'audit.json').read_text())
    assert audit['passed'] and len(audit['export_replay']) == 3
    assert all(c['passed'] for c in audit['actual_history_signal_check'])
    assert all(x['config']['fill_fraction'] == 1 for x in results)
    main_r = r['netzero_tp5']
    daily = read('netzero_tp5', 'daily')
    batches = read('netzero_tp5', 'batches')
    batches = batches[batches.role == 'funding'].copy()
    for column in ['held_vwap', 'exit_price']:
        if column not in batches:
            batches[column] = float('nan')
    assert len(batches) == main_r['funding_batches']
    trades = read('netzero_tp5', 'trades')
    zero_rows = []
    for batch in batches.to_dict('records'):
        near_zero = trades[(trades.timestamp_ms >= batch['start_ms']) &
                           (trades.timestamp_ms < batch['end_ms']) &
                           (trades.net_btc_qty < .001) & (trades.overlay_q < 0)]
        if len(near_zero):
            x = near_zero.iloc[0]
            zero_rows.append({'批次编号': int(batch['batch']), 'timestamp_ms': int(x.timestamp_ms),
                '永续标记价（美元）': x.mark, '现货数量（BTC）': x.spot_qty,
                '基础空单（BTC）': -x.q + x.overlay_q, '新增空单（BTC）': -x.overlay_q,
                '冻结新增量（BTC）': batch['planned_qty'], '剩余净BTC数量': x.net_btc_qty,
                '新增量完成比例（%）': -x.overlay_q / batch['planned_qty'] * 100})
    zero_events = pd.DataFrame(zero_rows)
    zero_events.to_csv(ROOT / 'near_zero_events.csv', index=False)
    rows = [{'方案': LABEL[x['id']], '年化收益（%）': x['cagr']*100,
             '最大回撤（%）': x['max_drawdown']*100, '期末净资产（美元）': x['final_equity'],
             '累计收益（%）': x['total_return']*100, '全部资金费净收入（美元）': x['funding_net'],
             '全部手续费（美元）': x['fees']} for x in results]
    previous_root = ROOT.parent / 'ath_target_funding_grid100_2y_20261014'
    old = next(x for x in json.loads((previous_root / 'results.json').read_text()) if x['id'] == 'funding100')
    assert old['config']['funding_fraction'] == .5 and old['config']['funding_levels'] == 100
    rows.insert(1, {'方案': '上一版：50%净敞口／100份', '年化收益（%）': old['cagr']*100,
                   '最大回撤（%）': old['max_drawdown']*100, '期末净资产（美元）': old['final_equity'],
                   '累计收益（%）': old['total_return']*100, '全部资金费净收入（美元）': old['funding_net'],
                   '全部手续费（美元）': old['fees']})
    second_root = ROOT.parent / 'ath_target_funding_grid200_fullnet_2y_20261015'
    old2 = next(x for x in json.loads((second_root / 'results.json').read_text()) if x['id'] == 'funding200')
    assert old2['config']['funding_fraction'] == 1 and old2['config']['funding_levels'] == 200
    rows.insert(2, {'方案': '上一版：100%净敞口／200份', '年化收益（%）': old2['cagr']*100,
                   '最大回撤（%）': old2['max_drawdown']*100, '期末净资产（美元）': old2['final_equity'],
                   '累计收益（%）': old2['total_return']*100, '全部资金费净收入（美元）': old2['funding_net'],
                   '全部手续费（美元）': old2['fees']})
    references = []
    for ref_root, scenario in [(previous_root, 'funding100'), (second_root, 'funding200')]:
        references.append({'source': str(ref_root / 'results.json'), 'scenario': scenario,
             'results_sha256': hashlib.sha256((ref_root / 'results.json').read_bytes()).hexdigest(),
             'daily_sha256': hashlib.sha256((ref_root / 'outputs' / f'{scenario}_daily.csv').read_bytes()).hexdigest()})
    (ROOT / 'reference_provenance.json').write_text(json.dumps(references, indent=2))
    wrong_root = ROOT.parent / 'ath_target_funding_grid100_fullnet_2y_20261015'
    wrong = next(x for x in json.loads((wrong_root / 'results.json').read_text()) if x['id']=='funding_full100')
    rows.insert(3, {'方案': '旧完成规则：100%敞口／100份／2%退出', '年化收益（%）': wrong['cagr']*100,
                   '最大回撤（%）': wrong['max_drawdown']*100, '期末净资产（美元）': wrong['final_equity'],
                   '累计收益（%）': wrong['total_return']*100, '全部资金费净收入（美元）': wrong['funding_net'],
                   '全部手续费（美元）': wrong['fees']})
    references.append({'source': str(wrong_root/'results.json'), 'scenario': 'funding_full100',
                       'results_sha256': hashlib.sha256((wrong_root/'results.json').read_bytes()).hexdigest(),
                       'daily_sha256': hashlib.sha256((wrong_root/'outputs/funding_full100_daily.csv').read_bytes()).hexdigest()})
    (ROOT/'reference_provenance.json').write_text(json.dumps(references,indent=2))
    comparison = pd.DataFrame(rows)
    comparison.to_csv(ROOT / 'comparison.csv', index=False)
    fig, ax = plt.subplots(figsize=(15, 6))
    for name in LABEL:
        d = read(name, 'daily')
        ax.plot(dates(d.timestamp_ms), d.equity/10000, label=LABEL[name], alpha=.85)
    previous_daily = pd.read_csv(previous_root / 'outputs/funding100_daily.csv')
    ax.plot(dates(previous_daily.timestamp_ms), previous_daily.equity/10000, label='上一版：50%净敞口／100份', ls='--')
    second_daily = pd.read_csv(second_root / 'outputs/funding200_daily.csv')
    ax.plot(dates(second_daily.timestamp_ms), second_daily.equity/10000, label='上一版：100%净敞口／200份', ls=':')
    wd = pd.read_csv(wrong_root/'outputs/funding_full100_daily.csv')
    ax.plot(dates(wd.timestamp_ms),wd.equity/10000,label='旧完成规则：100%／100份／2%退出',ls='-.')
    ax.set(title='两年组合净资产：资金费做空网格与原策略', ylabel='净资产（万美元）', xlabel='日期（UTC）')
    ax.legend(); ax.grid(alpha=.2); fig.tight_layout(); fig.savefig(ROOT / 'equity.png', dpi=150); plt.close(fig)
    fig, axes = plt.subplots(3, 1, figsize=(15, 10), sharex=True)
    dt = dates(daily.timestamp_ms)
    axes[0].plot(dt, daily.target_weight*100, label='基础理论目标', color='black')
    axes[0].plot(dt, daily.core_weight*100, label='基础实际比例', alpha=.8)
    axes[0].plot(dt, daily.weight*100, label='组合实际美元敞口比例', alpha=.8)
    axes[0].axhline(0, color='gray', lw=.7); axes[0].set_ylabel('BTC比例（%）')
    axes[1].plot(dt, -daily.overlay_q, label='新增空单数量'); axes[1].set_ylabel('BTC数量')
    f = pd.read_csv(ROOT / 'data/funding.csv')
    f = f[(f.timestamp_ms >= results[0]['start_ms']) & (f.timestamp_ms < results[0]['end_exclusive_ms'])]
    axes[2].step(dates(f.timestamp_ms), f.rate*100, where='post', label='最近已结算资金费率')
    axes[2].axhline(.02, ls='--', color='red', label='启动阈值0.02%'); axes[2].set_ylabel('每次资金费率（%）')
    axes[2].set_xlabel('日期（UTC）')
    for ax in axes: ax.legend(); ax.grid(alpha=.2)
    fig.tight_layout(); fig.savefig(ROOT / 'allocation.png', dpi=150); plt.close(fig)
    fig, ax = plt.subplots(figsize=(16, 7))
    ax.plot(dt, daily.mark, color='gray', lw=1, label='永续标记价（日终）')
    ax.scatter(dates(batches.start_ms), batches.anchor, marker='v', s=45, c='red', label=f'资金费网格启动（{len(batches)}批）')
    full = batches[batches.first_completed_ms.notna()]
    stop_marks = [trades.loc[trades.sequence == int(x), 'mark'].iloc[0] for x in full.stop_sequence] if len(full) else []
    ax.scatter(dates(full.first_completed_ms), stop_marks, marker='s', s=75, c='blue', label=f'净敞口归零、停止网格（{len(full)}批）')
    for reason, label, marker, color in [('funding_returned_to_anchor', '回到启动价退出', '^', 'green'), ('funding_5pct_take_profit', '均价下跌5%退出', '*', 'purple')]:
        b = batches[batches.end_reason == reason]
        y = b.anchor if reason == 'funding_returned_to_anchor' else b.exit_price
        ax.scatter(dates(b.end_ms), y, marker=marker, s=55 if marker == '^' else 150, c=color, label=f'{label}（{len(b)}批）')
    ax.set(title='资金费网格批次位置：同一时期标记可能重叠，完整记录见下表', ylabel='BTC价格（美元）', xlabel='日期（UTC）')
    ax.legend(); ax.grid(alpha=.2); fig.tight_layout(); fig.savefig(ROOT / 'funding_events.png', dpi=150); plt.close(fig)
    reasons = {'funding_returned_to_anchor': '回到启动价清仓', 'funding_5pct_take_profit': '持仓均价下跌5%清仓', 'open_at_end': '期末网格仍在运行', 'funding_waiting_at_end': '期末已停网格等待5%清仓'}
    table = pd.DataFrame({'批次编号': batches.batch, '启动时间（UTC）': dates(batches.start_ms).dt.strftime('%Y-%m-%d %H:%M'),
        '启动价（美元）': batches.anchor, '启动资金费（%）': batches.signal_funding_rate*100,
        '启动净BTC数量': batches.start_net_btc_qty, '冻结做空量（BTC）': batches.planned_qty,
        '份数': batches.actual_slots, '净敞口归零停网格时间（UTC）': dates(batches.first_completed_ms).dt.strftime('%Y-%m-%d %H:%M').fillna('未归零停网格'),
        '停网格时剩余空单均价（美元）': batches.held_vwap, '止盈触发价（美元）': batches.exit_price,
        '结束时间（UTC）': dates(batches.end_ms).dt.strftime('%Y-%m-%d %H:%M').fillna('未结束'),
        '结束原因': batches.end_reason.map(reasons).fillna(batches.end_reason),
        '交易净现金流（美元）': batches.cash_flow, '资金费净收入（美元）': batches.funding_received})
    trades = read('netzero_tp5', 'trades')
    peaks = {}
    for bid, group in trades[trades.component == 'funding'].groupby('batch'):
        peaks[bid] = float((-group.delta.cumsum()).max())
    table['峰值新增空单（BTC）'] = batches.batch.map(peaks).fillna(0).values
    table['最大已用冻结量（%）'] = table['峰值新增空单（BTC）'].values / batches.planned_qty.values * 100
    table['运行天数'] = ((batches.end_ms - batches.start_ms) / 86400000).values
    zero_times = {int(x['批次编号']): str(pd.to_datetime(x['timestamp_ms'], unit='ms', utc=True))
                  for x in zero_rows}
    table['首次合计净BTC近零（UTC）'] = batches.batch.map(zero_times).fillna('未达到').values
    table.to_csv(ROOT / 'funding_batches_cn.csv', index=False)
    improvement = main_r['final_equity'] - r['base_10pct']['final_equity']
    overlay = main_r['overlay_final_pnl']; funding = main_r['overlay_funding_received']
    body = '<h1>100%敞口／100份：净敞口归零停网格、均价下跌5%清仓</h1>'
    body += '<p>2024-10-02至2026-10-02（截止时刻不包含，UTC），初始50万美元。所有场景按正常完整成交。</p>'
    body += render(comparison)
    body += f'<p>相对原策略，期末组合增加<strong>{improvement:,.2f}美元</strong>。新增空单自身经济损益{overlay:,.2f}美元，其中资金费{funding:,.2f}美元，价差扣手续费{overlay-funding:,.2f}美元。基础部分路径变化贡献{improvement-overlay:,.2f}美元。资金费已包含在净资产与新增损益中，不可重复相加。</p>'
    old_core = old['final_equity'] - old['overlay_final_pnl']
    new_core = main_r['final_equity'] - main_r['overlay_final_pnl']
    body += f'<p>与上一版50%／100份相比，期末资产变化{main_r["final_equity"]-old["final_equity"]:,.2f}美元，年化变化{(main_r["cagr"]-old["cagr"])*100:.2f}个百分点。新增资金费网格自身经济损益变化{main_r["overlay_final_pnl"]-old["overlay_final_pnl"]:,.2f}美元，但基础部分变化{new_core-old_core:,.2f}美元，两部分合计形成组合收益差异。不能只看新增资金费收入判断组合优劣。</p>'
    body += f'<p>相对旧规则100%／200份方案，本次修改后，期末净资产变化{main_r["final_equity"]-old2["final_equity"]:,.2f}美元，年化变化{(main_r["cagr"]-old2["cagr"])*100:.2f}个百分点。两者份数、停止条件与退出距离不同，结果只能作为整体方案对比。</p>'
    body += f'<p>相对上一版错误完成条件的100%／100份方案，期末资产变化{main_r["final_equity"]-wrong["final_equity"]:,.2f}美元；本次同时修正归零停网格条件并将清仓距离从2%改为5%，不能将差额全部归因于其中一项。</p>'
    body += '<h2>规则</h2><p>沿用上一版基础策略：建仓前历史最高价73,800美元固定；36,900美元对应100% BTC，147,600美元对应0%。目标比例为（147,600−现货价）÷110,700并截取至0%–100%。初始目标78.40%；偏离目标10个百分点触发，冻结基础净资产10%的调整量，50份、0.1%移动网格。这里没有替换成界面另存的60,000–200,000美元区间，样本内也没有触及0%／100%目标端点。</p>'
    body += '<p>最新已知资金费严格大于0.02%时，冻结当前全部净BTC数量，分100份做空，每格0.1%。每批最多挂一买一卖，每次成交撤旧单并围绕成交价重挂。基础策略或新增网格任何成交使合计净BTC数量不足0.001 BTC时，立即停止当前资金费网格，撤掉该批双向挂单，冻结该批剩余新增空单的成交均价，设置均价×95%的清仓价。进入等待后，即使基础策略恢复净敞口，该批也不会重新报价或改均价。</p><p>归零之前回到启动价，仍按原规则清掉本批新增空单。归零停网格之后，回到启动价不再触发退出，只有价格到持仓均价×95%才清掉本批新增空单。费率降低不终止已有批次；若没有当前报价批次、最新费率仍高且净敞口足够，可再开新批，旧批继续等待5%退出。基础策略独立运行，其空单不会被本批退出一起平掉。</p>'
    body += '<p>例如冻结5.485 BTC，但基础策略又增加0.697 BTC空单，新增网格只卖出4.788 BTC就使合计净敞口近零：此时立即停网格，不等待原定5.485 BTC全部成交。5%清仓价使用停网格时该批仍持有的新增空单均价，不含基础空单，也不含已经回补的历史份额。</p>'
    body += '<p>基础策略和新增网格各保留自己的挂单、仓位和损益账本，基础目标不直接撤销新增对冲。两者同时运行时合计最多各两单。总卖出量受BTC数量下限约束，因此基础减仓可能被截断，基础路径并非完全不变。</p>'
    body += '<h2>净资产与敞口</h2><img src="equity.png"><img src="allocation.png">'
    anchor_closed = int((batches.end_reason == 'funding_returned_to_anchor').sum())
    profit_closed = int((batches.end_reason == 'funding_5pct_take_profit').sum())
    remaining = len(batches) - anchor_closed - profit_closed
    body += f'<p>{len(batches)}批新增网格：{anchor_closed}批回到启动价清仓，{profit_closed}批归零停网格后在均价下跌5%清仓，期末{remaining}批未结束，其中{main_r["funding_waiting_batches"]}批已停网格等待退出。最小数量不足导致不能启动的分钟数：{main_r["funding_min_lot_blocked_minutes"]}。成交账本最小净BTC数量保持非负。但现货与合约标记价的基差，使美元计价实际敞口最低出现{main_r["min_weight"]*100:.3f}%；“BTC数量非负”不等于“美元敞口比例始终非负”。</p>'
    longest = table.loc[table['运行天数'].idxmax()]
    body += f'<p>本次共有{len(full)}批因合计净BTC数量归零而停止网格。最长一批运行{longest["运行天数"]:.2f}天，峰值新增空单{longest["峰值新增空单（BTC）"]:.3f} BTC，占冻结量{longest["最大已用冻结量（%）"]:.2f}%。启动预算是否全部卖完不再作为该批停止网格的判据。</p>'
    body += '<h2>批次启动、归零停网格与退出位置</h2><img src="funding_events.png"><p>红倒三角启动，蓝方块净敞口归零停网格，绿正三角归零前回到启动价退出，紫星停网格后按均价下跌5%退出。标记按事件时刻的价格定位，时间接近时可能重叠，完整记录见下表。</p>'
    body += '<input id="search" placeholder="搜索批次、日期或退出原因"><div style="overflow:auto">' + render(table.fillna('—'), 'batches') + '</div>'
    body += '<h2>数据与实际执行限制</h2><p>Bybit同场所现货、永续成交价与标记价共1,051,200根完整分钟K线，无缺口或重复；2,190个8小时资金费时点，其中47个结算费率严格超过0.02%。信号使用最近已结算的历史费率，不使用尚未知晓的下一次结算费率，也没有盘中预测费率历史。结算先于当分钟交易，新开仓不能领取触发它的那笔资金费。</p>'
    body += '<p>研究手续费按VIP3 Maker 0.014%、Taker 0.035%，未核实账户历史等级费率。挂单穿透2基点计成交，重挂最早下一分钟生效；每个网格独立处理自身成交。启动价退出和5%止盈均按吃单、买入滑点1基点。数量步长0.001 BTC，价格步长0.1 USDT，启动预算不足100个最小数量单位则不启动。主场景分钟路径先低后高，另测先高后低；收益接近不代表真实盘口成交已获保证。</p>'
    body += '<p>采用真实USDT余额与合约盈亏核算，未把做空名义成交额当现金收入；融资利息设为0。组合曾出现USDT负余额，统一账户抵押不等于融资免费：本报告未重建历史UTA强平、借款利率、盘口排队及撤单延迟，因此尚不能作为实盘收益保证。</p>'
    body += '<p>14项规则检查、原策略关闭新增功能的回归、3个场景账本回放、实际历史资金费信号匹配均通过。仅研究，无实盘修改。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>'
    body += '<p><a href="audit.json">核验记录</a> · <a href="source_hashes.json">代码与数据哈希</a> · <a href="funding_batches_cn.csv">中文批次表</a> · <a href="outputs/netzero_tp5_trades.csv">成交原始账本</a> · <a href="period_manifest.json">数据区间</a></p>'
    script = '<script>document.querySelector("#search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
    (ROOT / 'report.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>100份资金费网格两年回测</title><style>body{font-family:system-ui;max-width:1600px;margin:24px auto;padding:0 16px}p{line-height:1.7}table{border-collapse:collapse;font-size:13px}td,th{border:1px solid #ddd;padding:7px}img{max-width:100%}input{padding:10px;min-width:300px}</style>'+body+script+'</html>')
    (ROOT / 'README.md').write_text('两年2024-10-02至2026-10-02；初始50万美元。基础波段调仓保持10个百分点触发、冻结10%基础净资产、50份移动网格。新增资金费网格使用最近已结算费率>0.02%触发，冻结当前净BTC数量100%，100份、每格0.1%。任何成交使合计净BTC数量不足0.001 BTC，立即撤掉该批双向挂单并冻结其剩余新增空单均价；均价下跌5%时吃单平掉该批。归零前保留回启动价退出。基础策略独立运行。\n复现：python3 test_funding.py；python3 run.py；python3 validate_export.py；python3 check_signal.py；python3 build_report.py。\n融资利息未计，未重建真实盘口和历史UTA强平。仅研究，无实盘变更。\n')
    hashes = json.loads((ROOT / 'source_hashes.json').read_text())
    for name in ['check_signal.py', 'build_report.py', 'reference_provenance.json']:
        hashes[name] = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
    (ROOT / 'source_hashes.json').write_text(json.dumps(hashes, indent=2))
    print('REPORT COMPLETE', ROOT / 'report.html')

if __name__ == '__main__':
    main()
