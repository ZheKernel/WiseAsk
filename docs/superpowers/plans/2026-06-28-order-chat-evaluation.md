# 订单 MCP Chat 链路评测实施计划

## 任务

- [x] 确认同步评测接口、登录方式和返回结构。
- [x] 更新简历中的 KV Cache 表述。
- [x] 编写批量用户、订单和清理 SQL。
- [x] 编写自然语言场景模板和确定性数据扩展器。
- [x] 实现登录、同步 Chat 评测请求和令牌缓存。
- [x] 实现意图、MCP 执行、语义和越权判定。
- [x] 实现并发运行、延迟指标和报告输出。
- [x] 补充单元测试和运行文档。
- [x] 执行 dry-run、单元测试并检查代码差异。
- [x] 提交聚焦的评测检查点。

## 验证命令

```powershell
python -m unittest discover -s evaluation/order-mcp/tests -t evaluation/order-mcp -v
python evaluation/order-mcp/run_evaluation.py --config evaluation/order-mcp/config.example.json --dry-run
```

启动 Ragent 和 Order MCP、执行评测 SQL 后，再运行：

```powershell
python evaluation/order-mcp/run_evaluation.py --config evaluation/order-mcp/config.example.json --requests 50 --concurrency 4
```
