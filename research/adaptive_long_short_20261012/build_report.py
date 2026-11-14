"""Independent ledger audit and standalone report; does not invoke exchange APIs."""
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
from backtest import DATA, CAPITAL, MAKER, TAKER, Config, load_data, signals

LABELS = {'adaptive_olhc':'自适应 · 先跌后涨', 'adaptive_ohlc':'自适应 · 先涨后跌',
          'adaptive_close':'自适应 · 仅开收价', 'stress_cost':'压力 · 双倍费用 / 5bp 滑点',
          'stress_half_fill':'压力 · 每单仅成交一半', 'no_grid':'消融 · 关闭网格',
          'holdout_olhc':'第二年独立启动 · 先跌后涨',
          'holdout_ohlc':'第二年独立启动 · 先涨后跌',
          'holdout_cost':'第二年独立启动 · 成本压力',
          'holdout_no_grid':'第二年独立启动 · 关闭网格',
          'range_train':'改进 · 仅震荡网格 · 第一年',
          'range_olhc':'改进 · 仅震荡网格 · 先跌后涨',
          'range_ohlc':'改进 · 仅震荡网格 · 先涨后跌',
          'range_cost':'改进 · 仅震荡网格 · 成本压力',
          'range_holdout':'改进 · 第二年 · 先跌后涨',
          'range_holdout_ohlc':'改进 · 第二年 · 先涨后跌',
          'range_holdout_cost':'改进 · 第二年 · 成本压力',
          'range_half_fill':'改进 · 限价成交一半',
          'range_close':'改进 · 仅开收价'}


def near(a, b, tolerance=1e-5):
    assert abs(float(a)-float(b)) < tolerance, (a, b)


def main():
    bundle = json.loads((ROOT/'results.json').read_text())
    bars, funding, manifest = load_data()
    config = Config(**bundle['selected_config'])
    expected_signals = signals(bars, config).reset_index(drop=True)
    audits, frames, monthly_rows, periods = {}, {}, [], []
    for name, s in bundle['results'].items():
        folder = ROOT/name
        trades = pd.read_csv(folder/'trades.csv')
        fu = pd.read_csv(folder/'funding_events.csv')
        eq = pd.read_csv(folder/'equity.csv')
        cy = pd.read_csv(folder/'cycles.csv')
        control = pd.read_csv(folder/'controls.csv')
        frames[name] = eq
        signs = trades.side.map({'BUY':1., 'SELL':-1.})
        signed = trades.qty*signs
        value = signed*trades.price
        assert trades.timestamp_ms.is_monotonic_increasing
        assert (signed.cumsum()-trades.net_qty).abs().max() < 1e-8
        cycle_direction = trades.groupby('cycle_id').first().side.map({'BUY':1.,'SELL':-1.})
        assert (trades.net_qty*trades.cycle_id.map(cycle_direction) >= -1e-10).all()
        assert (trades.qty_before-trades.net_qty+signed).abs().max() < 1e-8
        rate = trades.fee/(trades.qty*trades.price)
        expected_rate = np.where(trades.kind.eq('grid'), MAKER, TAKER)*s['fee_multiplier']
        assert np.max(abs(rate-expected_rate)) < 1e-12
        additions = trades.net_qty.abs() > trades.qty_before.abs()+1e-10
        # Check long and short additions against independently reconstructed equity.
        cash = CAPITAL-value.cumsum()-trades.fee.cumsum()
        j = np.searchsorted(fu.timestamp_ms, trades.timestamp_ms, side='right')-1
        accumulated_funding = np.where(j>=0, fu.paid.cumsum().to_numpy()[np.maximum(j,0)], 0.)
        cash -= accumulated_funding
        eq_fill = cash+trades.net_qty*trades.mark
        assert (trades.loc[additions,'net_qty'].abs()*trades.loc[additions,'mark']/eq_fill[additions]
                <= trades.loc[additions,'exposure_cap']+1e-8).all()
        assert cash.min() >= -1e-5
        ts = trades.timestamp_ms.to_numpy()
        j = np.searchsorted(ts, fu.timestamp_ms, side='left')-1
        carried = np.where(j>=0, trades.net_qty.to_numpy()[np.maximum(j,0)], 0.)
        assert np.max(abs(carried-fu.qty)) < 1e-8
        assert np.max(abs(fu.qty*fu.mark*fu.rate-fu.paid)) < 1e-7
        source = funding[funding.timestamp_ms.between(s['start_ms'], s['end_ms']-1)]
        assert np.array_equal(fu.timestamp_ms, source.timestamp_ms)
        assert np.max(abs(fu.rate.to_numpy()-source.rate.to_numpy())) < 1e-14
        bar_index = ((fu.timestamp_ms-manifest['start_ms'])//60000).astype(int)
        assert np.max(abs(fu.mark.to_numpy()-bars.mark_open.iloc[bar_index].to_numpy())) < 1e-8
        near(trades.fee.sum(), s['trading_fees'])
        near(fu.paid.sum(), s['funding_paid'])
        near(CAPITAL-value.sum()-trades.fee.sum()-fu.paid.sum()+s['open_qty']*eq.iloc[-1].mark,
             s['final_equity_marked'])
        # Reconstruct each hourly cash, inventory, equity from exported executions/settlements.
        j = np.searchsorted(ts, eq.timestamp_ms, side='right')-1
        values = value.cumsum().to_numpy()
        fees = trades.fee.cumsum().to_numpy()
        q = np.where(j>=0, trades.net_qty.to_numpy()[np.maximum(j,0)], 0.)
        cash_hour = CAPITAL-np.where(j>=0, (values+fees)[np.maximum(j,0)], 0.)
        fidx = np.searchsorted(fu.timestamp_ms, eq.timestamp_ms, side='right')-1
        cash_hour -= np.where(fidx>=0, fu.paid.cumsum().to_numpy()[np.maximum(fidx,0)], 0.)
        assert np.max(abs(q-eq.net_qty)) < 1e-8
        assert np.max(abs(cash_hour+q*eq.mark-eq.equity)) < 1e-5
        assert (eq.net_notional/eq.equity < 1).all()
        expected = expected_signals.iloc[((control.timestamp_ms-manifest['start_ms'])//3600000).astype(int)]
        assert np.array_equal(control.state, expected.state)
        assert np.allclose(control.step, expected.step, equal_nan=True)
        assert np.allclose(control.anchor, expected.anchor, equal_nan=True)
        assert len(cy) == s['completed_cycles'] and len(trades) == s['fills']
        near(cy.pnl.sum(), s['closed_cycle_pnl'])
        audits[name] = dict(passed=True, trades=len(trades), funding=len(fu),
            hourly_equity=len(eq), cycle_count=len(cy), cash_and_quantity_identity=True,
            carried_inventory_settlement=True, lagged_controls=True, signed_exposure_cap=True,
            cycle_direction_locked=True)
        months = pd.to_datetime(eq.timestamp_ms, unit='ms', utc=True).dt.strftime('%Y-%m')
        previous = CAPITAL
        for month, data in eq.groupby(months, sort=True):
            last = data.equity.iloc[-1]
            monthly_rows.append(dict(scenario=name, month=month, equity=last,
                                     net_pnl=last-previous, return_pct=(last/previous-1)*100))
            previous = last
        # These are continuous-account calendar slices, not separately trained models.
        stamps = pd.to_datetime(eq.timestamp_ms, unit='ms', utc=True)
        for label, start, end in [('第一年','2024-10-02','2025-10-02'),
                                  ('第二年前半年','2025-10-02','2026-04-02'),
                                  ('第二年后半年','2026-04-02','2026-10-02')]:
            subset = eq[(stamps>=pd.Timestamp(start,tz='UTC')) & (stamps<pd.Timestamp(end,tz='UTC'))]
            if subset.empty:
                continue
            before = eq[stamps < pd.Timestamp(start,tz='UTC')]
            initial = CAPITAL if before.empty else before.equity.iloc[-1]
            series = np.r_[initial, subset.equity.to_numpy()]
            dd = np.max((1-series/np.maximum.accumulate(series))*100)
            periods.append(dict(scenario=name, period=label, return_pct=(series[-1]/initial-1)*100,
                                hourly_max_drawdown_pct=dd))
    (ROOT/'audit.json').write_text(json.dumps(audits, indent=2))
    pd.DataFrame(monthly_rows).to_csv(ROOT/'monthly_results.csv', index=False)
    pd.DataFrame(periods).to_csv(ROOT/'period_results.csv', index=False)
    cycles_html = build_cycle_section(ROOT, {k:v for k,v in LABELS.items() if k in bundle['results']})
    fig, axs = plt.subplots(3,1,figsize=(13,10),sharex=True)
    for name in [n for n in ['adaptive_olhc','range_olhc','range_cost','no_grid'] if n in frames]:
        eq = frames[name]
        t = pd.to_datetime(eq.timestamp_ms, unit='ms', utc=True)
        axs[0].plot(t, eq.equity, label=name, lw=.9)
        axs[1].plot(t, (eq.equity/eq.equity.cummax()-1)*100, lw=.8)
        axs[2].plot(t, eq.net_notional/eq.equity*100, lw=.8)
    axs[0].legend();axs[0].set_ylabel('Equity / USDT')
    axs[1].set_ylabel('Hourly drawdown / %');axs[2].set_ylabel('Exposure / %')
    for ax in axs: ax.grid(alpha=.2)
    fig.tight_layout();fig.savefig(ROOT/'equity_drawdown.png',dpi=140);plt.close(fig)
    img = base64.b64encode((ROOT/'equity_drawdown.png').read_bytes()).decode()
    rows = []
    for name, s in bundle['results'].items():
        rows.append(dict(场景=LABELS[name], 收益率=f'{s["return_pct"]:.2f}%',
            路径采样最大回撤=f'{s["max_drawdown_pct"]:.2f}%',
            净收益=f'{s["net_pnl"]:,.2f}', 期末浮动轮盈亏=f'{s["open_cycle_pnl"]:,.2f}',
            期末BTC=f'{s["open_qty"]:.3f}', 手续费=f'{s["trading_fees"]:,.2f}',
            资金费=f'{s["funding_paid"]:,.2f}', 网格成交=s['grid_fills'],
            亏损轮次=s['losing_cycles']))
    table = pd.DataFrame(rows).to_html(index=False,escape=True)
    training = pd.DataFrame([dict(候选=i, 快EMA=s['config']['fast'], 慢EMA=s['config']['slow'],
        趋势目标仓位=s['config']['bullish'], 第一年收益=s['return_pct'],
        第一年回撤=s['max_drawdown_pct'], 选择分数=s['selection_score'])
        for i,s in enumerate(bundle['training'])]).to_html(index=False,float_format=lambda x:f'{x:.3f}')
    period_table = pd.DataFrame(periods).to_html(index=False,float_format=lambda x:f'{x:.3f}')
    baseline = json.loads((ROOT.parent/'dip_grid_500k_20261011/results.json').read_text())['results']
    older = json.loads((ROOT.parent/'water_exit_threshold_200k_20261002/results.json').read_text())['results']
    baseline_rows = [dict(报告策略=n, 本金=s['initial_capital'], 收益=s['return_pct'],
        最大回撤=s['max_drawdown_pct'], 期末持仓=s['open_qty'], 期末轮盈亏=s['open_cycle_pnl'])
        for n,s in list(baseline.items())+[(n,older[n]) for n in ['threshold_olhc','threshold_ohlc']]]
    adaptive = bundle['results']['adaptive_olhc']
    ablation = bundle['results']['no_grid']
    holdout = bundle['results']['holdout_olhc']
    cost = bundle['results']['holdout_cost']
    grid_difference = adaptive['return_pct']-ablation['return_pct']
    verdict = ('网格叠加在本样本中改善了收益，但仍需新的前向 Paper 数据。' if grid_difference>0 else
               '关闭网格收益更好，当前网格叠加未证明价值，不能把趋势收益归功于网格。')
    if holdout['return_pct']<=0 or cost['return_pct']<=0:
        verdict += '第二年或成本压力仍亏损，本候选未通过研发收益门槛。'
    if all(s['return_pct']<=0 for s in bundle['training']):
        verdict += '原多空候选第一年全部亏损，尚未证明跨行情稳定获利。'
    refinement_html = ''
    if 'range_olhc' in bundle['results']:
        phase = bundle['results']['range_olhc']
        delta = phase['return_pct']-ablation['return_pct']
        verdict += f'改为仅震荡启用网格后，全期收益 {phase["return_pct"]:.2f}%，相对无网格 {delta:+.2f} 个百分点。'
        refinement_html = '''<h2>第二阶段：只在震荡时启用网格</h2><p>第一阶段关闭网格的收益更高，因此保留相同已选 EMA、仓位和格距参数，
仅改变启用条件：趋势明确时持有目标仓位，震荡时启用三层网格。
没有使用第二年数据重选参数，原结果全部保留。</p>
<p><b>这是看到全期消融结果后的结构改进，第二年也属于回顾性验证，不能再称为新的留出或盲测。</b>
若它仍弱于关闭网格，应优先认可这个负面结果，下一步需要新数据验证。</p>'''
    report = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>自适应多空网格研发报告</title>
<style>body{{max-width:1400px;margin:32px auto;padding:0 20px;font:16px/1.65 system-ui;color:#17283d}}
table{{border-collapse:collapse;white-space:nowrap;width:100%;font-size:13px}}td,th{{padding:8px;border:1px solid #dae1e9;text-align:right}}
th{{background:#edf3fa}}h1,h2{{color:#164b78}}.scroll{{overflow:auto}}.note{{background:#edf3fa;padding:16px}}img{{width:100%}}</style>
<h1>BTC 自适应多空网格：趋势仓位 + 波动格距</h1>
<p>独立研究版 · 2024-10-02 至 2026-10-02 UTC · 50 万 USDT · 多空双向、名义敞口小于净值 · 未部署</p>
<div class="note"><b>结论：</b>{html.escape(verdict)} 全期网格相对相同仓位控制的无网格版本收益差：{grid_difference:+.2f} 个百分点。
该差额包含网格引起的持仓轨迹变化，不是可分离的纯网格套利利润。</div>
<h2>从既有报告得到的判断</h2>
<p>纯多网格的主要风险是下跌中不断增加库存；锁仓抄底扩大敞口并延长资金占用。
固定十万价位多空策略历史收益较好，但绝对价格不是稳定的行情状态指标。
下面原报告与新模型的资金利用率和成交机制不同，只能对照风险收益，不能视为同实现的严格消融。</p>
<div class="scroll">{pd.DataFrame(baseline_rows).to_html(index=False,float_format=lambda x:f'{x:,.3f}')}</div>
<h2>明确规则</h2><p>日线快 / 慢 EMA 为 {config.fast} / {config.slow}。
快线高于慢线、昨日收盘高于慢线且快线较五日前上升：上涨状态，目标仓位 {config.bullish*100:g}%；
快线低于慢线且昨日收盘低于慢线：下跌状态，下一小时开盘做空，目标敞口同为 {config.bullish*100:g}%；
其余为震荡，沿用最近明确趋势方向，目标敞口 {config.neutral*100:g}%。
反向切换先平旧方向 Cycle，再开启新方向；不净额跨零，不保证每轮盈利。
头 60 天留现金用于指标预热。所有日线指标延迟一天、小时指标延迟一小时使用。</p>
<p>每小时基于上一小时收盘重挂上下各三层，单层名义金额为当时净值的 2%。
格距 = 前一小时 ATR(24) / 收盘 × 1.5，限制在 0.4%–1.5%。
目标仓位上下各留 6 个百分点的库存区间；切换状态或越界时用市价调回目标。
每次加仓的实际执行绝对敞口不得超过目标 + 6 个百分点，最大候选为 71%，按当前净值限制。
空头使用有符号净仓与现金流记账；正资金费率时收取资金费，负费率时支付。
价格上涨造成敞口漂移在下一小时处理，不能把目标区间声称为瞬时硬上限。</p>
<h2>选择与时间验证</h2><p>预先列出四个小范围候选（10/40、20/60 EMA × 45%、65% 趋势仓位），
只使用第一年“收益 − 1.5 × 最大回撤”选择候选 {bundle['selected_index']}，第二年冻结参数。
第二年独立启动账户，从现金开始，使用之前已完成数据预热。
全期图展示同参数的持续运行结果，其中第一年属于训练期。</p>
<p><b>限制：</b>研发前已阅读全期旧报告，时间留出是回顾性验证，不能称为真正未见样本。
四候选也可能产生选择偏差；第二年结果没有反馈修改候选或选择规则。</p>
<div class="scroll">{training}</div>
<h2>结果与压力测试</h2><div class="scroll">{table}</div>
{refinement_html}
<img alt="Equity drawdown exposure" src="data:image/png;base64,{img}">
<h2>时间切片</h2><p>持续账户的半年段收益与小时采样回撤，不是每段重新调参。首月、末月为不完整月份。</p>
<div class="scroll">{period_table}</div>
<h2>成交假设与可信边界</h2><p>复用原报告 1,051,200 根分钟成交 / 标记 OHLC 与 2,190 条资金费数据，
已核对原始文件 SHA256、连续时间及 OHLC 合法性；未重新向交易所独立下载。
资金费按开分钟前持仓结算。基础 maker 0.014%、taker 0.035%，市价滑点 1bp。
挂单必须穿透限价至少 0.1 USDT 才成交，每单每小时最多成交一次；新挂单不得跨当前开盘价。
基础假设可全量成交，半量压力只允许计划数量一半成交后撤销余量；这不是订单簿排队模型。</p>
<p>分钟内先跌后涨 / 先涨后跌均为假设路径，成交发生在该路径端点，标记价也取端点。
本模型不承诺真实逐笔先后、实际排队、盘口流动性、停机断线、强平规则和资金费可用性。
仅开收价场景用于检查对分钟极值的依赖。图中是小时回撤，汇总最大回撤检查了路径节点与每次成交。
多空切换、风控减仓允许亏损；若要求“不止损”，需另外研发，该版本不满足那项约束。</p>
<h2>独立验收</h2><p>核对所有成交、资金费结算前有符号持仓、每小时现金与净值、多空每次加仓敞口限制、
延迟指标、完整 Cycle 净数量与费用。包含未来数据修改 / 截断不影响过去信号、触价不成交、
先平旧轮再反向开仓、空头资金费收入与买入平仓测试。</p>
<p><a href="results.json">参数与结果</a> · <a href="audit.json">账本验收</a> ·
<a href="monthly_results.csv">月度结果</a> · <a href="period_results.csv">时间分段</a></p>
{cycles_html}<p>外部复核未执行：本会话没有可用内置浏览器或 ChatGPT Pro 工具。由主 Codex 本地研发和独立账本验收。</p></html>'''
    (ROOT/'report.html').write_text(report)
    source_hashes = {p.name:hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in [ROOT/'backtest.py',ROOT/'test_backtest.py',ROOT/'build_report.py',
                               ROOT/'run_range.py', ROOT.parent/'cycle_report.py']}
    (ROOT/'source_hashes.json').write_text(json.dumps(source_hashes,indent=2))
    if 'range_olhc' in bundle['results']:
        phase = bundle['results']['range_olhc']
        decision = dict(status='RESEARCH_CANDIDATE_ONLY', main_scenario='range_olhc',
            return_pct=phase['return_pct'], max_drawdown_pct=phase['max_drawdown_pct'],
            grid_increment_vs_same_controller_pct=phase['return_pct']-ablation['return_pct'],
            first_year_return_pct=bundle['results']['range_train']['return_pct'],
            independent_second_year_return_pct=bundle['results']['range_holdout']['return_pct'],
            marked_equity_includes_open_cycle=True,
            limitations=['First year loses money', 'Small grid contribution',
                         'Refinement is retrospective, not blind validation',
                         'Minute path and fill assumptions need forward Paper validation'],
            all_exported_scenarios_audited=len(audits), live_deployed=False)
        (ROOT/'decision.json').write_text(json.dumps(decision,indent=2))
    print(json.dumps(dict(audited_scenarios=len(audits), grid_difference_pct=grid_difference,
                         conclusion=verdict),ensure_ascii=False),flush=True)


if __name__ == '__main__':
    main()
