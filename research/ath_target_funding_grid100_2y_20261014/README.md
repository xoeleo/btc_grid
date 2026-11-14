两年固定波段再平衡，10个百分点触发／10%调整、50份移动网格；叠加最近已结算资金费>0.02%触发的100份做空网格。每批冻结当前净BTC数量一半；未满回启动价退出，卖满后均价下跌2%退出；费率仍高可继续下一批。
复现：python3 test_funding.py；python3 run.py；python3 validate_export.py；python3 check_signal.py；python3 build_report.py。
融资利息未计，真实盘口与历史UTA强平未重建。仅研究，无实盘变更。
