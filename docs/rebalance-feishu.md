# 再平衡大额成交通知

仅用于再平衡（RBAL_）订单：同一订单实际累计成交数量 >= 当前 Cycle 单格数量 × 3 时发送一次飞书通知，买卖都适用。使用 BTC 数量，不使用 USDT 成交金额或挂单数量。自动模式沿用该 Cycle 的自动单格数量；其他模式沿用普通单格数量。

沿用 predict 的环境变量 `FEISHU_WEBHOOK_URL` 和可选 `FEISHU_WEBHOOK_SECRET`，支持同样的 HmacSHA256 时间戳签名。也可以用 `REBALANCE_FEISHU_WEBHOOK_URL` 和 `REBALANCE_FEISHU_WEBHOOK_SECRET` 配置独立机器人；独立 URL 存在时只使用独立 secret，不混用两组配置。如设置关键词，可使用“再平衡”。未配置时仅记录 `rebalance.feishu.disabled`。

两个启动脚本会在启动 Java 前加载 `${HOME}/.config/btc-grid/rebalance-feishu.env`，可通过 `REBALANCE_FEISHU_ENV_FILE` 指定其他文件路径。文件使用 shell 环境变量赋值语法，权限设为 600；不要提交到 Git 或放入发布包。systemd 也可以通过 `EnvironmentFile=` 直接加载这两个变量。已有运行进程需要发布新代码并重启目标服务才能生效。

网络发送在有界后台队列中执行，超时或飞书业务拒绝记录 `rebalance.feishu.failed`，成功记录 `rebalance.feishu.sent`。当前不自动重试，避免响应丢失导致重复通知；进程退出时队列不持久化。

重复执行 ID 不重复累计。重启时使用已恢复的当前 Cycle 成交记录恢复累计数量，历史已达到阈值的订单不会补发。阈值在该订单第一笔成交时锁定，后续拆单成交达到阈值时发送。若机器人未配置或发送失败，已经触发的订单不会再次触发。

协议参考：https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot

## 2026-09-11 实盘发布

已发布至 `47.236.51.5:8789` 的 `btc-grid-rebalance.service`。以线上原 JAR 为底包，仅替换 `RebalanceService.class`，新增 `RebalanceFillNotifier.class` 和 `FeishuNotifier.class`；部署前确认去掉通知改动后的本地 RebalanceService 与线上方法字节码一致。

- JAR SHA256：`bffc208f05bfd97de3b77294bf9eb5e7d99348bc58d8134ebeb7c3d0f1cc87fe`
- 备份目录：`/opt/btc_grid_rebalance/backups/feishu-20260911`
- 私有配置：`/opt/btc_grid_rebalance/runtime/mainnet/rebalance-feishu.env`，权限 600；通过该服务的 `feishu.conf` systemd drop-in 加载。
- 发布后 PID `2426010`，`NRestarts=0`，`rebalanceRunning=true`，`GRID_ACTIVE`；恢复 940 条 Cycle 成交和 2 个挂单，私有流订阅成功，SQLite 完整性检查通过。
- Bybit 只读查询确认两个挂单均为 New，与本地订单一致。8787 PID `1263020` 未变，策略配置文件哈希未变。
- 当前做多 Cycle 单格 `0.003 BTC`，通知阈值 `0.009 BTC`。
- 24 项相关测试通过。使用线上 JAR 和私有配置发送一次明确标注“非成交”的部署验证消息，飞书返回成功；此验证不代表已观察到真实大额成交触发。

代码回滚时恢复备份中的 `original.jar` 并移除本次 `feishu.conf`，只重启再平衡服务；不要回滚已经继续产生新成交的数据库或状态文件。
