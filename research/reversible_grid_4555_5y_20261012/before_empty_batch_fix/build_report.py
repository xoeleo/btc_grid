from pathlib import Path
import json, hashlib
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
from matplotlib.ticker import FuncFormatter

ROOT=Path(__file__).resolve().parent
REF=ROOT.parent/'reversible_grid_4555_100_stop_20261012'
FONT=Path('/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc')
if FONT.exists():
    matplotlib.font_manager.fontManager.addfont(str(FONT))
    plt.rcParams['font.family']=FontProperties(fname=str(FONT)).get_name()
plt.rcParams['axes.unicode_minus']=False
LABEL={'direct4555_minute':'45–55直接再平衡至50%'}
for levels in [20,50,100]:
    prefix=f'grid{levels}'
    for suffix, label in [('minute','基础'),('OHLC','另一分钟内价格路径'),('half_fill','每次半成交'),('fee2','手续费翻倍'),('borrow5','负USDT按5%计息'),('borrow10','负USDT按10%计息')]:
        LABEL[f'{prefix}_{suffix}']=f'{levels}格完成即停：{label}'

def financial_table(rows):
    return pd.DataFrame([{'方案':LABEL[x['id']], '期末净资产（USDT）':x['final_equity'],
        '总收益（%）':100*x['total_return'], '年化收益（%）':100*x['cagr'], '最大回撤（%）':100*x['max_drawdown'],
        '资金费净收入（USDT）':x['funding_net'], '全部手续费（USDT）':x['fees'], '模拟融资利息（USDT）':x['borrow_interest'],
        '配对往返次数':x['grid_pair_count'], '配对净价差（USDT）':x['matched_grid_profit']} for x in rows]).to_html(index=False,float_format=lambda x:f'{x:,.2f}')

def batch_table(batches):
    d=batches.copy()
    for col,title in [('start_ms','启动时间（UTC）'),('first_completed_ms','全格完成时间（UTC）'),('end_ms','结束时间（UTC）')]:
        d[title]=pd.to_datetime(d[col],unit='ms',utc=True).astype(str).replace('NaT','—')
    d=d.drop(columns=['start_ms','first_completed_ms','end_ms'])
    d.direction=d.direction.map({'sell_up_buy_back':'上涨加空／回落平空','buy_down_sell_back':'下跌平空／反弹加空'})
    d.end_reason=d.end_reason.replace({'adjustment_completed_orders_cancelled':'全部份额成交，撤单保留调整','all_returned_inside_band':'全部回补且回到触发带内','open_at_end':'期末仍未完成'})
    for col in ['ever_traded','retired']:d[col]=d[col].map({True:'是',False:'否'})
    headers={'batch':'批次编号','direction':'调仓方向','anchor':'启动合约价格（USDT）','start_equity':'启动净资产（USDT）','start_weight':'启动净敞口比例','planned_qty':'冻结调整数量（BTC）','requested_slots':'计划格数','actual_slots':'实际格数','total_units':'计划数量单位数（每单位0.001 BTC）','held_units':'已调整未回补单位数','ever_traded':'是否曾成交','retired':'是否已结束','completion_count':'全格完成次数','end_reason':'结束状态','end_weight':'结束净敞口比例','first_completed_weight':'全格完成净敞口比例'}
    assert set(d.columns)<=set(headers)|{'启动时间（UTC）','全格完成时间（UTC）','结束时间（UTC）'}
    return d.rename(columns=headers).to_html(index=False,float_format=lambda x:f'{x:,.6f}')

def event_chart(levels, direct_events):
    name=f'grid{levels}_minute'
    b=pd.read_csv(ROOT/'outputs'/f'{name}_batches.csv')
    t=pd.read_csv(ROOT/'outputs'/f'{name}_trades.csv')
    d=pd.read_csv(ROOT/'outputs'/f'{name}_daily.csv')
    complete=b[b.end_reason=='adjustment_completed_orders_cancelled']
    fig,axes=plt.subplots(2,1,figsize=(14,8),sharex=True,gridspec_kw={'height_ratios':[2,1]})
    axes[0].plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,color='#d97706',label=f'{levels}格组合净资产')
    axes[1].plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.weight*100,label='日终净BTC敞口')
    for bound in [45,50,55]:axes[1].axhline(bound,color='#888',ls='--',lw=.7)
    for direction,marker,color,label in [('sell_up_buy_back','v','#b91c1c','加空启动'),('buy_down_sell_back','^','#15803d','减空启动')]:
        points=b[b.direction==direction]
        if len(points):
            dates=pd.to_datetime(points.start_ms,unit='ms',utc=True)
            axes[0].scatter(dates,points.start_equity,marker=marker,color=color,alpha=.65,s=38,zorder=5,label=f'{label}（{len(points)}批）')
            axes[1].scatter(dates,points.start_weight*100,marker=marker,color=color,s=25,zorder=5)
    last=t[t.batch.isin(complete.batch)].groupby('batch',sort=False).tail(1)
    if len(last):
        dates=pd.to_datetime(last.timestamp_ms,unit='ms',utc=True)
        axes[0].scatter(dates,last.equity,marker='s',facecolors='none',edgecolors='#1d4ed8',s=65,zorder=6,label=f'全部成交、撤单结束（{len(last)}批）')
        axes[1].scatter(dates,last.weight*100,marker='s',facecolors='none',edgecolors='#1d4ed8',s=45,zorder=6)
    axes[0].set_title(f'{levels}格网格：启动位置、完成位置及净BTC敞口')
    axes[0].yaxis.set_major_formatter(FuncFormatter(lambda value,_:f'{value/10000:,.0f}'));axes[0].set_ylabel('组合净资产（万美元）');axes[1].set_ylabel('净BTC敞口（%）');axes[1].set_xlabel('日期（UTC）')
    for ax in axes:ax.grid(alpha=.25);ax.legend(fontsize=9,ncol=2)
    fig.tight_layout();fig.savefig(ROOT/f'grid{levels}_events.png',dpi=150);plt.close(fig)
    return b,complete

def main():
    results=json.loads((ROOT/'results.json').read_text());refs=json.loads((ROOT/'reference_results.json').read_text())
    r={x['id']:x for x in refs+results}
    names=['grid20_minute','grid50_minute','grid100_minute','direct4555_minute']
    direct_trades=pd.read_csv(ROOT/'outputs/direct4555_minute_trades.csv')
    events=direct_trades[direct_trades.kind=='rebalance']
    plt.figure(figsize=(14,7))
    for name in names:
        base=ROOT
        d=pd.read_csv(base/'outputs'/f'{name}_daily.csv')
        plt.plot(pd.to_datetime(d.timestamp_ms,unit='ms',utc=True),d.equity,label=LABEL[name])
    for sign,marker,label in [(False,'v','直接再平衡：降低敞口'),(True,'^','直接再平衡：提高敞口')]:
        points=events[(events.delta>0)==sign]
        if len(points):plt.scatter(pd.to_datetime(points.timestamp_ms,unit='ms',utc=True),points.equity,marker=marker,facecolors='none',edgecolors='black',s=100,zorder=5,label=f'{label}（{len(points)}次）')
    plt.title('五年45%–55%触发：20／50／100格完成即停，对照直接再平衡')
    plt.gca().yaxis.set_major_formatter(FuncFormatter(lambda value,_:f'{value/10000:,.0f}'));plt.ylabel('组合净资产（万美元）');plt.xlabel('日期（UTC）');plt.legend(fontsize=9,ncol=2);plt.grid(alpha=.25);plt.tight_layout();plt.savefig(ROOT/'equity.png',dpi=150);plt.close()
    batch_details={};stats=[]
    for levels in [20,50,100]:
        b,completed=event_chart(levels,events);batch_details[levels]=b
        stats.append({'方案':f'{levels}格','加空启动批次':int((b.direction=='sell_up_buy_back').sum()),'减空启动批次':int((b.direction=='buy_down_sell_back').sum()),'全部份额成交批次':len(completed),'全部回补结束批次':int((b.end_reason=='all_returned_inside_band').sum()),'期末未完成批次':int((b.end_reason=='open_at_end').sum()),'完成后最低敞口（%）':completed.end_weight.min()*100,'完成后最高敞口（%）':completed.end_weight.max()*100})
    pd.DataFrame(stats).to_csv(ROOT/'batch_comparison.csv',index=False)
    yearly=pd.read_csv(ROOT/'yearly.csv');yearly.id=yearly.id.map(LABEL);yearly=yearly.rename(columns={'id':'方案','year':'年份','return_pct':'年度收益（%）'})
    annual_counts=[]
    for name in names:
        if name=='direct4555_minute':
            started=events;finished=events;start_col=end_col='timestamp_ms'
        else:
            started=pd.read_csv(ROOT/'outputs'/f'{name}_batches.csv')
            finished=started[started.end_reason=='adjustment_completed_orders_cancelled'];start_col='start_ms';end_col='end_ms'
        starts=pd.to_datetime(started[start_col],unit='ms',utc=True).dt.year.value_counts()
        ends=pd.to_datetime(finished[end_col],unit='ms',utc=True).dt.year.value_counts()
        for year in range(2021,2027):annual_counts.append({'方案':LABEL[name],'年份':year,'启动调仓次数':int(starts.get(year,0)),'完成整批调整次数':int(ends.get(year,0))})
    pd.DataFrame(annual_counts).to_csv(ROOT/'annual_rebalance_counts.csv',index=False)
    audit=json.loads((ROOT/'audit.json').read_text());assert len(audit['independent_export_and_slot_replay'])==len(results)
    body='<h1>五年回测：45–55再平衡，20／50／100格完成即停</h1><p>回测区间2021-10-02至2026-10-02。初始50万美元BTC现货存量＋约25万美元永续空单。基础结果包含实际历史资金费和研究手续费，未计潜在融资利息。全部网格采用最终确认的结束规则，不引用完成后仍回补的旧模型作为当前策略。</p><img src="equity.png"><p>黑色空心三角：直接再平衡实际成交，共'+str(len(events))+'次。</p><h2>核心对比</h2>'+financial_table([r[x] for x in names])
    body+='<h2>调仓启动与完成次数</h2>'+pd.DataFrame(stats).to_html(index=False,float_format=lambda x:f'{x:,.2f}')
    body+='<h2>执行规则及格数区别</h2><p>净敞口＝（现货市值－空单标记市值）／组合净资产。触发45%或55%后，冻结当时净资产5%折算的BTC合约数量，分成指定份数，格距0.1%。完成前：上侧上涨加空、回落一格平对应份额；下侧镜像。回补只恢复已调整份额，上侧不超55%、下侧不低45%。<strong>全部份额成交后立即撤掉本批所有剩余挂单及回补单，保留调整后的仓位；此后只有再次触发45%或55%才新建批次。</strong>未完成时，全部回补且敞口回到触发带内也结束本批。</p><p>20格累计上涨约2.02%、下跌约1.98%；50格约5.12%／4.88%；100格约10.51%／9.52%。每份对应冻结预算的1／20、1／50或1／100。冻结数量不逐笔重算，全部成交后的比例不保证恰为50%。无24小时强制吃单，无主动永久恢复已完成批次。</p>'
    for levels in [20,50,100]:
        body+=f'<h2>{levels}格调仓位置</h2><img src="grid{levels}_events.png"><p>红色倒三角：加空批次启动；绿色正三角：减空批次启动；蓝色空心方块：全部份额成交并撤单结束。启动挂单不是实际成交，日终敞口线与事件标记的观察时间也不同。时间均为UTC。</p><input class="search" data-table="batches{levels}" placeholder="筛选日期、方向、状态"><div id="batches{levels}">'+batch_table(batch_details[levels])+'</div>'
    body+='<h2>逐年调仓次数</h2>'+pd.DataFrame(annual_counts).to_html(index=False)
    body+='<h2>新回测压力情景</h2>'+financial_table(results)+'<h2>年度收益（首尾为部分年份）</h2>'+yearly.to_html(index=False,float_format=lambda x:f'{x:,.2f}')
    body+='<h2>数据及成本假设</h2><p>同一冻结Bybit现货、永续成交价、标记价数据：2021-10-02至2026-10-02，2,629,440根完整分钟数据及5,478次资金费。五年全程采用VIP3研究费率：挂单0.014%、吃单0.035%，未经账户接口核实，也未还原历史VIP等级及费率变化。初始建空及直接再平衡吃单，滑点1基点；网格挂单须穿透2基点才记成交，新单及返向单最早下一分钟成交。数量步长0.001 BTC、价格步长0.1 USDT；未重建真实订单簿、历史统一账户制度、抵押折扣及强平规则；这是一致假设下的历史行情研究，不代表该账户方案在五年前即可原样执行。</p><p>配对网格净价差扣除对应两笔挂单费，已经包含在组合损益中，不得重复相加。融资情景按模拟USDT负余额逐小时计5%／10%年息，均是假设。不同成本可能改变触发路径，结果不保证单调。</p>'
    body+=f'<p>{len(audit["unit_checks"])}项针对性检查、{len(results)}个五年场景账本及每格库存回放全部通过。<a href="audit.json">核验记录</a> · <a href="source_hashes.json">数据与代码哈希</a> · <a href="comparison.csv">比较数据</a>。没有实盘修改。外部复核未执行：本会话没有可用浏览器或ChatGPT Pro工具。</p>'
    doc='<!doctype html><meta charset="utf-8"><title>五年45–55网格份数比较</title><style>body{font-family:system-ui;max-width:1700px;margin:30px auto;padding:0 15px}p{line-height:1.65}table{border-collapse:collapse;font-size:12px}td,th{padding:6px;border:1px solid #ddd}img{max-width:100%}input{padding:8px}</style>'+body+'<script>document.querySelectorAll(".search").forEach(input=>input.oninput=function(){document.querySelectorAll("#"+this.dataset.table+" tbody tr").forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))})</script>'
    (ROOT/'report.html').write_text(doc)
    (ROOT/'README.md').write_text('45%–55%触发，冻结5%调整量，20／50／100份、0.1%格距，全部份额成交后撤单结束并保留调整结果。五年数据2021-10-02至2026-10-02，含20／50／100格和直接再平衡。\n\npython3 test_variants.py；python3 run.py；python3 validate_export.py；python3 build_report.py。run.py使用四个本地进程运行独立场景，无实盘变更。\n')
    (ROOT/'decision.json').write_text(json.dumps({'research_only':True,'stop_on_completion':True,'low':.45,'high':.55,'adjustment_fraction':.05,'levels':[20,50,100],'step':.001,'main_comparison':[{k:x[k] for k in ['id','final_equity','cagr','max_drawdown']} for x in [r[n] for n in names]]},indent=2))
    hashes=json.loads((ROOT/'source_hashes.json').read_text());hashes['build_report.py']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(ROOT/'source_hashes.json').write_text(json.dumps(hashes,indent=2))
    print('report complete',len(results),'new scenarios')

if __name__=='__main__':main()
