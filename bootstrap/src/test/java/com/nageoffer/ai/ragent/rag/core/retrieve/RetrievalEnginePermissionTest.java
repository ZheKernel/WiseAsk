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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.permission.RagResourcePermissionService;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalEnginePermissionTest {

    private final SearchChannelProperties searchProperties = new SearchChannelProperties();
    private final ContextFormatter contextFormatter = mock(ContextFormatter.class);
    private final PromptTemplateLoader templateLoader = mock(PromptTemplateLoader.class);
    private final McpParameterExtractor mcpParameterExtractor = mock(McpParameterExtractor.class);
    private final McpToolRegistry mcpToolRegistry = mock(McpToolRegistry.class);
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine = mock(MultiChannelRetrievalEngine.class);
    private final RagResourcePermissionService permissionService = mock(RagResourcePermissionService.class);
    private final RetrievalEngine engine = new RetrievalEngine(
            searchProperties,
            contextFormatter,
            templateLoader,
            mcpParameterExtractor,
            mcpToolRegistry,
            multiChannelRetrievalEngine,
            permissionService,
            Runnable::run,
            Runnable::run
    );

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldSkipMcpToolWhenCurrentUserHasNoPermission() {
        UserContext.set(loginUser("user-1", "user"));
        SubQuestionIntent intent = new SubQuestionIntent("question", List.of(mcpScore("admin-tool")));
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), anyInt())).thenReturn(List.of());
        when(permissionService.canCallMcpTool(any(LoginUser.class), eq("admin-tool"))).thenReturn(false);

        RetrievalContext result = engine.retrieve(List.of(intent), 3);

        Assertions.assertTrue(result.isEmpty());
        verify(permissionService).canCallMcpTool(any(LoginUser.class), eq("admin-tool"));
        verifyNoInteractions(mcpToolRegistry, mcpParameterExtractor, contextFormatter, templateLoader);
    }

    @Test
    void shouldPassVerifiedCallerToAllowedMcpTool() {
        LoginUser caller = loginUser("admin-1", "admin");
        UserContext.set(caller);
        SubQuestionIntent intent = new SubQuestionIntent("all orders", List.of(mcpScore("order_admin_search")));
        McpToolExecutor executor = mock(McpToolExecutor.class);
        Tool tool = Tool.builder()
                .name("order_admin_search")
                .description("orders")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        CallToolResult toolResult = CallToolResult.builder()
                .content(List.of(new TextContent("orders")))
                .isError(false)
                .build();
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), anyInt())).thenReturn(List.of());
        when(permissionService.canCallMcpTool(caller, "order_admin_search")).thenReturn(true);
        when(mcpToolRegistry.getExecutor("order_admin_search")).thenReturn(Optional.of(executor));
        when(executor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq("all orders"), same(tool), any())).thenReturn(Map.of());
        when(executor.execute(anyMap(), same(caller))).thenReturn(toolResult);
        when(contextFormatter.formatMcpContext(anyMap(), anyList())).thenReturn("orders");

        RetrievalContext result = engine.retrieve(List.of(intent), 3);

        Assertions.assertEquals("orders", result.getMcpContext());
        verify(executor).execute(anyMap(), same(caller));
    }

    private NodeScore mcpScore(String toolId) {
        return NodeScore.builder()
                .node(IntentNode.builder()
                        .id("mcp-" + toolId)
                        .kind(IntentKind.MCP)
                        .mcpToolId(toolId)
                        .build())
                .score(0.9)
                .build();
    }

    private LoginUser loginUser(String userId, String role) {
        return LoginUser.builder()
                .userId(userId)
                .username(userId)
                .role(role)
                .build();
    }
}
