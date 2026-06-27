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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端自动配置
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(McpClientProperties.class)
public class McpClientAutoConfiguration {

    private final McpClientProperties properties;
    private final McpToolRegistry toolRegistry;
    private final McpIdentityTokenService identityTokenService;
    private final McpRequestIdentityContext requestIdentityContext;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @PostConstruct
    public void init() {
        List<McpClientProperties.ServerConfig> servers = properties.getServers();
        if (servers == null || servers.isEmpty()) {
            log.info("未配置 MCP Server，跳过远程工具注册");
            return;
        }

        for (McpClientProperties.ServerConfig server : servers) {
            if (!server.isEnabled()) {
                log.info("MCP Server [{}] 已禁用，跳过工具注册", server.getName());
                continue;
            }
            registerRemoteTools(server);
        }
        log.info("MCP 远程工具注册流程完成, 当前注册表共 {} 个工具", toolRegistry.size());
    }

    private void registerRemoteTools(McpClientProperties.ServerConfig server) {
        String serverName = server.getName();
        String serverUrl = server.getUrl();
        log.info("连接 MCP Server: name={}, url={}", serverName, serverUrl);

        try {
            String mcpUrl = serverUrl.endsWith("/mcp") ? serverUrl : serverUrl + "/mcp";
            HttpClientStreamableHttpTransport.Builder transportBuilder =
                    HttpClientStreamableHttpTransport.builder(mcpUrl);
            if (server.isAuthEnabled()) {
                if (StrUtil.isBlank(server.getAudience())) {
                    throw new IllegalArgumentException("Authenticated MCP server audience must not be blank");
                }
                transportBuilder.httpRequestCustomizer(McpHttpAuthorizationSupport.bearerTokenCustomizer());
            }
            HttpClientStreamableHttpTransport transport = transportBuilder.build();

            McpClient.SyncSpec clientSpec = McpClient.sync(transport)
                    .clientInfo(new Implementation("ragent-bootstrap", "1.0.0"));
            if (server.isAuthEnabled()) {
                clientSpec.transportContextProvider(() -> {
                    String token = requestIdentityContext.currentToken();
                    return McpHttpAuthorizationSupport.transportContext(
                            StrUtil.isNotBlank(token)
                                    ? token
                                    : identityTokenService.issueServiceIdentity(server.getAudience())
                    );
                });
            }
            McpSyncClient client = clientSpec.build();
            client.initialize();
            clients.add(client);

            ListToolsResult result = client.listTools();
            List<Tool> tools = result.tools();
            if (CollUtil.isEmpty(tools)) {
                log.info("MCP Server [{}] 未发现可用工具，跳过工具注册", serverName);
                return;
            }
            log.info("MCP Server [{}] 返回 {} 个工具", serverName, tools.size());

            for (Tool tool : tools) {
                McpClientToolExecutor executor = new McpClientToolExecutor(
                        client,
                        tool,
                        server.isAuthEnabled(),
                        server.getAudience(),
                        identityTokenService,
                        requestIdentityContext
                );
                toolRegistry.register(executor);
            }
        } catch (Exception e) {
            log.error("连接 MCP Server [{}] 失败，跳过工具注册，reason={}", serverName, e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        for (McpSyncClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端失败", e);
            }
        }
    }
}
