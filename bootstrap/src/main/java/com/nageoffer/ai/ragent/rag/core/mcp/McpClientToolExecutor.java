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
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具执行器：通过官方 SDK 的 McpSyncClient 调用远端 MCP Server 暴露的工具
 * 负责工具发现（tools/list）、参数封装、调用结果与异常的标准化处理
 */
@Slf4j
@RequiredArgsConstructor
public class McpClientToolExecutor implements McpToolExecutor {

    private final McpSyncClient mcpClient;
    private final Tool toolDefinition;
    private final boolean authEnabled;
    private final String audience;
    private final McpIdentityTokenService identityTokenService;
    private final McpRequestIdentityContext requestIdentityContext;

    @Override
    public Tool getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters, LoginUser caller) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = parameters != null ? parameters : Map.of();
            if (authEnabled) {
                log.info("[MCP-AUTH][MCP_CALL_PREPARE] preparing authenticated MCP tool call, "
                                + "userId={}, role={}, toolId={}, audience={}",
                        caller != null ? caller.getUserId() : null,
                        caller != null ? caller.getRole() : null,
                        toolDefinition.name(), audience);
            }
            CallToolResult result;
            if (authEnabled) {
                result = callAuthenticated(args, caller);
            } else {
                result = mcpClient.callTool(new CallToolRequest(toolDefinition.name(), args));
            }
            log.info("{} remote MCP tool call completed, userId={}, role={}, toolId={}, "
                            + "contentSize={}, elapsed={}ms",
                    authEnabled ? "[MCP-AUTH][RAGENT_RESULT]" : "[MCP][RAGENT_RESULT]",
                    caller != null ? caller.getUserId() : null,
                    caller != null ? caller.getRole() : null,
                    toolDefinition.name(),
                    result.content() != null ? result.content().size() : 0,
                    System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("{} remote MCP tool call failed, userId={}, role={}, toolId={}, "
                            + "elapsed={}ms, reason={}",
                    authEnabled ? "[MCP-AUTH][RAGENT_RESULT]" : "[MCP][RAGENT_RESULT]",
                    caller != null ? caller.getUserId() : null,
                    caller != null ? caller.getRole() : null,
                    toolDefinition.name(),
                    System.currentTimeMillis() - startMs, reason);
            return CallToolResult.builder()
                    .content(List.of(new TextContent("远程调用失败: " + reason)))
                    .isError(true)
                    .build();
        }
    }

    private CallToolResult callAuthenticated(Map<String, Object> args, LoginUser caller) {
        String token = identityTokenService.issue(caller, audience);
        String tokenJti = McpJwtLogSupport.tokenId(token);
        log.info("[MCP-AUTH][MCP_CALL] attaching Bearer credential to MCP request, userId={}, "
                        + "role={}, toolId={}, audience={}, tokenJti={}",
                caller.getUserId(), caller.getRole(), toolDefinition.name(), audience, tokenJti);
        try {
            return callWithToken(args, token);
        } catch (RuntimeException ex) {
            if (!isUnauthorized(ex)) {
                throw ex;
            }
            log.warn("[MCP-AUTH][TOKEN_REJECTED] Order MCP rejected credential, userId={}, "
                            + "toolId={}, audience={}, tokenJti={}, action=refresh_and_retry_once",
                    caller.getUserId(), toolDefinition.name(), audience, tokenJti);
            identityTokenService.invalidate(caller, audience);
            String refreshedToken = identityTokenService.issue(caller, audience);
            log.info("[MCP-AUTH][MCP_CALL_RETRY] retrying MCP request with refreshed credential, "
                            + "userId={}, toolId={}, audience={}, tokenJti={}",
                    caller.getUserId(), toolDefinition.name(), audience,
                    McpJwtLogSupport.tokenId(refreshedToken));
            return callWithToken(args, refreshedToken);
        }
    }

    private CallToolResult callWithToken(Map<String, Object> args, String token) {
        return requestIdentityContext.withToken(token,
                () -> mcpClient.callTool(new CallToolRequest(toolDefinition.name(), args)));
    }

    private boolean isUnauthorized(RuntimeException ex) {
        String message = ex.getMessage();
        return message != null
                && (message.contains("401") || message.toLowerCase().contains("unauthorized"));
    }
}
