"""Render all closed and open cycles from the exported research ledgers."""
import csv
from datetime import datetime, timezone
from decimal import Decimal as D
import html
import json


def near(a, b):
    assert abs(D(str(a)) - D(str(b))) < D('0.0001'), (a, b)


def timestamp(ms):
    return datetime.fromtimestamp(int(ms) / 1000, timezone.utc).isoformat(
        sep=' ', timespec='milliseconds').replace('+00:00', '')


def build_cycle_section(root, names, extra_columns=(), cycle_metadata=None):
    sections = []
    exported = []
    counts = {}
    columns = [
        ('cycle_id', 'Cycle'), ('status', '状态'), ('direction', '方向'),
        ('start_utc', '开始时间 UTC'), ('end_utc', '结束 / 期末时间 UTC'),
        ('duration_hours', '持续小时'), ('entry_price', '首笔成交价'),
        ('last_price', '末笔成交价'), ('liquidation_qty', '清仓前仓位 BTC'),
        ('buys', '买入笔数'), ('sells', '卖出笔数'),
        ('buy_qty', '买入 BTC'), ('sell_qty', '卖出 BTC'),
        ('peak_qty', '峰值持仓 BTC'), ('remaining_qty', '剩余持仓 BTC（有符号）'),
        ('trading_fee', '手续费 USDT'), ('funding_paid', '资金费净支出 USDT'),
        ('pnl', '净盈亏 USDT'), ('cumulative_pnl', '累计净盈亏 USDT'),
        ('reason_label', '结束原因'),
    ]
    columns += list(extra_columns)
    for name, label in names.items():
        folder = root / name
        summary = json.loads((folder / 'summary.json').read_text(), parse_float=D)
        with (folder / 'cycles.csv').open() as f:
            closed = list(csv.DictReader(f))
        groups = {}
        with (folder / 'trades.csv').open() as f:
            for trade in csv.DictReader(f):
                cid = int(trade['cycle_id'])
                if cid not in groups:
                    groups[cid] = dict(first=trade, last=trade, buys=0, sells=0,
                                       buy_qty=D(0), sell_qty=D(0), value=D(0),
                                       fee=D(0), peak=D(0), position=D(0))
                g = groups[cid]
                qty, price = D(trade['qty']), D(trade['price'])
                buy = trade['side'] == 'BUY'
                signed_qty = qty * (1 if buy else -1)
                before_qty = D(trade['net_qty']) - signed_qty
                assert abs(before_qty - g['position']) < D('0.000000001')
                g['last_before_qty'] = before_qty
                g['position'] = D(trade['net_qty'])
                g['buys' if buy else 'sells'] += 1
                g['buy_qty' if buy else 'sell_qty'] += qty
                g['value'] += qty * price * (1 if buy else -1)
                g['fee'] += D(trade['fee'])
                g['peak'] = max(g['peak'], abs(D(trade['net_qty'])))
                g['last'] = trade
        assert len(closed) == summary['completed_cycles']
        records = []
        closed_ids = set()
        closed_funding = D(0)
        cumulative = D(0)
        for cycle in closed:
            cid = int(cycle['cycle_id'])
            assert cid not in closed_ids
            closed_ids.add(cid)
            g = groups[cid]
            near(g['buy_qty'] - g['sell_qty'], 0)
            assert abs(g['position']) < D('0.000000001')
            assert abs(abs(g['last_before_qty']) - D(g['last']['qty'])) < D('0.000000001')
            assert g['last_before_qty'] * (1 if g['first']['side'] == 'BUY' else -1) > 0
            near(g['fee'], cycle['trading_fee'])
            near(-g['value'] - g['fee'] - D(cycle['funding_paid']), cycle['pnl'])
            assert g['buys'] == int(cycle['buys']) and g['sells'] == int(cycle['sells'])
            assert int(g['first']['timestamp_ms']) == int(cycle['start_ms'])
            assert int(g['last']['timestamp_ms']) <= int(cycle['end_ms'])
            record = dict(cycle)
            record['status'] = '已结束'
            closed_funding += D(cycle['funding_paid'])
            records.append((record, g))
        open_ids = set(groups) - closed_ids
        assert len(open_ids) <= 1
        if open_ids:
            cid = open_ids.pop()
            g = groups[cid]
            near(g['buy_qty'] - g['sell_qty'], summary['open_qty'])
            records.append((dict(
                cycle_id=cid, status='未结束', start_ms=g['first']['timestamp_ms'],
                end_ms=summary['end_ms'], duration_hours=summary['open_cycle_hours'],
                pnl=summary['open_cycle_pnl'], trading_fee=g['fee'],
                funding_paid=D(str(summary['funding_paid'])) - closed_funding,
                reason='open'), g))
        else:
            near(summary['open_qty'], 0)
            near(summary['open_cycle_pnl'], 0)
        near(sum(D(str(c['pnl'])) for c, _ in records if c['status'] == '已结束'),
             summary['closed_cycle_pnl'])
        near(sum(g['fee'] for _, g in records), summary['trading_fees'])
        near(sum(D(str(c['funding_paid'])) for c, _ in records), summary['funding_paid'])
        rows = []
        for c, g in records:
            cumulative += D(str(c['pnl']))
            direction = c.get('direction') or ('LONG' if g['first']['side'] == 'BUY' else 'SHORT')
            row = dict(c)
            row.update(scenario=name, scenario_label=label,
                       direction='做多' if direction == 'LONG' else '做空',
                       start_utc=timestamp(c['start_ms']), end_utc=timestamp(c['end_ms']),
                       entry_price=g['first']['price'], last_price=g['last']['price'],
                       liquidation_qty=abs(g['last_before_qty']) if c['status'] == '已结束' else '',
                       buys=g['buys'], sells=g['sells'], buy_qty=g['buy_qty'],
                       sell_qty=g['sell_qty'], peak_qty=g['peak'],
                       remaining_qty=g['buy_qty'] - g['sell_qty'], cumulative_pnl=cumulative,
                       reason_label={'2pct': '动态水位 2% 清仓', '5pct': '动态水位 5% 清仓',
                                     'flat': '网格持仓归零', 'open': '期末仍持仓'}.get(c['reason'], c['reason']))
            if extra_columns:
                metadata = (cycle_metadata or {}).get(name, {}).get(int(c['cycle_id']), {})
                row.update({key: metadata.get(key, '—') for key, _ in extra_columns})
            exported.append(row)
            cells = []
            for key, _ in columns:
                value = row[key]
                if key in ['duration_hours', 'entry_price', 'last_price', 'trading_fee',
                           'funding_paid', 'pnl', 'cumulative_pnl']:
                    value = f'{D(str(value)):,.2f}'
                elif key == 'liquidation_qty':
                    value = f'{value:.3f}' if value != '' else '未清仓'
                elif key in ['buy_qty', 'sell_qty', 'peak_qty', 'remaining_qty']:
                    value = f'{D(str(value)):.3f}'
                cells.append('<td>' + html.escape(str(value)) + '</td>')
            rows.append('<tr data-cycle-id="' + str(c['cycle_id']) + '">' + ''.join(cells) + '</tr>')
        near(cumulative, summary['net_pnl'])
        counts[name] = dict(closed=len(closed), open=len(records)-len(closed), total=len(records),
                            net_pnl=str(cumulative), liquidation_positions_checked=len(closed), passed=True)
        header = ''.join('<th>' + title + '</th>' for _, title in columns)
        sections.append(f'<h3>{html.escape(label)}：{len(closed)} 条已结束，'
                        f'{len(records)-len(closed)} 条未结束</h3>'
                        f'<div class="cycle-scroll"><table class="cycle-table" data-scenario="{name}">'
                        f'<thead><tr>{header}</tr></thead><tbody>{"".join(rows)}</tbody></table></div>')
    fields = ['scenario', 'scenario_label'] + [k for k, _ in columns] + ['start_ms', 'end_ms', 'reason']
    with (root / 'cycle_details.csv').open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(exported)
    (root / 'cycle_details_audit.json').write_text(json.dumps(counts, indent=2))
    return '''<section id="cycle-details"><h2>每条 Cycle 明细</h2>
<p>所有场景、所有轮次直接列在下表，包括期末未结束的 Cycle。时间为 UTC（北京时间加 8 小时）；
未结束轮次的结束栏为回测期末，末笔成交价不是期末标记价。
清仓前仓位是最终将持仓归零的那笔成交前的实际持仓绝对值，多空方向见方向栏；
普通网格平仓也按此口径列出，未结束轮次标为未清仓。
净盈亏已扣手续费和资金费；未结束轮次包含轮内已实现盈亏及期末持仓浮盈亏，未计假设平仓成本。
资金费负值表示净收入。累计盈亏按各场景分别累计，不跨场景相加。</p>
<p><a href="cycle_details.csv">下载全部 Cycle 明细 CSV</a> ·
<a href="cycle_details_audit.json">逐轮汇总核对结果</a></p>
<label>筛选明细：<input id="cycle-filter" type="search" placeholder="Cycle 编号、日期、做多 / 做空、未结束" style="padding:8px;width:320px;max-width:90%"></label>
<style>.cycle-scroll{overflow:auto;max-height:650px;margin-bottom:28px}.cycle-table thead th{position:sticky;top:0;background:#edf3fa;z-index:1}.cycle-table tbody tr:hover{background:#f3f7fc}</style>
''' + ''.join(sections) + '''
<script>document.getElementById('cycle-filter').addEventListener('input',function(){
const q=this.value.trim().toLowerCase();document.querySelectorAll('.cycle-table tbody tr').forEach(function(r){
r.hidden=q!==''&&!r.textContent.toLowerCase().includes(q);});});</script></section>'''
