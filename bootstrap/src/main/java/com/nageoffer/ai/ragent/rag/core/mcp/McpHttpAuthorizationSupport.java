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

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;

import java.util.Map;

/**
 * Maps the caller token in MCP transport context to an HTTP bearer header.
 */
public final class McpHttpAuthorizationSupport {

    public static final String TOKEN_CONTEXT_KEY = "ragent.mcp.identity-token";

    private McpHttpAuthorizationSupport() {
    }

    public static McpTransportContext transportContext(String token) {
        return McpTransportContext.create(Map.of(TOKEN_CONTEXT_KEY, token));
    }

    public static McpSyncHttpClientRequestCustomizer bearerTokenCustomizer() {
        return (requestBuilder, method, uri, body, context) -> {
            Object token = context.get(TOKEN_CONTEXT_KEY);
            if (token instanceof String value && !value.isBlank()) {
                requestBuilder.setHeader("Authorization", "Bearer " + value);
            }
        };
    }
}
