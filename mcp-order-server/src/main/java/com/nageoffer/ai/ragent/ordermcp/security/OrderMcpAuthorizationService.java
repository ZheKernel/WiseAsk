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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OrderMcpAuthorizationService {

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_USER = "user";

    public void requireSelfRead(McpCallerIdentity caller) {
        requireOrderUser(caller);
        if (!caller.hasScope(McpScopes.ORDER_READ_SELF)) {
            log.warn("[MCP-AUTH][AUTHZ_DENY] self-order read rejected, tokenJti={}, clientId={}, "
                            + "userId={}, role={}, requiredScope={}",
                    caller.tokenId(), caller.clientId(), caller.userId(), caller.role(),
                    McpScopes.ORDER_READ_SELF);
            throw new OrderMcpAuthorizationException("order:read:self scope is required");
        }
        log.info("[MCP-AUTH][AUTHZ_ALLOW] self-order read allowed, tokenJti={}, clientId={}, "
                        + "userId={}, role={}, matchedScope={}",
                caller.tokenId(), caller.clientId(), caller.userId(), caller.role(),
                McpScopes.ORDER_READ_SELF);
    }

    public boolean canReadAny(McpCallerIdentity caller) {
        return caller != null
                && ROLE_ADMIN.equalsIgnoreCase(caller.role())
                && caller.hasScope(McpScopes.ORDER_READ_ANY);
    }

    public void requireAdminReadAny(McpCallerIdentity caller) {
        if (!canReadAny(caller)) {
            log.warn("[MCP-AUTH][AUTHZ_DENY] cross-user order read rejected, tokenJti={}, "
                            + "clientId={}, userId={}, role={}, scopes={}",
                    tokenId(caller), clientId(caller), userId(caller), role(caller),
                    caller != null ? caller.scopes() : null);
            throw new OrderMcpAuthorizationException(
                    "Administrator role and order:read:any scope are required"
            );
        }
        log.info("[MCP-AUTH][AUTHZ_ALLOW] cross-user order read allowed, tokenJti={}, "
                        + "clientId={}, userId={}, role={}, matchedScope={}",
                caller.tokenId(), caller.clientId(), caller.userId(), caller.role(),
                McpScopes.ORDER_READ_ANY);
    }

    private void requireOrderUser(McpCallerIdentity caller) {
        if (caller == null
                || caller.userId() == null
                || caller.userId().isBlank()
                || (!ROLE_ADMIN.equalsIgnoreCase(caller.role())
                && !ROLE_USER.equalsIgnoreCase(caller.role()))) {
            log.warn("[MCP-AUTH][AUTHZ_DENY] caller identity is not eligible for order queries, "
                            + "tokenJti={}, clientId={}, userId={}, role={}",
                    tokenId(caller), clientId(caller), userId(caller), role(caller));
            throw new OrderMcpAuthorizationException("Order query is not allowed for this caller");
        }
    }

    private String tokenId(McpCallerIdentity caller) {
        return caller == null ? null : caller.tokenId();
    }

    private String clientId(McpCallerIdentity caller) {
        return caller == null ? null : caller.clientId();
    }

    private String userId(McpCallerIdentity caller) {
        return caller == null ? null : caller.userId();
    }

    private String role(McpCallerIdentity caller) {
        return caller == null ? null : caller.role();
    }
}
