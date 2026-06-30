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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ordermcp.order.OrderQueryCriteria;
import com.nageoffer.ai.ragent.ordermcp.order.OrderQueryService;
import com.nageoffer.ai.ragent.ordermcp.security.McpCallerIdentity;
import com.nageoffer.ai.ragent.ordermcp.security.McpScopes;
import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpIdentityContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderMcpExecutorTest {

    @Test
    void shouldExposeAllOrderTools() {
        OrderMcpExecutor executor = new OrderMcpExecutor(
                mock(OrderQueryService.class),
                new ObjectMapper().findAndRegisterModules()
        );

        Set<String> toolNames = List.of(
                        executor.orderListMineToolSpecification(),
                        executor.orderDetailToolSpecification(),
                        executor.orderAdminSearchToolSpecification()
                ).stream()
                .map(specification -> specification.tool().name())
                .collect(Collectors.toSet());

        Assertions.assertEquals(Set.of(
                OrderMcpExecutor.TOOL_LIST_MINE,
                OrderMcpExecutor.TOOL_DETAIL,
                OrderMcpExecutor.TOOL_ADMIN_SEARCH
        ), toolNames);
    }

    @Test
    void shouldIgnoreModelSuppliedUserIdForMineTool() {
        OrderQueryService queryService = mock(OrderQueryService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OrderMcpExecutor executor = new OrderMcpExecutor(queryService, objectMapper);
        McpCallerIdentity caller = new McpCallerIdentity(
                "user-1",
                "alice",
                "user",
                Set.of(McpScopes.ORDER_READ_SELF),
                "ragent"
        );
        McpSyncServerExchange exchange = exchange(caller);
        when(queryService.listMine(same(caller), any())).thenReturn(List.of());

        CallToolResult result = executor.handleListMine(
                exchange,
                new CallToolRequest(OrderMcpExecutor.TOOL_LIST_MINE, Map.of(
                        "userId", "user-2",
                        "limit", 10
                ))
        );

        ArgumentCaptor<OrderQueryCriteria> criteriaCaptor = ArgumentCaptor.forClass(OrderQueryCriteria.class);
        verify(queryService).listMine(same(caller), criteriaCaptor.capture());
        Assertions.assertNull(criteriaCaptor.getValue().userId());
        Assertions.assertFalse(result.isError());
    }

    private McpSyncServerExchange exchange(McpCallerIdentity identity) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(OrderMcpIdentityContext.transportContext(identity));
        return exchange;
    }
}
