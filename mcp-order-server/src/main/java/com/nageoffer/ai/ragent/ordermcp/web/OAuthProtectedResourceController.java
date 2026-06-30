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

package com.nageoffer.ai.ragent.ordermcp.web;

import com.nageoffer.ai.ragent.mcpauth.McpScopes;
import com.nageoffer.ai.ragent.ordermcp.config.OrderMcpAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OAuthProtectedResourceController {

    private final OrderMcpAuthProperties properties;

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", properties.getResource(),
                "authorization_servers", List.of(properties.getIssuer()),
                "scopes_supported", List.of(
                        McpScopes.DISCOVER,
                        McpScopes.ORDER_READ_SELF,
                        McpScopes.ORDER_READ_ANY
                ),
                "bearer_methods_supported", List.of("header")
        );
    }
}
