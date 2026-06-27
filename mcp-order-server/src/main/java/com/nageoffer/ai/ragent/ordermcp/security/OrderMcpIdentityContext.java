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

package com.nageoffer.ai.ragent.ordermcp.security;

import com.nageoffer.ai.ragent.mcpauth.McpCallerIdentity;
import io.modelcontextprotocol.common.McpTransportContext;

import java.util.Map;

public final class OrderMcpIdentityContext {

    public static final String REQUEST_ATTRIBUTE = OrderMcpIdentityContext.class.getName() + ".identity";
    public static final String TRANSPORT_CONTEXT_KEY = "ragent.order-mcp.caller";

    private OrderMcpIdentityContext() {
    }

    public static McpTransportContext transportContext(McpCallerIdentity identity) {
        return McpTransportContext.create(Map.of(TRANSPORT_CONTEXT_KEY, identity));
    }

    public static McpCallerIdentity requireIdentity(McpTransportContext context) {
        Object identity = context == null ? null : context.get(TRANSPORT_CONTEXT_KEY);
        if (identity instanceof McpCallerIdentity caller) {
            return caller;
        }
        throw new OrderMcpAuthorizationException("Missing verified MCP caller identity");
    }
}
