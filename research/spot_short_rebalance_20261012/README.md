研究结论：默认网格未超过直接合约再平衡。基础收益未计可能的负USDT利息，须同时查看5%/10%敏感性。

           id  final_equity     cagr  max_drawdown  funding_net       fees
recent_direct 671421.809623 0.158928      0.296551 46377.054680 213.527453
  recent_grid 665116.654429 0.153470      0.308951 45732.663651 130.502132

运行：python3 fetch_data.py；python3 run.py；python3 build_report.py。完整模型与限制见report.html。初始现货是存量，统一账户真实借贷与强平规则未核实，非实盘安全证明。
外部复核未执行：本会话没有可用内置浏览器或 ChatGPT Pro 工具。
