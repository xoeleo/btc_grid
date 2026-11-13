# Rebalance 近一日、近三日买卖次数

“买入 / 卖出”卡片保留当前 Cycle 的累计数量、次数，新增近一日和近三日次数。
每行显示买入次数、卖出次数和两者合计。

- 时间窗口为汇总生成时间往前滚动 24 / 72 小时，包含窗口起点和生成时间。
- 只统计当前 Cycle 的 BUY / SELL 成交记录，沿用累计次数的成交明细口径；一张订单拆成多笔成交时分别计数。
- 资金费、旧 Cycle、未来时间记录不进入窗口统计；重复 execution 不重复入账。
- 统计读取完整当前 Cycle 账本，不受页面成交列表分页影响。
- 接口 `/api/rebalance-current-cycle` 新增 `buyCount24h`、`sellCount24h`、`buyCount72h`、`sellCount72h`，页面按原刷新周期更新。缺少字段时显示 `-`。

## 2026-11-09 发布

目标为 `47.236.51.5:8789`、`btc-grid-rebalance.service`。
以线上 JAR 为底包，仅替换 `CurrentCycleSummary`、`SqliteFillStore`、`HttpStatusServer` 三个类。
候选包 SHA-256 为 `6400216764c033245239ee0a7f385fde3b37b46ce09925dd87aed38426e96e5a`。

本地源码测试和线上底包候选测试各 26 项通过；候选 HTML 与线上原页面相比仅增加本次统计。
本地普通 Grid 的监控／恢复提示未随包发布：线上 `/api/control` 尚未返回对应 `recoveryStatus` 字段，需要配套后端恢复机制一并验证。

北京时间 20:38:54 发布验收：

- Rebalance PID `1339662`、`NRestarts=0`，状态 `GRID_ACTIVE`。
- 原 Cycle 起点 `1791163761.783`、成交 7730 笔、净数量 `0.972 BTC` 恢复，私有成交流已订阅。
- 两张有效挂单与 Bybit 只读查询一致，交易配置哈希未变。
- 近一日买入 209 / 卖出 202；近三日买入 338 / 卖出 341，与同一生成时刻的 SQLite 查询逐项一致。这些数字会随成交和窗口滚动变化。
- 线上与一致性备份 SQLite 完整性均为 `ok`。
- 普通 Grid PID `1024` 和 JAR 哈希未变。

备份：`/opt/btc_grid_rebalance/backups/rolling-counts-20261109T123740Z`，包含原 JAR、配置、运行状态、一致性 SQLite 与发布验收 JSON。
本地证据：`.build/rolling-counts-20261109/`，包含包差异、测试、页面和独立复核记录。

首次尝试因验收仅等待 50 秒，早于历史资金费对账结束，已自动回滚代码；数据库、状态未回滚。
核对启动期间无代码异常后，使用同一候选包和 300 秒就绪等待重新发布成功。
该服务在恢复 Cycle 和成交流后仍会核对历史资金费，不能仅因 HTTP 尚未监听判定启动失败。

如需代码回滚，只恢复该备份中的 `original.jar` 并重启 Rebalance 服务；保留持续更新的账本和运行状态。

## BTC 占比卡片补充理论占比

2026-11-09 21:13:55 已向同一 Rebalance 服务发布“当前理论占比”一行，位于启动比例、当前比例下方。
直接同步页面上方的理论 BTC 占比：`(最高价 - 当前价) / (最高价 - 最低价)`，限制在 0%—100%。
使用已保存的高低价区间与当前行情；未保存的区间仅影响预览。缺少有效数据时显示 `-`。

本次仅替换 `HttpStatusServer.class`，JAR SHA-256 为 `c52c4b1350ceb6247ca84a44268bb5a770cf31db1570fb762e1970a255cfe451`。
候选页面渲染验证覆盖区间内价格、上下界、缺少配置、未保存预览，以及与顶部理论占比的一致性。
备份：`/opt/btc_grid_rebalance/backups/theory-ratio-20261109T131241Z`。
发布验收 PID `1342830`、`NRestarts=0`，原 Cycle、私有流和两张有效挂单恢复，交易配置与普通 Grid 均未改动。
本地证据：`.build/theory-ratio-20261109/`。
