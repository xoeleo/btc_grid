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
LABEL = {'base_10pct': '原10%波段网格', 'funding100': '叠加100份资金费做空网格', 'funding100_OHLC': '叠加网格：分钟内先高后低'}

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
    main_r = r['funding100']
    daily = read('funding100', 'daily')
    batches = read('funding100', 'batches')
    batches = batches[batches.role == 'funding'].copy()
    assert len(batches) == main_r['funding_batches']
    rows = [{'方案': LABEL[x['id']], '年化收益（%）': x['cagr']*100,
             '最大回撤（%）': x['max_drawdown']*100, '期末净资产（美元）': x['final_equity'],
             '累计收益（%）': x['total_return']*100, '全部资金费净收入（美元）': x['funding_net'],
             '全部手续费（美元）': x['fees']} for x in results]
    comparison = pd.DataFrame(rows)
    comparison.to_csv(ROOT / 'comparison.csv', index=False)
    fig, ax = plt.subplots(figsize=(15, 6))
    for name in LABEL:
        d = read(name, 'daily')
        ax.plot(dates(d.timestamp_ms), d.equity/10000, label=LABEL[name], alpha=.85)
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
    ax.scatter(dates(full.first_completed_ms), full.held_vwap, marker='s', s=75, c='blue', label=f'100份卖满：标注持仓均价（{len(full)}批）')
    for reason, label, marker, color in [('funding_returned_to_anchor', '回到启动价退出', '^', 'green'), ('funding_2pct_take_profit', '均价下跌2%退出', '*', 'purple')]:
        b = batches[batches.end_reason == reason]
        y = b.anchor if reason == 'funding_returned_to_anchor' else b.exit_price
        ax.scatter(dates(b.end_ms), y, marker=marker, s=55 if marker == '^' else 150, c=color, label=f'{label}（{len(b)}批）')
    ax.set(title='资金费网格批次位置：同一时期标记可能重叠，完整记录见下表', ylabel='BTC价格（美元）', xlabel='日期（UTC）')
    ax.legend(); ax.grid(alpha=.2); fig.tight_layout(); fig.savefig(ROOT / 'funding_events.png', dpi=150); plt.close(fig)
    reasons = {'funding_returned_to_anchor': '回到启动价清仓', 'funding_2pct_take_profit': '持仓均价下跌2%清仓', 'open_at_end': '期末未结束'}
    table = pd.DataFrame({'批次编号': batches.batch, '启动时间（UTC）': dates(batches.start_ms).dt.strftime('%Y-%m-%d %H:%M'),
        '启动价（美元）': batches.anchor, '启动资金费（%）': batches.signal_funding_rate*100,
        '启动净BTC数量': batches.start_net_btc_qty, '冻结做空量（BTC）': batches.planned_qty,
        '份数': batches.actual_slots, '卖满时间（UTC）': dates(batches.first_completed_ms).dt.strftime('%Y-%m-%d %H:%M').fillna('未卖满'),
        '卖满剩余持仓均价（美元）': batches.held_vwap, '止盈触发价（美元）': batches.exit_price,
        '结束时间（UTC）': dates(batches.end_ms).dt.strftime('%Y-%m-%d %H:%M').fillna('未结束'),
        '结束原因': batches.end_reason.map(reasons).fillna(batches.end_reason),
        '交易净现金流（美元）': batches.cash_flow, '资金费净收入（美元）': batches.funding_received})
    table.to_csv(ROOT / 'funding_batches_cn.csv', index=False)
    improvement = main_r['final_equity'] - r['base_10pct']['final_equity']
    overlay = main_r['overlay_final_pnl']; funding = main_r['overlay_funding_received']
    body = '<h1>波段再平衡＋100份资金费做空网格：两年回测</h1>'
    body += '<p>2024-10-02至2026-10-02（截止时刻不包含，UTC），初始50万美元。所有场景按正常完整成交。</p>'
    body += render(comparison)
    body += f'<p>相对原策略，期末组合增加<strong>{improvement:,.2f}美元</strong>。新增空单自身经济损益{overlay:,.2f}美元，其中资金费{funding:,.2f}美元，价差扣手续费{overlay-funding:,.2f}美元。基础部分路径变化贡献{improvement-overlay:,.2f}美元。资金费已包含在净资产与新增损益中，不可重复相加。</p>'
    body += '<h2>规则</h2><p>沿用上一版基础策略：建仓前历史最高价73,800美元固定；36,900美元对应100% BTC，147,600美元对应0%。目标比例为（147,600−现货价）÷110,700并截取至0%–100%。初始目标78.40%；偏离目标10个百分点触发，冻结基础净资产10%的调整量，50份、0.1%移动网格。这里没有替换成界面另存的60,000–200,000美元区间，样本内也没有触及0%／100%目标端点。</p>'
    body += '<p>最新已知资金费严格大于0.02%时，冻结当前净BTC数量的一半，分100份做空。每个方向最多挂一单；本批每次成交撤掉本批旧单，在成交价上下0.1%重挂，只有先卖出的份额可以回补。未卖满时价格回到本批启动价，清掉本批剩余空单；100份净卖满后停止报价，等待剩余空单持仓均价下跌2%清仓。每批卖满或结束后，费率仍高于阈值则可开启下一批，旧批继续等止盈。费率下降不终止已有批次。</p>'
    body += '<p>例如当时净敞口4 BTC：第一批最多新增2 BTC空单；卖满后净敞口约2 BTC，下一批再取约1 BTC。实际启动量取决于当时净敞口，原策略的成交也会改变该数量。这里的50%指净BTC数量的一半，不是价格上涨50%。</p>'
    body += '<p>基础策略和新增网格各保留自己的挂单、仓位和损益账本，基础目标不直接撤销新增对冲。两者同时运行时合计最多各两单。总卖出量受BTC数量下限约束，因此基础减仓可能被截断，基础路径并非完全不变。</p>'
    body += '<h2>净资产与敞口</h2><img src="equity.png"><img src="allocation.png">'
    body += f'<p>75批新增网格：73批回到启动价清仓，2批卖满后在均价下跌2%清仓，期末无新增未平批次。成交账本最小净BTC数量保持非负。但现货与合约标记价的基差，使美元计价实际敞口最低出现{main_r["min_weight"]*100:.3f}%；“BTC数量非负”不等于“美元敞口比例始终非负”。</p>'
    body += '<h2>批次启动、卖满与退出位置</h2><img src="funding_events.png"><p>红倒三角启动，蓝方块卖满，绿正三角回到启动价退出，紫星均价下跌2%退出。时间接近时标记会重叠，表格列出全部批次。</p>'
    body += '<input id="search" placeholder="搜索批次、日期或退出原因"><div style="overflow:auto">' + render(table.fillna('—'), 'batches') + '</div>'
    body += '<h2>数据与实际执行限制</h2><p>Bybit同场所现货、永续成交价与标记价共1,051,200根完整分钟K线，无缺口或重复；2,190个8小时资金费时点，其中47个结算费率严格超过0.02%。信号使用最近已结算的历史费率，不使用尚未知晓的下一次结算费率，也没有盘中预测费率历史。结算先于当分钟交易，新开仓不能领取触发它的那笔资金费。</p>'
    body += '<p>研究手续费按VIP3 Maker 0.014%、Taker 0.035%，未核实账户历史等级费率。挂单穿透2基点计成交，重挂最早下一分钟生效；每个网格独立处理自身成交。启动价退出和2%止盈均按吃单、买入滑点1基点。数量步长0.001 BTC，价格步长0.1 USDT，启动预算不足100个最小数量单位则不启动。主场景分钟路径先低后高，另测先高后低；收益接近不代表真实盘口成交已获保证。</p>'
    body += '<p>采用真实USDT余额与合约盈亏核算，未把做空名义成交额当现金收入；融资利息设为0。组合曾出现USDT负余额，统一账户抵押不等于融资免费：本报告未重建历史UTA强平、借款利率、盘口排队及撤单延迟，因此尚不能作为实盘收益保证。</p>'
    body += '<p>8项规则检查、原策略关闭新增功能的回归、3个场景账本回放、实际历史资金费信号匹配均通过。仅研究，无实盘修改。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>'
    body += '<p><a href="audit.json">核验记录</a> · <a href="source_hashes.json">代码与数据哈希</a> · <a href="funding_batches_cn.csv">中文批次表</a> · <a href="outputs/funding100_trades.csv">成交原始账本</a> · <a href="period_manifest.json">数据区间</a></p>'
    script = '<script>document.querySelector("#search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
    (ROOT / 'report.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>100份资金费网格两年回测</title><style>body{font-family:system-ui;max-width:1600px;margin:24px auto;padding:0 16px}p{line-height:1.7}table{border-collapse:collapse;font-size:13px}td,th{border:1px solid #ddd;padding:7px}img{max-width:100%}input{padding:10px;min-width:300px}</style>'+body+script+'</html>')
    (ROOT / 'README.md').write_text('两年固定波段再平衡，10个百分点触发／10%调整、50份移动网格；叠加最近已结算资金费>0.02%触发的100份做空网格。每批冻结当前净BTC数量一半；未满回启动价退出，卖满后均价下跌2%退出；费率仍高可继续下一批。\n复现：python3 test_funding.py；python3 run.py；python3 validate_export.py；python3 check_signal.py；python3 build_report.py。\n融资利息未计，真实盘口与历史UTA强平未重建。仅研究，无实盘变更。\n')
    hashes = json.loads((ROOT / 'source_hashes.json').read_text())
    for name in ['check_signal.py', 'build_report.py']:
        hashes[name] = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
    (ROOT / 'source_hashes.json').write_text(json.dumps(hashes, indent=2))
    print('REPORT COMPLETE', ROOT / 'report.html')

if __name__ == '__main__':
    main()
