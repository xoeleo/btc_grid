"""Audit cash ledgers, locked inventory, execution conditions, and render complete HTML."""
from pathlib import Path
import base64
import hashlib
import html
import json
import sys
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent))
from cycle_report import build_cycle_section

NAMES = {'baseline_olhc': '原策略 · 先跌后涨', 'baseline_ohlc': '原策略 · 先涨后跌',
         'dip5_olhc': '5% 分段抄底 · 先跌后涨', 'dip5_ohlc': '5% 分段抄底 · 先涨后跌'}
dip_interval = json.loads((ROOT / 'dip5_olhc/summary.json').read_text())['dip_interval']
assert dip_interval == json.loads((ROOT / 'dip5_ohlc/summary.json').read_text())['dip_interval']
interval_pct = f'{dip_interval * 100:g}'
first_node_pct = f'{(1 - dip_interval) * 100:g}'
NAMES['dip5_olhc'] = f'{interval_pct}% 分段抄底 · 先跌后涨'
NAMES['dip5_ohlc'] = f'{interval_pct}% 分段抄底 · 先涨后跌'


def near(a, b, tol=0.0001):
    assert abs(float(a) - float(b)) < tol, (a, b)


manifest = json.loads((ROOT / 'data/manifest.json').read_text())
for name, expected in manifest['files'].items():
    assert hashlib.sha256((ROOT / 'data' / name).read_bytes()).hexdigest() == expected
for name, expected in json.loads((ROOT / 'frozen_hashes.json').read_text()).items():
    assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected

# Re-run the copied baseline against frozen classes before accepting a comparison.
expected = json.loads((ROOT / 'baseline_olhc/summary.json').read_text())
reproduced = json.loads((ROOT / 'baseline_verify/summary.json').read_text())
assert {k: v for k, v in expected.items() if k != 'scenario'} == {
    k: v for k, v in reproduced.items() if k != 'scenario'}
for filename in ['trades.csv', 'cycles.csv', 'funding_events.csv', 'equity.csv']:
    assert hashlib.sha256((ROOT / 'baseline_olhc' / filename).read_bytes()).digest() == hashlib.sha256(
        (ROOT / 'baseline_verify' / filename).read_bytes()).digest(), filename

bars = pd.read_csv(ROOT / 'data/bars.csv.gz')
assert len(bars) == manifest['rows'] and bars.timestamp_ms.diff().iloc[1:].eq(60000).all()
funding_source = pd.read_csv(ROOT / 'data/funding.csv')
results, audit, frames, metadata, band_tables, monthly_rows = {}, {}, {}, {}, [], []
for name, label in NAMES.items():
    folder = ROOT / name
    s = json.loads((folder / 'summary.json').read_text())
    results[name] = s
    tr = pd.read_csv(folder / 'trades.csv')
    cy = pd.read_csv(folder / 'cycles.csv')
    fu = pd.read_csv(folder / 'funding_events.csv')
    eq = pd.read_csv(folder / 'equity.csv')
    frames[name] = eq
    assert s['minutes'] == manifest['rows'] and s['start_ms'] == manifest['start_ms']
    assert s['end_ms'] == manifest['end_exclusive_ms']
    assert len(tr) == s['fills'] and len(cy) == s['completed_cycles']
    assert tr.timestamp_ms.is_monotonic_increasing
    tr['signed_qty'] = tr.qty * np.where(tr.side.eq('BUY'), 1, -1)
    tr['signed_value'] = tr.signed_qty * tr.price
    assert (tr.groupby('cycle_id').signed_qty.cumsum() - tr.net_qty).abs().max() < 1e-9
    assert tr.net_qty.between(-1e-9, 5 + 1e-9).all()
    rate = tr.fee / (tr.qty * tr.price)
    assert np.minimum(abs(rate - 0.00014), abs(rate - 0.00035)).max() < 1e-12
    near(tr.fee.sum(), s['trading_fees'])
    assert np.array_equal(fu.timestamp_ms, funding_source.timestamp_ms) and len(fu) == 2190
    assert (fu.rate - funding_source.rate).abs().max() < 1e-14
    assert (fu.qty * fu.mark * fu.rate - fu.paid).abs().max() < 1e-9
    indices = np.searchsorted(tr.timestamp_ms.values, fu.timestamp_ms.values, side='left') - 1
    q_funding = np.where(indices >= 0, tr.net_qty.values[np.maximum(indices, 0)], 0)
    assert np.max(abs(q_funding - fu.qty.values)) < 1e-9
    bar_index = ((fu.timestamp_ms - manifest['start_ms']) // 60000).astype(int)
    assert np.max(abs(bars.mark_open.iloc[bar_index].values - fu.mark.values)) < 1e-9
    near(fu.paid.sum(), s['funding_paid'])
    grouped = tr.groupby('cycle_id').agg(qty=('signed_qty', 'sum'),
                                       value=('signed_value', 'sum'), fee=('fee', 'sum'))
    for row in cy.itertuples():
        g = grouped.loc[row.cycle_id]
        near(g.qty, 0, 1e-9)
        near(g.fee, row.trading_fee)
        near(-g.value - g.fee - row.funding_paid, row.pnl)
        paid = fu.loc[fu.timestamp_ms.between(row.start_ms, row.end_ms), 'paid'].sum()
        near(paid, row.funding_paid)
    near(cy.pnl.sum(), s['closed_cycle_pnl'])
    open_ids = set(grouped.index) - set(cy.cycle_id)
    assert len(open_ids) <= 1
    if open_ids:
        g = grouped.loc[open_ids.pop()]
        near(g.qty, s['open_qty'], 1e-9)
        near(g.qty * bars.iloc[-1].mark_close - g.value - g.fee -
             (s['funding_paid'] - cy.funding_paid.sum()), s['open_cycle_pnl'])
    near(s['closed_cycle_pnl'] + s['open_cycle_pnl'], s['net_pnl'])
    near(s['initial_capital'] + s['net_pnl'], s['final_equity_marked'])
    assert s['min_free_margin'] >= -0.01
    audit[name] = {'passed': True, 'fills_checked': len(tr), 'closed_cycles_checked': len(cy),
                   'funding_events_checked': len(fu), 'quantity_and_cash_identity': True}
    for month, equity in s['monthly_end_equity'].items():
        monthly_rows.append({'scenario': name, 'month': month, 'equity': equity})
    if not name.startswith('dip'):
        continue
    context = pd.read_csv(folder / 'trade_context.csv')
    bands = pd.read_csv(folder / 'band_events.csv')
    controls = pd.read_csv(folder / 'control_events.csv')
    extra = pd.read_csv(folder / 'dip_cycles.csv')
    assert len(context) == len(tr) and np.array_equal(context.fill_index, np.arange(1, len(tr) + 1))
    assert np.array_equal(context.timestamp_ms, tr.timestamp_ms) and np.array_equal(context.cycle_id, tr.cycle_id)
    active = context.deepest_band.gt(0)
    grid = active & tr.kind.eq('grid')
    assert (context.loc[grid, 'spot'] <= context.loc[grid, 'node'] + 1e-9).all()
    assert (tr.loc[active & ~tr.kind.eq('dip_exit'), 'net_qty'] >=
            context.loc[active & ~tr.kind.eq('dip_exit'), 'locked_qty'] - 1e-9).all()
    assert not (active & tr.kind.eq('water')).any()
    exits = tr.kind.eq('dip_exit')
    assert int(exits.sum()) == s['dip_exits'] and (tr.loc[exits, 'net_qty'].abs() < 1e-9).all()
    assert (context.loc[exits, 'spot'] > context.loc[exits, 'recovery_price']).all()
    assert (tr.loc[exits, 'price'] > context.loc[exits, 'water_exit_threshold']).all()
    # Independently derive every execution's water from signed cash flow and settlement-before-fill funding.
    before_q = tr.groupby('cycle_id').signed_qty.cumsum() - tr.signed_qty
    before_value = tr.groupby('cycle_id').signed_value.cumsum() - tr.signed_value
    before_fee = tr.groupby('cycle_id').fee.cumsum() - tr.fee
    fu['cumulative'] = fu.paid.cumsum()
    j = np.searchsorted(fu.timestamp_ms.values, tr.timestamp_ms.values, side='right') - 1
    funding_before = np.where(j >= 0, fu.cumulative.values[np.maximum(j, 0)], 0.)
    starts = tr.groupby('cycle_id').timestamp_ms.first()
    before_start = np.searchsorted(fu.timestamp_ms.values, starts.values, side='left') - 1
    initial_funding = pd.Series(np.where(before_start >= 0,
                                fu.cumulative.values[np.maximum(before_start, 0)], 0.), index=starts.index)
    water = (before_value + before_fee + funding_before - tr.cycle_id.map(initial_funding)) / before_q.replace(0, np.nan)
    assert (water.loc[exits] - context.loc[exits, 'water_before']).abs().max() < 1e-6 if exits.any() else True
    if exits.any():
        assert (tr.loc[exits, 'price'] > water.loc[exits] * 1.01).all()
    assert (cy.loc[cy.reason.eq('dip_recovery'), 'pnl'] > 0).all()
    assert len(bands) == s['band_events']
    assert (bands.node - bands.anchor * (1 - bands.band * s['dip_interval'])).abs().max() < 1e-8 if len(bands) else True
    assert (bands.recovery_price - bands.anchor * (1 - bands.band * s['dip_interval'] / 2)).abs().max() < 1e-8 if len(bands) else True
    assert (bands.price <= bands.node + 1e-9).all()
    if len(bands):
        assert bands.groupby('cycle_id').band.diff().dropna().eq(1).all()
        assert bands.groupby('cycle_id').locked_qty.diff().dropna().ge(-1e-9).all()
        assert bands.groupby('cycle_id').first().band.eq(1).all()
    audit[name].update(locked_inventory_checked=True, no_grid_above_node_checked=True,
                       native_water_disabled_after_first_band=True, dip_exits_checked=int(exits.sum()),
                       midpoint_and_executable_water_plus_1pct_checked=True, bands_checked=len(bands))
    metadata[name] = {}
    for row in extra.itertuples():
        metadata[name][row.cycle_id] = {
            'anchor_price': f'{row.anchor:,.2f}', 'deepest_band': str(row.deepest_band),
            'deepest_drop': f'{row.deepest_drop_pct:.1f}%', 'node_price': f'{row.node:,.2f}',
            'locked_btc': f'{row.locked_qty:.3f}',
            'recovery_price': f'{row.recovery_price:,.2f}' if row.deepest_band else '未进入抄底',
            'exit_water': f'{row.water_before_exit:,.2f}',
            'water_plus_1pct': f'{row.water_exit_threshold:,.2f}',
            'actual_exit_price': f'{row.exit_price:,.2f}' if row.reason != 'open' else '未清仓'}
    display = bands.copy()
    display.insert(0, '场景', label)
    display['timestamp_ms'] = pd.to_datetime(display.timestamp_ms, unit='ms', utc=True).dt.strftime('%Y-%m-%d %H:%M:%S.%f').str[:-3]
    display = display.rename(columns={'timestamp_ms': '时间 UTC', 'cycle_id': 'Cycle', 'band': '档位',
                           'anchor': '初始价', 'node': '节点价', 'price': '触发价', 'locked_qty': '换档底仓 BTC',
                           'net_value': '累计净买入金额', 'fees': '累计费用', 'water': '动态水位', 'recovery_price': '反弹门槛'})
    band_tables.append('<h3>' + html.escape(label) + f'：{len(bands)} 次换档</h3><div class="cycle-scroll">' +
                       display.to_html(index=False, float_format=lambda v: f'{v:,.3f}') + '</div>')

extra_columns = [('anchor_price', '本轮初始价'), ('deepest_band', '最深档位'),
                 ('deepest_drop', '节点跌幅'), ('node_price', '最深节点价'), ('locked_btc', '锁定底仓 BTC'),
                 ('recovery_price', '反弹清仓门槛'), ('exit_water', '清仓前 / 期末保本水位'),
                 ('water_plus_1pct', '水位 × 1.01'), ('actual_exit_price', '实际清仓成交价')]
cycles_html = build_cycle_section(ROOT, NAMES, extra_columns, metadata)
cycles_html = cycles_html.replace('dip_recovery', '反弹且高于水位 1% 清仓')
fig, axes = plt.subplots(3, 1, figsize=(13, 10), sharex=True)
for name, label in NAMES.items():
    eq = frames[name]
    t = pd.to_datetime(eq.timestamp_ms, unit='ms', utc=True)
    axes[0].plot(t, eq.equity, label=name)
    axes[1].plot(t, (eq.equity / eq.equity.cummax() - 1) * 100)
    axes[2].plot(t, eq.net_notional / eq.equity * 100)
axes[0].set_ylabel('Equity / USDT');axes[0].legend(fontsize=9)
axes[1].set_ylabel('Hourly drawdown / %');axes[2].set_ylabel('Exposure / %')
for ax in axes:ax.grid(alpha=.2)
fig.tight_layout();fig.savefig(ROOT / 'equity_drawdown.png', dpi=150);plt.close(fig)
image = base64.b64encode((ROOT / 'equity_drawdown.png').read_bytes()).decode()
rows = ''
for name, label in NAMES.items():
    s = results[name]
    rows += f'<tr><td>{html.escape(label)}</td><td>{s["final_equity_marked"]:,.2f}</td><td>{s["net_pnl"]:+,.2f}</td><td>{s["return_pct"]:+.2f}%</td><td>{s["max_drawdown_pct"]:.2f}%</td><td>{s["completed_cycles"]}</td><td>{s.get("dip_exits", "—")}</td><td>{s["open_qty"]:.3f}</td><td>{s["open_cycle_pnl"]:+,.2f}</td><td>{s["trading_fees"]:,.2f}</td><td>{s["funding_paid"]:,.2f}</td></tr>'
monthly = pd.DataFrame(monthly_rows).pivot(index='month', columns='scenario', values='equity')
monthly = monthly.diff().fillna(monthly.iloc[0] - 500000).rename(columns=NAMES)
monthly.to_csv(ROOT / 'monthly_results.csv', encoding='utf-8-sig')
links = ''.join(f'<li>{html.escape(label)}：<a href="{name}/trades.csv">逐笔成交</a> / <a href="{name}/cycles.csv">轮次账本</a> / <a href="{name}/funding_events.csv">资金费</a> / <a href="{name}/summary.json">结果</a></li>' for name, label in NAMES.items())
html_body = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>50万 BTC 分段抄底网格回测</title>
<style>body{{font:16px/1.65 system-ui,sans-serif;background:#f4f7fb;color:#172335;margin:0}}main{{max-width:1400px;margin:auto;padding:28px}}section{{background:white;padding:24px;border-radius:12px;margin:20px 0}}table{{border-collapse:collapse;width:100%;font-size:14px}}th,td{{padding:9px;border-bottom:1px solid #ddd;text-align:right;white-space:nowrap}}th:first-child,td:first-child{{text-align:left}}.scroll{{overflow:auto}}img{{width:100%}}a{{color:#1464cc}}</style><main>
<h1>50万 USDT · {interval_pct}% 分段抄底网格</h1><p>2024-10-02 00:00 UTC 至 2026-10-02 00:00 UTC（右端不含）。初始50万USDT、只做多、不借款；与原策略使用同一数据、费用、单格数量和持仓上限。</p>
<section><h2>策略规则</h2><ol><li>每轮初始市场价为 P₀。首次跌到 P₀×{first_node_pct}% 之前，沿用原动态水位：盈利暂停、1%减半、2%清仓。</li>
<li>首次触及{interval_pct}%节点后，本轮不再使用原水位暂停／减半／2%退出逻辑。每再跌{interval_pct}个百分点换档，均相对 P₀，非复利递减。换档撤旧单、重设网格价格参考；不额外买初始仓、不重置账本或实际成本。</li>
<li>换档时全部现有 BTC 锁为底仓，普通网格只能卖该档新增 BTC。价格高于最深节点时撤全部网格单并暂停；回到节点及以下恢复。阶段边界优先于同价网格成交。</li>
<li>进入抄底后必须同时满足：市场价严格高于 P₀×(1−最深档位×{interval_pct}%÷2)，且预计实际清仓成交价严格高于动态水位×1.01，并确认含清仓手续费的净盈亏为正，然后一次卖出全部持仓。动态水位=(累计净买入金额+累计交易手续费+资金费净支出)÷剩余BTC，包含已实现网格盈亏。底仓价格以上暂停网格期间，资金费仍改变水位。</li>
<li>清仓确认后在后续15秒轮询启动下一轮，重新确定 P₀、恢复初始0.030 BTC和原水位规则。抄底触价事件沿分钟假设路径逐个处理；不读取未来极值来提前换档。</li></ol>
<p>单格0.006 BTC、网格间距0.1%、15分钟重报价、缩小系数0.85，单笔网格上限0.1 BTC、总仓位上限5 BTC。maker 0.014%、taker 0.035%；主动买卖使用单边1bp滑点及0.1 USDT价差。资金费使用历史费率。没有亏损止损或追加资金。</p></section>
<section><h2>结果对比</h2><div class="scroll"><table><tr><th>策略 / 路径</th><th>期末净值</th><th>净利润</th><th>收益</th><th>最大回撤</th><th>已结束Cycle</th><th>抄底清仓轮数</th><th>期末BTC</th><th>未结束净盈亏</th><th>手续费</th><th>资金费净支出</th></tr>{rows}</table></div><p>总收益包含未结束轮次的期末浮盈亏；盈利清仓条件不保证期间不会发生回撤，或期末未平仓仍盈利。最大回撤来自全部事件，曲线为小时采样。</p></section>
<section><h2>净值、回撤和资金敞口</h2><img src="data:image/png;base64,{image}" alt="基准与抄底策略的净值回撤及敞口"></section>
{cycles_html}
<section><h2>每次抄底换档明细</h2><p>列出节点、触发价、换档底仓、当时成本和动态水位。遇到开盘跳空跨档时，按实际已知价格逐档更新，不虚构中间价成交。</p>{''.join(band_tables)}</section>
<section><h2>月度净利润（含持仓浮盈亏）</h2><div class="scroll">{monthly.to_html(float_format=lambda v:f'{v:,.2f}')}</div></section>
<section><h2>验收与限制</h2><p>原策略副本重跑的四份逐笔／轮次／资金费／净值CSV，与历史基准逐字节一致；汇总结果一致。新策略独立核对每笔数量、买卖费用、资金费结算数量、逐轮现金盈亏、期末浮盈亏，以及底仓保护、节点上方无网格成交、全部抄底退出的反弹门槛和实际成交价超过保本水位1%。</p><p>分钟内使用 O→L→H→C 或 O→H→L→C 的线性路径，不能还原真实逐笔价格顺序，两种路径并非严格收益上下界。假设触价全额成交、撤单即时确认；未模拟盘口排队、部分成交或网络延迟。结果为历史研究，不是实盘收益或独立未来期间验证。外部复核未执行：无可用的外部浏览器复核通道。</p></section>
<section><h2>下载与复现</h2><p><a href="results.json">全部结果</a> · <a href="audit.json">独立校验</a> · <a href="cycle_details.csv">全部Cycle明细</a> · <a href="source_hashes.json">研究源代码哈希</a></p><ul>{links}</ul><p>源码、冻结依赖和结果仅位于独立研究目录；主项目策略和线上服务未修改。执行 run.sh 复现默认5%间隔，或用 run.sh 0.025 测试2.5%间隔（重建本研究目录的新策略结果与报告）。</p></section></main></html>'''
(ROOT / 'report.html').write_text(html_body)
(ROOT / 'audit.json').write_text(json.dumps({'baseline_reproduced_byte_identical': True,
    'synthetic_acceptance': (ROOT / 'acceptance.log').read_text().strip(), 'scenarios': audit}, indent=2, ensure_ascii=False))
(ROOT / 'results.json').write_text(json.dumps({'parameters': {'initial_capital': 500000, 'dip_interval': dip_interval,
    'water_profit_buffer': 0.01, 'locked_floor': True, 'pause_above_deepest_node': True}, 'results': results}, indent=2))
sources = list(ROOT.glob('*.java')) + list(ROOT.glob('*.py')) + list(ROOT.glob('*.sh')) + [ROOT.parent / 'cycle_report.py']
(ROOT / 'source_hashes.json').write_text(json.dumps({str(p.relative_to(ROOT)) if p.parent==ROOT else '../'+p.name:
    hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}, indent=2))
print(json.dumps({'audit': audit, 'results': {n: {k: s[k] for k in ['return_pct', 'max_drawdown_pct', 'net_pnl', 'open_qty', 'open_cycle_pnl']} for n, s in results.items()}}, indent=2))
