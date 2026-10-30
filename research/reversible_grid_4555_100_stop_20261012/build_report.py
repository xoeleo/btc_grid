"""Chinese report for user-confirmed 100-slot grid that stops on completion."""
from pathlib import Path
import json, hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties

ROOT = Path(__file__).resolve().parent
OLD = ROOT.parent / 'reversible_grid_100_20261012'
FONT = Path('/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc')
if FONT.exists():
    matplotlib.font_manager.fontManager.addfont(str(FONT))
    plt.rcParams['font.family'] = FontProperties(fname=str(FONT)).get_name()
plt.rcParams['axes.unicode_minus'] = False
LABEL = {
    'grid100_minute': '旧版45–55：100格，完成后仍回补',
    'grid100_borrow5': '旧版：模拟负USDT按5%计息',
    'grid100_borrow10': '旧版：模拟负USDT按10%计息',
    'direct4555_100_minute': '45–55直接再平衡至50%',
    'grid4555_100_minute': '修正版45–55：100格完成即停',
    'grid4555_100_OHLC': '修正版：另一分钟内价格路径',
    'grid4555_100_half_fill': '修正版：每次半成交',
    'grid4555_100_fee2': '修正版：手续费翻倍',
    'grid4555_100_borrow5': '修正版：模拟负USDT按5%计息',
    'grid4555_100_borrow10': '修正版：模拟负USDT按10%计息',
}

def financial_table(rows):
    return pd.DataFrame([{
        '方案': LABEL[x['id']], '期末净资产（USDT）': x['final_equity'],
        '总收益（%）': 100*x['total_return'], '年化收益（%）': 100*x['cagr'],
        '最大回撤（%）': 100*x['max_drawdown'], '资金费净收入（USDT）': x['funding_net'],
        '全部手续费（USDT）': x['fees'], '模拟融资利息（USDT）': x['borrow_interest'],
        '配对往返次数': x['grid_pair_count'], '配对净价差（USDT）': x['matched_grid_profit'],
    } for x in rows]).to_html(index=False, float_format=lambda x: f'{x:,.2f}')

def main():
    results = json.loads((ROOT/'results.json').read_text())
    prior = json.loads((ROOT/'reference_results.json').read_text())
    r = {x['id']: x for x in prior+results}
    name = 'grid4555_100_minute'
    grid, direct, old = r[name], r['direct4555_100_minute'], r['grid100_minute']
    batches = pd.read_csv(ROOT/'outputs'/f'{name}_batches.csv')
    trades = pd.read_csv(ROOT/'outputs'/f'{name}_trades.csv')
    completed = batches[batches.end_reason == 'adjustment_completed_orders_cancelled']
    plt.figure(figsize=(14, 7))
    for key in ['grid100_minute', name, 'direct4555_100_minute']:
        base = OLD if key == 'grid100_minute' else ROOT
        d = pd.read_csv(base/'outputs'/f'{key}_daily.csv')
        plt.plot(pd.to_datetime(d.timestamp_ms, unit='ms', utc=True), d.equity, label=LABEL[key])
    counts = {}
    for direction, marker, color, label in [
        ('sell_up_buy_back', 'v', '#b91c1c', '网格启动：加空、降低BTC敞口'),
        ('buy_down_sell_back', '^', '#15803d', '网格启动：减空、提高BTC敞口')]:
        points = batches[batches.direction == direction]
        counts[direction] = len(points)
        if len(points):
            plt.scatter(pd.to_datetime(points.start_ms, unit='ms', utc=True), points.start_equity,
                        marker=marker, color=color, alpha=.65, s=38, zorder=5,
                        label=f'{label}（{len(points)}批）')
    last = trades[trades.batch.isin(completed.batch)].groupby('batch', sort=False).tail(1)
    if len(last):
        plt.scatter(pd.to_datetime(last.timestamp_ms, unit='ms', utc=True), last.equity,
                    marker='s', facecolors='none', edgecolors='#1d4ed8', s=65, zorder=6,
                    label=f'100份全成交、撤单结束（{len(last)}批）')
    dt = pd.read_csv(ROOT/'outputs/direct4555_100_minute_trades.csv')
    events = dt[dt.kind == 'rebalance']
    for positive, marker, label in [(False, 'v', '直接再平衡：降低敞口'), (True, '^', '直接再平衡：提高敞口')]:
        points = events[(events.delta > 0) == positive]
        if len(points):
            plt.scatter(pd.to_datetime(points.timestamp_ms, unit='ms', utc=True), points.equity,
                        marker=marker, facecolors='none', edgecolors='black', s=120,
                        linewidths=1.8, zorder=7, label=f'{label}（{len(points)}次）')
    plt.title('45%–55%触发：5%分100份，完成后撤单结束')
    plt.xlabel('日期（UTC）'); plt.ylabel('组合净资产（USDT）')
    plt.legend(fontsize=9, ncol=2); plt.grid(alpha=.25); plt.tight_layout()
    plt.savefig(ROOT/'equity.png', dpi=150); plt.close()

    display = batches.copy()
    for col, title in [('start_ms', '启动时间（UTC）'), ('first_completed_ms', '全格完成时间（UTC）'), ('end_ms', '结束时间（UTC）')]:
        display[title] = pd.to_datetime(display[col], unit='ms', utc=True).astype(str).replace('NaT', '—')
    display = display.drop(columns=['start_ms', 'first_completed_ms', 'end_ms'])
    display.direction = display.direction.map({'sell_up_buy_back': '上涨加空／回落平空', 'buy_down_sell_back': '下跌平空／反弹加空'})
    display.end_reason = display.end_reason.replace({'adjustment_completed_orders_cancelled': '100份全成交，撤单保留调整', 'all_returned_inside_band': '全部回补且敞口回到触发带内', 'open_at_end': '期末未完成'})
    for col in ['ever_traded', 'retired']: display[col] = display[col].map({True:'是', False:'否'})
    headers = {'batch':'批次编号', 'direction':'调仓方向', 'anchor':'启动合约价格（USDT）', 'start_equity':'启动净资产（USDT）', 'start_weight':'启动净敞口比例', 'planned_qty':'冻结调整数量（BTC）', 'requested_slots':'计划格数', 'actual_slots':'实际格数', 'total_units':'计划数量单位数（每单位0.001 BTC）', 'held_units':'已调整未回补单位数', 'ever_traded':'是否曾成交', 'retired':'是否已结束', 'completion_count':'全格完成次数', 'end_reason':'结束状态', 'end_weight':'结束净敞口比例', 'first_completed_weight':'全格完成净敞口比例'}
    display = display.rename(columns=headers)
    yearly = pd.read_csv(ROOT/'yearly.csv')
    yearly.id = yearly.id.map(LABEL)
    yearly = yearly.rename(columns={'id':'方案', 'year':'年份', 'return_pct':'年度收益（%）'})
    audit = json.loads((ROOT/'audit.json').read_text())
    assert len(audit['independent_export_and_slot_replay']) == len(results)
    summary = f'<p><strong>修正版年化{grid["cagr"]*100:.2f}%，最大回撤{grid["max_drawdown"]*100:.2f}%；直接方案年化{direct["cagr"]*100:.2f}%，最大回撤{direct["max_drawdown"]*100:.2f}%。</strong>基础结果未计潜在融资利息。</p>'
    notes = '''<p>初始50万美元BTC现货存量＋约25万美元永续空单，现货数量固定。净BTC敞口＝（现货市值－空单标记市值）／组合净资产。45%或55%触发后冻结当时净资产5%折算的BTC数量，分100份，格距0.1%。上侧每涨一格加空、回落一格平对应份额；下侧镜像。完成前只回补已调整份额；返向不得超出55%／45%边界。</p>
<p><strong>100份全部成交后立即结束本批，撤掉所有剩余挂单及回补单，保留调整后的仓位。之后只有再次达到45%或55%才启动新批次。</strong>未完成时若全部份额回补且敞口在45%–55%内，本批也结束。无24小时吃单调整。数量冻结，不逐笔重新对齐50%；价格变化使完成比例不保证恰为50%。100格累计上涨约10.51%、下跌约9.52%，这意味着调整可能持续较长时间。</p>
<p>2024-10-02至2026-10-02，共1,051,200根Bybit分钟行情及2,190次历史资金费。VIP3研究费率：挂单0.014%、吃单0.035%，未经账户签名接口核实。初始建空与直接再平衡采用吃单，滑点1基点；网格采用挂单，穿透2基点才计成交，新挂单及返向单最早下一分钟成交。数量步长0.001 BTC、价格步长0.1 USDT。分钟内路径模拟不等于真实订单簿成交。</p>
<p>配对净网格价差扣除两笔挂单费，已计入组合损益，不得再次相加。融资压力情景按实际模拟USDT负余额逐小时计息，5%及10%均为假设，不代表已核实账户借款。未重建历史统一账户抵押折扣和强平规则。旧版100格完成后仍回补，与最终规则不一致，只保留作历史追溯。</p>'''
    body = '<h1>45–55／100格修正版：完成即停</h1>'+summary+'<img src="equity.png">'
    body += f'<p>红色倒三角：加空网格启动{counts["sell_up_buy_back"]}批；绿色正三角：减空网格启动{counts["buy_down_sell_back"]}批。蓝色空心方块：100份全成交结束{len(completed)}批。黑色空心三角：直接再平衡实际成交{len(events)}次。网格启动不是成交；具体时间和状态见下表。时间均为UTC。</p>'
    if len(completed):
        body += f'<p>本次{len(completed)}个完成批次的结束净BTC敞口范围：{completed.end_weight.min()*100:.2f}%至{completed.end_weight.max()*100:.2f}%。固定5%数量分100格并不保证完成后恰为50%。</p>'
    body += '<h2>主结果与旧模型</h2>'+financial_table([old, grid, direct])
    body += '<h2>成交及费用压力情景</h2>'+financial_table(results)
    body += '<h2>年度收益（首尾为部分年份）</h2>'+yearly.to_html(index=False, float_format=lambda x:f'{x:,.2f}')
    body += '<h2>执行规则</h2>'+notes+'<h2>网格全部批次</h2><input id="search" placeholder="筛选日期、方向、状态"><div id="batches">'+display.to_html(index=False, float_format=lambda x:f'{x:,.6f}')+'</div>'
    body += f'<p>全部{len(results)}个场景通过导出账本及每格库存独立回放，{len(audit["unit_checks"])}项检查通过。<a href="audit.json">核验记录</a> · <a href="source_hashes.json">数据及源代码哈希</a> · <a href="comparison.csv">比较数据</a>。没有实盘修改。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>'
    doc = '<!doctype html><meta charset="utf-8"><title>45–55网格完成即停回测</title><style>body{font-family:system-ui;max-width:1700px;margin:30px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{padding:6px;border:1px solid #ddd}img{max-width:100%}input{padding:8px}</style>'+body+'<script>document.getElementById("search").oninput=function(){document.querySelectorAll("#batches tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))}</script>'
    (ROOT/'report.html').write_text(doc)
    (ROOT/'README.md').write_text('45%–55%触发，冻结5%调整量，分100份，每格0.1%，100份全部成交后撤单结束并保留调整结果。\n\npython3 test_4555.py；python3 run.py；python3 validate_export.py；python3 build_report.py。研究数据通过data相对软链接共享。没有实盘变更。\n')
    (ROOT/'decision.json').write_text(json.dumps({'research_only':True, 'stop_on_completion':True, 'low':.45, 'high':.55, 'adjustment_fraction':.05, 'levels':100, 'step':.001, 'new_minus_direct_equity':grid['final_equity']-direct['final_equity'], 'new_minus_old_model_equity':grid['final_equity']-old['final_equity'], 'completed_batches':len(completed), 'upper_starts':counts['sell_up_buy_back'], 'lower_starts':counts['buy_down_sell_back'], 'direct_rebalances':len(events)}, indent=2))
    hashes=json.loads((ROOT/'source_hashes.json').read_text())
    hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
    print('report complete',len(results),'scenarios',len(batches),'batches',len(completed),'completed')

if __name__ == '__main__': main()
