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
import com.nageoffer.ai.ragent.mcpauth.McpIdentityTokenCodec;
import com.nageoffer.ai.ragent.ordermcp.config.OrderMcpAuthProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

class OrderMcpAuthenticationFilterTest {

    private static final String SECRET = "test-order-mcp-shared-secret-value-123456";

    @Test
    void shouldAttachVerifiedIdentityToMcpRequest() throws Exception {
        OrderMcpAuthProperties properties = properties();
        McpIdentityTokenCodec codec = new McpIdentityTokenCodec(SECRET, "ragent", Duration.ofMinutes(5));
        OrderMcpAuthenticationFilter filter = new OrderMcpAuthenticationFilter(codec, properties);
        McpCallerIdentity expected = new McpCallerIdentity("user-1", "alice", "user");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + codec.issue(expected, "order-mcp"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(HttpStatus.OK, response.getStatus());
        Assertions.assertEquals(expected, request.getAttribute(OrderMcpIdentityContext.REQUEST_ATTRIBUTE));
    }

    @Test
    void shouldRejectMissingBearerToken() throws Exception {
        OrderMcpAuthProperties properties = properties();
        McpIdentityTokenCodec codec = new McpIdentityTokenCodec(SECRET, "ragent", Duration.ofMinutes(5));
        OrderMcpAuthenticationFilter filter = new OrderMcpAuthenticationFilter(codec, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        Assertions.assertEquals("{\"error\":\"unauthorized\"}", response.getContentAsString());
    }

    private OrderMcpAuthProperties properties() {
        OrderMcpAuthProperties properties = new OrderMcpAuthProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("ragent");
        properties.setAudience("order-mcp");
        return properties;
    }

    private static final class HttpStatus {
        private static final int OK = 200;
        private static final int UNAUTHORIZED = 401;
    }
}
