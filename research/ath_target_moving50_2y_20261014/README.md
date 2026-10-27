建仓时固定历史最高价参考，不随创新高更新。低点ATH/2→100% BTC，高点ATH×2→0%；价格线性计算目标比例，偏离目标5个百分点触发冻结净资产5%调整量，50份、0.1%移动网格。主方案固定BTC现货＋合约空单，净多仅作对照。

区间2024-10-02至2026-10-02。
python3 prepare_data.py
python3 test_target.py
python3 run.py
python3 validate_export.py
python3 build_report.py

分钟数据中新挂单下一分钟生效，每分钟最多一次网格成交。无实盘变更。
