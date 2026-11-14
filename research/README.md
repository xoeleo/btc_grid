# 研究代码版本管理

此目录保存独立研究源码、测试、运行脚本、说明文档和文本配置。
各研究子目录的 README 说明其规则、依赖和运行方式。

`.gitignore` 将下载行情、回测账本、JSON 结果、HTML 报告、图片、缓存、
编译文件和依赖二进制保留在本地。这些文件未随 Git 提交，完整重放还需要
对应的行情数据及各脚本引用的冻结依赖；Git 源码提交不代表数据备份完成。

Python 研究脚本通常依赖 numpy、pandas、matplotlib。不同研究目录包含同名
模块，应分别执行各目录的测试脚本，避免一次导入时串用其他研究的 engine。
例如在仓库根目录执行：

```bash
python3 research/reversible_grid_4555_5y_20261012/test_variants.py
python3 -m unittest discover -s research/adaptive_long_short_20261012 -p test_backtest.py -v
```

## 归档与检查记录

`reversible_grid_4555_5y_20261012/before_empty_batch_fix/` 是修复前的历史源码
快照，保留其原始内容。2026-10-17 检查时，当前研究代码的 43 个测试脚本全部
通过；该归档中的 `test_engine.py` 通过，`test_4060.py`、`test_4555.py` 和
`test_variants.py` 因快照多嵌套一级后旧参考引擎的相对路径失效而失败。
归档中的失败不代表当前修复版本的测试失败。

研究源码及测试的提交不代表策略已经部署或完成实盘验收。
