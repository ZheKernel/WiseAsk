-- Order MCP intent nodes for the main ragent database.
-- Safe to rerun because IDs are stable and updated on conflict.

INSERT INTO t_intent_node
(id, intent_code, name, level, parent_code, description, examples, mcp_tool_id, kind,
 param_prompt_template, sort_order, enabled, create_by, update_by, create_time, update_time, deleted)
VALUES
('3101523723396309001', 'order-service', '订单服务', 0, NULL,
 '查询订单列表、订单详情以及管理员订单统计信息',
 '["查询我的订单","查看订单详情","管理员查询全部订单"]',
 NULL, 2, NULL, 50, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),

('3101523723396309002', 'order-self-query', '我的订单查询', 1, 'order-service',
 '查询当前登录用户自己的订单列表，可按状态和日期筛选',
 '["查询我的订单","我最近有哪些订单","查看我已支付的订单","查询本月我的订单"]',
 'order_list_mine', 2,
 '只从用户问题中提取 status、startDate、endDate、limit。禁止生成 userId，用户身份由服务端认证上下文决定。',
 51, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),

('3101523723396309003', 'order-detail-query', '订单详情查询', 1, 'order-service',
 '根据订单编号查询详情，普通用户只能查看自己的订单',
 '["查询订单ORD-20260601-001详情","这个订单现在是什么状态","查看指定订单"]',
 'order_detail', 2,
 '只提取订单编号 orderNo，不要提取或生成 userId、role 等身份字段。',
 52, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),

('3101523723396309004', 'order-admin-query', '管理员订单查询', 1, 'order-service',
 '管理员查询全部用户订单，也可以指定用户、状态或日期范围',
 '["查询所有用户订单","统计用户2001523723396309002的订单","查看全部待处理订单"]',
 'order_admin_search', 2,
 '提取可选参数 userId、status、startDate、endDate、limit。该工具仅供管理员调用。',
 53, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO UPDATE SET
    intent_code = EXCLUDED.intent_code,
    name = EXCLUDED.name,
    level = EXCLUDED.level,
    parent_code = EXCLUDED.parent_code,
    description = EXCLUDED.description,
    examples = EXCLUDED.examples,
    mcp_tool_id = EXCLUDED.mcp_tool_id,
    kind = EXCLUDED.kind,
    param_prompt_template = EXCLUDED.param_prompt_template,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    update_by = EXCLUDED.update_by,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;