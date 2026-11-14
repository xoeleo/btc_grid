"""Reconstruct exported ledgers independently and publish complete research evidence."""
from dataclasses import asdict
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
from engine import ROOT,CAPITAL,MAKER,TAKER
from features import MacroConfig,load_data,macro_features,controller,hourly_signals
sys.path.insert(0,str(ROOT.parent))
from cycle_report import build_cycle_section

LABELS={'full_olhc':'第一组百分位 · 全期先跌后涨','full_ohlc':'第一组百分位 · 全期先涨后跌',
    'full_close':'第一组百分位 · 开收价','full_cost':'第一组百分位 · 成本压力',
    'full_half':'第一组百分位 · 半量成交','full_no_grid':'第一组百分位 · 无网格',
    'validation_olhc':'第一组百分位 · 2023+','validation_cost':'第一组百分位 · 2023+成本',
    'validation_no_grid':'第一组百分位 · 2023+无网格','recent_hourly':'第一组百分位 · 最近两年小时',
    'recent_hourly_no_grid':'第一组百分位 · 最近两年无网格','no_confirmation':'第一组 · 无转折确认',
    'chain_confirmation':'第一组 · MVRV组合',
    'peak_full':'四年高点模型 · 全期先跌后涨','peak_ohlc':'四年高点模型 · 全期先涨后跌',
    'peak_close':'四年高点模型 · 开收价','peak_cost':'四年高点模型 · 成本压力',
    'peak_half':'四年高点模型 · 半量成交','peak_no_grid':'四年高点模型 · 无网格',
    'peak_validation':'四年高点模型 · 2023+','peak_validation_cost':'四年高点模型 · 2023+成本',
    'peak_validation_no_grid':'四年高点模型 · 2023+无网格',
    'peak_recent_hourly':'四年高点模型 · 最近两年小时','peak_recent_no_grid':'四年高点模型 · 最近两年无网格',
    'peak_no_confirmation':'四年高点模型 · 无转折确认','peak_chain':'四年高点模型 · MVRV过滤',
    'peak_minute':'最近两年分钟 · 先跌后涨','peak_minute_ohlc':'最近两年分钟 · 先涨后跌',
    'peak_minute_close':'最近两年分钟 · 开收价','peak_minute_cost':'最近两年分钟 · 成本压力',
    'peak_minute_half':'最近两年分钟 · 半量成交','peak_minute_no_grid':'最近两年分钟 · 无网格',
    'peak_minute_no_grid_cost':'最近两年分钟 · 无网格成本压力'}


def near(a,b,tol=1e-5):assert abs(float(a)-float(b))<tol,(a,b)


def main():
    bundle=json.loads((ROOT/'results.json').read_text())
    daily,hourly,funding,manifest=load_data()
    minute=pd.read_csv(ROOT/'data/recent_minutes.csv.gz')
    for name,h in bundle['minute_manifest']['files'].items():
        assert hashlib.sha256((ROOT/'data'/name).read_bytes()).hexdigest()==h
    frame=macro_features(daily)
    cache={}
    audits,frames,year_rows,month_rows,direction_rows={}, {},[],[],[]
    for name,s in bundle['results'].items():
        c=MacroConfig(**s['config']);key=json.dumps(asdict(c),sort_keys=True)
        if key not in cache:cache[key]=hourly_signals(hourly,controller(frame,c),c)
        expected=cache[key]
        source=minute if s['bar_interval_ms']==60000 else hourly
        folder=ROOT/name
        tr=pd.read_csv(folder/'trades.csv');fu=pd.read_csv(folder/'funding_events.csv')
        eq=pd.read_csv(folder/'equity.csv');cy=pd.read_csv(folder/'cycles.csv')
        control=pd.read_csv(folder/'controls.csv');frames[name]=eq
        assert len(tr)==s['fills'] and len(cy)==s['completed_cycles']
        assert tr.timestamp_ms.is_monotonic_increasing
        signed=tr.qty*tr.side.map({'BUY':1.,'SELL':-1.});value=signed*tr.price
        assert (signed.cumsum()-tr.net_qty).abs().max()<1e-8
        assert (tr.net_qty-tr.qty_before-signed).abs().max()<1e-8
        first=tr.groupby('cycle_id').first()
        sign=first.side.map({'BUY':1.,'SELL':-1.})
        assert (tr.net_qty*tr.cycle_id.map(sign)>=-1e-10).all()
        rate=tr.fee/(tr.qty*tr.price)
        assert np.max(abs(rate-np.where(tr.kind.eq('grid'),MAKER,TAKER)*s['fee_multiplier']))<1e-12
        fidx=np.searchsorted(fu.timestamp_ms,tr.timestamp_ms,side='right')-1
        fpaid=np.where(fidx>=0,fu.paid.cumsum().to_numpy()[np.maximum(fidx,0)],0.)
        cash=CAPITAL-value.cumsum()-tr.fee.cumsum()-fpaid
        trade_eq=cash+tr.net_qty*tr.mark
        addition=tr.net_qty.abs()>tr.qty_before.abs()+1e-10
        assert (tr.loc[addition,'net_qty'].abs()*tr.loc[addition,'mark']/trade_eq[addition]
                <= tr.loc[addition,'exposure_cap']+1e-8).all()
        assert cash.min()>=-1e-5
        assert (trade_eq>tr.net_qty.abs()*tr.mark).all()
        carried_index=np.searchsorted(tr.timestamp_ms,fu.timestamp_ms,side='left')-1
        carried=np.where(carried_index>=0,tr.net_qty.to_numpy()[np.maximum(carried_index,0)],0.)
        assert np.max(abs(carried-fu.qty))<1e-8
        assert np.max(abs(fu.qty*fu.mark*fu.rate-fu.paid))<1e-7
        ref=funding[funding.timestamp_ms.between(s['start_ms'],s['end_ms']-1)]
        assert np.array_equal(ref.timestamp_ms,fu.timestamp_ms)
        assert np.max(abs(ref.rate.to_numpy()-fu.rate.to_numpy()))<1e-14
        indices=((fu.timestamp_ms-source.timestamp_ms.iloc[0])//s['bar_interval_ms']).astype(int)
        assert np.max(abs(fu.mark.to_numpy()-source.mark_open.iloc[indices].to_numpy()))<1e-8
        qidx=np.searchsorted(tr.timestamp_ms,eq.timestamp_ms,side='right')-1
        position=np.where(qidx>=0,tr.net_qty.to_numpy()[np.maximum(qidx,0)],0.)
        vf=(value.cumsum()+tr.fee.cumsum()).to_numpy()
        cash_hour=CAPITAL-np.where(qidx>=0,vf[np.maximum(qidx,0)],0.)
        idx=np.searchsorted(fu.timestamp_ms,eq.timestamp_ms,side='right')-1
        cash_hour-=np.where(idx>=0,fu.paid.cumsum().to_numpy()[np.maximum(idx,0)],0.)
        assert np.max(abs(position-eq.net_qty))<1e-8
        assert np.max(abs(cash_hour+position*eq.mark-eq.equity))<1e-5
        assert np.max(abs(eq.net_notional-position.__abs__()*eq.mark))<1e-7
        assert (eq.net_notional/eq.equity<1).all()
        h=((control.timestamp_ms-hourly.timestamp_ms.iloc[0])//3600000).astype(int)
        assert np.array_equal(control.state,expected.state.iloc[h])
        assert np.allclose(control.target,expected.target.iloc[h])
        assert np.allclose(control.anchor,expected.anchor.iloc[h],equal_nan=True)
        assert np.allclose(control.step,expected.step.iloc[h],equal_nan=True)
        near(tr.fee.sum(),s['trading_fees']);near(fu.paid.sum(),s['funding_paid'])
        near(cy.pnl.sum(),s['closed_cycle_pnl'])
        near(CAPITAL-value.sum()-tr.fee.sum()-fu.paid.sum()+s['open_qty']*eq.iloc[-1].mark,s['final_equity_marked'])
        audits[name]=dict(passed=True,trades=len(tr),funding=len(fu),hourly_equity=len(eq),
            cycles=len(cy),cash_identity=True,signed_position=True,carried_funding=True,
            exposure_on_additions=True,no_historical_leverage=True,delayed_controls=True)
        dates=pd.to_datetime(eq.timestamp_ms,unit='ms',utc=True)
        previous=CAPITAL
        for month,data in eq.groupby(dates.dt.strftime('%Y-%m')):
            ending=data.equity.iloc[-1]
            month_rows.append(dict(scenario=name,month=month,equity=ending,return_pct=(ending/previous-1)*100));previous=ending
        previous=CAPITAL
        for year,data in eq.groupby(dates.dt.year):
            values=np.r_[previous,data.equity.to_numpy()]
            year_rows.append(dict(scenario=name,year=year,return_pct=(values[-1]/previous-1)*100,
                hourly_drawdown_pct=np.max((1-values/np.maximum.accumulate(values))*100),
                average_exposure_pct=(data.net_notional/data.equity).mean()*100))
            previous=values[-1]
        for direction,direction_sign in [('LONG',1),('SHORT',-1)]:
            ids=sign[sign==direction_sign].index
            closed=cy[cy.cycle_id.isin(ids)]
            open_ids=set(ids)-set(cy.cycle_id)
            open_pnl=s['open_cycle_pnl'] if open_ids else 0.
            direction_rows.append(dict(scenario=name,direction=direction,closed_cycles=len(closed),
                closed_pnl=closed.pnl.sum(),open_cycle_pnl=open_pnl,total_pnl=closed.pnl.sum()+open_pnl))
    pd.DataFrame(year_rows).to_csv(ROOT/'yearly_results.csv',index=False)
    pd.DataFrame(month_rows).to_csv(ROOT/'monthly_results.csv',index=False)
    pd.DataFrame(direction_rows).to_csv(ROOT/'direction_results.csv',index=False)
    (ROOT/'audit.json').write_text(json.dumps(audits,indent=2))
    names={name:LABELS.get(name,name) for name in bundle['results']}
    cycles_html=build_cycle_section(ROOT,names)
    selected=MacroConfig(**bundle['selected_config'])
    selected_daily=controller(frame,selected)
    events=selected_daily[selected_daily.state.ne(selected_daily.state.shift(1)) & selected_daily.state.notna()].copy()
    events.to_csv(ROOT/'signal_events.csv',index_label='available_day_utc')
    fig,axes=plt.subplots(3,1,figsize=(14,10),sharex=True)
    axes[0].plot(frame.index,frame.close,lw=.8,label='BTC reference price')
    axes[0].plot(frame.index,frame.four_year_peak*selected.low_ratio,lw=.8,label='Low-zone boundary')
    axes[0].plot(frame.index,frame.four_year_peak*selected.high_ratio,lw=.8,label='High-zone boundary')
    axes[0].set_yscale('log');axes[0].set_ylabel('BTC price / log scale');axes[0].legend()
    axes[1].plot(frame.index,frame.peak_ratio,lw=.8);axes[1].axhline(selected.low_ratio,color='#35945f',ls='--')
    axes[1].axhline(selected.high_ratio,color='#d97624',ls='--');axes[1].set_ylabel('Price / trailing 4y peak')
    axes[2].plot(selected_daily.index,selected_daily.target*100,lw=.8);axes[2].set_ylabel('Target exposure / %')
    for ax in axes:ax.grid(alpha=.2)
    fig.tight_layout();fig.savefig(ROOT/'macro_position.png',dpi=140);plt.close(fig)
    fig,axes=plt.subplots(2,1,figsize=(14,8),sharex=True)
    for name in ['peak_full','peak_no_grid','peak_cost']:
        e=frames[name];t=pd.to_datetime(e.timestamp_ms,unit='ms',utc=True)
        axes[0].plot(t,e.equity,label=name,lw=.9)
        axes[1].plot(t,(e.equity/e.equity.cummax()-1)*100,lw=.8)
    axes[0].legend();axes[0].set_ylabel('Equity / USDT');axes[1].set_ylabel('Hourly drawdown / %')
    for ax in axes:ax.grid(alpha=.2)
    fig.tight_layout();fig.savefig(ROOT/'equity_drawdown.png',dpi=140);plt.close(fig)
    image=lambda name:base64.b64encode((ROOT/name).read_bytes()).decode()
    table_rows=[]
    for name,s in bundle['results'].items():
        table_rows.append(dict(场景=names[name],收益率=f'{s["return_pct"]:.2f}%',
            路径节点回撤=f'{s["max_drawdown_pct"]:.2f}%',净收益=f'{s["net_pnl"]:,.2f}',
            期末轮盈亏=f'{s["open_cycle_pnl"]:,.2f}',期末BTC=f'{s["open_qty"]:.3f}',
            费用=f'{s["trading_fees"]:,.2f}',资金费=f'{s["funding_paid"]:,.2f}',
            平仓轮次=s['completed_cycles'],亏损轮次=s['losing_cycles']))
    table=pd.DataFrame(table_rows).to_html(index=False,escape=True)
    key_scenarios=['peak_full','peak_validation','peak_minute','peak_minute_no_grid']
    years=pd.DataFrame(year_rows);year_table=years[years.scenario.isin(key_scenarios)].to_html(index=False,float_format=lambda x:f'{x:.3f}')
    directions=pd.DataFrame(direction_rows);direction_table=directions[directions.scenario.isin(key_scenarios)].to_html(index=False,float_format=lambda x:f'{x:,.2f}')
    diagnostic=pd.read_csv(ROOT/'position_diagnostics.csv');diagnostic_table=diagnostic[diagnostic.horizon_days.eq(180)].to_html(index=False,float_format=lambda x:f'{x:.3f}')
    training_table=pd.DataFrame([dict(候选=i,低位比例=s['config']['low_ratio'],确认天数=s['config']['confirm_days'],
        训练期收益=s['return_pct'],训练期回撤=s['max_drawdown_pct'],选择分数=s['selection_score'])
        for i,s in enumerate(bundle['training'])]).to_html(index=False,float_format=lambda x:f'{x:.3f}')
    recent=bundle['results']['peak_minute'];base=bundle['results']['peak_minute_no_grid']
    delta=recent['return_pct']-base['return_pct']
    snapshot=json.loads((ROOT/'latest_snapshot.json').read_text()) if (ROOT/'latest_snapshot.json').exists() else None
    snapshot_html=''
    if snapshot:
        region={'low':'低位区域','high':'高位区域','middle':'中间 / 恢复区域'}[snapshot['region']]
        snapshot_html=f'''<h2>最新已收盘日线快照</h2><p>截至 {snapshot['completed_day_utc']} UTC 收盘，BTC 为 {snapshot['close']:,.2f}，
过去四年最高收盘为 {snapshot['four_year_peak']:,.2f}，比例 {snapshot['peak_ratio']*100:.2f}%，属于<b>{region}</b>。
当前低位边界 {snapshot['low_zone_price']:,.2f}，高位边界 {snapshot['high_zone_price']:,.2f}。
模型在 {snapshot['signal_available_day_utc']} 的状态为 {snapshot['state']}（1 多、-1 空、0 等待），目标敞口 {snapshot['target']*100:g}%。
这是历史触发后延续的模型状态，不是账户持仓或新开仓指令；当前价格所在区域不能单独决定交易。</p>'''
    report=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BTC 大周期高低位与网格研究</title><style>body{{max-width:1450px;margin:30px auto;padding:0 20px;font:16px/1.65 system-ui;color:#17283d}}
h1,h2{{color:#164b78}}table{{border-collapse:collapse;white-space:nowrap;font-size:13px;width:100%}}td,th{{border:1px solid #d8e1ea;padding:8px;text-align:right}}
th{{background:#edf3fa}}.scroll{{overflow:auto}}.note{{padding:16px;background:#edf3fa}}img{{width:100%}}</style>
<h1>BTC 大周期相对高低位：定位与网格分别验证</h1>
<p>2020-01-01 至 2026-10-02 UTC 合约回测 · 2010 年以来价格预热 · 50 万 USDT · 未部署</p>
<div class="note"><b>当前结论：</b>长期趋势偏离百分位不能直接作为逆向网格依据；四年高点回撤结合中期趋势反转更有研究价值。
最近两年同源分钟网格收益 {recent['return_pct']:.2f}%、回撤 {recent['max_drawdown_pct']:.2f}%；相同方向和仓位的无网格版本收益
{base['return_pct']:.2f}%、回撤 {base['max_drawdown_pct']:.2f}%。网格增量为 {delta:+.2f} 个百分点，当前没有证明近期网格优于持仓。</div>
{snapshot_html}
<h2>为什么第一种相对高低位判断失败</h2><p>“价格偏离两年对数趋势”与“价格 / 两年均线”的历史百分位，都可能把强牛市误判为高估。
第一组四候选在 2020–2022 训练期全部亏损，失败结果与完整账本保留。
下面是按至少 30 天间隔采样的 180 天未来收益，只用于诊断，绝不输入交易信号；样本依然重叠，不能视为独立显著性检验。
高位样本的未来收益经常仍为正，所以不能从“历史百分位高”直接推导“适合开空”。</p><div class="scroll">{diagnostic_table}</div>
<h2>第二组：四年高点回撤 + 转折确认</h2><p>每天计算 R = 当天收盘 / 此前 1460 天（含当天）的最高收盘。
R ≤ {selected.low_ratio:g}，记录低位候选，即距离四年高点至少跌 {100*(1-selected.low_ratio):g}%；
R ≥ {selected.high_ratio:g}，记录高位候选，即距离四年高点不超过 {100*(1-selected.high_ratio):g}%。
这是价格区域，不是精确顶底预测，也不是价格百分位。</p>
<p>候选记忆保留 {selected.arm_days} 天。多头：近期出现过低位，收盘突破此前 {selected.confirm_days} 天最高收盘，
收盘高于 EMA100 且 EMA100 比五天前上升，价格尚未进入高位区域。
空头：近期出现过高位，收盘跌破此前 {selected.confirm_days} 天最低收盘，收盘低于 EMA100 且 EMA100 在下降，价格尚未进入低位区域。
所有日线规则次日使用，小时格距使用此前完成的小时信息。等待确认意味着通常不会在最高点开空或最低点开多。</p>
<p>目标名义敞口为净值的 {selected.exposure*100:g}%；上下三层网格，每层约净值的 {selected.lot*100:g}%。
格距为上一小时 ATR(24) / 收盘 × 1.5，限定 0.5%–2%。加仓上限为目标 + 三层额度，即 {100*(selected.exposure+selected.lot*selected.levels):g}%。
每小时处理仓位漂移；历史样本不借贷，不把这个小时检查声称为瞬时强平保护。</p>
<p>退出条件：到达相反区域、中期趋势再次反转、相对本次日线信号参考价不利变动超过 20%，或持有满 730 天。
20% 是日线收盘检查，不是盘中硬止损；下一开盘执行可能超过该损失。退出后冷却 30 天。
多空切换先平旧 Cycle，再开新 Cycle。独立启动账户继承当时已有宏观信号状态，止损参考来自该宏观信号的历史触发价。</p>
<img src="data:image/png;base64,{image('macro_position.png')}" alt="Macro position history">
<h2>选参与可信边界</h2><p>第二组只比较低位比例 0.45 / 0.55 × 确认 28 / 42 天的四候选，
其余参数固定。按 2020–2022 “收益 − 最大回撤”选择候选 {bundle['selected_index']}，2023 年以后冻结参数，
所有候选的后段结果均公开。</p><div class="scroll">{training_table}</div>
<p><b>第二组是在看到第一组全期失败后提出的结构改进，且研发已看过原两年报告。
这是回顾性时间分段验证，不是真正未见数据或盲测；不能据此承诺未来收益。</b>
全期累计收益包含训练期，2023+账户从现金启动。数据含多个牛熊阶段，但独立大周期数量仍然有限。</p>
<h2>所有场景结果</h2><p>所有收益均为累计净值收益，含未结束 Cycle 的盈亏，并已扣交易费用及资金费；不是年化收益。
小时路径无法证明真实分钟成交，最近两年另外下载了同一交易所的 1,051,200 根分钟成交与标记 OHLC。
仅开收价、半量成交和费用压力均保留，不能只挑最乐观路径。</p><div class="scroll">{table}</div>
<img src="data:image/png;base64,{image('equity_drawdown.png')}" alt="Equity and drawdown">
<h2>逐年收益与资金使用</h2><p>年初沿用持续账户净值；2026 年和分钟回测首年为不完整年份。年度回撤为小时采样。
低回撤可能来自低仓位和长时间空仓，不能直接归因于高低位指标有效。</p><div class="scroll">{year_table}</div>
<h2>多空贡献</h2><div class="scroll">{direction_table}</div>
<h2>MVRV 的作用和局限</h2><p>已补齐公开 MVRV 至 {manifest['mvrv_last_valid']}。第一组组合价格 / MVRV 百分位，
第二组额外要求近期 MVRV ≤ 1.2 的低位条件或 ≥ 2.5 的高位条件；均作为公开消融，不用于选择主候选。
MVRV 额外延迟一天，加上日线次日执行，总共采用两天滞后的指标。
下载的是当前版本修订历史，没有历史发布日期和数据版本快照，因此链上结果不能称为严格 point-in-time 回测。</p>
<h2>数据与执行验收</h2><p>日线 {manifest['daily_rows']} 天，合约小时线 {manifest['hourly_rows']} 根，资金费 {manifest['funding_rows']} 条。
2020 年前价格采用 Coin Metrics USD，之后为 Binance USDT 合约收盘，拼接存在报价与市场基差差异。
所有 Binance ZIP 核对官方 SHA256 并检查 CRC，月度标记价格缺失的 216 小时以官方日包修复，
分钟数据也按连续时间严格检查。原始下载、来源与校验记录完整保留。</p>
<p>资金费原始毫秒事件时间保留；模型把边界后一秒内的结算归到所在小时开头，先对携带仓位结算再交易。
资金费率和持仓符号均按原始记录核查，空头可能收取或支付资金费。
基础 maker 0.014%、taker 0.035%，市价滑点 1bp；成本压力使用双倍费用和 5bp 滑点。
限价必须穿透 0.1 USDT 才假定成交，每单每小时最多成交一次；半量模型成交计划数量的一半并撤销余量。
这些假设没有真实盘口、排队、延迟、断线或强平模型，且费率跨年固定，不能当成可部署收益。</p>
<p>独立核对 {len(audits)} 个场景的有符号持仓、现金、资金费结算前仓位、每小时净值、每笔加仓容量和延迟控制。
测试覆盖未来修改 / 数据截断不改变过去、当前值不进入历史百分位、链上缺失不影响价格主模型、分钟 / 小时恒定行情一致、触价不成交。</p>
<p><a href="results.json">全部参数与结果</a> · <a href="audit.json">账本核验</a> · <a href="yearly_results.csv">逐年结果</a> ·
<a href="direction_results.csv">多空贡献</a> · <a href="signal_events.csv">全部方向变更</a> · <a href="latest_snapshot.json">最新周期快照</a></p>
{cycles_html}<p>外部复核未执行：本会话没有可用内置浏览器或 ChatGPT Pro 工具。主 Codex 完成本地研究与独立账本核验；未部署。</p></html>'''
    (ROOT/'report.html').write_text(report)
    decision=dict(status='POSITION_RESEARCH_CANDIDATE_GRID_EDGE_NOT_PROVEN',main_scenario='peak_minute',
        recent_return_pct=recent['return_pct'],recent_drawdown_pct=recent['max_drawdown_pct'],
        grid_increment_pct=delta,selected_config=asdict(selected),live_deployed=False,
        all_scenarios_audited=len(audits),limitations=bundle['warnings'])
    (ROOT/'decision.json').write_text(json.dumps(decision,indent=2))
    source_files=list(ROOT.glob('*.py'))+[ROOT.parent/'cycle_report.py']
    (ROOT/'source_hashes.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files},indent=2))
    print(json.dumps(dict(audited=len(audits),grid_increment_pct=delta,report=str(ROOT/'report.html')),ensure_ascii=False),flush=True)


if __name__=='__main__':main()
