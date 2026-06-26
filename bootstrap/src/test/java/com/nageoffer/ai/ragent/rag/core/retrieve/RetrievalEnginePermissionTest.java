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
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.permission.RagResourcePermissionService;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
