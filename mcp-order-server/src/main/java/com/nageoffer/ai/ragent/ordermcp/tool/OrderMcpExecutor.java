/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.ordermcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ordermcp.order.OrderQueryCriteria;
import com.nageoffer.ai.ragent.ordermcp.order.OrderQueryService;
import com.nageoffer.ai.ragent.ordermcp.order.OrderRecord;
import com.nageoffer.ai.ragent.ordermcp.security.McpCallerIdentity;
import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpAuthorizationException;
import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpIdentityContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMcpExecutor {

    public static final String TOOL_LIST_MINE = "order_list_mine";
    public static final String TOOL_DETAIL = "order_detail";
    public static final String TOOL_ADMIN_SEARCH = "order_admin_search";

    private static final List<String> STATUSES =
            List.of("PENDING", "PAID", "SHIPPED", "COMPLETED", "CANCELLED");

    private final OrderQueryService orderQueryService;
    private final ObjectMapper objectMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification orderListMineToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(
                listMineTool(),
                this::handleListMine
        );
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification orderDetailToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(
                detailTool(),
                this::handleDetail
        );
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification orderAdminSearchToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(
                adminSearchTool(),
                this::handleAdminSearch
        );
    }

    CallToolResult handleListMine(McpSyncServerExchange exchange, CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        McpCallerIdentity caller = identity(exchange);
        try {
            OrderQueryCriteria criteria = criteria(request.arguments(), false);
            List<OrderRecord> records = orderQueryService.listMine(caller, criteria);
            logAudit(caller, TOOL_LIST_MINE, records.size(), startMs);
            return success(Map.of(
                    "scope", "SELF",
                    "count", records.size(),
                    "orders", records
            ));
        } catch (Exception ex) {
            return failure(caller, TOOL_LIST_MINE, ex, startMs);
        }
    }

    CallToolResult handleDetail(McpSyncServerExchange exchange, CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        McpCallerIdentity caller = identity(exchange);
        try {
            String orderNo = stringArg(request.arguments(), "orderNo");
            Optional<OrderRecord> record = orderQueryService.findDetail(caller, orderNo);
            logAudit(caller, TOOL_DETAIL, record.isPresent() ? 1 : 0, startMs);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", record.isPresent());
            record.ifPresent(value -> result.put("order", value));
            return success(result);
        } catch (Exception ex) {
            return failure(caller, TOOL_DETAIL, ex, startMs);
        }
    }

    CallToolResult handleAdminSearch(McpSyncServerExchange exchange, CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        McpCallerIdentity caller = identity(exchange);
        try {
            OrderQueryCriteria criteria = criteria(request.arguments(), true);
            List<OrderRecord> records = orderQueryService.adminSearch(caller, criteria);
            logAudit(caller, TOOL_ADMIN_SEARCH, records.size(), startMs);
            return success(Map.of(
                    "scope", "ADMIN",
                    "count", records.size(),
                    "orders", records
            ));
        } catch (Exception ex) {
            return failure(caller, TOOL_ADMIN_SEARCH, ex, startMs);
        }
    }

    private Tool listMineTool() {
        Map<String, Object> properties = commonFilterProperties();
        return Tool.builder()
                .name(TOOL_LIST_MINE)
                .description("查询当前登录用户自己的订单，不允许指定其他用户ID")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();
    }

    private Tool detailTool() {
        Map<String, Object> properties = Map.of(
                "orderNo", Map.of(
                        "type", "string",
                        "description", "订单编号"
                )
        );
        return Tool.builder()
                .name(TOOL_DETAIL)
                .description("查询订单详情，普通用户只能查询自己的订单，管理员可以查询任意订单")
                .inputSchema(new JsonSchema("object", properties, List.of("orderNo"), null, null, null))
                .build();
    }

    private Tool adminSearchTool() {
        Map<String, Object> properties = commonFilterProperties();
        properties.put("userId", Map.of(
                "type", "string",
                "description", "用户ID，不填则查询全部用户"
        ));
        return Tool.builder()
                .name(TOOL_ADMIN_SEARCH)
                .description("管理员查询全部用户订单，可按用户、状态和日期筛选")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();
    }

    private Map<String, Object> commonFilterProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Map.of(
                "type", "string",
                "description", "订单状态",
                "enum", STATUSES
        ));
        properties.put("startDate", Map.of(
                "type", "string",
                "description", "开始日期，格式为 yyyy-MM-dd"
        ));
        properties.put("endDate", Map.of(
                "type", "string",
                "description", "结束日期，格式为 yyyy-MM-dd"
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "返回数量，默认20，最大100",
                "default", 20
        ));
        return properties;
    }

    private OrderQueryCriteria criteria(Map<String, Object> arguments, boolean includeUserId) {
        return new OrderQueryCriteria(
                includeUserId ? stringArg(arguments, "userId") : null,
                stringArg(arguments, "status"),
                dateArg(arguments, "startDate"),
                dateArg(arguments, "endDate"),
                intArg(arguments, "limit")
        );
    }

    private McpCallerIdentity identity(McpSyncServerExchange exchange) {
        return OrderMcpIdentityContext.requireIdentity(exchange.transportContext());
    }

    private String stringArg(Map<String, Object> arguments, String name) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(name);
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer intArg(Map<String, Object> arguments, String name) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private LocalDate dateArg(Map<String, Object> arguments, String name) {
        String value = stringArg(arguments, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(name + " must use yyyy-MM-dd");
        }
    }

    private CallToolResult success(Object value) {
        try {
            return CallToolResult.builder()
                    .content(List.of(new TextContent(objectMapper.writeValueAsString(value))))
                    .isError(false)
                    .build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize order result", ex);
        }
    }

    private CallToolResult failure(McpCallerIdentity caller, String toolId, Exception ex, long startMs) {
        boolean forbidden = ex instanceof OrderMcpAuthorizationException;
        String message = forbidden ? "无权执行该订单查询" : "订单查询失败";
        if (ex instanceof IllegalArgumentException) {
            message = ex.getMessage();
        }
        log.warn("Order MCP call failed, actor={}, role={}, tool={}, elapsed={}ms, reason={}",
                caller.userId(), caller.role(), toolId, System.currentTimeMillis() - startMs, ex.getMessage());
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    private void logAudit(McpCallerIdentity caller, String toolId, int resultCount, long startMs) {
        log.info("Order MCP audit, actor={}, role={}, tool={}, resultCount={}, elapsed={}ms",
                caller.userId(), caller.role(), toolId, resultCount, System.currentTimeMillis() - startMs);
    }
}
