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

package com.nageoffer.ai.ragent.ordermcp.config;

import com.nageoffer.ai.ragent.ordermcp.security.McpCallerIdentity;
import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpIdentityContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OrderMcpServerConfig {

    @Bean
    public HttpServletStreamableServerTransportProvider orderMcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .contextExtractor(this::extractTransportContext)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> orderMcpServlet(
            HttpServletStreamableServerTransportProvider orderMcpTransportProvider) {
        return new ServletRegistrationBean<>(orderMcpTransportProvider, "/mcp");
    }

    @Bean
    public McpSyncServer orderMcpServer(
            HttpServletStreamableServerTransportProvider orderMcpTransportProvider,
            List<McpServerFeatures.SyncToolSpecification> toolSpecifications) {
        return McpServer.sync(orderMcpTransportProvider)
                .serverInfo("ragent-order-mcp-server", "1.0.0")
                .tools(toolSpecifications)
                .build();
    }

    private io.modelcontextprotocol.common.McpTransportContext extractTransportContext(
            HttpServletRequest request) {
        Object value = request.getAttribute(OrderMcpIdentityContext.REQUEST_ATTRIBUTE);
        if (!(value instanceof McpCallerIdentity identity)) {
            return io.modelcontextprotocol.common.McpTransportContext.EMPTY;
        }
        return OrderMcpIdentityContext.transportContext(identity);
    }
}
