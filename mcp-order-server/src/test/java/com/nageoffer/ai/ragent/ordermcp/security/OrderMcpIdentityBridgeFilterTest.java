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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

class OrderMcpIdentityBridgeFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAttachJwtIdentityToMcpRequest() throws Exception {
        Jwt jwt = new Jwt(
                "signed-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "user-1",
                        "username", "alice",
                        "role", "user",
                        "scope", "order:read:self",
                        "client_id", "ragent",
                        "jti", "token-jti-1",
                        "aud", List.of("order-mcp")
                )
        );
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");

        new OrderMcpIdentityBridgeFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain()
        );

        Assertions.assertEquals(
                new McpCallerIdentity(
                        "user-1",
                        "alice",
                        "user",
                        Set.of("order:read:self"),
                        "ragent",
                        "token-jti-1"
                ),
                request.getAttribute(OrderMcpIdentityContext.REQUEST_ATTRIBUTE)
        );
    }

    @Test
    void shouldNotInventIdentityWithoutAuthenticatedJwt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");

        new OrderMcpIdentityBridgeFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain()
        );

        Assertions.assertNull(request.getAttribute(OrderMcpIdentityContext.REQUEST_ATTRIBUTE));
    }
}
