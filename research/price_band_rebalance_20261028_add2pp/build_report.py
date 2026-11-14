"""Build the Chinese report for the price-band direct-rebalance study.

The simulation and audit are deliberately kept outside this file.  This
generator only reads ``results.json``, the exported per-scenario CSVs, and the
already-produced ``audit.json``/``source_manifest.json``.  It writes the two
presentation artifacts ``report.html`` and ``equity.png`` after the runner has
finished.

The report describes a fixed linear target curve: 60,000 USD is 100% net BTC
and 200,000 USD is 0% net BTC.  The strategy retains the initial spot BTC and
uses Bybit USDT-perpetual taker trades to move net exposure back to that
target.  It is not a grid or a price-drawdown rule.
"""

from __future__ import annotations

import html
import json
import math
import re
from pathlib import Path
from typing import Any, Iterable

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties, fontManager
import pandas as pd


ROOT = Path(__file__).resolve().parent
LOW_PRICE = 60_000.0
HIGH_PRICE = 200_000.0
INITIAL_CAPITAL = 500_000.0
QTY_STEP = 0.001
TAKER_RATE = 0.00035
SLIPPAGE = 0.0001

MAIN_IDS = ["two_year_5pp", "two_year_2pp", "two_year_1pp", "two_year_hold"]
EXPECTED_IDS = MAIN_IDS + [
    "year1_5pp",
    "year1_2pp",
    "year1_1pp",
    "year2_5pp",
    "year2_2pp",
    "year2_1pp",
    "two_year_5pp_fee2",
    "two_year_2pp_fee2",
    "two_year_1pp_fee2",
    "two_year_5pp_borrow5",
    "two_year_5pp_borrow10",
    "two_year_2pp_borrow5",
    "two_year_1pp_borrow5",
    "two_year_2pp_borrow10",
    "two_year_1pp_borrow10",
    "two_year_5pp_allow_long",
    "two_year_2pp_allow_long",
    "two_year_1pp_allow_long",
]


def _configure_font() -> None:
    """Use the local Chinese font when available, with a portable fallback."""

    candidates = [
        Path("/home/xieke/.local/share/fonts/catalog9-local-ui-msyh.ttc"),
        Path("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
    ]
    for candidate in candidates:
        if candidate.exists():
            try:
                fontManager.addfont(str(candidate))
                plt.rcParams["font.family"] = FontProperties(fname=str(candidate)).get_name()
                break
            except (OSError, RuntimeError):
                continue
    plt.rcParams["axes.unicode_minus"] = False


_configure_font()


def _read_json(path: Path, default: Any = None) -> Any:
    if not path.exists():
        return default
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def _result_list(payload: Any) -> list[dict[str, Any]]:
    """Accept the normal list schema and harmless wrapper variants."""

    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict) and "id" in payload:
        return [payload]
    if isinstance(payload, dict):
        for key in ("results", "scenarios", "runs"):
            value = payload.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
    raise ValueError("results.json must contain a list of scenario result objects")


def _read_output(scenario_id: str, suffix: str) -> pd.DataFrame:
    path = ROOT / "outputs" / f"{scenario_id}_{suffix}.csv"
    if not path.exists():
        return pd.DataFrame()
    try:
        return pd.read_csv(path)
    except pd.errors.EmptyDataError:
        return pd.DataFrame()


def _number(value: Any, default: float | None = None) -> float | None:
    if value is None or value == "":
        return default
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


def _integer(value: Any, default: int = 0) -> int:
    number = _number(value)
    return default if number is None else int(number)


def _money(value: Any) -> str:
    number = _number(value)
    return "—" if number is None else f"${number:,.2f}"


def _percent(value: Any) -> str:
    number = _number(value)
    return "—" if number is None else f"{number * 100:.2f}%"


def _points(value: Any) -> str:
    number = _number(value)
    return "—" if number is None else f"{number * 100:.2f} 点"


def _quantity(value: Any) -> str:
    number = _number(value)
    return "—" if number is None else f"{number:,.3f} BTC"


def _hours(value: Any) -> str:
    number = _number(value)
    return "—" if number is None else f"{number:,.1f} 小时"


def _timestamp(value: Any) -> pd.Timestamp | None:
    number = _number(value)
    if number is None:
        return None
    try:
        unit = "ms" if abs(number) > 10_000_000_000 else "s"
        return pd.to_datetime(number, unit=unit, utc=True)
    except (TypeError, ValueError, OverflowError):
        return None


def _date_range(result: dict[str, Any]) -> str:
    start = _timestamp(result.get("start_ms"))
    end = _timestamp(result.get("end_exclusive_ms"))
    if start is None or end is None:
        return "—"
    return f"{start.strftime('%Y-%m-%d')}–{end.strftime('%Y-%m-%d')}（UTC）"


def _config(result: dict[str, Any]) -> dict[str, Any]:
    value = result.get("config")
    return value if isinstance(value, dict) else {}


def _thresholds(result: dict[str, Any]) -> tuple[float | None, float | None]:
    cfg = _config(result)
    fallback = _number(cfg.get("threshold"))
    lower = _number(cfg.get("lower_threshold"), fallback)
    upper = _number(cfg.get("upper_threshold"), fallback)
    if lower is None and upper is None:
        match = re.search(r"_(5|2|1)pp(?:_|$)", str(result.get("id", "")))
        fallback = (int(match.group(1)) / 100) if match else None
        lower = upper = fallback
    return lower, upper


def _period_label(scenario_id: str) -> str:
    if scenario_id.startswith("two_year"):
        return "主期两年"
    if scenario_id.startswith("year1"):
        return "第1年（独立50万美元重置）"
    if scenario_id.startswith("year2"):
        return "第2年（独立50万美元重置）"
    return "—"


def _scenario_label(result: dict[str, Any]) -> str:
    scenario_id = str(result.get("id", ""))
    if scenario_id == "two_year_hold" or scenario_id.endswith("_hold"):
        return "对照：BTC现货持有"
    if "allow_long" in scenario_id:
        base = "允许合约净多对照"
    elif "fee2" in scenario_id:
        base = "手续费×2敏感性"
    elif "borrow10" in scenario_id:
        base = "负USDT年息10%敏感性"
    elif "borrow5" in scenario_id:
        base = "负USDT年息5%敏感性"
    elif scenario_id.startswith("year"):
        base = "价格带直接再平衡"
    else:
        base = "主方案：价格带直接再平衡"
    lower, upper = _thresholds(result)
    threshold_text = ""
    if lower is not None and upper is not None:
        if abs(lower - upper) < 1e-9:
            threshold_text = f"，偏差{lower * 100:.0f}点触发"
        else:
            threshold_text = f"，低侧{lower * 100:.0f}点/高侧{upper * 100:.0f}点"
    return f"{base}{threshold_text}"


def _scenario_sort_key(result: dict[str, Any]) -> tuple[int, str]:
    scenario_id = str(result.get("id", ""))
    order = {name: index for index, name in enumerate(EXPECTED_IDS)}
    return order.get(scenario_id, 1000), scenario_id


def _target_weight(price: float) -> float:
    return max(0.0, min(1.0, (HIGH_PRICE - price) / (HIGH_PRICE - LOW_PRICE)))


def _render(frame: pd.DataFrame, table_id: str | None = None) -> str:
    if frame.empty:
        return '<p class="missing">暂无可用结果。</p>'
    return frame.to_html(
        index=False,
        table_id=table_id,
        border=0,
        escape=True,
        classes="data-table",
    )


def _main_comparison(results: Iterable[dict[str, Any]]) -> pd.DataFrame:
    rows = []
    for result in results:
        lower, upper = _thresholds(result)
        cfg = _config(result)
        fee_multiplier = _number(cfg.get("fee_multiplier"), 1.0) or 1.0
        borrow_apr = _number(cfg.get("borrow_apr"), 0.0) or 0.0
        slip = _number(cfg.get("slip"), SLIPPAGE) or SLIPPAGE
        rows.append(
            {
                "方案": _scenario_label(result),
                "场景ID": result.get("id", ""),
                "区间": _date_range(result),
                "合约方向": "允许净多" if bool(cfg.get("allow_perp_long", False)) else "short-only",
                "Taker费率": f"{TAKER_RATE * fee_multiplier * 100:.3f}%",
                "滑点": f"{slip * 10_000:.2f} bp",
                "负USDT年息": f"{borrow_apr * 100:.1f}%",
                "期末净资产": _money(result.get("final_equity")),
                "累计收益": _percent(result.get("total_return")),
                "年化收益": _percent(result.get("cagr")),
                "最大回撤": _percent(result.get("max_drawdown")),
                "手续费": _money(result.get("fees")),
                "资金费净额": _money(result.get("funding_net")),
                "负USDT利息": _money(result.get("borrow_interest")),
                "Taker成交笔数": _integer(result.get("taker_fills")),
                "期末净BTC权重": _percent(result.get("final_weight")),
                "期末目标权重": _percent(result.get("final_target")),
                "短仓目标不可达": _hours(result.get("short_only_target_unreachable_hours")),
                "最大负USDT": _money(result.get("max_negative_usdt")),
                "低/高侧触发": (
                    f"{lower * 100:.2f}/{upper * 100:.2f} 点"
                    if lower is not None and upper is not None
                    else "—"
                ),
            }
        )
    return pd.DataFrame(rows)


def _trade_stats(result: dict[str, Any]) -> dict[str, Any]:
    scenario_id = str(result.get("id", ""))
    trades = _read_output(scenario_id, "trades")
    settlements = _read_output(scenario_id, "settlements")
    initial_fills = 0
    initial_fees = 0.0
    if not trades.empty:
        kind = trades.get("kind", pd.Series("", index=trades.index)).astype(str).str.lower()
        initial_mask = kind.str.contains("initial|entry|bootstrap", regex=True)
        initial_fills = int(initial_mask.sum())
        if "fee" in trades.columns:
            initial_fees = float(pd.to_numeric(trades.loc[initial_mask, "fee"], errors="coerce").fillna(0).sum())
    total_fee = _number(result.get("fees"), 0.0) or 0.0
    funding_settlements = 0
    funding_payment = _number(result.get("funding_net"), 0.0) or 0.0
    interest_settlements = 0
    interest_payment = -(_number(result.get("borrow_interest"), 0.0) or 0.0)
    if not settlements.empty:
        kinds = settlements.get("kind", pd.Series("", index=settlements.index)).astype(str).str.lower()
        if "payment" in settlements.columns:
            payments = pd.to_numeric(settlements["payment"], errors="coerce").fillna(0)
        else:
            payments = pd.Series(0.0, index=settlements.index)
        funding_mask = kinds.str.contains("fund")
        interest_mask = kinds.str.contains("interest|borrow")
        funding_settlements = int(funding_mask.sum())
        interest_settlements = int(interest_mask.sum())
        if "funding_net" not in result:
            funding_payment = float(payments[funding_mask].sum())
        if "borrow_interest" not in result:
            interest_payment = float(payments[interest_mask].sum())
    start_spot = end_spot = spot_range = None
    daily = _read_output(scenario_id, "daily")
    if not daily.empty and "spot_qty" in daily.columns:
        spot = pd.to_numeric(daily["spot_qty"], errors="coerce").dropna()
        if not spot.empty:
            start_spot = float(spot.iloc[0])
            end_spot = float(spot.iloc[-1])
            spot_range = float(spot.max() - spot.min())
    return {
        "scenario": _scenario_label(result),
        "场景ID": scenario_id,
        "初始Taker笔数": initial_fills,
        "再平衡Taker笔数": max(0, _integer(result.get("taker_fills"), len(trades)) - initial_fills),
        "Taker手续费": _money(total_fee),
        "其中初始合约调整费": _money(initial_fees),
        "资金费结算次数": funding_settlements,
        "资金费净额": _money(funding_payment),
        "借款利息结算次数": interest_settlements,
        "借款利息现金影响": _money(interest_payment),
        "期初现货BTC": _quantity(start_spot),
        "期末现货BTC": _quantity(end_spot),
        "现货数量最大变动": _quantity(spot_range),
    }


def _annual_rows(results: list[dict[str, Any]]) -> pd.DataFrame:
    independent = [
        result
        for result in results
        if str(result.get("id", "")).startswith("year")
    ]
    rows = []
    for result in sorted(independent, key=_scenario_sort_key):
        rows.append(
            {
                "场景": _scenario_label(result),
                "场景ID": result.get("id", ""),
                "区间": _date_range(result),
                "期末净资产": _money(result.get("final_equity")),
                "区间收益": _percent(result.get("total_return")),
                "年化收益": _percent(result.get("cagr")),
                "最大回撤": _percent(result.get("max_drawdown")),
                "手续费": _money(result.get("fees")),
                "资金费净额": _money(result.get("funding_net")),
                "Taker成交笔数": _integer(result.get("taker_fills")),
            }
        )
    if rows:
        return pd.DataFrame(rows)

    # If the runner omitted the independently-reset year jobs, still expose a
    # transparent daily-curve estimate instead of inventing a second result.
    for result in results:
        scenario_id = str(result.get("id", ""))
        if not scenario_id.startswith("two_year") or scenario_id.endswith("hold"):
            continue
        daily = _read_output(scenario_id, "daily")
        if daily.empty or "timestamp_ms" not in daily or "equity" not in daily:
            continue
        frame = daily.copy()
        frame["timestamp"] = pd.to_datetime(frame["timestamp_ms"], unit="ms", utc=True, errors="coerce")
        frame["equity"] = pd.to_numeric(frame["equity"], errors="coerce")
        frame = frame.dropna(subset=["timestamp", "equity"]).sort_values("timestamp")
        start = _timestamp(result.get("start_ms"))
        if start is None or frame.empty:
            continue
        boundaries = [start, start + pd.DateOffset(years=1), start + pd.DateOffset(years=2)]
        previous = INITIAL_CAPITAL
        for index in range(2):
            end = boundaries[index + 1]
            available = frame[frame["timestamp"] <= end]
            if available.empty:
                continue
            finish = float(available.iloc[-1]["equity"])
            interval_days = max(1.0, (end - boundaries[index]).total_seconds() / 86_400.0)
            gain = finish / previous - 1.0
            rows.append(
                {
                    "场景": f"{_scenario_label(result)}（日终曲线估算）",
                    "场景ID": scenario_id,
                    "区间": f"{boundaries[index].strftime('%Y-%m-%d')}–{end.strftime('%Y-%m-%d')}（UTC）",
                    "期末净资产": _money(finish),
                    "区间收益": _percent(gain),
                    "年化收益": _percent((1.0 + gain) ** (365.25 / interval_days) - 1.0),
                    "最大回撤": "见主期",
                    "手续费": "按主期累计",
                    "资金费净额": "按主期累计",
                    "Taker成交笔数": "按主期累计",
                }
            )
            previous = finish
    return pd.DataFrame(rows)


def _metadata_block(manifest: Any, audit: Any) -> str:
    rows = []
    if isinstance(manifest, dict):
        keys = [
            "period_start",
            "period_end_exclusive",
            "minute_rows",
            "funding_rows",
            "spot_rows",
            "perp_rows",
            "data_sha256",
        ]
        for key in keys:
            if key in manifest:
                rows.append({"字段": key, "值": str(manifest[key])})
    if isinstance(audit, dict):
        if "passed" in audit:
            rows.append({"字段": "audit.passed", "值": str(audit["passed"])})
        for key in ("unit_checks", "scenarios", "export_replay"):
            value = audit.get(key)
            if isinstance(value, list):
                rows.append({"字段": f"audit.{key}", "值": f"{len(value)} 条"})
    return _render(pd.DataFrame(rows))


def _plot_equity(results: list[dict[str, Any]]) -> list[str]:
    plotted: list[str] = []
    fig, ax = plt.subplots(figsize=(16, 8))
    plot_ids = [scenario_id for scenario_id in MAIN_IDS if any(r.get("id") == scenario_id for r in results)]
    for scenario_id in plot_ids:
        daily = _read_output(scenario_id, "daily")
        if daily.empty or not {"timestamp_ms", "equity"}.issubset(daily.columns):
            continue
        timestamp = pd.to_datetime(daily["timestamp_ms"], unit="ms", utc=True, errors="coerce")
        equity = pd.to_numeric(daily["equity"], errors="coerce")
        valid = timestamp.notna() & equity.notna()
        if not valid.any():
            continue
        result = next(r for r in results if r.get("id") == scenario_id)
        ax.plot(timestamp[valid], equity[valid], linewidth=1.1, label=_scenario_label(result))
        plotted.append(scenario_id)
    if not plotted:
        ax.text(0.5, 0.5, "等待主代理生成 outputs/*_daily.csv", ha="center", va="center", transform=ax.transAxes)
    ax.set_title("固定价格带直接再平衡：组合净资产（初始 50 万美元）")
    ax.set_xlabel("日期（UTC）")
    ax.set_ylabel("组合净资产（美元）")
    ax.grid(alpha=0.25)
    ax.legend(loc="best")
    fig.tight_layout()
    fig.savefig(ROOT / "equity.png", dpi=155)
    plt.close(fig)
    return plotted


def _missing_artifacts(results: list[dict[str, Any]]) -> list[str]:
    missing: list[str] = []
    for scenario_id in [str(r.get("id", "")) for r in results]:
        for suffix in ("daily", "trades", "settlements"):
            path = ROOT / "outputs" / f"{scenario_id}_{suffix}.csv"
            if not path.exists():
                missing.append(path.relative_to(ROOT).as_posix())
    return missing


def _links() -> str:
    links = ["<a href=\"results.json\">results.json</a>"]
    for filename, label in (
        ("audit.json", "audit.json"),
        ("source_manifest.json", "source_manifest.json"),
    ):
        if (ROOT / filename).exists():
            links.append(f'<a href="{filename}">{label}</a>')
    return " · ".join(links)


def main() -> None:
    results_path = ROOT / "results.json"
    if not results_path.exists():
        raise SystemExit(f"等待 results.json：{results_path}")
    results = sorted(_result_list(_read_json(results_path)), key=_scenario_sort_key)
    if not results:
        raise SystemExit("results.json 中没有场景结果")
    by_id = {str(result.get("id", "")): result for result in results}
    missing_expected = [scenario_id for scenario_id in EXPECTED_IDS if scenario_id not in by_id]
    missing_artifacts = _missing_artifacts(results)
    manifest = _read_json(ROOT / "source_manifest.json")
    audit = _read_json(ROOT / "audit.json")
    plotted = _plot_equity(results)

    main_results = [by_id[scenario_id] for scenario_id in MAIN_IDS if scenario_id in by_id]
    main_table = _main_comparison(main_results)
    sensitivity_results = [
        result
        for result in results
        if str(result.get("id", "")) not in MAIN_IDS
        and not str(result.get("id", "")).startswith("year")
    ]
    sensitivity_table = _main_comparison(sensitivity_results)
    annual_table = _annual_rows(results)
    cost_table = pd.DataFrame([_trade_stats(result) for result in results])
    target_table = pd.DataFrame(
        [
            {
                "BTC现货价格（美元）": f"${price:,.0f}",
                "目标净BTC权重": f"{_target_weight(price) * 100:.0f}%",
            }
            for price in (60_000, 80_000, 100_000, 120_000, 140_000, 160_000, 180_000, 200_000)
        ]
    )

    warnings: list[str] = []
    if missing_expected:
        warnings.append("缺少的预期场景：" + ", ".join(missing_expected))
    if missing_artifacts:
        warnings.append("缺少场景导出文件（报告已保留可用结果）：" + ", ".join(missing_artifacts[:20]))
        if len(missing_artifacts) > 20:
            warnings.append(f"另有 {len(missing_artifacts) - 20} 个缺失导出文件")
    if not isinstance(audit, dict):
        warnings.append("尚未找到 audit.json；本报告不替代独立账本核验")
    if not isinstance(manifest, dict):
        warnings.append("尚未找到 source_manifest.json；数据来源与哈希未在本报告中确认")

    warning_html = "".join(f'<div class="warning">{html.escape(message)}</div>' for message in warnings)
    period_text = "2024-10-02–2026-10-02（UTC，主期；子区间各自从 50 万美元重置）"
    body = f"""
<h1>固定价格带：直接再平衡净 BTC 权重回测</h1>
<p class="lead"><strong>规则：</strong>BTC 现货价格 60,000 美元对应 100% 净 BTC 权重，
200,000 美元对应 0%，中间按价格线性映射并截断在 0%–100%。初始 50 万美元全部折算为既有现货 BTC，
现货数量保持不变，不收现货建仓费；随后只用 Bybit USDT 永续合约调整净仓位。</p>
<p>每分钟按<strong>仓位相对目标的偏差</strong>触发，比较 5、2、1 个百分点阈值；触发后以 Taker 直接调回目标，
不是网格、不是按价格跌幅触发。初始合约调整和每次再平衡使用 Taker 0.035%，滑点 1bp，合约数量步长 0.001 BTC。
资金费使用历史真实结算记录；基础场景的融资利率为 0。主组仅开空/平空；允许合约净多、手续费翻倍、负 USDT 年息 5%/10% 只作敏感性。</p>
<p><strong>回测区间：</strong>{period_text}。本页面由 build_report.py 读取 results.json、daily/trades/settlements 导出，
成交、资金费和净值详见完整导出；独立核验结果见 audit.json。</p>
{warning_html}

<h2>固定价格到目标权重</h2>
<p>目标净 BTC 权重 = clamp((200,000 − BTC 现货价格) / 140,000, 0, 1)。目标权重使用现货价格；
永续标记价用于净资产与持仓标记。现货 BTC 是期初持仓，不在报告中伪造一笔现货买入或现货手续费。</p>
{_render(target_table)}

<h2>主期核心比较</h2>
{_render(main_table, "main-comparison")}
<img src="equity.png" alt="主期组合净资产曲线">
<p>净资产图实际绘制的场景：{html.escape(', '.join(plotted) if plotted else '暂无 daily 导出')}。最大回撤来自导出结果；若引擎以分钟 OHLC 路径估计，
它是保守的小时/分钟路径上界，不是逐笔 tick 精确回撤。</p>

<h2>独立年度子区间</h2>
{_render(annual_table, "annual-returns")}
<p>year1/year2 场景各自以 50 万美元和期初现货折算重新开始，不能把第二段的期末资产当作第一段的复利本金。
若主代理未提供独立年度结果，页面只会从两年日终曲线标注“估算”，不会冒充独立回测。</p>

<h2>敏感性场景</h2>
{_render(sensitivity_table, "sensitivity")}
<p>allow_perp_long 只用于检验 short-only 的目标不可达约束；fee2、borrow5、borrow10 分别改变费用或负 USDT 利息假设。
这些结果不改变主方案定义，也不表示账户已经获得借款或允许净多。</p>

<h2>成本、资金费与成交次数</h2>
{_render(cost_table, "cost-breakdown")}
<p>初始现货不计建仓手续费；初始合约调仓计入 Taker 费。手续费、资金费和借款利息已经进入各场景净资产，
不能再把它们重复加到收益。成交次数按 Taker 成交记录或结果字段展示；资金费结算次数来自 settlements.csv（如已导出）。
现货数量起末值及最大变动用于检查“保留原 BTC 数量”约束。</p>

<h2>结果边界与研究声明</h2>
<ul>
<li>这是历史样本事后参数研究，不是前瞻验证，也没有部署或交易。</li>
<li>分钟 OHLC 只能给出保守路径假设；没有重建逐笔盘口、队列优先级、撤单竞态、真实滑点分布或强平路径。</li>
<li>short-only 在目标要求超过期初现货可表达范围时会报告 short_only_target_unreachable_hours；不能把不可达时长当成策略成功。</li>
<li>USDT 负余额利息情景是敏感性假设，不能替代账户历史借款、抵押折扣和风险档位的核实。</li>
<li>外部复核未执行：当前工具无法确认 ChatGPT Pro Extra High 可用。</li>
</ul>
<p>审计与来源：{_links()}。</p>
{_metadata_block(manifest, audit)}
<input id="search" placeholder="筛选表格中的场景、区间或成本">
"""
    script = """
<script>
document.getElementById('search').addEventListener('input', function () {
  const needle = this.value.toLowerCase();
  document.querySelectorAll('table tbody tr').forEach(function (row) {
    row.hidden = !row.textContent.toLowerCase().includes(needle);
  });
});
</script>
"""
    style = """
<style>
body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;max-width:1900px;margin:26px auto;padding:0 18px;color:#17202a}
p{line-height:1.72}.lead{font-size:1.05rem}.warning{background:#fff4e5;border-left:4px solid #d97706;padding:9px 12px;margin:8px 0;line-height:1.5}
.missing{color:#9a3412}.data-table{border-collapse:collapse;font-size:12px;margin:8px 0 18px;white-space:nowrap}
.data-table td,.data-table th{border:1px solid #d7dde5;padding:6px 8px}.data-table th{background:#eef2f7;position:sticky;top:0}
img{max-width:100%;height:auto;border:1px solid #e5e7eb}input{margin:12px 0;padding:10px;min-width:360px;border:1px solid #9ca3af;border-radius:4px}
li{line-height:1.7}
</style>
"""
    document = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>固定价格带直接再平衡回测</title>" + style + "</head><body>" + body + script + "</body></html>"
    (ROOT / "report.html").write_text(document, encoding="utf-8")
    print(f"REPORT COMPLETE: {ROOT / 'report.html'} ({len(results)} scenarios; plotted={plotted})")


if __name__ == "__main__":
    main()
