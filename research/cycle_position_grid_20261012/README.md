# BTC 大周期高低位与网格研究

独立研究目录，只访问公开行情与链上接口。没有账户 API、凭证或下单逻辑，不修改既有策略或服务。

打开 `report.html` 查看结论、失败实验、逐年 / 多空结果及全部 Cycle。

## 复现顺序

依赖 Python 3、requests、numpy、pandas、matplotlib。

```bash
python3 research/cycle_position_grid_20261012/fetch_data.py
python3 research/cycle_position_grid_20261012/fetch_minutes.py
python3 -m unittest discover -s research/cycle_position_grid_20261012 -p test_research.py -v
python3 research/cycle_position_grid_20261012/run_research.py
python3 research/cycle_position_grid_20261012/run_peak.py
python3 research/cycle_position_grid_20261012/run_minutes.py
python3 research/cycle_position_grid_20261012/latest_snapshot.py
python3 research/cycle_position_grid_20261012/build_report.py
```

两个下载器缓存原始 ZIP、官方 CHECKSUM、公开 JSON 和 CSV。冻结回测区间为
2020-01-01 至 2026-10-02 UTC；分钟区间为 2024-10-02 至 2026-10-02 UTC。
最新快照是独立文件，更新它不会修改回测输入或选择参数。

## 研究协议

第一组比较两年对数趋势偏离百分位与两年均线价格倍数百分位，四候选仅用 2020–2022
选参。它们训练期全部亏损；原结果保存在 `results_phase1.json` 和各场景账本。

第二组在发现第一组失败后提出，用过去四年最高收盘价作移动参考。四候选为低位比例
0.45 / 0.55 × 转折确认 28 / 42 天，其余规则固定，只用 2020–2022 收益减回撤选参。
选中 0.55、28 天。高位比例固定 0.8，候选记忆 180 天，中期趋势用 EMA100 及其五日斜率。
所有日线信号次日执行；小时波动次小时使用。

主候选敞口 35%，网格三层、单层 2.5% 净值，加仓上限 42.5%；格距 0.5%–2%。
退出依据相反区域、趋势反转、日线参考价不利变动超过 20% 或 730 天超时，退出后冷却 30 天。
这里的 20% 不是盘中止损，也不是账户最大损失保证。

第二组是在已经看到第一组全期结果后提出，研发也已看过此前两年报告。后段结果只能叫
回顾性时间验证，不能声称新的盲测。MVRV 是当前版本的修订历史，没有 point-in-time 版本。

## 数据与输出

- `data/manifest.json`：5,920 天日线、59,184 根合约小时线、7,398 条资金费的来源与校验。
- `data/minute_manifest.json`：同源两年 1,051,200 根分钟成交 / 标记 OHLC 的来源与校验。
- `results.json`：全部 49 个场景、参数、训练选择与冻结后结果。
- `audit.json`、`cycle_details_audit.json`：独立账本和逐轮核验。
- `yearly_results.csv`、`direction_results.csv`：逐年收益、敞口及多空贡献。
- `signal_events.csv`：日线控制方向改变及可用日期。
- `latest_snapshot.json`：最新已收盘日线位置，明确区分模型状态与实际账户持仓。
- `decision.json`：当前研究结论；近期网格收益仍低于相同控制的无网格持仓。

Binance 月度标记价格包的 216 个缺失小时以官方日包修复。资金费原始毫秒事件时间
保留，模型将边界后一秒内的结算先计入携带仓位，再执行该小时订单。

所有成交使用假设路径与容量规则，未模拟真实盘口、排队、断线、强平或跨年费率变化。
小时回测不能代替分钟验证；分钟回测也不能代替前向 Paper。

外部复核未执行：本会话没有可用内置浏览器或 ChatGPT Pro 工具。未部署。
