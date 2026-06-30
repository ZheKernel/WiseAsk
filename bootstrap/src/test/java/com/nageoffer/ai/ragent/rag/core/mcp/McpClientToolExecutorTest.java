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

package com.nageoffer.ai.ragent.rag.core.mcp;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class McpClientToolExecutorTest {

    @Test
    void shouldBindCallerTokenDuringAuthenticatedCall() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpIdentityTokenService tokenService = mock(McpIdentityTokenService.class);
        McpRequestIdentityContext identityContext = new McpRequestIdentityContext();
        LoginUser caller = LoginUser.builder().userId("user-1").username("alice").role("user").build();
        CallToolResult expected = result("ok");
        when(tokenService.issue(caller, "order-mcp")).thenReturn("user-token");
        when(client.callTool(any(CallToolRequest.class))).thenAnswer(invocation -> {
            Assertions.assertEquals("user-token", identityContext.currentToken());
            return expected;
        });
        McpClientToolExecutor executor = new McpClientToolExecutor(
                client,
                tool("order_list_mine"),
                true,
                "order-mcp",
                tokenService,
                identityContext
        );

        CallToolResult actual = executor.execute(Map.of("limit", 5), caller);

        Assertions.assertSame(expected, actual);
        Assertions.assertNull(identityContext.currentToken());
        verify(tokenService).issue(caller, "order-mcp");
    }

    @Test
    void shouldNotIssueTokenForUnauthenticatedServer() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpIdentityTokenService tokenService = mock(McpIdentityTokenService.class);
        McpRequestIdentityContext identityContext = new McpRequestIdentityContext();
        LoginUser caller = LoginUser.builder().userId("user-1").role("user").build();
        CallToolResult expected = result("ok");
        when(client.callTool(any(CallToolRequest.class))).thenReturn(expected);
        McpClientToolExecutor executor = new McpClientToolExecutor(
                client,
                tool("weather_query"),
                false,
                null,
                tokenService,
                identityContext
        );

        Assertions.assertSame(expected, executor.execute(Map.of(), caller));

        verifyNoInteractions(tokenService);
    }

    @Test
    void shouldRefreshTokenAndRetryOnlyOnceAfterUnauthorizedResponse() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpIdentityTokenService tokenService = mock(McpIdentityTokenService.class);
        McpRequestIdentityContext identityContext = new McpRequestIdentityContext();
        LoginUser caller = LoginUser.builder().userId("user-1").role("user").build();
        CallToolResult expected = result("ok");
        when(tokenService.issue(caller, "order-mcp"))
                .thenReturn("expired-token", "fresh-token");
        when(client.callTool(any(CallToolRequest.class)))
                .thenThrow(new IllegalStateException("HTTP 401 Unauthorized"))
                .thenAnswer(invocation -> {
                    Assertions.assertEquals("fresh-token", identityContext.currentToken());
                    return expected;
                });
        McpClientToolExecutor executor = new McpClientToolExecutor(
                client,
                tool("order_list_mine"),
                true,
                "order-mcp",
                tokenService,
                identityContext
        );

        Assertions.assertSame(expected, executor.execute(Map.of(), caller));

        verify(tokenService).invalidate(caller, "order-mcp");
        verify(tokenService, times(2)).issue(caller, "order-mcp");
    }

    private Tool tool(String name) {
        return Tool.builder()
                .name(name)
                .description(name)
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
    }

    private CallToolResult result(String content) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(content)))
                .isError(false)
                .build();
    }
}
